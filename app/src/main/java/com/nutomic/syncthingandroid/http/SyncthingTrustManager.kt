package com.nutomic.syncthingandroid.http

import android.annotation.SuppressLint
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
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

    /**
     * Verifies certs against public key of the local syncthing instance
     */
    @Throws(CertificateException::class)
    override fun checkServerTrusted(
        certs: Array<X509Certificate>,
        authType: String?
    ) {
        var `is`: InputStream? = null
        try {
            `is` = FileInputStream(mHttpsCertPath)
            val cf = CertificateFactory.getInstance("X.509")
            val ca = cf.generateCertificate(`is`) as X509Certificate
            for (cert in certs) {
                cert.verify(ca.publicKey)
            }
        } catch (e: FileNotFoundException) {
            throw CertificateException("Untrusted Certificate!", e)
        } catch (e: NoSuchAlgorithmException) {
            throw CertificateException("Untrusted Certificate!", e)
        } catch (e: InvalidKeyException) {
            throw CertificateException("Untrusted Certificate!", e)
        } catch (e: NoSuchProviderException) {
            throw CertificateException("Untrusted Certificate!", e)
        } catch (e: SignatureException) {
            throw CertificateException("Untrusted Certificate!", e)
        } finally {
            try {
                `is`?.close()
            } catch (e: IOException) {
                Log.w(TAG, e)
            }
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate?>? {
        return null
    }

    companion object {
        private const val TAG = "SyncthingTrustManager"
    }
}