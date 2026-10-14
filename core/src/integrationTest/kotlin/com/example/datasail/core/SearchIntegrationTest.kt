package com.example.datasail.core
import com.example.datasail.fixture.SearchContract
import org.junit.Test
import java.io.File
import java.util.Properties
class SearchIntegrationTest {
    private fun fixture(): SearchContract {
        val config = Properties().apply { File(System.getProperty("datasail.test.config")).inputStream().use(::load) }
        return SearchContract(ConnectionProfile(DatabaseKind.valueOf(config.getProperty("kind")), "localhost", config.getProperty("port").toInt(), "synthetic", "reader",
            trustStore = TrustStoreSpec(System.getProperty("datasail.test.trust"))), config.getProperty("password"), config.getProperty("faultPort").toInt())
    }
    @Test fun singleObjectWritesAndConflicts() = fixture().writes()
    @Test fun readsMappingPaginationAndTypes() { repeat(System.getProperty("datasail.search.readRepeats", "1").toInt()) { fixture().reads() } }
    @Test fun strictTlsAndAuthentication() = fixture().identityAndAuth()
    @Test fun adversarialTransport() = fixture().transportBoundsAndCancellation()
}
