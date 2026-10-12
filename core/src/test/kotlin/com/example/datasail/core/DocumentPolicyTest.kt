package com.example.datasail.core

import org.bson.RawBsonDocument
import org.junit.Assert.*
import org.junit.Test

class DocumentPolicyTest {
    @Test fun filterAllowsTypedComparisonsAndRejectsExecutionAndDeepInput() {
        val value = DocumentFilter.parse("""{"_id":{"${'$'}oid":"0123456789abcdef01234567"},"n":{"${'$'}gte":{"${'$'}numberLong":"9007199254740993"}}}""")
        assertTrue(value["_id"]!!.isObjectId)
        assertEquals(9007199254740993L, value["n"]!!.asDocument()["\$gte"]!!.asInt64().value)
        listOf("{\"\$where\":\"return true\"}", "{\"x\":{\"\$regex\":\".*\"}}", "{\"\$expr\":{}}", "{\"x\":{\"\$code\":\"evil\"}}", "{\"x\": " + "[".repeat(1000), "{'x':1}", "{\"x\":NumberLong(1)}").forEach {
            assertThrows(WorkspaceRejected::class.java) { DocumentFilter.parse(it) }
        }
        assertThrows(WorkspaceRejected::class.java) { ReadQueryPolicy.validate("SELECT 1", DatabaseKind.MONGODB) }
    }
    @Test fun canonicalPreviewPreservesTypesAndExplicitlyRejectsLargeValues() {
        val document = RawBsonDocument.parse("""{"n":{"${'$'}numberLong":"9007199254740993"},"d":{"${'$'}numberDecimal":"1.2300"},"empty":"","null":null}""")
        val json = MongoSession.preview(document).json!!
        assertTrue(json.contains("9007199254740993")); assertTrue(json.contains("1.2300")); assertTrue(json.contains("\$numberLong"))
        val large = RawBsonDocument.parse("{\"value\":\"" + "x".repeat(40000) + "\"}")
        assertNull(MongoSession.preview(large).json); assertNotNull(MongoSession.preview(large).unavailable)
    }
    @Test fun failedSessionConstructionClosesCredentials() {
        val secret = Credentials("synthetic".toCharArray())
        val done = java.util.concurrent.CountDownLatch(1)
        DocumentRunner().use { runner ->
            val profile = ConnectionProfile(DatabaseKind.MONGODB, "localhost", 27017, "synthetic", "reader", trustStore = TrustStoreSpec("/nonexistent-datasail-test-ca"))
            assertTrue(runner.connect(profile, secret) { assertTrue(it.isFailure); done.countDown() })
            assertTrue(done.await(3, java.util.concurrent.TimeUnit.SECONDS))
            assertThrows(IllegalStateException::class.java) { secret.usePassword { it.length } }
        }
    }
    @Test fun closedTlsRegistryRejectsLateSockets() {
        val registry = SocketRegistry(); val first = java.net.Socket(); registry.add(first); registry.close()
        assertTrue(first.isClosed)
        val late = java.net.Socket(); assertThrows(java.net.SocketException::class.java) { registry.add(late) }; assertTrue(late.isClosed)
    }
}
