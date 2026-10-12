package com.example.datasail

import androidx.test.platform.app.InstrumentationRegistry
import com.example.datasail.core.DatabaseKind
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class QueryStoreTest {
    @Test fun interruptedWritesRestoreCommittedBackupWhenBaseFileIsAbsent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val queries = QueryStore(context); val connections = ConnectionStore(context)
        val oldQueries = queries.load(); val oldConnections = connections.load()
        val query = SavedQuery(name = "恢复查询", kind = DatabaseKind.POSTGRESQL, sql = "SELECT 1")
        val connection = SavedConnection(name = "恢复连接", host = "localhost", database = "synthetic", username = "fixture")
        try {
            queries.save(listOf(query)); connections.save(listOf(connection))
            for (name in listOf("queries-v1.json", "connections-v1.json")) {
                val base = File(context.filesDir, name)
                assertTrue(base.renameTo(File(context.filesDir, "$name.bak")))
            }
            assertEquals(listOf(query), QueryStore(context).load())
            assertEquals(listOf(connection), ConnectionStore(context).load())
            assertFalse(File(context.filesDir, "connections-v1.json.bak").exists())
            assertFalse(File(context.filesDir, "queries-v1.json.bak").exists())
        } finally { queries.save(oldQueries); connections.save(oldConnections) }
    }

    @Test fun concurrentInstancesCannotRestoreStaleAtomicBackups() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val queries = QueryStore(context); val connections = ConnectionStore(context)
        val oldQueries = queries.load(); val oldConnections = connections.load()
        val query = SavedQuery(name = "0", kind = DatabaseKind.POSTGRESQL, sql = "SELECT 1")
        val connection = SavedConnection(name = "0", host = "localhost", database = "synthetic", username = "fixture")
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        val start = java.util.concurrent.CountDownLatch(1)
        try {
            queries.save(listOf(query)); connections.save(listOf(connection))
            val writer = pool.submit {
                start.await()
                repeat(20) { index ->
                    QueryStore(context).save(listOf(query.copy(name = index.toString())))
                    ConnectionStore(context).save(listOf(connection.copy(name = index.toString())))
                }
            }
            val reader = pool.submit {
                start.await()
                repeat(100) {
                    assertEquals(1, QueryStore(context).load().size)
                    assertEquals(1, ConnectionStore(context).load().size)
                }
            }
            start.countDown()
            writer.get(30, java.util.concurrent.TimeUnit.SECONDS); reader.get(30, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals("19", queries.load().single().name)
            assertEquals("19", connections.load().single().name)
        } finally {
            pool.shutdown(); check(pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS))
            queries.save(oldQueries); connections.save(oldConnections)
        }
    }

    @Test fun favoritesAreExplicitBoundedAndPreservedOnRejectedWrite() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = QueryStore(context); val original = store.load()
        try {
            val item = SavedQuery(name = "合成查询", kind = DatabaseKind.POSTGRESQL, sql = "SELECT '0007'")
            store.save(listOf(item)); assertEquals(listOf(item), QueryStore(context).load())
            assertThrows(Exception::class.java) { store.save(listOf(item.copy(sql = "DELETE FROM example"))) }
            assertEquals(listOf(item), store.load())
            assertThrows(Exception::class.java) { store.save(List(101) { item }) }
            assertEquals(listOf(item), store.load())
            val file = File(context.filesDir, "queries-v1.json"); val bytes = file.readBytes()
            try { file.writeText("{\"schema\":99,\"queries\":[]}"); assertThrows(Exception::class.java) { store.load() } }
            finally { file.writeBytes(bytes) }
        } finally { store.save(original) }
    }
}
