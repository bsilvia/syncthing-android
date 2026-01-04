package com.nutomic.syncthingandroid.http

import android.annotation.SuppressLint
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.security.InvalidKeyException
import java.security.NoSuchAlgorithmException
import java.security.NoSuchProviderException
import java.security.SignatureException
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/*
* TrustManager checking against the local Syncthing instance's https public key.
*
* Based on http://stackoverflow.com/questions/16719959#16759793
*/
@SuppressLint("CustomX509TrustManager")
internal class SyncthingTrustManager(private val mHttpsCertPath: File) : X509TrustManager {
    @SuppressLint("TrustAllX509TrustManager")
    @Throws(CertificateException::class)
    override fun checkClientTrusted(chain: Array<X509Certificate?>?, authType: String?) {
    }

    private val trustedCertificate: X509Certificate by lazy {
        FileInputStream(mHttpsCertPath).use { inputStream ->
            val cf = CertificateFactory.getInstance("X.509")
            cf.generateCertificate(inputStream) as X509Certificate
        }
    }

    /**
     * Verifies certs against public key of the local syncthing instance
     */
    @Throws(CertificateException::class)
    override fun checkServerTrusted(
        certs: Array<X509Certificate>,
        authType: String?
    ) {

        try {
            FileInputStream(mHttpsCertPath).use { inputStream ->
                for (cert in certs) {
                    cert.verify(trustedCertificate.publicKey)
                }
            }
        } catch (e: FileNotFoundException) {
            throw CertificateException("Certificate file not found at ${mHttpsCertPath.absolutePath}", e)
        } catch (e: NoSuchAlgorithmException) {
            throw CertificateException("Certificate verification failed: ${e.message}", e)
        } catch (e: InvalidKeyException) {
            throw CertificateException("Certificate verification failed: ${e.message}", e)
        } catch (e: NoSuchProviderException) {
            throw CertificateException("Cryptographic provider error during certificate verification: ${e.message}", e)
        } catch (e: SignatureException) {
            throw CertificateException("Certificate verification failed: ${e.message}", e)
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate?>? {
        return null
    }
}