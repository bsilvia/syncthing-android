package com.nutomic.syncthingandroid.activities

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Proxy
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Parcelable
import android.util.ArrayMap
import android.util.Base64
import android.util.Log
import android.view.View
import android.webkit.SslErrorHandler
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import android.content.SharedPreferences
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.databinding.ActivityWebGuiBinding
import com.nutomic.syncthingandroid.service.Constants.getHttpsCertFile
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.service.SyncthingService.OnServiceStateChangeListener
import com.nutomic.syncthingandroid.service.SyncthingServiceBinder
import com.nutomic.syncthingandroid.util.ConfigXml
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.lang.reflect.Constructor
import java.nio.charset.StandardCharsets
import java.security.InvalidKeyException
import java.security.NoSuchAlgorithmException
import java.security.NoSuchProviderException
import java.security.SignatureException
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import androidx.core.net.toUri

/**
 * Holds a WebView that shows the web ui of the local syncthing instance.
 */
class WebGuiActivity : StateDialogActivity(), OnServiceStateChangeListener {
    private var mCaCert: X509Certificate? = null

    private var mConfig: ConfigXml? = null

    private var binding: ActivityWebGuiBinding? = null

    /**
     * Hides the loading screen and shows the WebView once it is fully loaded.
     */
    private val mWebViewClient: WebViewClient = object : WebViewClient() {
        /**
         * Catch (self-signed) SSL errors and test if they correspond to Syncthing's certificate.
         */
        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler, error: SslError) {
            // If user has previously trusted this host, proceed automatically.
            val host = view?.url?.let { android.net.Uri.parse(it).host }
            val prefs: SharedPreferences = getSharedPreferences("syncthing_prefs", Context.MODE_PRIVATE)
            val trusted = prefs.getStringSet("trusted_ssl_hosts", emptySet()) ?: emptySet()
            if (host != null && trusted.contains(host)) {
                Log.i(TAG, "Host $host is in trusted list; proceeding despite SSL error")
                handler.proceed()
                return
            }

            // Otherwise show a dialog offering the user a choice.
            Log.w(TAG, "onReceivedSslError: SSL error for URL=${view?.url}; showing trust dialog")
            runOnUiThread {
                try {
                    val cert = error.certificate
                    val issuedTo = cert?.getIssuedTo()?.toString() ?: ""
                    val issuedBy = cert?.getIssuedBy()?.toString() ?: ""
                    val msg = StringBuilder()
                    msg.append("SSL error for site: ")
                    msg.append(host ?: view?.url)
                    msg.append("\nError: ")
                    msg.append(error.primaryError)
                    if (issuedTo.isNotEmpty() || issuedBy.isNotEmpty()) {
                        msg.append("\n\nCertificate:\n")
                        if (issuedTo.isNotEmpty()) msg.append("Issued To: ").append(issuedTo).append("\n")
                        if (issuedBy.isNotEmpty()) msg.append("Issued By: ").append(issuedBy).append("\n")
                    }

                    AlertDialog.Builder(this@WebGuiActivity)
                        .setTitle("SSL error")
                        .setMessage(msg.toString())
                        .setPositiveButton("Trust once") { _, _ ->
                            handler.proceed()
                        }
                        .setNeutralButton("Trust always") { _, _ ->
                            if (host != null) {
                                val newSet = HashSet(trusted)
                                newSet.add(host)
                                prefs.edit().putStringSet("trusted_ssl_hosts", newSet).apply()
                                Log.i(TAG, "Added $host to trusted SSL hosts")
                            }
                            handler.proceed()
                        }
                        .setNegativeButton("Cancel") { _, _ ->
                            handler.cancel()
                        }
                        .setCancelable(true)
                        .show()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to show SSL trust dialog", e)
                    try {
                        Toast.makeText(this@WebGuiActivity, "SSL error loading page", Toast.LENGTH_LONG).show()
                    } catch (t: Exception) {
                        Log.w(TAG, "Failed to show SSL error toast", t)
                    }
                    handler.cancel()
                }
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
            val uri = url!!.toUri()
            if (uri.host == service!!.webGuiUrl!!.host) {
                return false
            } else {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
                return true
            }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            binding!!.webview.visibility = View.VISIBLE
            binding!!.loading.visibility = View.GONE
        }
    }

    /**
     * Initialize WebView.
     *
     * Ignore lint javascript warning as js is loaded only from our known, local service.
     */
    @SuppressLint("SetJavaScriptEnabled")
    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebGuiBinding.inflate(layoutInflater)
        setContentView(binding!!.getRoot())

        mConfig = ConfigXml(this)
        loadCaCert()

        binding!!.webview.getSettings().javaScriptEnabled = true
        binding!!.webview.getSettings().domStorageEnabled = true
        binding!!.webview.setWebViewClient(mWebViewClient)
        binding!!.webview.clearCache(true)

