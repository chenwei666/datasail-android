package com.example.datasail.core

import java.net.SocketException
import java.sql.Connection
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Metadata only. A discovery connection is always closed before selecting a target. */
data class DiscoveredDatabases(val names: List<String>, val limited: Boolean = false, val note: String = "") {
    companion object {
        const val LIMIT = 200
        fun bounded(names: List<String>, note: String = ""): DiscoveredDatabases {
            require(names.all { it.length in 1..128 && it.none(Char::isISOControl) })
            return DiscoveredDatabases(names.take(LIMIT).distinct().sorted(), names.size > LIMIT, note)
        }
    }
}

/** Bootstrap is explicit only where the protocol needs a database (PostgreSQL). */
object DiscoveryDefaults {
    fun database(kind: DatabaseKind, hint: String = ""): String = when (kind) {
        DatabaseKind.POSTGRESQL -> hint.trim().ifEmpty { "postgres" }
        DatabaseKind.ELASTICSEARCH, DatabaseKind.OPENSEARCH -> hint.trim().ifEmpty { "*" }
        DatabaseKind.REDIS, DatabaseKind.VALKEY -> "0"
        DatabaseKind.MONGODB -> "admin"
        DatabaseKind.SQL_SERVER, DatabaseKind.MYSQL, DatabaseKind.MARIADB -> "unused"
        DatabaseKind.SQLITE -> throw WorkspaceRejected("SQLite 请使用系统文件选择器")
    }
}

interface DiscoveryTask : AutoCloseable {
    fun scan(profile: ConnectionProfile, credentials: Credentials): DiscoveredDatabases
}

class ServerDatabaseDiscovery : DiscoveryTask {
    private val stopped = AtomicBoolean(false)
    private val active = AtomicReference<AutoCloseable?>()
    private fun <T : AutoCloseable> own(resource: T): T {
        active.set(resource)
        if (stopped.get()) { active.getAndSet(null)?.close(); throw SocketException("Cancelled") }
        return resource
    }
    override fun scan(profile: ConnectionProfile, credentials: Credentials): DiscoveredDatabases = credentials.use {
        try {
            if (stopped.get()) throw SocketException("Cancelled")
            when {
                profile.kind.isSearch -> {
                    val session = own(HttpsSearchSession(profile)); session.login(credentials)
                    DiscoveredDatabases.bounded(session.indices(), "仅列出当前范围内可见的普通索引；索引列表受账号权限限制。")
                }
                profile.kind.isDocument -> {
                    val session = own(MongoSession(profile)); session.login(credentials)
                    DiscoveredDatabases.bounded(session.databases(), "仅列出当前账号获授权的数据库；认证数据库独立设置。")
                }
                profile.kind.isKeyValue -> {
                    val session = own(RespKeyValueSession(profile)); session.login(credentials, selectDatabase = false)
                    DiscoveredDatabases.bounded(session.databases(), "INFO keyspace 仅列出有键的逻辑库，不含空库；需要 INFO 权限。空库可手动填写编号。")
                }
                profile.kind == DatabaseKind.SQLITE -> throw WorkspaceRejected("SQLite 请使用系统文件选择器")
                else -> jdbc(own(JdbcConnector().connectServer(profile, credentials)), profile.kind)
            }
        } finally { close() }
    }
    private fun jdbc(connection: Connection, kind: DatabaseKind): DiscoveredDatabases {
        val sql = when (kind) {
            DatabaseKind.SQL_SERVER -> "SELECT name FROM sys.databases WHERE HAS_DBACCESS(name)=1 AND state=0 ORDER BY name"
            DatabaseKind.POSTGRESQL -> "SELECT datname FROM pg_database WHERE datallowconn AND NOT datistemplate AND has_database_privilege(datname, 'CONNECT') ORDER BY datname"
            else -> "SHOW DATABASES"
        }
        val names = ArrayList<String>()
        connection.createStatement().use { statement ->
            statement.queryTimeout = 10
            statement.maxRows = DiscoveredDatabases.LIMIT + 1
            statement.executeQuery(sql).use { rows ->
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
                while (rows.next()) {
                    if (stopped.get()) throw SocketException("Cancelled")
                    if (System.nanoTime() > deadline) throw java.net.SocketTimeoutException()
                    names += rows.getString(1)
                    if (names.size > DiscoveredDatabases.LIMIT) break
                }
            }
        }
        return DiscoveredDatabases.bounded(names, "列表由当前账号权限决定；进入所选库时会重新连接并验证访问权限。")
    }
    override fun close() {
        stopped.set(true)
        active.getAndSet(null)?.close()
    }
}

/** One scan at a time. Cancellation invalidates callbacks and closes the actual transport off the UI thread. */
class DiscoveryRunner(private val factory: () -> DiscoveryTask = { ServerDatabaseDiscovery() }) : AutoCloseable {
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(1))
    private val control = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(1))
    private var active: DiscoveryTask? = null
    private var generation = 0L
    private var busy = false
    private var closed = false
    @Synchronized fun scan(profile: ConnectionProfile, credentials: Credentials, done: (Result<DiscoveredDatabases>) -> Unit): Boolean {
        if (closed || busy) { credentials.close(); return false }
        busy = true
        val ticket = generation
        try { worker.execute {
            val result = try {
                credentials.use {
                    val task = factory()
                    task.use {
                        synchronized(this) { if (ticket != generation) throw SocketException("Cancelled"); active = task }
                        Result.success(task.scan(profile, credentials))
                    }
                }
            } catch (error: Exception) { Result.failure(error) } catch (error: LinkageError) { Result.failure(error) }
            synchronized(this) { active = null; busy = false; if (ticket == generation) done(result) }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { busy = false; credentials.close(); return false }
        return true
    }
    @Synchronized fun cancel() {
        generation++
        val task = active.also { active = null }
        if (task != null) try { control.execute { try { task.close() } catch (_: Exception) { } } }
        catch (_: java.util.concurrent.RejectedExecutionException) { /* Previous cancellation is still disposing this task. */ }
    }
    @Synchronized override fun close() { closed = true; cancel(); worker.shutdown(); control.shutdown() }
}
