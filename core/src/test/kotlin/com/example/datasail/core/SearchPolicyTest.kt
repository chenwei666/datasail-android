package com.example.datasail.core

import org.junit.Assert.*
import org.junit.Test

class SearchPolicyTest {
    @Test fun jsonPreservesNumericLexemesAndRejectsAmbiguities() {
        val source = "{\"n\":9007199254740993,\"d\":1.2300,\"s\":\"0007\",\"z\":null,\"e\":1e99}"
        assertEquals(source, SearchJson.parse(source).encode())
        listOf("{\"x\":1,\"x\":2}", "[01]", "[NaN]", "\"\\u+123\"", "{'a':1}", "[1,]", "{}x", "\"\\uD800\"", "[".repeat(34) + "0" + "]".repeat(34)).forEach { bad ->
            assertThrows(WorkspaceRejected::class.java) { SearchJson.parse(bad) }
        }
    }
    @Test fun onlyBoundedSearchConditionsAreAccepted() {
        listOf("{\"match_all\":{}}", "{\"term\":{\"code\":\"0007\"}}", "{\"bool\":{\"filter\":[{\"range\":{\"n\":{\"gte\":9007199254740993}}}]}}").forEach { SearchPolicy.query(it) }
        listOf("{\"script\":{\"source\":\"x\"}}", "{\"query\":{\"match_all\":{}}}", "{\"term\":{\"code\":{\"index\":\"secret\"}}}", "{\"bool\":{}}", "{\"match_all\":{\"boost\":2}}").forEach { bad ->
            assertThrows(WorkspaceRejected::class.java) { SearchPolicy.query(bad) }
        }
        assertFalse(DatabaseKind.ELASTICSEARCH.isSql); assertFalse(DatabaseKind.OPENSEARCH.isDocument)
        listOf("logs-*", "synthetic", "*").forEach { assertTrue(SearchPolicy.validScope(it)) }
        listOf("../secret", "_all", "a,b", "a?x", "https://x", "a%2fb").forEach { assertFalse(SearchPolicy.validScope(it)) }
    }
    @Test fun statusClassificationDoesNotLeakServerBody() {
        assertEquals(ErrorKind.AUTHENTICATION, Failures.classify(SearchHttpFailure(401)).kind)
        assertEquals(ErrorKind.PERMISSION, Failures.classify(SearchHttpFailure(403)).kind)
        assertEquals(ErrorKind.TIMEOUT, Failures.classify(SearchHttpFailure(504)).kind)
    }
}
