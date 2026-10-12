package com.example.datasail.core

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException
import java.sql.SQLTimeoutException
import javax.net.ssl.SSLException

object Failures {
    fun classify(error: Throwable): SafeFailure {
        val chain = generateSequence(error) { it.cause }.take(10).toList()
        val sql = chain.filterIsInstance<SQLException>().firstOrNull()
        val state = sql?.sqlState?.takeIf { it.matches(Regex("[A-Z0-9]{5}")) }
        val kind = when {
            chain.any { it is SSLException || it is java.security.cert.CertificateException } -> ErrorKind.TLS
            // Connector/J 3.5.10 discards the SSLException cause for hostname mismatches.
            // Classify its fixed marker internally; never expose its message or host details.
            sql?.sqlState in setOf("08006", "08000") && sql?.message?.let {
                it.contains("SSL hostname verification failed :") || it.contains("Trying to connect with ssl, but ssl not enabled in the server")
            } == true -> ErrorKind.TLS
            chain.any { it is UnknownHostException } -> ErrorKind.DNS
            chain.any { it is SQLTimeoutException || it is SocketTimeoutException } -> ErrorKind.TIMEOUT
            sql?.sqlState == "08001" && sql.message == "Connection attempt timed out." -> ErrorKind.TIMEOUT
            state == "HYT00" || state == "HYT01" -> ErrorKind.TIMEOUT
            state == "HY008" || state == "70100" || state == "57014" -> ErrorKind.CANCELLED
            state?.startsWith("28") == true || sql?.errorCode in setOf(18452, 18456, 18487, 18488) -> ErrorKind.AUTHENTICATION
            state == "42501" || sql?.errorCode in setOf(229, 1142, 1143) -> ErrorKind.PERMISSION
            state == "0A000" -> ErrorKind.UNSUPPORTED
            chain.any { it is SQLFeatureNotSupportedException || it is LinkageError } -> ErrorKind.UNSUPPORTED
            chain.any { it is ConnectException } || state?.startsWith("08") == true -> ErrorKind.NETWORK
            sql != null -> ErrorKind.SQL
            else -> ErrorKind.INTERNAL
        }
        return SafeFailure(kind, state, sql?.errorCode)
    }
}

/** Closed vocabulary logger: no raw SQL, connection strings, messages or throwable objects. */
object SafeLog {
    fun event(state: ExecutionState, failure: SafeFailure? = null): String =
        "DataSail state=${state.name} category=${failure?.kind?.name ?: "NONE"} sqlState=${failure?.sqlState ?: "NONE"} code=${failure?.vendorCode ?: 0}"
}
