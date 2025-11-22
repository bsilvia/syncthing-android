package com.nutomic.syncthingandroid.http

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.widget.ImageView
import com.android.volley.AuthFailureError
import com.android.volley.DefaultRetryPolicy
import com.android.volley.RequestQueue
import com.android.volley.Response
import com.android.volley.VolleyError
import com.android.volley.toolbox.HurlStack
import com.android.volley.toolbox.ImageRequest
import com.android.volley.toolbox.StringRequest
import com.android.volley.toolbox.Volley
import com.google.common.base.Function
import com.google.common.base.Optional
import com.google.common.collect.ImmutableMap
import com.nutomic.syncthingandroid.service.Constants.getHttpsCertFile
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyManagementException
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager
import androidx.core.net.toUri
import javax.net.ssl.SSLSocketFactory


abstract class ApiRequest internal constructor(
    private val mContext: Context,
    private val mUrl: URL,
    private val mPath: String?,
    private val mApiKey: String
) {
    fun interface OnSuccessListener {
        fun onSuccess(result: String?)
    }

    fun interface OnImageSuccessListener {
        fun onImageSuccess(result: Bitmap?)
    }

    fun interface OnErrorListener {
        fun onError(error: VolleyError?)
    }

    private val volleyQueue: RequestQueue?
        get() {
            if (sVolleyQueue == null) {
                val context = mContext.applicationContext
                sVolleyQueue = Volley.newRequestQueue(context, NetworkStack())
            }
            return sVolleyQueue
        }

    fun buildUri(params: MutableMap<String, String>): Uri? {
        val uriBuilder = mUrl.toString().toUri()
            .buildUpon()
            .path(mPath)
        for (entry in params.entries) {
            uriBuilder.appendQueryParameter(entry.key, entry.value)
        }
        return uriBuilder.build()
    }

    /**
     * Opens the connection, then returns success status and response string.
     */
    fun connect(
        requestMethod: Int, uri: Uri, requestBody: String?,
        listener: OnSuccessListener?, errorListener: OnErrorListener?
    ) {
        Log.v(TAG, "Performing request to $uri")
        val request: StringRequest = object :
            StringRequest(requestMethod, uri.toString(), Response.Listener { reply: String? ->
                listener?.onSuccess(reply)
            }, Response.ErrorListener { error: VolleyError? ->
                if (errorListener != null) {
                    errorListener.onError(error)
                } else {
                    Log.w(TAG, "Request to " + uri + " failed, " + error!!.message)
                }
            }) {
            @Throws(AuthFailureError::class)
            override fun getHeaders(): MutableMap<String, String> {
                return ImmutableMap.of(HEADER_API_KEY, mApiKey)
            }

            @Throws(AuthFailureError::class)
            override fun getBody(): ByteArray? {
                return Optional.fromNullable(requestBody)
                    .transform(Function { obj: String? -> obj!!.toByteArray() })
                    .orNull()
            }
        }

        // Some requests seem to be slow or fail, make sure this doesn't break the app
        // (eg if an event request fails, new event requests won't be triggered).
        request.retryPolicy = DefaultRetryPolicy(
            5000, 5,
            DefaultRetryPolicy.DEFAULT_BACKOFF_MULT
        )
        this.volleyQueue?.add(request)
    }

    /**
     * Opens the connection, then returns success status and response bitmap.
     */
    fun makeImageRequest(
        uri: Uri, imageListener: OnImageSuccessListener?,
        errorListener: OnErrorListener?
    ) {
        val imageRequest: ImageRequest = object : ImageRequest(
            uri.toString(),
            Response.Listener { bitmap: Bitmap? ->
                imageListener?.onImageSuccess(bitmap)
            },
            0,
            0,
            ImageView.ScaleType.CENTER,
            Bitmap.Config.RGB_565,
            Response.ErrorListener { volleyError: VolleyError? ->
                errorListener?.onError(volleyError)
                Log.d(TAG, "onErrorResponse: $volleyError")
            }) {
            @Throws(AuthFailureError::class)
            override fun getHeaders(): MutableMap<String, String> {
                return ImmutableMap.of(HEADER_API_KEY, mApiKey)
            }
        }

        this.volleyQueue?.add(imageRequest)
    }

    /**
     * Extends [HurlStack], uses [.getSslSocketFactory] and disables hostname
     * verification.
     */
    private inner class NetworkStack : HurlStack(null, this.sslSocketFactory) {
        @Throws(IOException::class)
        override fun createConnection(url: URL?): HttpURLConnection? {
            if (mUrl.toString().startsWith("https://")) {
                val connection = super.createConnection(url) as HttpsURLConnection
                connection.setHostnameVerifier { _: String?, _: SSLSession? -> true }
                return connection
            }
            return super.createConnection(url)
        }
    }

    private val sslSocketFactory: SSLSocketFactory?
        get() {
            try {
                val sslContext =
                    SSLContext.getInstance("TLS")
                val httpsCertPath =
                    getHttpsCertFile(mContext)
                sslContext.init(
                    null,
                    arrayOf<TrustManager>(SyncthingTrustManager(httpsCertPath)),
                    SecureRandom()
                )
                return sslContext.socketFactory
            } catch (e: NoSuchAlgorithmException) {
                Log.w(TAG, e)
                return null
            } catch (e: KeyManagementException) {
                Log.w(TAG, e)
                return null
            }
        }

    companion object {
        private const val TAG = "ApiRequest"

        /**
         * The name of the HTTP header used for the syncthing API key.
         */
        private const val HEADER_API_KEY = "X-API-Key"

        private var sVolleyQueue: RequestQueue? = null
    }
}
