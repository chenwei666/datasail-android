package com.example.datasail.core

import org.bson.*

/** Whole document and every scalar BSON type must still match, using binary collation. */
internal object MongoWrites {
    fun document(text: String): BsonDocument {
        val doc = DocumentFilter.parse(text)
        fun validate(value: BsonValue) {
            when {
                value.isDocument -> value.asDocument().forEach { (key, child) ->
                    if (key.startsWith('$') || '.' in key || '\u0000' in key) throw WorkspaceRejected("带 $ 或点号的字段暂不支持图形编辑")
                    validate(child)
                }
                value.isArray -> value.asArray().forEach(::validate)
                value.isDouble && !value.asDouble().value.isFinite() -> throw WorkspaceRejected("非有限数值暂不支持编辑")
                value.bsonType !in setOf(BsonType.STRING, BsonType.INT32, BsonType.INT64, BsonType.DOUBLE, BsonType.DECIMAL128, BsonType.BOOLEAN, BsonType.NULL, BsonType.OBJECT_ID, BsonType.DATE_TIME, BsonType.BINARY, BsonType.TIMESTAMP) -> throw WorkspaceRejected("文档包含暂不支持安全比较的类型")
            }
        }
        validate(doc); return doc
    }
    fun predicate(original: BsonDocument): BsonDocument {
        if (!original.containsKey("_id")) throw WorkspaceRejected("文档缺少 _id")
        fun literal(value: BsonValue) = BsonDocument("\$literal", value)
        fun equal(left: BsonValue, right: BsonValue) = BsonDocument("\$eq", BsonArray(listOf(left, right)))
        val conditions = mutableListOf<BsonValue>(equal(BsonString("\$\$ROOT"), literal(original)))
        fun visit(value: BsonValue, expression: BsonValue) {
            if (conditions.size >= 200) throw WorkspaceRejected("文档字段过多，暂不支持安全编辑")
            conditions += equal(BsonDocument("\$type", expression), BsonDocument("\$type", literal(value)))
            if (value.isDocument) value.asDocument().forEach { (key, child) ->
                visit(child, BsonDocument("\$getField", BsonDocument("field", literal(BsonString(key))).append("input", expression)))
            } else if (value.isArray) value.asArray().forEachIndexed { index, child ->
                visit(child, BsonDocument("\$arrayElemAt", BsonArray(listOf(expression, BsonInt32(index)))))
            }
        }
        visit(original, BsonString("\$\$ROOT"))
        return BsonDocument("_id", original["_id"]).append("\$expr", BsonDocument("\$and", BsonArray(conditions)))
    }
}
