package com.example.datasail.core

import java.io.File
import java.math.BigDecimal
import java.util.Properties
import org.junit.Assert.*
import org.junit.Test

class SqlServerIntegrationTest {
    @Test fun temporalValuesPreservePrecisionWithoutDesktopOnlyApis() {
        connect().use { connection ->
            com.example.datasail.fixture.SqlServerTemporalContract.createTable(connection)
            try { SqlServerSession(connection, profile()).use { session ->
                com.example.datasail.fixture.SqlServerTemporalContract.verify(session)
                com.example.datasail.fixture.SqlServerTemporalContract.verifyPage(session.page(session.tables("dbo").single { it.name == "ui_temporal_v091" }))
            } } finally { connect().use { it.createStatement().use { s -> s.execute("DROP TABLE IF EXISTS dbo.ui_temporal_v091") } } }
        }
    }
    private val config = Properties().apply { File(System.getProperty("datasail.sqlserver.config")).inputStream().use(::load) }
    private fun profile(host: String = "localhost", trusted: Boolean = true) = ConnectionProfile(DatabaseKind.SQL_SERVER,
        host, config.getProperty("port").toInt(), "master", "sa", trustStore = if (trusted) TrustStoreSpec(System.getProperty("datasail.sqlserver.trust")) else null)
    private fun connect(p: ConnectionProfile = profile()) = Credentials(config.getProperty("password").toCharArray()).use { JdbcConnector().connect(p, it) }
    @Test fun bufferedResponseIsRejectedByDriverBudget() {
        connect().use { c -> c.createStatement().use { statement ->
            statement.queryTimeout = 10
            // maxResultBuffer bounds retained driver buffers, not bytes consumed by an adaptive stream.
            statement.unwrap(com.microsoft.sqlserver.jdbc.SQLServerStatement::class.java).setResponseBuffering("full")
            val failure = assertThrows(java.sql.SQLException::class.java) {
                statement.executeQuery("SELECT REPLICATE(CAST(N'x' AS nvarchar(max)), 5000000)").use { rows ->
                    assertTrue(rows.next()); rows.getString(1)
                }
            }
            assertTrue("driver budget must cause rejection", failure.message.orEmpty().contains("maxResultBuffer", ignoreCase = true))
        } }
    }
    private fun fixture(wrap: (java.sql.Connection) -> java.sql.Connection = { it }, block: (SqlServerSession, TableRef) -> Unit) {
        connect().use { connection ->
            connection.createStatement().use { s ->
                s.execute("IF DB_ID('datasail_v020') IS NULL CREATE DATABASE datasail_v020")
                connection.catalog = "datasail_v020"
                s.execute("DROP TABLE IF EXISTS dbo.records")
                s.execute("CREATE TABLE dbo.records(id bigint IDENTITY PRIMARY KEY, label nvarchar(120) NOT NULL, amount decimal(24,8) NULL, active bit NOT NULL DEFAULT 1, version rowversion)")
                s.execute("INSERT dbo.records(label,amount) VALUES (N'00123 · 数舟 · Tiếng Việt',1234567890123456.12345678)")
            }
            SqlServerSession(wrap(connection), profile()).use { session ->
                session.switchCatalog("datasail_v020")
                block(session, session.tables("dbo").single { it.name == "records" })
            }
        }
    }
    @Test fun wideTableLastWindowIsVisibleAndCannotBeEdited() {
        connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP TABLE IF EXISTS dbo.wide_ai_fixture")
                statement.execute("CREATE TABLE dbo.wide_ai_fixture(id int PRIMARY KEY," + (1..129).joinToString(",") { "c$it nvarchar(40)" } + ")")
                statement.execute("INSERT dbo.wide_ai_fixture(id,c129) VALUES(1,N'last-column-0007')")
            }
            try { SqlServerSession(connection, profile()).use { session ->
                val table = session.tables("dbo").single { it.name == "wide_ai_fixture" }
                val page = session.page(table, 0, 128)
                assertEquals(130, page.totalColumns); assertEquals(listOf("c128", "c129"), page.columns.map { it.name })
                assertEquals(DbValue.Text("last-column-0007"), page.rows.single()[1])
                session.setWritable(true)
                assertThrows(WorkspaceRejected::class.java) { session.preview(MutationKind.UPDATE, 0, mapOf("c129" to CellInput("changed"))) }
            } } finally { connect().use { it.createStatement().use { statement -> statement.execute("DROP TABLE IF EXISTS dbo.wide_ai_fixture") } } }
        }
    }
    @Test fun browseTypesAndSafeWrites() = fixture { s, table ->
        com.example.datasail.fixture.DiscoveryContract.verify(profile(), config.getProperty("password"), "datasail_v020")
        assertTrue("datasail_v020" in s.catalogs()); assertTrue("dbo" in s.schemas())
        val page = s.page(table)
        assertEquals(DbValue.Text("00123 · 数舟 · Tiếng Việt"), page.rows[0][1])
        assertEquals(DbValue.Decimal(BigDecimal("1234567890123456.12345678")), page.rows[0][2])
        assertNull(page.tableSchema!!.readOnlyReason)
        s.setWritable(true)
        val update = s.preview(MutationKind.UPDATE, 0, mapOf("label" to CellInput("O'Brien; DROP TABLE x --")))
        assertFalse(update.sql.contains("O'Brien"))
        assertEquals(MutationStatus.COMMITTED, s.confirm(update.token).status)
        assertThrows(WorkspaceRejected::class.java) { s.confirm(update.token) }
        assertEquals(DbValue.Text("O'Brien; DROP TABLE x --"), s.page(table).rows[0][1])
        val insert = s.preview(MutationKind.INSERT, null, mapOf("label" to CellInput(""), "amount" to CellInput(isNull = true)))
        assertEquals(MutationStatus.COMMITTED, s.confirm(insert.token).status)
        val inserted = s.page(table); assertEquals(2, inserted.rows.size)
        assertEquals(DbValue.Text(""), inserted.rows[1][1]); assertEquals(DbValue.Null, inserted.rows[1][2])
        val delete = s.preview(MutationKind.DELETE, 1, emptyMap())
        assertEquals(MutationStatus.COMMITTED, s.confirm(delete.token).status)
        assertEquals(1, s.page(table).rows.size)
    }
    @Test fun conflictDoesNotOverwriteConcurrentUpdate() = fixture { s, table ->
        s.page(table); s.setWritable(true)
        val change = s.preview(MutationKind.UPDATE, 0, mapOf("label" to CellInput("stale")))
        connect().use { c -> c.createStatement().use { it.executeUpdate("UPDATE datasail_v020.dbo.records SET label=N'concurrent'") } }
        assertEquals(MutationStatus.CONFLICT, s.confirm(change.token).status)
        assertEquals(DbValue.Text("concurrent"), s.page(table).rows[0][1])
    }
    @Test fun queryAndSwitchInvalidateApprovalAndQueryIsReadOnly() = fixture { s, table ->
        s.page(table); s.setWritable(true)
        val change = s.preview(MutationKind.DELETE, 0, emptyMap())
        s.select("SELECT 1 AS one_value")
        assertThrows(WorkspaceRejected::class.java) { s.confirm(change.token) }
        assertThrows(WorkspaceRejected::class.java) { s.preview(MutationKind.DELETE, 0, emptyMap()) }
        s.page(table)
        val next = s.preview(MutationKind.DELETE, 0, emptyMap())
        s.switchCatalog("master")
        assertFalse(s.writable)
        assertThrows(WorkspaceRejected::class.java) { s.confirm(next.token) }
    }
    @Test fun boundedPagingAndUnsafeObjectsReadOnly() = fixture { s, table ->
        connect().use { c -> c.createStatement().use {
            it.execute("INSERT datasail_v020.dbo.records(label) SELECT TOP (205) N'page' FROM sys.all_objects")
            it.execute("DROP TABLE IF EXISTS datasail_v020.dbo.no_key; CREATE TABLE datasail_v020.dbo.no_key(v int)")
        } }
        val first = s.page(table); assertEquals(100, first.rows.size); assertTrue(first.hasMore)
        val next = s.page(table, 100); assertEquals(100, next.rows.size)
        assertNotEquals(first.rows[0][0], next.rows[0][0])
        assertEquals(6, s.page(table, 200).rows.size)
        val noKey = s.tables("dbo").single { it.name == "no_key" }
        assertNotNull(s.page(noKey).tableSchema!!.readOnlyReason)
        s.setWritable(true)
        assertThrows(WorkspaceRejected::class.java) { s.preview(MutationKind.INSERT, null, mapOf("v" to CellInput("1"))) }
    }
    @Test fun wrongHostnameAndUntrustedCaRejected() {
        listOf(profile("127.0.0.1"), profile(trusted = false)).forEach { p ->
            val error = assertThrows(Exception::class.java) { connect(p).close() }
            assertEquals(ErrorKind.TLS, Failures.classify(error).kind)
        }
    }
    @Test fun textBudgetPagingDoesNotSkipRecordsAndTemporalValuesRemainReadOnly() = fixture { s, _ ->
        connect().use { c -> c.createStatement().use {
            it.execute("DROP TABLE IF EXISTS datasail_v020.dbo.budget_rows; CREATE TABLE datasail_v020.dbo.budget_rows(id int IDENTITY PRIMARY KEY, a nvarchar(2048), b nvarchar(2048)); INSERT datasail_v020.dbo.budget_rows SELECT TOP (150) REPLICATE(N'x',2048),REPLICATE(N'y',2048) FROM sys.all_objects")
        } }
        val table = s.tables("dbo").single { it.name == "budget_rows" }
        val first = s.page(table)
        assertTrue(first.rows.size in 1..99); assertTrue(first.hasMore)
        val next = s.page(table, first.rows.size)
        assertEquals((first.rows.last()[0] as DbValue.Integer).value + 1, (next.rows.first()[0] as DbValue.Integer).value)
        val temporal = s.select("SELECT CAST('2026-10-12T12:00:00+07:00' AS datetimeoffset) AS moment")
        assertTrue(temporal.rows.single().single() is DbValue.Temporal)
    }
    @Test fun failedCatalogAcknowledgementClosesAmbiguousSession() = fixture(wrap = { raw ->
        proxy(java.sql.Connection::class.java, raw) { method, args ->
            if (method.name == "setCatalog" && args!![0] == "master") {
                raw.catalog = "master"
                throw java.sql.SQLException("simulated lost USE acknowledgement", "S0001")
            }
            method.invoke(raw, *(args ?: emptyArray()))
        }
    }) { s, _ ->
        assertThrows(java.sql.SQLException::class.java) { s.switchCatalog("master") }
        assertTrue(s.isClosed()); assertFalse(s.writable)
        assertThrows(IllegalStateException::class.java) { s.select("SELECT 1") }
    }
    @Test fun partialOperationFailureReportsActualCatalogAndReadOnlyState() = fixture { _, _ ->
        WorkspaceRunner().use { runner ->
            val ready = java.util.concurrent.CountDownLatch(1)
            val connected = java.util.concurrent.atomic.AtomicReference<Result<Unit>>()
            runner.connect(profile(), Credentials(config.getProperty("password").toCharArray())) { connected.set(it); ready.countDown() }
            assertTrue(ready.await(15, java.util.concurrent.TimeUnit.SECONDS)); connected.get().getOrThrow()
            val done = java.util.concurrent.CountDownLatch(1)
            val reply = java.util.concurrent.atomic.AtomicReference<SessionReply<Unit>>()
            assertTrue(runner.execute<Unit>({ reply.set(it); done.countDown() }) { session ->
                session.setWritable(true)
                session.switchCatalog("datasail_v020")
                throw WorkspaceRejected("simulated schema refresh failure")
            })
            assertTrue(done.await(15, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(reply.get().result.isFailure)
            assertEquals(SessionSnapshot("datasail_v020", false, true), reply.get().session)
        }
    }
    @Test fun probeTypesTimeoutAndCancellation() {
        for (mode in ProbeMode.entries) {
            val done = java.util.concurrent.CountDownLatch(1)
            val report = java.util.concurrent.atomic.AtomicReference<ProbeReport>()
            ProbeRunner().use { runner ->
                assertTrue(runner.start(profile(), Credentials(config.getProperty("password").toCharArray()), mode, {}, { report.set(it); done.countDown() }))
                assertTrue(done.await(25, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals(if (mode == ProbeMode.CANCEL) ExecutionState.UNKNOWN else ExecutionState.SUCCEEDED, report.get().state)
            }
        }
    }
    @Test fun wrongPasswordRejectsWithoutRetry() {
        val error = assertThrows(Exception::class.java) { Credentials("synthetic-wrong-password".toCharArray()).use { JdbcConnector().connect(profile(), it).close() } }
        assertEquals(ErrorKind.AUTHENTICATION, Failures.classify(error).kind)
    }
    @Test fun changedTriggerBlocksOldApproval() = fixture { s, table ->
        s.page(table); s.setWritable(true)
        val change = s.preview(MutationKind.DELETE, 0, emptyMap())
        connect().use { c ->
            c.catalog = "datasail_v020"
            c.createStatement().use { it.execute("CREATE TRIGGER block_edit ON dbo.records AFTER UPDATE AS BEGIN SET NOCOUNT ON; END") }
        }
        assertEquals(MutationStatus.ROLLED_BACK, s.confirm(change.token).status)
        assertEquals(1, s.page(table).rows.size)
        assertNotNull(s.page(table).tableSchema!!.readOnlyReason)
    }
    @Test fun collationAndTrailingSpaceChangesAreConflictsWithoutRowversion() = fixture { s, _ ->
        connect().use { c -> c.createStatement().use {
            it.execute("DROP TABLE IF EXISTS datasail_v020.dbo.exact_text; CREATE TABLE datasail_v020.dbo.exact_text(id int PRIMARY KEY, label nvarchar(20)); INSERT datasail_v020.dbo.exact_text VALUES(1,N'Abc')")
        } }
        val table = s.tables("dbo").single { it.name == "exact_text" }
        s.setWritable(true)
        for (updated in listOf("abc", "abc ")) {
            s.page(table)
            val change = s.preview(MutationKind.DELETE, 0, emptyMap())
            connect().use { c -> c.prepareStatement("UPDATE datasail_v020.dbo.exact_text SET label=?").use { it.setNString(1, updated); it.executeUpdate() } }
            assertEquals(MutationStatus.CONFLICT, s.confirm(change.token).status)
            assertEquals(DbValue.Text(updated), s.page(table).rows[0][1])
        }
    }
    @Test fun abnormalAffectedCountRollsBackActualWrite() = fixture(wrap = { raw ->
        proxy(java.sql.Connection::class.java, raw) { method, args ->
            if (method.name == "prepareStatement" && (args!![0] as String).startsWith("UPDATE")) {
                val statement = method.invoke(raw, *args) as java.sql.PreparedStatement
                proxy(java.sql.PreparedStatement::class.java, statement) { inner, parameters ->
                    if (inner.name == "executeUpdate") { statement.executeUpdate(); 2 } else inner.invoke(statement, *(parameters ?: emptyArray()))
                }
            } else method.invoke(raw, *(args ?: emptyArray()))
        }
    }) { s, table ->
        s.page(table); s.setWritable(true)
        val change = s.preview(MutationKind.UPDATE, 0, mapOf("label" to CellInput("must roll back")))
        assertEquals(MutationStatus.CONFLICT, s.confirm(change.token).status)
        assertEquals(DbValue.Text("00123 · 数舟 · Tiếng Việt"), s.page(table).rows[0][1])
    }
    @Test fun lostCommitAcknowledgementIsUnknownAndNeverReplayed() {
        var commits = 0
        fixture(wrap = { raw -> proxy(java.sql.Connection::class.java, raw) { method, args ->
            if (method.name == "commit") { commits++; raw.commit(); throw java.sql.SQLException("simulated lost acknowledgement", "08006") }
            method.invoke(raw, *(args ?: emptyArray()))
        } }) { s, table ->
            s.page(table); s.setWritable(true)
            val change = s.preview(MutationKind.UPDATE, 0, mapOf("label" to CellInput("committed but acknowledgement lost")))
            assertEquals(MutationStatus.UNKNOWN, s.confirm(change.token).status)
            assertTrue(s.isClosed()); assertEquals(1, commits)
            assertThrows(WorkspaceRejected::class.java) { s.confirm(change.token) }
            connect().use { c -> c.createStatement().use { statement ->
                statement.executeQuery("SELECT label FROM datasail_v020.dbo.records").use { rows -> rows.next(); assertEquals("committed but acknowledgement lost", rows.getString(1)) }
            } }
        }
    }
    private fun <T> proxy(type: Class<T>, @Suppress("UNUSED_PARAMETER") delegate: T,
        call: (java.lang.reflect.Method, Array<out Any?>?) -> Any?): T = type.cast(java.lang.reflect.Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
        try { call(method, args) } catch (error: java.lang.reflect.InvocationTargetException) { throw error.targetException }
    })

}
