package com.example.datasail

import android.app.Application
import org.conscrypt.Conscrypt
import java.security.Security

class DataSailApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // JDBC TLS wraps a protocol socket: operate on its streams, never its native fd.
        Conscrypt.setUseEngineSocketByDefault(true)
        // Keep Android's trust manager and CA policy when selecting this crypto provider.
        val provider = Conscrypt.newProviderBuilder().setName("DataSailTLS")
            .provideTrustManager(false).build()
        check(Security.insertProviderAt(provider, 1) == 1)
    }
}