        // SyncthingService needs to be started from this activity as the user
        // can directly launch this activity from the recent activity switcher.
        val serviceIntent = Intent(this, SyncthingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }


        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding!!.webview.canGoBack()) {
                    binding!!.webview.goBack()
                } else {
                    finish()
                }
            }
        })
    }

    override fun onServiceConnected(componentName: ComponentName?, iBinder: IBinder?) {
        super.onServiceConnected(componentName, iBinder)
        val syncthingServiceBinder = iBinder as SyncthingServiceBinder
        syncthingServiceBinder.service!!.registerOnServiceStateChangeListener(this)
    }

    override fun onServiceStateChange(currentState: SyncthingService.State?) {
        Log.v(TAG, "onServiceStateChange($currentState)")
        if (currentState == SyncthingService.State.ACTIVE) {
            if (binding!!.webview.getUrl() == null) {
                binding!!.webview.stopLoading()
                setWebViewProxy(
                    binding!!.webview.context.applicationContext,
                    "",
                    0,
                    "localhost|0.0.0.0|127.*|[::1]"
                )
                val credentials = mConfig!!.userName + ":" + mConfig!!.apiKey
                val b64Credentials = Base64.encodeToString(
                    credentials.toByteArray(StandardCharsets.UTF_8),
                    Base64.NO_WRAP
                )
                val headers: MutableMap<String?, String?> = HashMap()
                headers["Authorization"] = "Basic $b64Credentials"
                binding!!.webview.loadUrl(service?.webGuiUrl.toString(), headers)
            }
        }
    }

    public override fun onPause() {
        binding!!.webview.onPause()
        binding!!.webview.pauseTimers()
        super.onPause()
    }

    public override fun onResume() {
        super.onResume()
        binding!!.webview.resumeTimers()
        binding!!.webview.onResume()
    }

    override fun onDestroy() {
        val mSyncthingService = service
        mSyncthingService?.unregisterOnServiceStateChangeListener(this)
        binding!!.webview.destroy()
        super.onDestroy()
    }

    /**
     * Reads the SyncthingService.HTTPS_CERT_FILE Ca Cert key and loads it in memory
     */
    private fun loadCaCert() {
        var inStream: InputStream? = null
        val httpsCertFile = getHttpsCertFile(this)
        if (!httpsCertFile.exists()) {
            Toast.makeText(this@WebGuiActivity, R.string.config_file_missing, Toast.LENGTH_LONG)
                .show()
            finish()
            return
        }
        try {
            inStream = FileInputStream(httpsCertFile)
            val cf = CertificateFactory.getInstance("X.509")
            mCaCert = cf.generateCertificate(inStream) as X509Certificate
        } catch (e: FileNotFoundException) {
            throw IllegalArgumentException("Untrusted Certificate", e)
        } catch (e: CertificateException) {
            throw IllegalArgumentException("Untrusted Certificate", e)
        } finally {
            try {
                inStream?.close()
            } catch (e: IOException) {
                Log.w(TAG, e)
            }
        }
    }

    companion object {
        private const val TAG = "WebGuiActivity"

        /**
         * Set webview proxy and sites that are not retrieved using proxy.
         * Compatible with KitKat or higher android version.
         * Returns boolean if successful.
         * Source: https://stackoverflow.com/a/26781539
         */
        @SuppressLint("PrivateApi")
        fun setWebViewProxy(
            appContext: Context?,
            host: String?,
            port: Int,
            exclusionList: String?
        ): Boolean {
            val properties = System.getProperties()
            properties.setProperty("http.proxyHost", host)
            properties.setProperty("http.proxyPort", port.toString())
            properties.setProperty("https.proxyHost", host)
            properties.setProperty("https.proxyPort", port.toString())
            properties.setProperty("http.nonProxyHosts", exclusionList)
            properties.setProperty("https.nonProxyHosts", exclusionList)

            try {
                val applictionCls = Class.forName("android.app.Application")
                val loadedApkField = applictionCls.getDeclaredField("mLoadedApk")
                loadedApkField.isAccessible = true
                val loadedApk = loadedApkField.get(appContext)
                val loadedApkCls = Class.forName("android.app.LoadedApk")
                val receiversField = loadedApkCls.getDeclaredField("mReceivers")
                receiversField.isAccessible = true
                val receivers = receiversField.get(loadedApk) as ArrayMap<*, *>?
                for (receiverMap in receivers!!.values) {
                    for (rec in (receiverMap as ArrayMap<*, *>).keys) {
                        val clazz: Class<*> = rec.javaClass
                        if (clazz.getName().contains("ProxyChangeListener")) {
                            val onReceiveMethod = clazz.getDeclaredMethod(
                                "onReceive",
                                Context::class.java,
                                Intent::class.java
                            )
                            val intent = Intent(Proxy.PROXY_CHANGE_ACTION)
                            val CLASS_NAME = "android.net.ProxyInfo"
                            val cls = Class.forName(CLASS_NAME)
                            val constructor: Constructor<*> = cls.getConstructor(
                                String::class.java,
                                Integer.TYPE,
                                String::class.java
                            )
                            constructor.isAccessible = true
                            val proxyProperties: Any =
                                constructor.newInstance(host, port, exclusionList)
                            intent.putExtra("proxy", proxyProperties as Parcelable)

                            onReceiveMethod.invoke(rec, appContext, intent)
                        }
                    }
                }
                return true
            } catch (e: Exception) {
                Log.w(TAG, "setWebViewProxy exception", e)
            }
            return false
        }
    }
}
