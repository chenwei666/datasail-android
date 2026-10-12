package com.example.datasail.core

import java.sql.Connection
import java.sql.Statement
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** One bounded worker owns the session. Cancellation invalidates all old callbacks and approvals. */
class WorkspaceRunner(private val connector: SqlConnector = JdbcConnector()) : AutoCloseable {
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(1))
    private val control = ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, ArrayBlockingQueue(4))
    private val statement = AtomicReference<Statement?>(null)
    private val connection = AtomicReference<Connection?>(null)
    private var session: SqlServerSession? = null // worker only
    private var generation = 0L
    private var busy = false
    private var closed = false

    fun connect(profile: ConnectionProfile, credentials: Credentials, done: (Result<Unit>) -> Unit): Boolean {
        val accepted = submit(done) {
            session?.close(); session = null
            val raw = credentials.use { connector.connect(profile, it) }
            connection.set(raw)
            try { session = SqlServerSession(raw, profile, statement::set) }
            catch (error: Throwable) { raw.close(); throw error }
        }
        // Accepted credentials are owned by the queued operation, never by UI state.
        if (!accepted) credentials.close()
        return accepted
    }

    fun <T> execute(done: (Result<T>) -> Unit, operation: (SqlServerSession) -> T): Boolean = submit(done) {
        val current = session ?: throw WorkspaceRejected("请先连接 SQL Server")
        if (current.isClosed()) throw WorkspaceRejected("连接已失效，请手动重新连接")
        operation(current)
    }

    @Synchronized private fun <T> submit(done: (Result<T>) -> Unit, operation: () -> T): Boolean {
        if (closed || busy) return false
        busy = true
        val ticket = generation
        try { worker.execute {
            val result = try { Result.success(operation()) } catch (error: Exception) { Result.failure(error) }
                catch (error: LinkageError) { Result.failure(error) }
            val stale = synchronized(this) { ticket != generation }
            val fatal = result.exceptionOrNull()?.let {
                Failures.classify(it).kind in setOf(ErrorKind.NETWORK, ErrorKind.TIMEOUT, ErrorKind.CANCELLED, ErrorKind.UNSUPPORTED)
            } == true
            if (stale || fatal) { session?.close(); session = null; connection.set(null) }
            synchronized(this) {
                busy = false
                if (ticket == generation) done(result)
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { busy = false; return false }
        return true
    }

    @Synchronized fun disconnect() {
        generation++
        val oldStatement = statement.getAndSet(null)
        val oldConnection = connection.getAndSet(null)
        control { try { oldStatement?.cancel() } catch (_: Exception) { } }
        control { try { oldConnection?.close() } catch (_: Exception) { } }
        // Ordered behind current work; a new connect cannot overtake disposal.
        try { worker.execute { session?.close(); session = null } }
        catch (_: java.util.concurrent.RejectedExecutionException) { }
    }
    private fun control(action: () -> Unit) {
        try { control.execute(action) } catch (_: java.util.concurrent.RejectedExecutionException) { }
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true; disconnect(); worker.shutdown(); control.shutdown()
    }
}
