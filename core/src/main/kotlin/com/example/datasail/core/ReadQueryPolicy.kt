package com.example.datasail.core

/** Conservative syntax gate, never an authorization boundary. Require a least-privilege account. */
object ReadQueryPolicy {
    private val forbidden = setOf("INTO", "INSERT", "UPDATE", "DELETE", "MERGE", "DROP", "ALTER", "CREATE",
        "TRUNCATE", "EXEC", "EXECUTE", "GRANT", "REVOKE", "DENY", "DBCC", "BACKUP", "RESTORE", "USE", "SET",
        "OPENROWSET", "OPENQUERY", "OPENDATASOURCE", "BULK", "NEXT", "WAITFOR", "SHUTDOWN", "KILL", "GO", "OUTFILE", "DUMPFILE", "LOAD_FILE", "GET_LOCK", "RELEASE_LOCK")
    fun validate(sql: String, kind: DatabaseKind = DatabaseKind.SQL_SERVER): String {
        if (!kind.isSql) throw WorkspaceRejected("此引擎不支持 SQL 查询")
        if (kind != DatabaseKind.SQL_SERVER && (sql.contains("\\") || sql.contains("/*!") || sql.contains("/*M!", true) || sql.contains('$')))
            throw WorkspaceRejected("本版不支持反斜线转义、可执行注释或美元引用，请使用标准 SQL 引用")
        val mysql = kind in setOf(DatabaseKind.MYSQL, DatabaseKind.MARIADB)
        if (sql.isBlank() || sql.length > 16000) throw WorkspaceRejected("请输入不超过 16000 字符的单条 SELECT")
        val tokens = mutableListOf<String>()
        var i = 0
        var ended = false
        while (i < sql.length) {
            val c = sql[i]
            when {
                c.isWhitespace() -> i++
                c == '-' && sql.getOrNull(i + 1) == '-' -> {
                    if (mysql && sql.getOrNull(i + 2)?.isWhitespace() == false) throw WorkspaceRejected("MySQL 行注释需要空白分隔")
                    i += 2; while (i < sql.length && sql[i] != '\n') i++
                }
                c == '/' && sql.getOrNull(i + 1) == '*' -> {
                    var depth = 1; i += 2
                    while (i < sql.length && depth > 0) {
                        if (sql.substring(i).startsWith("/*")) { depth++; i += 2 }
                        else if (sql.substring(i).startsWith("*/")) { depth--; i += 2 }
                        else i++
                    }
                    if (depth != 0) throw WorkspaceRejected("注释未结束")
                }
                ended -> throw WorkspaceRejected("本版只允许一条 SELECT，不支持批次或多语句")
                c == ';' -> { ended = true; i++ }
                c == '\'' || c == '"' || (c == '[' && kind == DatabaseKind.SQL_SERVER) || (c == '`' && mysql) -> {
                    val closing = if (c == '[') ']' else c
                    i++; var closed = false
                    while (i < sql.length) {
                        if (sql[i] == closing) {
                            i++
                            if (sql.getOrNull(i) == closing) i++ else { closed = true; break }
                        } else i++
                    }
                    if (!closed) throw WorkspaceRejected("字符串或标识符未闭合")
                }
                c.isLetter() || c == '_' -> {
                    val start = i++
                    while (i < sql.length && (sql[i].isLetterOrDigit() || sql[i] == '_')) i++
                    tokens += sql.substring(start, i).uppercase(java.util.Locale.ROOT)
                }
                c == '@' || c == ':' || c == '#' -> throw WorkspaceRejected("不支持变量赋值、CLR 调用或临时对象")
                else -> i++
            }
        }
        if (tokens.firstOrNull() != "SELECT" || tokens.any { it in forbidden })
            throw WorkspaceRejected("仅支持受限单条 SELECT；写操作请使用表格的逐次确认编辑")
        return sql.trim()
    }
}
