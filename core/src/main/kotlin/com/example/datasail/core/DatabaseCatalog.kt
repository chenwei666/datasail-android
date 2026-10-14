package com.example.datasail.core

/** Product scope is deliberately separate from selectable, implemented connection types. */
data class DatabaseTarget(val name: String, val family: String, val connectionKind: DatabaseKind? = null) {
    val capability: String get() = when (connectionKind) {
        DatabaseKind.SQL_SERVER -> "浏览、查询、单行编辑；整表删除另行确认"
        DatabaseKind.MYSQL, DatabaseKind.MARIADB, DatabaseKind.POSTGRESQL -> "浏览、查询、受限单行增删改"
        DatabaseKind.SQLITE -> "导入副本、浏览查询、受限单行增删改"
        DatabaseKind.REDIS, DatabaseKind.VALKEY -> "键扫描、类型、TTL；完整字符串增删改"
        DatabaseKind.MONGODB -> "集合、筛选、分页；完整单文档增删改"
        DatabaseKind.ELASTICSEARCH, DatabaseKind.OPENSEARCH -> "索引、映射、DSL 分页；版本校验单文档增删改"
        null -> "规划中，尚不可连接"
    }
}
object DatabaseCatalog {
    val targets: List<DatabaseTarget> = DatabaseKind.entries.map {
        DatabaseTarget(it.label, if (it.isSearch) "搜索" else if (it.isDocument) "文档" else if (it.isKeyValue) "键值" else if (it == DatabaseKind.SQLITE) "文件数据库" else "关系型", it)
    } + listOf(
        "关系型" to listOf("Oracle Database", "IBM Db2", "SAP HANA", "SAP ASE / Sybase", "Firebird", "Informix", "Teradata", "InterBase"),
        "兼容协议与国产数据库（需独立验收）" to listOf("TiDB", "OceanBase", "CockroachDB", "YugabyteDB", "Amazon Aurora", "Amazon Redshift", "Greenplum", "openGauss", "GaussDB", "达梦 DM", "人大金仓 KingbaseES", "PolarDB", "腾讯 TDSQL"),
        "文档" to listOf("Amazon DocumentDB", "CouchDB", "Couchbase", "Azure Cosmos DB"),
        "键值与宽列" to listOf("KeyDB", "Memcached", "Apache Cassandra", "ScyllaDB", "HBase", "Amazon DynamoDB"),
        "搜索" to listOf("Apache Solr"),
        "分析与数据仓库" to listOf("ClickHouse", "DuckDB", "Apache Doris", "StarRocks", "Trino", "Presto", "Apache Hive", "Snowflake", "Google BigQuery", "Databricks SQL", "Vertica"),
        "时序" to listOf("InfluxDB", "TimescaleDB", "QuestDB", "TDengine", "Apache IoTDB"),
        "图与多模型" to listOf("Neo4j", "NebulaGraph", "ArangoDB", "OrientDB"),
        "文件数据库" to listOf("H2", "HSQLDB", "Apache Derby", "Microsoft Access")
    ).flatMap { (family, names) -> names.map { DatabaseTarget(it, family) } }
}
