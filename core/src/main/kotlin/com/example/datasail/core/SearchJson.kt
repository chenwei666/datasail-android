package com.example.datasail.core

/** Bounded strict JSON; numeric lexemes are never converted through Double. No executable extensions. */
internal sealed interface SearchJson {
    data class Obj(val fields: Map<String, SearchJson>) : SearchJson
    data class Arr(val values: List<SearchJson>) : SearchJson
    data class Str(val value: String) : SearchJson
    data class Atom(val raw: String) : SearchJson
    fun encode(): String = when (this) {
        is Obj -> fields.entries.joinToString(",", "{", "}") { Str(it.key).encode() + ":" + it.value.encode() }
        is Arr -> values.joinToString(",", "[", "]") { it.encode() }
        is Str -> buildString { append('"'); value.forEach { c -> when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        } }; append('"') }
        is Atom -> raw
    }
    companion object {
        fun parse(text: String): SearchJson = Parser(text).parse()
    }
}
private class Parser(private val text: String) {
    private var at = 0
    private var nodes = 0
    private fun reject(): Nothing = throw WorkspaceRejected("JSON 格式或大小不符合限制")
    fun parse(): SearchJson {
        if (text.length > 2_097_152) reject()
        val result = value(0); space(); if (at != text.length) reject(); return result
    }
    private fun space() { while (at < text.length && text[at] in " \n\t\r") at++ }
    private fun take(c: Char): Boolean { space(); if (at < text.length && text[at] == c) { at++; return true }; return false }
    private fun value(depth: Int): SearchJson {
        if (depth > 32 || ++nodes > 20_000) reject()
        space(); if (at >= text.length) reject()
        if (take('{')) {
            val fields = linkedMapOf<String, SearchJson>()
            if (!take('}')) do {
                space(); val key = string(); if (!take(':') || fields.containsKey(key)) reject()
                fields[key] = value(depth + 1)
                if (take('}')) break
                if (!take(',')) reject()
            } while (true)
            return SearchJson.Obj(fields)
        }
        if (take('[')) {
            val values = mutableListOf<SearchJson>()
            if (!take(']')) do {
                values += value(depth + 1)
                if (take(']')) break
                if (!take(',')) reject()
            } while (true)
            return SearchJson.Arr(values)
        }
        if (text[at] == '"') return SearchJson.Str(string())
        val start = at
        while (at < text.length && text[at] !in ",]} \r\n\t") at++
        val atom = text.substring(start, at)
        if (atom !in setOf("true", "false", "null") && !atom.matches(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))) reject()
        return SearchJson.Atom(atom)
    }
    private fun string(): String {
        if (at >= text.length || text[at++] != '"') reject()
        val result = StringBuilder()
        while (at < text.length) {
            val c = text[at++]
            if (c == '"') {
                val s = result.toString(); var i = 0
                while (i < s.length) { if (s[i].isHighSurrogate()) { if (++i >= s.length || !s[i].isLowSurrogate()) reject() } else if (s[i].isLowSurrogate()) reject(); i++ }
                return s
            }
            if (c < ' ') reject()
            if (c != '\\') { result.append(c); continue }
            if (at >= text.length) reject()
            result.append(when (val escape = text[at++]) {
                '"', '\\', '/' -> escape
                'b' -> '\b'; 'f' -> '\u000c'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                'u' -> { if (at + 4 > text.length) reject(); val hex = text.substring(at, at + 4); if (!hex.matches(Regex("[0-9a-fA-F]{4}"))) reject(); val n = hex.toInt(16); at += 4; n.toChar() }
                else -> reject()
            })
        }
        reject()
    }
}
internal fun SearchJson.obj() = (this as? SearchJson.Obj)?.fields ?: throw WorkspaceRejected("服务器 JSON 结构不受支持")
internal fun SearchJson.string() = (this as? SearchJson.Str)?.value ?: throw WorkspaceRejected("服务器 JSON 字段不受支持")

object SearchPolicy {
    fun validScope(value: String) = value.length in 1..128 && value.matches(Regex("[a-z0-9*][a-z0-9._*-]*"))
    internal fun validIndex(value: String) = value.length in 1..255 && value.matches(Regex("[a-z0-9][a-z0-9._-]*"))
    internal fun query(text: String): SearchJson {
        if (text.length !in 2..8000) throw WorkspaceRejected("查询条件限 2–8000 字符")
        val parsed = SearchJson.parse(text)
        clause(parsed, 0); return parsed
    }
    private fun clause(value: SearchJson, depth: Int) {
        fun reject(): Nothing = throw WorkspaceRejected("仅支持 match_all、term、match、range 和 bool 查询条件；不执行脚本或聚合")
        if (depth > 8) reject()
        val objectValue = value.obj(); if (objectValue.size != 1) reject()
        val (op, arg) = objectValue.entries.single(); val fields = arg.obj()
        fun scalar(v: SearchJson) = v is SearchJson.Str || v is SearchJson.Atom && v.raw != "null"
        when (op) {
            "match_all" -> if (fields.isNotEmpty()) reject()
            "term", "match", "range" -> {
                if (fields.size != 1) reject()
                val (field, term) = fields.entries.single()
                if (!field.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}"))) reject()
                if (op == "range") { val range = term.obj(); if (range.isEmpty() || range.keys.any { it !in setOf("gt", "gte", "lt", "lte") } || range.values.any { !scalar(it) }) reject() }
                else if (!scalar(term)) reject()
            }
            "bool" -> {
                if (fields.isEmpty() || fields.keys.any { it !in setOf("must", "filter", "should", "must_not") }) reject()
                fields.values.forEach { nested -> if (nested is SearchJson.Arr) { if (nested.values.isEmpty() || nested.values.size > 20) reject(); nested.values.forEach { clause(it, depth + 1) } } else clause(nested, depth + 1) }
            }
            else -> reject()
        }
    }
}
