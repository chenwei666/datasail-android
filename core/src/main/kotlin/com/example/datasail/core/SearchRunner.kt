package com.example.datasail.core

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Independent capability path: a search session cannot execute SQL or accept mutation tokens. */
class SearchRunner : AutoCloseable {
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(1))
    private var session: HttpsSearchSession? = null
    private var generation = 0L
    private var busy = false
    private var closed = false
    @Synchronized fun connect(profile: ConnectionProfile, credentials: Credentials, done: (Result<Unit>) -> Unit): Boolean {
        val ticket = generation
        val accepted = submit(done) { credentials.use { owned ->
            val fresh = HttpsSearchSession(profile)
            synchronized(this) {
                if (ticket != generation) { fresh.close(); credentials.close(); throw java.net.SocketException("Cancelled") }
                session?.close(); session = fresh
            }
            fresh.login(owned)
        } }
        if (!accepted) credentials.close()
        return accepted
    }
    fun <T> execute(done: (Result<T>, Boolean) -> Unit, operation: (SearchSession) -> T): Boolean = submit({ result ->
        done(result, synchronized(this) { session?.isClosed() == false })
    }) { operation(synchronized(this) { session } ?: throw WorkspaceRejected("请先连接数据库")) }
    @Synchronized private fun <T> submit(done: (Result<T>) -> Unit, operation: () -> T): Boolean {
        if (closed || busy) return false
        busy = true
        val ticket = generation
        try { worker.execute {
            val result = try { Result.success(operation()) } catch (error: Exception) { Result.failure(error) }
                catch (error: LinkageError) { Result.failure(error) }
            synchronized(this) {
                if (ticket != generation || result.isFailure) { session?.close(); session = null }
                busy = false
                if (ticket == generation) done(result)
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { busy = false; return false }
        return true
    }
    @Synchronized fun disconnect() { generation++; session?.close(); session = null }
    @Synchronized override fun close() { closed = true; disconnect(); worker.shutdown() }
}
