package com.doodlepop.app

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/**
 * Full-screen Activity that renders Vertex's interactive HTML5 creative inside
 * a WebView. Launched by [VertexCustomEventInterstitial] when AdMob's mediation
 * waterfall routes an interstitial impression to the Vertex custom event.
 *
 * The WebView loads `<workerBase>/simid.html` (vertex's SIMID-compliant page
 * meant to be hosted in an IMA SDK iframe). We monkey-patch `window.parent`
 * inside the page so its outbound `postMessage` calls reach a Kotlin
 * @JavascriptInterface bridge, and drive the createSession / init /
 * startCreative handshake from native code — no IMA / Google Mobile Ads SDK
 * dependency required at runtime to render the creative itself.
 *
 * AdMob lifecycle:
 *  - Adapter calls onAdOpened + reportAdImpression when this Activity is
 *    launched (in showAd).
 *  - When the user dismisses (back press or click-through to landing), the
 *    Activity finishes and onDestroy fires onAdClosed via the shared
 *    callback in [VertexCustomEventInterstitial.activeCallback].
 */
class VertexInterstitialActivity : AppCompatActivity() {

    private var webView: WebView? = null
    private val sessionId = "native-${System.currentTimeMillis()}"

    private lateinit var workerBase: String
    private lateinit var campaignId: String
    private var landingUrl: String = ""
    private var duration: String = "00:00:30"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        workerBase = intent.getStringExtra(EXTRA_WORKER_BASE).orEmpty()
        campaignId = intent.getStringExtra(EXTRA_CAMPAIGN).orEmpty()
        landingUrl = intent.getStringExtra(EXTRA_LANDING).orEmpty()
        duration = intent.getStringExtra(EXTRA_DURATION) ?: "00:00:30"

        if (workerBase.isEmpty() || campaignId.isEmpty()) {
            Log.e(TAG, "Missing workerBase or campaign — closing")
            finish()
            return
        }

        Log.i(TAG, "onCreate · workerBase=$workerBase campaign=$campaignId")

        // Enable Chrome DevTools remote debugging (chrome://inspect) so we can
        // see the SIMID page live when triaging render issues.
        WebView.setWebContentsDebuggingEnabled(true)

        val container = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
        }

        val wv = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            // Opaque background so the creative's alpha channel composites onto
            // black instead of whatever sits behind the WebView.
            setBackgroundColor(Color.BLACK)

            addJavascriptInterface(object {
                @JavascriptInterface
                fun onSimidEvent(raw: String) = post { handleSimidEvent(raw) }

                @JavascriptInterface
                fun onConsoleLog(line: String) = Log.d(TAG, "[js] $line")
            }, "NativeSimidHost")

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    injectHostEmulator(view)
                }
            }
        }

        webView = wv
        container.addView(wv)
        setContentView(container)

        val url = "$workerBase/simid.html"
        Log.i(TAG, "loading SIMID: $url")
        wv.loadUrl(url)
    }

    override fun onDestroy() {
        webView?.apply {
            (parent as? ViewGroup)?.removeView(this)
            destroy()
        }
        webView = null

        // Notify AdMob's mediation SDK that the user dismissed the ad. Cleared
        // after firing so a subsequent load() doesn't double-fire.
        VertexCustomEventInterstitial.activeCallback?.onAdClosed()
        VertexCustomEventInterstitial.activeCallback = null
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // SIMID handshake
    // -------------------------------------------------------------------------

    private fun sendToSimid(msg: JSONObject, then: (() -> Unit)? = null) {
        val escaped = msg.toString()
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
        val js = "window.postMessage('$escaped', '*');"
        val wv = webView ?: return
        wv.post { wv.evaluateJavascript(js) { _ -> then?.invoke() } }
    }

    private fun sendCreateSession(onSent: () -> Unit) {
        val msg = JSONObject().apply {
            put("type", "createSession")
            put("sessionId", sessionId)
            put("messageId", 1)
        }
        sendToSimid(msg) { onSent() }
    }

    private fun sendInit() {
        val adParams = JSONObject().apply {
            put("src", "$workerBase/$campaignId/vrt")
            put("audio", "$workerBase/$campaignId/audio")
            put("campaign", campaignId)
            put("landing", landingUrl)
            put("duration", duration)
        }
        val msg = JSONObject().apply {
            put("type", "init")
            put("args", JSONObject().apply {
                put("creativeData", JSONObject().apply {
                    put("adParameters", adParams.toString())
                })
            })
            put("messageId", 2)
        }
        sendToSimid(msg)
    }

    private fun sendStartCreative() {
        val msg = JSONObject().apply {
            put("type", "startCreative")
            put("messageId", 3)
        }
        sendToSimid(msg)
    }

    private fun handleSimidEvent(rawJson: String) {
        val json = try {
            JSONObject(rawJson)
        } catch (e: Exception) {
            Log.w(TAG, "malformed SIMID payload: $rawJson")
            return
        }
        when (json.optString("type", "")) {
            "initializeComplete" -> sendStartCreative()
            "requestNavigate" -> {
                val uri = json.optJSONObject("args")?.optString("uri").orEmpty()
                if (uri.isNotEmpty()) openClickThrough(uri)
            }
            "fatalError" -> {
                val code = json.optJSONObject("args")?.opt("errorCode")
                Log.e(TAG, "creative fatalError · errorCode=$code — closing")
                finish()
            }
            "stopCreativeComplete" -> finish()
        }
    }

    private fun openClickThrough(uri: String) {
        try {
            VertexCustomEventInterstitial.activeCallback?.reportAdClicked()
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "failed to open click-through: ${e.message}")
        }
        // Treat click-through as ad end so AdMob's frequency rules see it.
        finish()
    }

    // -------------------------------------------------------------------------
    // Host emulator injection
    // -------------------------------------------------------------------------

    private fun injectHostEmulator(view: WebView) {
        // Force the WebGL path (WebView's WebGPU is unstable on many devices),
        // route window.parent.postMessage to native, then kick off the handshake.
        val emulator = """
            (function() {
              var native = window.NativeSimidHost;
              try {
                Object.defineProperty(navigator, 'gpu', {
                  value: undefined, configurable: true, writable: false
                });
              } catch (e) {}
              try {
                Object.defineProperty(window, 'parent', {
                  value: {
                    postMessage: function(data) {
                      try {
                        native.onSimidEvent(
                          typeof data === 'string' ? data : JSON.stringify(data)
                        );
                      } catch (e) {}
                    }
                  },
                  writable: false, configurable: false
                });
              } catch (e) {}
              ['log','warn','error','info'].forEach(function(level){
                var orig = console[level].bind(console);
                console[level] = function(){
                  try {
                    var s = Array.prototype.slice.call(arguments).map(function(a){
                      if (a === null || a === undefined) return String(a);
                      if (typeof a === 'object') {
                        try { return JSON.stringify(a); } catch (e) { return String(a); }
                      }
                      return String(a);
                    }).join(' ');
                    native.onConsoleLog(level + ': ' + s);
                  } catch (e) {}
                  orig.apply(console, arguments);
                };
              });
            })();
        """.trimIndent()

        view.evaluateJavascript(emulator) { _ ->
            sendCreateSession { sendInit() }
        }
    }

    companion object {
        private const val TAG = "VertexInterstitial"

        const val EXTRA_WORKER_BASE = "workerBase"
        const val EXTRA_CAMPAIGN = "campaign"
        const val EXTRA_LANDING = "landing"
        const val EXTRA_DURATION = "duration"
    }
}
