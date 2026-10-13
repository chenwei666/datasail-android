package com.example.datasail.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.sql.SQLException
import java.util.Properties

/** Must run once per actual server, including MariaDB separately from MySQL. */
class JdbcWorkspaceIntegrationTest {
    private val config = Properties().apply { File(System.getProperty("datasail.test.config")).inputStream().use(::load) }
    private val kind = DatabaseKind.valueOf(config.getProperty("kind"))
    private val pg = kind == DatabaseKind.POSTGRESQL
    private fun profile(host: String = "localhost", trusted: Boolean = true) = ConnectionProfile(kind, host,
        config.getProperty("port").toInt(), "synthetic", "datasail", trustStore = if (trusted) TrustStoreSpec(System.getProperty("datasail.test.trust")) else null)
    private fun connect(profile: ConnectionProfile = profile()) = Credentials(config.getProperty("password").toCharArray()).use { JdbcConnector().connect(profile, it) }
    private fun fixture(block: (JdbcBrowseSession, TableRef) -> Unit) {
        connect().use { raw ->
            raw.createStatement().use { statement ->
                statement.execute("DROP TABLE IF EXISTS workbench_fixture")
                statement.execute("CREATE TABLE workbench_fixture (id BIGINT PRIMARY KEY, code VARCHAR(40), amount DECIMAL(20,4), note VARCHAR(4096), moment TIMESTAMP NULL)")
            }
            raw.prepareStatement("INSERT INTO workbench_fixture VALUES (?,?,?,?,NULL)").use { statement ->
                for (i in 1..105) {
                    statement.setLong(1, i.toLong()); statement.setString(2, if (i == 1) "0007 中文 🚢" else "")
                    statement.setBigDecimal(3, java.math.BigDecimal("12345678901234.5678"))
                    statement.setString(4, if (i == 1) "x".repeat(3000) else null); statement.addBatch()
                }
                statement.executeBatch()
            }
            JdbcBrowseSession(raw, profile()).use { session ->
                val schema = if (pg) "public" else "synthetic"
                block(session, session.tables(schema).single { it.name == "workbench_fixture" })
            }
        }
    }
    @Test fun wideTableWindowsReachLastColumnWithExactType() {
        connect().use { raw ->
            raw.createStatement().use { statement ->
                statement.execute("CREATE TABLE wide_columns_fixture(id BIGINT PRIMARY KEY," + (1..129).joinToString(",") { if (it == 129) "c$it VARCHAR(30)" else "c$it INTEGER" } + ")")
                statement.execute("INSERT INTO wide_columns_fixture(id,c129) VALUES(1,'last-column-0007')")
            }
            JdbcBrowseSession(raw, profile()).use { session ->
                val table = session.tables(if (pg) "public" else "synthetic").single { it.name == "wide_columns_fixture" }
                val last = session.page(table, 0, 128)
                assertEquals(130, last.totalColumns); assertEquals(128, last.columnOffset)
                assertEquals(listOf("c128", "c129"), last.columns.map { it.name })
                assertEquals(DbValue.Text("last-column-0007"), last.rows.single()[1])
            }
        }
    }
    @Test fun browsingPreservesTypesAndPaginationAndClosesTransactions() = fixture { session, table ->
        com.example.datasail.fixture.DiscoveryContract.verify(profile(), config.getProperty("password"))
        assertTrue(session.catalogs().contains("synthetic"))
        val description = session.describe(table)
        assertEquals(listOf("id"), description.keys.map { it.name })
        assertFalse(description.columns.single { it.name == "moment" }.rowVersion)
        assertNotNull(description.readOnlyReason)
        val page = session.page(table)
        assertEquals(100, page.rows.size); assertTrue(page.hasMore)
        assertEquals(DbValue.Text("0007 中文 🚢"), page.rows[0][1])
        assertEquals(DbValue.Decimal(java.math.BigDecimal("12345678901234.5678")), page.rows[0][2])
        assertTrue(page.rows[0][3] is DbValue.Unavailable)
        assertEquals(DbValue.Null, page.rows[1][3]); assertEquals(DbValue.Null, page.rows[0][4])
        val second = session.page(table, 100)
        assertEquals(DbValue.Integer(101), second.rows[0][0]); assertEquals(5, second.rows.size); assertFalse(second.hasMore)
        assertEquals(DbValue.Integer(105), session.select("SELECT COUNT(*) FROM workbench_fixture").rows.single().single())
        assertFalse(session.writable)
        assertThrows(WorkspaceRejected::class.java) { session.setWritable(true) }
        connect().use { other -> other.createStatement().use { it.executeUpdate("UPDATE workbench_fixture SET code='updated' WHERE id=1") } }
        assertEquals(DbValue.Text("updated"), session.page(table).rows.first()[1])
    }
    @Test fun rejectsWrongHostnameUntrustedCaAndWrongPassword() {
        for (p in listOf(profile("127.0.0.1"), profile(trusted = false))) {
            val failure = assertThrows(SQLException::class.java) { connect(p).close() }
            assertEquals(ErrorKind.TLS, Failures.classify(failure).kind)
        }
        val failure = assertThrows(SQLException::class.java) {
            Credentials("wrong-synthetic-password".toCharArray()).use { JdbcConnector().connect(profile(), it).close() }
        }
        assertEquals(ErrorKind.AUTHENTICATION, Failures.classify(failure).kind)
    }
    @Test fun serverEnforcesReadonlyBeyondSyntaxAndCannotPoisonNextTransaction() {
        if (pg) {
            connect().use { raw -> raw.createStatement().use {
                it.execute("CREATE TABLE IF NOT EXISTS readonly_guard (id INT)")
                it.execute("CREATE OR REPLACE FUNCTION datasail_try_write() RETURNS INT LANGUAGE plpgsql AS 'BEGIN INSERT INTO readonly_guard VALUES (1); RETURN 1; END'")
            } }
            connect().use { raw -> JdbcBrowseSession(raw, profile()).use { session ->
                session.select("SELECT set_config('default_transaction_read_only','off',false)")
                val failure = assertThrows(SQLException::class.java) { session.select("SELECT datasail_try_write()") }
                assertEquals("25006", failure.sqlState)
                assertEquals(DbValue.Integer(0), session.select("SELECT COUNT(*) FROM readonly_guard").rows.single().single())
            } }
        } else fixture { session, _ ->
            assertEquals(DbValue.Integer(1), session.select("SELECT 1").rows.single().single())
            // Syntax cannot be the only guard: a raw write on the same session is refused by the server.
            connect().use { raw -> JdbcBrowseSession(raw, profile()).use {
                val failure = assertThrows(SQLException::class.java) { raw.createStatement().use { it.executeUpdate("UPDATE workbench_fixture SET code='bad'") } }
                assertEquals(1792, failure.errorCode)
                raw.rollback()
            } }
            assertEquals(DbValue.Decimal(java.math.BigDecimal("18446744073709551615")), session.select("SELECT CAST('18446744073709551615' AS UNSIGNED)").rows.single().single())
        }
    }
    @Test fun unusualIdentifiersAreQuotedAndExactMetadataDoesNotMatchWildcards() {
        val name = "odd_%\"`table"
        val column = "x\"`value"
        fun quote(value: String): String { val q = if (pg) "\"" else "`"; return q + value.replace(q, q + q) + q }
        connect().use { raw ->
            raw.createStatement().use { statement ->
                statement.execute("CREATE TABLE ${quote(name)} (${quote(column)} VARCHAR(12))")
                statement.execute("INSERT INTO ${quote(name)} VALUES ('0007')")
            }
            JdbcBrowseSession(raw, profile()).use { session ->
                val table = session.tables(if (pg) "public" else "synthetic").single { it.name == name }
                val page = session.page(table)
                assertEquals(column, page.columns.single().name)
                assertEquals(DbValue.Text("0007"), page.rows.single().single())
                if (pg) {
                    assertTrue(session.select("SELECT CAST(REPEAT('9',3000) AS NUMERIC)").rows.single().single() is DbValue.Unavailable)
                    assertEquals(DbValue.Text("NaN"), session.select("SELECT CAST('NaN' AS NUMERIC)").rows.single().single())
                }
            }
        }
    }
    @Test fun rejectsWriteSyntaxAndExecutableComments() = fixture { session, _ ->
        for (sql in listOf("DELETE FROM workbench_fixture", "SELECT 1; DELETE FROM workbench_fixture", "SELECT 1 /*! INTO OUTFILE '/tmp/never' */")) {
            assertThrows(WorkspaceRejected::class.java) { session.select(sql) }
        }
        assertEquals(DbValue.Integer(105), session.select("SELECT COUNT(*) FROM workbench_fixture").rows.single().single())
    }
}
