package com.heshamtaha.masrofi

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.HapticFeedbackConstants
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pausedAt = 0L
    private var quickAdd = false

    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        fileCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
        fileCallback = null
    }
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        root = FrameLayout(this)
        web = WebView(this)
        root.addView(web, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        // Keep the page clear of the status bar, navigation bar and keyboard
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsetsCompat.CONSUMED
        }

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            textZoom = 100
            setSupportZoom(false)
        }
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.setBackgroundColor(ContextCompat.getColor(this, R.color.app_bg))

        web.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                // Opened from the "مصروف جديد" shortcut: show the add sheet once the page is ready
                if (quickAdd) {
                    quickAdd = false
                    js("window.__quickAdd && window.__quickAdd()")
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == APP_HOST) return false
                // Facebook / YouTube / GitHub links open outside the app
                try { startActivity(Intent(Intent.ACTION_VIEW, request.url)) } catch (_: Exception) { }
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?, callback: ValueCallback<Array<Uri>>?, params: FileChooserParams?
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                val wantsImage = params?.acceptTypes?.any { it.startsWith("image") } == true
                return try { pickFile.launch(if (wantsImage) "image/*" else "*/*"); true } catch (_: Exception) { fileCallback = null; false }
            }
        }

        web.addJavascriptInterface(Bridge(), "AndroidBridge")
        quickAdd = intent?.action == ACTION_ADD_EXPENSE
        if (savedInstanceState == null) web.loadUrl("https://$APP_HOST/assets/www/index.html")
        else web.restoreState(savedInstanceState)

        Reminders.createChannel(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)

        // Back button: close sheets / go to the home tab first, then leave the app
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("window.__onBack ? window.__onBack() : false") { result ->
                    if (result != "true") {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // App already open in the background and the shortcut was tapped
        if (intent.action == ACTION_ADD_EXPENSE) js("window.__quickAdd && window.__quickAdd()")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        pausedAt = System.currentTimeMillis()
    }

    override fun onResume() {
        super.onResume()
        // Lock again if the app was in the background for more than 30 seconds
        if (pausedAt > 0 && System.currentTimeMillis() - pausedAt > 30_000) {
            js("window.__relock && window.__relock()")
        }
        pausedAt = 0
    }

    private fun js(code: String) = web.post { web.evaluateJavascript(code, null) }

    /** Methods the web app calls through window.AndroidBridge */
    inner class Bridge {

        @JavascriptInterface
        fun saveFile(name: String, base64: String, mime: String): Boolean = try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Masrofi")
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
                contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            } else {
                val dir = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Masrofi").apply { mkdirs() }
                File(dir, name).writeBytes(bytes)
            }
            true
        } catch (_: Exception) { false }

        @JavascriptInterface
        fun shareFile(name: String, base64: String, mime: String): Boolean = try {
            val dir = File(cacheDir, "share").apply { mkdirs() }
            val file = File(dir, name).apply { writeBytes(Base64.decode(base64, Base64.DEFAULT)) }
            val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runOnUiThread { startActivity(Intent.createChooser(send, "احفظ النسخة الاحتياطية")) }
            true
        } catch (_: Exception) { false }

        @JavascriptInterface
        fun haptic() = runOnUiThread {
            root.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }

        @JavascriptInterface
        fun canBiometric(): Boolean =
            BiometricManager.from(this@MainActivity).canAuthenticate(BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS

        @JavascriptInterface
        fun authenticate() = runOnUiThread {
            val prompt = BiometricPrompt(this@MainActivity, ContextCompat.getMainExecutor(this@MainActivity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        js("window.__bioResult && window.__bioResult(true)")
                    }
                    override fun onAuthenticationError(code: Int, msg: CharSequence) {
                        js("window.__bioResult && window.__bioResult(false)")
                    }
                })
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle("افتح مصروفي")
                .setSubtitle("استخدم بصمتك")
                .setNegativeButtonText("الرقم السري")
                .setAllowedAuthenticators(BIOMETRIC_WEAK)
                .build()
            prompt.authenticate(info)
        }

        @JavascriptInterface
        fun syncReminders(json: String) {
            Reminders.save(this@MainActivity, json)
            Reminders.apply(this@MainActivity)
        }

        @JavascriptInterface
        fun setBars(color: String, dark: Boolean) = runOnUiThread {
            try { root.setBackgroundColor(Color.parseColor(color)) } catch (_: Exception) { }
            WindowCompat.getInsetsController(window, root).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    companion object {
        const val APP_HOST = "appassets.androidplatform.net"
        const val ACTION_ADD_EXPENSE = "com.heshamtaha.masrofi.ADD_EXPENSE"
    }
}
