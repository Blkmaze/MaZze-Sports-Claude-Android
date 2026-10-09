package com.mazze.sportsclaude

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

object Store {
    var names: List<String> = emptyList()
    var urls: List<String> = emptyList()
    var order: IntArray = IntArray(0)
}

fun httpOpen(start: String): HttpURLConnection {
    var u = start
    var hops = 0
    while (true) {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 60000
        c.instanceFollowRedirects = false
        c.setRequestProperty("User-Agent", "MaZzeSports/1.0 (Android TV)")
        c.setRequestProperty("Accept", "*/*")
        val code = c.responseCode
        if (code in 300..399 && hops < 6) {
            val loc = c.getHeaderField("Location")
            if (loc != null) {
                u = URL(URL(u), loc).toString()
                c.disconnect()
                hops++
                continue
            }
        }
        return c
    }
}

class MainActivity : Activity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = true
        s.mediaPlaybackRequiresUserGesture = false
        web.setBackgroundColor(0xFF0D0D10.toInt())
        web.isFocusable = true
        web.isFocusableInTouchMode = true
        web.addJavascriptInterface(Bridge(), "Android")
        web.webViewClient = WebViewClient()
        web.loadUrl("file:///android_asset/index.html")
        web.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onPause() {
        super.onPause()
        web.onPause()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            web.evaluateJavascript("(window.__back ? window.__back() : false)") { r ->
                if (r != "true") finish()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    inner class Bridge {
        @JavascriptInterface
        fun httpGet(id: Int, url: String) {
            thread {
                var err: String? = null
                var text = ""
                try {
                    val c = httpOpen(url)
                    val code = c.responseCode
                    if (code in 200..299) {
                        text = c.inputStream.bufferedReader().use { it.readText() }
                    } else {
                        err = "HTTP " + code
                    }
                    c.disconnect()
                } catch (e: Exception) {
                    err = e.toString()
                }
                val e2 = if (err == null) "null" else JSONObject.quote(err)
                val js = "window.__cb(" + id + "," + e2 + "," + JSONObject.quote(text) + ")"
                runOnUiThread { web.evaluateJavascript(js, null) }
            }
        }

        @JavascriptInterface
        fun setChannels(json: String) {
            try {
                val arr = JSONArray(json)
                val n = ArrayList<String>(arr.length())
                val u = ArrayList<String>(arr.length())
                for (i in 0 until arr.length()) {
                    val p = arr.getJSONArray(i)
                    n.add(p.getString(0))
                    u.add(p.getString(1))
                }
                Store.names = n
                Store.urls = u
            } catch (e: Exception) {
                // ignore bad data
            }
        }

        @JavascriptInterface
        fun play(orderJson: String, pos: Int) {
            try {
                val arr = JSONArray(orderJson)
                Store.order = IntArray(arr.length()) { arr.getInt(it) }
                runOnUiThread {
                    val i = Intent(this@MainActivity, PlayerActivity::class.java)
                    i.putExtra("pos", pos)
                    startActivity(i)
                }
            } catch (e: Exception) {
                // ignore
            }
        }

        @JavascriptInterface
        fun openUrl(u: String) {
            runOnUiThread {
                try {
                    val i = Intent(Intent.ACTION_VIEW, Uri.parse(u))
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(i)
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "No browser found. Open: " + u, Toast.LENGTH_LONG).show()
                }
            }
        }

        @Suppress("DEPRECATION")
        @JavascriptInterface
        fun versionCode(): Int {
            return try {
                packageManager.getPackageInfo(packageName, 0).versionCode
            } catch (e: Exception) {
                0
            }
        }

        @JavascriptInterface
        fun installApk(url: String) {
            thread {
                try {
                    val dir = File(cacheDir, "apk")
                    dir.mkdirs()
                    val f = File(dir, "update.apk")
                    val c = httpOpen(url)
                    c.inputStream.use { inp -> f.outputStream().use { out -> inp.copyTo(out) } }
                    val uri = FileProvider.getUriForFile(this@MainActivity, packageName + ".fileprovider", f)
                    val inst = Intent(Intent.ACTION_VIEW)
                    inst.setDataAndType(uri, "application/vnd.android.package-archive")
                    inst.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    runOnUiThread { startActivity(inst) }
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Update failed: " + e.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
}

class PlayerActivity : Activity() {
    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView
    private lateinit var title: TextView
    private var pos = 0
    private var retries = 0
    private val h = Handler(Looper.getMainLooper())
    private val hide = Runnable { title.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this)
        root.setBackgroundColor(0xFF000000.toInt())
        view = PlayerView(this)
        view.useController = false
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        title = TextView(this)
        title.setTextColor(0xFFFFFFFF.toInt())
        title.textSize = 26f
        title.setPadding(32, 18, 32, 18)
        title.setBackgroundColor(0xB0000000.toInt())
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START)
        lp.setMargins(60, 0, 0, 60)
        root.addView(title, lp)
        setContentView(root)
        pos = intent.getIntExtra("pos", 0)
    }

    override fun onStart() {
        super.onStart()
        startPlayer()
        registerNet()
    }

    override fun onStop() {
        super.onStop()
        unregisterNet()
        h.removeCallbacksAndMessages(null)
        player?.release()
        player = null
    }

    private fun startPlayer() {
        val f = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("MaZzeSports/1.0")
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(30000)
        val lc = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(45000, 90000, 3000, 10000)
            .setTargetBufferBytes(64 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(20000, false)
            .build()
        val ts = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(this)
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(f))
            .setLoadControl(lc)
            .setTrackSelector(ts)
            .build()
        var stalls = 0
        var wasReady = false
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    wasReady = true
                    h.postDelayed({ if (stalls > 0) stalls-- }, 120000)
                } else if (state == Player.STATE_BUFFERING && wasReady) {
                    stalls++
                    if (stalls == 2) {
                        ts.setParameters(ts.buildUponParameters().setMaxVideoSize(1280, 720))
                    }
                }
            }
        })
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                retry()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) retries = 0
            }
        })
        view.player = p
        player = p
        playCurrent()
    }

    private var cm: android.net.ConnectivityManager? = null
    private var netCb: android.net.ConnectivityManager.NetworkCallback? = null
    private var lost = false

    private fun registerNet() {
        try {
            val c = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val cb = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onLost(n: android.net.Network) {
                    lost = true
                }
                override fun onAvailable(n: android.net.Network) {
                    if (lost) {
                        lost = false
                        h.post {
                            val pl = player
                            if (pl != null) {
                                pl.prepare()
                                pl.playWhenReady = true
                            }
                        }
                    }
                }
            }
            c.registerDefaultNetworkCallback(cb)
            cm = c
            netCb = cb
        } catch (t: Throwable) {
        }
    }

    private fun unregisterNet() {
        try {
            val cb = netCb
            if (cb != null) cm?.unregisterNetworkCallback(cb)
        } catch (t: Throwable) {
        }
        netCb = null
    }

    private fun playCurrent() {
        val o = Store.order
        if (o.isEmpty()) {
            finish()
            return
        }
        if (pos < 0) pos = o.size - 1
        if (pos >= o.size) pos = 0
        val idx = o[pos]
        if (idx < 0 || idx >= Store.urls.size) {
            finish()
            return
        }
        retries = 0
        val p = player ?: return
        p.setMediaItem(MediaItem.fromUri(Store.urls[idx]))
        p.prepare()
        p.playWhenReady = true
        showTitle(Store.names[idx] + "   (" + (pos + 1) + "/" + o.size + ")")
    }

    private fun retry() {
        if (retries < 3) {
            retries++
            h.postDelayed({ player?.prepare() }, 2000)
        } else {
            showTitle("Can't play this channel. Press Up or Down for another.")
        }
    }

    private fun showTitle(t: String) {
        title.text = t
        title.visibility = View.VISIBLE
        h.removeCallbacks(hide)
        h.postDelayed(hide, 4000)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                pos -= 1
                playCurrent()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                pos += 1
                playCurrent()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                val o = Store.order
                if (pos in o.indices && o[pos] in Store.names.indices) {
                    showTitle(Store.names[o[pos]] + "   (" + (pos + 1) + "/" + o.size + ")")
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
