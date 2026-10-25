package com.overwhelmedyy.espvocab

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import org.json.JSONObject
import java.util.Locale

/**
 * 整个应用就是一个 WebView，加载打包在 assets 里的 web/index.html。
 * 原生这边只补网页在 WebView 里做不到的几件事：
 *  - 朗读：WebView 不支持 speechSynthesis，改用系统 TextToSpeech（window.Android.speak）
 *  - 保存备份文件：用系统"另存为"对话框（window.Android.saveFile）
 *  - 选择要导入的文件：响应网页里的 <input type="file">
 *  - 返回键：先交给网页处理（window.appBack），网页不处理时才退出
 */
class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private lateinit var web: WebView
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsInitDone = false
    private var ttsWarned = false

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingSave: String? = null

    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        fileCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
        fileCallback = null
    }

    private val createDoc = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val body = pendingSave
        pendingSave = null
        if (uri == null || body == null) {
            reportSave(false, "已取消")
            return@registerForActivityResult
        }
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            reportSave(true, uri.lastPathSegment?.substringAfterLast('/') ?: "")
        } catch (e: Exception) {
            reportSave(false, e.message ?: "写入失败")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 边到边显示：状态栏颜色跟随网页背景，内容避开系统栏
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.page_bg))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        tts = TextToSpeech(this, this)

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.page_bg))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true          // localStorage：保存学习进度
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClientCompat() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                    assetLoader.shouldInterceptRequest(request.url)

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url
                    if (url.host == APP_HOST) return false
                    // 其他链接交给系统浏览器打开
                    try { startActivity(Intent(Intent.ACTION_VIEW, url)) } catch (_: ActivityNotFoundException) {}
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    webView: WebView,
                    callback: ValueCallback<Array<Uri>>,
                    params: FileChooserParams
                ): Boolean {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = callback
                    return try {
                        pickFile.launch("*/*")
                        true
                    } catch (e: ActivityNotFoundException) {
                        fileCallback = null
                        false
                    }
                }
            }
            addJavascriptInterface(Bridge(), "Android")
        }

        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        else web.loadUrl("https://$APP_HOST/assets/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("(window.appBack && window.appBack()) === true") { handled ->
                    if (handled != "true") {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    // ---- 朗读 ----
    override fun onInit(status: Int) {
        ttsInitDone = true
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) return
        val candidates = listOf(Locale("es", "ES"), Locale("es"), Locale("es", "US"), Locale("es", "MX"))
        ttsReady = candidates.any { loc ->
            val r = engine.setLanguage(loc)
            r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        }
        engine.setSpeechRate(0.9f)
    }

    private fun speak(text: String) {
        val engine = tts
        if (engine == null || !ttsInitDone) return
        if (!ttsReady) {
            if (!ttsWarned) {
                ttsWarned = true
                Toast.makeText(this, R.string.tts_missing, Toast.LENGTH_LONG).show()
            }
            return
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "word")
    }

    private fun reportSave(ok: Boolean, info: String) {
        web.evaluateJavascript("window.onSaveResult && window.onSaveResult($ok, ${JSONObject.quote(info)})", null)
    }

    inner class Bridge {
        @JavascriptInterface
        fun speak(text: String) = runOnUiThread { this@MainActivity.speak(text) }

        @JavascriptInterface
        fun saveFile(name: String, body: String) = runOnUiThread {
            pendingSave = body
            try {
                createDoc.launch(name)
            } catch (e: ActivityNotFoundException) {
                pendingSave = null
                reportSave(false, "系统没有可用的文件管理器")
            }
        }

        @JavascriptInterface
        fun appVersion(): String = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (_: Exception) { "" }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        web.destroy()
        super.onDestroy()
    }

    companion object {
        private const val APP_HOST = "appassets.androidplatform.net"
    }
}
