package com.example.datasail.core

import java.math.BigDecimal
import java.sql.Connection
import java.sql.Statement
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One session, one worker, bounded cancellation workers. Nothing accepts arbitrary SQL. */
class ProbeRunner(private val connector: SqlConnector = JdbcConnector()) : AutoCloseable {
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(1))
    private val control = ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, ArrayBlockingQueue(4))
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val active = AtomicReference<Run?>(null)
    private val closed = AtomicBoolean(false)
    private class Run(val update: (ExecutionState) -> Unit) {
        var finished = false // guarded by the runner's monitor
        val cancelled = AtomicBoolean(false)
        val statement = AtomicReference<Statement?>(null)
        val connection = AtomicReference<Connection?>(null)
    }

    @Synchronized fun start(profile: ConnectionProfile, credentials: Credentials, mode: ProbeMode,
        update: (ExecutionState) -> Unit, complete: (ProbeReport) -> Unit): Boolean {
        if (closed.get()) { credentials.close(); return false }
        val run = Run(update)
        if (!active.compareAndSet(null, run)) { credentials.close(); return false }
        update(ExecutionState.CONNECTING)
        worker.execute {
            val checks = mutableListOf<ProbeCheck>()
            var failure: SafeFailure? = null
            var state = ExecutionState.FAILED
            var cancellationTimer: ScheduledFuture<*>? = null
            try {
                val connection = connector.connect(profile, credentials)
                run.connection.set(connection)
                checks += ProbeCheck("连接", CheckStatus.PASSED, "驱动登录完成；仅限本次输入的测试目标")
                checkCancelled(run)
                synchronized(this@ProbeRunner) {
                    checkCancelled(run)
                    update(ExecutionState.RUNNING)
                }
                connection.createStatement().use { statement ->
                    run.statement.set(statement)
                    checkCancelled(run)
                    statement.queryTimeout = 3
                    statement.maxRows = 2
                    // Driver identity verification is mandatory. Negative-certificate tests are separate.
                    checks += ProbeCheck("TLS 策略", CheckStatus.PASSED, "已按强制加密及主机名/证书校验策略登录；错误证书反例尚需独立验证")
                    if (mode == ProbeMode.BASIC) {
                        basic(profile.kind, statement, checks)
                    } else {
                        if (mode == ProbeMode.CANCEL) {
                            statement.queryTimeout = 12
                            cancellationTimer = timer.schedule({ cancel(run) }, 1, TimeUnit.SECONDS)
                        }
                        val started = System.nanoTime()
                        try {
                            val sleepInterrupted = statement.executeQuery(delaySql(profile.kind)).use { result ->
                                profile.kind in setOf(DatabaseKind.MYSQL, DatabaseKind.MARIADB) && result.next() && result.getInt(1) == 1
                            }
                            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                            // MySQL SLEEP returns 1 on interruption when it is the only expression;
                            // it need not throw SQLTimeoutException. Normal completion returns 0.
                            if (mode == ProbeMode.TIMEOUT && sleepInterrupted && elapsed in 2000..8000) {
                                checks += ProbeCheck("查询超时", CheckStatus.PASSED, "${elapsed}ms 后 MySQL SLEEP 返回中断标记 1；不推广为任意 SQL 的回滚保证")
                            } else checks += ProbeCheck(mode.label, CheckStatus.FAILED, "延迟查询正常返回，未观察到预期中断")
                        } catch (error: Exception) {
                            val classified = Failures.classify(error)
                            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                            if (mode == ProbeMode.TIMEOUT && classified.kind == ErrorKind.TIMEOUT && elapsed in 2000..8000) {
                                checks += ProbeCheck("查询超时", CheckStatus.PASSED, "${elapsed}ms 后驱动报告超时；这不证明服务端已停止")
                            } else throw error
                        }
                    }
                    checkCancelled(run)
                    state = if (checks.any { it.status == CheckStatus.FAILED }) ExecutionState.FAILED else ExecutionState.SUCCEEDED
                }
            } catch (_: CancelRequested) {
                state = ExecutionState.UNKNOWN
            } catch (error: Exception) {
                failure = Failures.classify(error)
                state = if (run.cancelled.get() || failure.kind in setOf(ErrorKind.TIMEOUT, ErrorKind.NETWORK, ErrorKind.CANCELLED)) ExecutionState.UNKNOWN else ExecutionState.FAILED
            } catch (error: LinkageError) {
                failure = Failures.classify(error)
                state = ExecutionState.FAILED
            } finally {
                cancellationTimer?.cancel(false)
                run.statement.set(null)
                val connection = run.connection.getAndSet(null)
                if (connection != null) {
                    try { connection.close(); checks += ProbeCheck("断开", CheckStatus.PASSED, "客户端连接已关闭") }
                    catch (_: Exception) {
                        checks += ProbeCheck("断开", CheckStatus.UNKNOWN, "连接关闭异常，服务端状态未知")
                        state = ExecutionState.UNKNOWN
                    }
                }
                credentials.close()
                synchronized(this@ProbeRunner) {
                    run.finished = true
                    if (run.cancelled.get()) {
                        state = ExecutionState.UNKNOWN
                        checks += ProbeCheck("取消", CheckStatus.UNKNOWN, "已请求 Statement.cancel 和连接关闭；执行结果未知，需核对服务端")
                    }
                    active.compareAndSet(run, null)
                    complete(ProbeReport(state, checks.toList(), failure))
                }
            }
        }
        return true
    }

    fun cancel() { active.get()?.let(::cancel) }
    private fun cancel(run: Run) {
        synchronized(this) {
            if (run.finished || active.get() !== run || !run.cancelled.compareAndSet(false, true)) return
            run.update(ExecutionState.CANCELLING)
        }
        // Separate workers: a stuck Statement.cancel cannot prevent scheduling Connection.close.
        val statement = run.statement.get()
        val connection = run.connection.get()
        submitControl { try { statement?.cancel() } catch (_: Exception) { /* report remains UNKNOWN */ } }
        submitControl { try { connection?.close() } catch (_: Exception) { /* report remains UNKNOWN */ } }
    }
    private fun submitControl(action: () -> Unit) {
        try { control.execute(action) } catch (_: java.util.concurrent.RejectedExecutionException) { /* bounded; socket timeout remains */ }
    }
    private fun checkCancelled(run: Run) { if (run.cancelled.get()) throw CancelRequested() }
    private class CancelRequested : Exception()

    @Synchronized override fun close() {
        if (!closed.compareAndSet(false, true)) return
        cancel()
        worker.shutdown()
        timer.shutdownNow()
        control.shutdown()
    }

    companion object {
        private const val UNICODE = "数舟 · Tiếng Việt · 00123"
        private val DECIMAL = BigDecimal("1234567890123456.12345678")
        internal fun delaySql(kind: DatabaseKind) = when (kind) {
            DatabaseKind.SQL_SERVER -> "WAITFOR DELAY '00:00:10'; SELECT 1"
            DatabaseKind.MYSQL, DatabaseKind.MARIADB -> "SELECT SLEEP(10)"
            DatabaseKind.POSTGRESQL -> "SELECT pg_sleep(10)"
            DatabaseKind.ELASTICSEARCH, DatabaseKind.OPENSEARCH -> throw WorkspaceRejected("搜索引擎使用独立工作台")
            DatabaseKind.MONGODB -> throw WorkspaceRejected("文档引擎使用独立工作台")
            DatabaseKind.REDIS, DatabaseKind.VALKEY -> throw WorkspaceRejected("键值引擎使用独立工作台")
            DatabaseKind.SQLITE -> throw WorkspaceRejected("SQLite 不提供网络探针")
        }
        private fun basic(kind: DatabaseKind, statement: Statement, checks: MutableList<ProbeCheck>) {
            val text = if (kind == DatabaseKind.SQL_SERVER) "N'$UNICODE'" else "'$UNICODE'"
            val sql = "SELECT 1 AS one_value, $text AS text_value, NULL AS null_value, CAST(1234567890123456.12345678 AS DECIMAL(24,8)) AS decimal_value"
            statement.executeQuery(sql).use { rows ->
                check(rows.next()) { "Probe returned no row" }
                val one = DbValue.Integer(rows.getLong(1))
                val unicode = DbValue.Text(rows.getString(2))
                val nullable = if (rows.getObject(3) == null) DbValue.Null else DbValue.Text("unexpected")
                val decimal = DbValue.Decimal(rows.getBigDecimal(4))
                listOf("SELECT 1" to (one.value == 1L), "Unicode / 前导零" to (unicode.value == UNICODE),
                    "NULL" to (nullable == DbValue.Null), "精确小数" to (decimal.value == DECIMAL)).forEach { (name, passed) ->
                    checks += ProbeCheck(name, if (passed) CheckStatus.PASSED else CheckStatus.FAILED, if (passed) "合成值精确匹配" else "返回类型或值与预期不符")
                }
            }
        }
    }
}
