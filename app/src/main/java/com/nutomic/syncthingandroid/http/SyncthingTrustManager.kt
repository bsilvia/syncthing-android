package com.nutomic.syncthingandroid.http

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * TrustManager that first delegates to the platform trust managers and then
 * falls back to a local Syncthing CA certificate if provided via `mHttpsCertPath`.
 * This preserves secure behavior while allowing the app to trust a bundled/local CA.
 */
internal class SyncthingTrustManager(mHttpsCertPath: File?) : X509TrustManager {

    companion object {
        private const val TAG = "SyncthingTrustManager"
    }

    private val systemTrustManagers: Array<X509TrustManager>
    private val localTrustManager: X509TrustManager?

    init {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        systemTrustManagers = tmf.trustManagers.filterIsInstance<X509TrustManager>().toTypedArray()

        var localTm: X509TrustManager? = null
        if (mHttpsCertPath != null && mHttpsCertPath.exists()) {
            try {
                val cf = CertificateFactory.getInstance("X.509")
                FileInputStream(mHttpsCertPath).use { fis ->
                    val ca = cf.generateCertificate(fis) as X509Certificate
                    val ks = KeyStore.getInstance(KeyStore.getDefaultType())
                    ks.load(null, null)
                    ks.setCertificateEntry("syncthing_local", ca)
                    val localTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                    localTmf.init(ks)
                    localTm = localTmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load local Syncthing CA", e)
            }
        }
        localTrustManager = localTm
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        // Delegate to system trust managers
        for (tm in systemTrustManagers) {
            tm.checkClientTrusted(chain, authType)
        }
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        // First attempt validation with system trust managers
        for (tm in systemTrustManagers) {
            try {
                tm.checkServerTrusted(chain, authType)
                return
            } catch (e: CertificateException) {
                // try next
            }
        }

        // Fallback to local Syncthing CA if present
        if (localTrustManager != null) {
            try {
                localTrustManager.checkServerTrusted(chain, authType)
                return
            } catch (e: CertificateException) {
                // fall through to error
            }
        }

        throw CertificateException("Server certificate chain is not trusted")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> {
        val list = mutableListOf<X509Certificate>()
        for (tm in systemTrustManagers) list.addAll(tm.acceptedIssuers)
        localTrustManager?.let { list.addAll(it.acceptedIssuers) }
        return list.toTypedArray()
    }
}
