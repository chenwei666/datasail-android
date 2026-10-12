package com.example.datasail.core

import java.math.BigDecimal

enum class DatabaseKind(val label: String, val defaultPort: Int) {
    SQL_SERVER("SQL Server", 1433), MYSQL("MySQL（MariaDB 驱动）", 3306)
}
enum class TlsPolicy { VERIFY_IDENTITY }
enum class ConnectionEnvironment(val label: String) { TEST("测试"), PRODUCTION("生产（本版只读）") }
class TrustStoreSpec(val path: String, val type: String = "PKCS12") {
    override fun toString() = "TrustStoreSpec([LOCAL CA])"
}
enum class ProbeMode(val label: String) {
    BASIC("连接 / TLS / 类型"), TIMEOUT("查询超时（约 3 秒）"), CANCEL("取消（约 1 秒后请求）")
}
enum class ExecutionState { IDLE, CONNECTING, RUNNING, CANCELLING, SUCCEEDED, FAILED, UNKNOWN }
enum class ErrorKind { DNS, NETWORK, TLS, AUTHENTICATION, PERMISSION, TIMEOUT, CANCELLED, SQL, UNSUPPORTED, INTERNAL }
enum class CheckStatus { PASSED, FAILED, NOT_RUN, UNKNOWN }

/** Deliberately not a data class: profile details must not leak via generated toString. */
class ConnectionProfile(
    val kind: DatabaseKind,
    val host: String,
    val port: Int,
    val database: String,
    val username: String,
    val tls: TlsPolicy = TlsPolicy.VERIFY_IDENTITY,
    val environment: ConnectionEnvironment = ConnectionEnvironment.TEST,
    val trustStore: TrustStoreSpec? = null,
) {
    init {
        require(host.length in 1..253 && host.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?"))) { "主机仅支持 DNS 名称或 IPv4，不可填连接串" }
        require(port in 1..65535) { "端口范围为 1–65535" }
        require(database.length in 1..128 && database.all { it.isLetterOrDigit() || it == '_' || it == '-' }) { "技术验证版库名仅支持字母、数字、下划线和连字符" }
        require(username.isNotBlank() && username.length <= 128) { "请填写有效用户名" }
    }
    override fun toString() = "ConnectionProfile(${kind.name}, details=[REDACTED])"
}

/** Memory only. Never persisted or included in UI state, saved state, logs, or backup. */
class Credentials(password: CharArray) : AutoCloseable {
    private val secret = password.copyOf()
    private var closed = false
    @Synchronized fun <T> usePassword(block: (String) -> T): T {
        check(!closed) { "Credentials closed" }
        return block(String(secret)) // JDBC requires a String; JVM cannot guarantee erasure of it.
    }
    @Synchronized override fun close() { secret.fill('\u0000'); closed = true }
    override fun toString() = "Credentials([REDACTED])"
}

sealed interface DbValue {
    data object Null : DbValue
    data class Text(val value: String) : DbValue
    data class Integer(val value: Long) : DbValue
    data class Decimal(val value: BigDecimal) : DbValue
    data class Bool(val value: Boolean) : DbValue
    data class Temporal(val value: String) : DbValue
    data class Approximate(val value: String) : DbValue
    data class Unavailable(val reason: String) : DbValue
    class Binary(bytes: ByteArray) : DbValue {
        private val bytes = bytes.copyOf()
        fun copyBytes() = bytes.copyOf()
        override fun toString() = "Binary(${bytes.size} bytes)"
    }
}
data class SqlCapabilities(val fixedReadOnlyProbes: Boolean = true, val arbitrarySql: Boolean = false,
    val editing: Boolean = false, val cancellationRequested: Boolean = true, val verifiedOnAndroid: Boolean = false)
data class ProbeCheck(val name: String, val status: CheckStatus, val detail: String)
data class SafeFailure(val kind: ErrorKind, val sqlState: String? = null, val vendorCode: Int? = null) {
    val description: String get() = when (kind) {
        ErrorKind.DNS -> "域名解析失败"
        ErrorKind.NETWORK -> "网络或端口不可达"
        ErrorKind.TLS -> "TLS 握手或证书身份校验失败；未降级为明文"
        ErrorKind.AUTHENTICATION -> "认证失败，请核对测试账号"
        ErrorKind.PERMISSION -> "权限不足"
        ErrorKind.TIMEOUT -> "操作超时，服务端执行结果需要核对"
        ErrorKind.CANCELLED -> "驱动报告已取消；仍需核对服务端状态"
        ErrorKind.SQL -> "数据库返回 SQL 错误"
        ErrorKind.UNSUPPORTED -> "当前驱动或运行时不支持此能力"
        ErrorKind.INTERNAL -> "客户端错误，未记录敏感异常文本"
    }
}
data class ProbeReport(val state: ExecutionState, val checks: List<ProbeCheck>, val failure: SafeFailure? = null)

// AI has no session, connector, credentials, or execution capability.
interface AiDraftProvider { suspend fun draft(request: ApprovedAiContext): SqlDraft }
data class ApprovedAiContext(val dialect: DatabaseKind, val userApprovedText: String)
data class SqlDraft(val sql: String, val explanation: String)
interface DocumentConnector
interface KeyValueConnector
interface SearchConnector
