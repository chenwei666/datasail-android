package com.example.datasail.core

import com.example.datasail.fixture.KeyValueContract
import org.junit.Test
import java.io.File
import java.util.Properties

class KeyValueIntegrationTest {
    private fun fixture(): KeyValueContract {
        val config = Properties().apply { File(System.getProperty("datasail.test.config")).inputStream().use(::load) }
        return KeyValueContract(ConnectionProfile(DatabaseKind.valueOf(config.getProperty("kind")), "localhost", config.getProperty("port").toInt(), "0", "reader",
            trustStore = TrustStoreSpec(System.getProperty("datasail.test.trust"))), config.getProperty("password"))
    }
    @Test fun singleObjectWritesAndConflicts() = fixture().writes()
    @Test fun readsAndBounds() = fixture().reads()
    @Test fun strictIdentityAndAuthentication() = fixture().identityAndAuth()
    @Test fun deadlinesAndCancellation() = fixture().deadlinesAndCancellation()
}
