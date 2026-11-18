package com.mdelba.tiktokdl

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var web: WebView
    private val io = Executors.newCachedThreadPool()

    @Volatile
    private var pending: String? = null

    private val ua = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private val host = "appassets.androidplatform.net"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readIntent(intent)

        web = WebView(this)
        web.setBackgroundColor(Color.BLACK)
        web.overScrollMode = WebView.OVER_SCROLL_NEVER
        setContentView(web)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            textZoom = 100
            setSupportZoom(false)
            mediaPlaybackRequiresUserGesture = true
        }

        val loader = WebViewAssetLoader.Builder()
            .setDomain(host)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean {
                if (request.url.host == host) return false
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                } catch (_: Exception) {
                }
                return true
            }
        }

        web.addJavascriptInterface(Bridge(), "Android")
        web.loadUrl("https://$host/assets/index.html")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
        // If the page is already loaded, hand over the link right away.
        web.evaluateJavascript(
            "window.onShared&&window.onShared(Android.takeShared())", null
        )
    }

    private fun readIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND && i.type?.startsWith("text/") == true) {
            pending = i.getStringExtra(Intent.EXTRA_TEXT)
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        web.evaluateJavascript("window.__back?window.__back():0") { r ->
            if (r != "1") finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    /** Send a result back to JavaScript. */
    private fun cb(id: String, kind: String, data: String) {
        runOnUiThread {
            web.evaluateJavascript(
                "window.__native(${JSONObject.quote(id)},${JSONObject.quote(kind)},${JSONObject.quote(data)})",
                null
            )
        }
    }

    /** Native bridge exposed to the page as `Android`. */
    inner class Bridge {

        @JavascriptInterface
        fun isNative(): Boolean = true

        @JavascriptInterface
        fun takeShared(): String {
            val s = pending ?: ""
            pending = null
            return s
        }

        @JavascriptInterface
        fun finish() {
            runOnUiThread { this@MainActivity.finish() }
        }

        @JavascriptInterface
        fun paste(id: String) {
            runOnUiThread {
                try {
                    val cm = getSystemService(ClipboardManager::class.java)
                    val t = cm.primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString() ?: ""
                    cb(id, "ok", t)
                } catch (e: Exception) {
                    cb(id, "err", "Clipboard unavailable")
                }
            }
        }

        @JavascriptInterface
        fun copy(text: String) {
            runOnUiThread {
                val cm = getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("link", text))
            }
        }

        /** Calls the tikwm API natively, so there are no CORS problems. */
        @JavascriptInterface
        fun lookup(id: String, link: String, region: String) {
            io.execute {
                try {
                    val c = URL("https://www.tikwm.com/api/").openConnection() as HttpURLConnection
                    c.requestMethod = "POST"
                    c.doOutput = true
                    c.connectTimeout = 15000
                    c.readTimeout = 25000
                    c.setRequestProperty("User-Agent", ua)
                    c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    var body = "url=" + URLEncoder.encode(link, "UTF-8") + "&hd=1"
                    if (region.isNotBlank()) body += "&region=" + URLEncoder.encode(region, "UTF-8")
                    c.outputStream.use { it.write(body.toByteArray()) }
                    val stream = if (c.responseCode < 400) c.inputStream else c.errorStream
                    val text = stream.bufferedReader().use { it.readText() }
                    cb(id, "ok", text)
                } catch (e: Exception) {
                    cb(id, "err", e.message ?: "Network error")
                }
            }
        }

        /** Streams a file into the public Movies / Music / Pictures folder with progress. */
        @JavascriptInterface
        fun download(id: String, url: String, name: String, mime: String) {
            io.execute { saveFile(id, url, name, mime) }
        }
    }

    private fun saveFile(id: String, url: String, name: String, mime: String) {
        val collection: Uri
        val dir: String
        when {
            mime.startsWith("video") -> {
                collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                dir = Environment.DIRECTORY_MOVIES
            }
            mime.startsWith("audio") -> {
                collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                dir = Environment.DIRECTORY_MUSIC
            }
            else -> {
                collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                dir = Environment.DIRECTORY_PICTURES
            }
        }
        val rel = "$dir/MD TikTok"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(collection, values)
        if (uri == null) {
            cb(id, "err", "Cannot create file")
            return
        }
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.setRequestProperty("User-Agent", ua)
            c.setRequestProperty("Referer", "https://www.tiktok.com/")
            if (c.responseCode !in 200..299) throw IOException("Server returned ${c.responseCode}")

            val total = c.contentLengthLong
            var done = 0L
            var lastTime = 0L
            var lastPct = -2

            c.inputStream.use { input ->
                contentResolver.openOutputStream(uri)!!.use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - lastTime > 120) {
                            lastTime = now
                            val p = if (total > 0) (done * 100 / total).toInt() else -1
                            if (p != lastPct) {
                                lastPct = p
                                cb(id, "progress", p.toString())
                            }
                        }
                    }
                }
            }
            if (done == 0L) throw IOException("Empty file")

            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            cb(id, "progress", "100")
            cb(id, "done", rel)
        } catch (e: Exception) {
            try {
                contentResolver.delete(uri, null, null)
            } catch (_: Exception) {
            }
            cb(id, "err", e.message ?: "Download failed")
        }
    }
}
