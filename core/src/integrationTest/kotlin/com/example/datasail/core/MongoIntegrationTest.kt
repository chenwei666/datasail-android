package com.example.datasail.core

import com.example.datasail.fixture.MongoContract
import org.junit.Test
import java.io.File
import java.util.Properties

class MongoIntegrationTest {
    private fun fixture(): MongoContract {
        val config = Properties().apply { File(System.getProperty("datasail.test.config")).inputStream().use(::load) }
        return MongoContract(ConnectionProfile(DatabaseKind.MONGODB, "localhost", config.getProperty("port").toInt(), "synthetic", "reader",
            trustStore = TrustStoreSpec(System.getProperty("datasail.test.trust"))), config.getProperty("password"))
    }
    @Test fun singleObjectWritesAndConflicts() = fixture().writes()
    @Test fun readsAndBounds() = fixture().reads()
    @Test fun strictIdentityAndAuthentication() = fixture().identityAndAuth()
    @Test fun deadlinesAndCancellation() = fixture().deadlinesAndCancellation()
}
