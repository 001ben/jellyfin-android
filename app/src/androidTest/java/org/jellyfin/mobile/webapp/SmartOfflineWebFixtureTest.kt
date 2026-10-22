package org.jellyfin.mobile.webapp

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Synthetic local Jellyfin Web 10.11-shaped DOM; no real server, account, media or native actions. */
class SmartOfflineWebFixtureTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled") // The tested asset is JavaScript; networking and file/content access are disabled.
    private fun show() {
        val asset = instrumentation.targetContext.assets.open("native/SmartOfflinePlugin.js").bufferedReader().use { it.readText() }
        val script = asset.replace(Regex("(?m)^export (?=(?:function|class))"), "")
        compose.setContent {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                WebView(context).apply {
                    web = this
                    settings.javaScriptEnabled = true
                    settings.blockNetworkLoads = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                            WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(byteArrayOf()))
                    }
                    loadDataWithBaseURL("https://offline-fixture.invalid", html(script), "text/html", "UTF-8", null)
                }
            })
        }
        compose.waitUntil(10_000) { evaluate("Boolean(window.__fixtureReady)") == "true" }
        compose.waitUntil(10_000) {
            evaluate("document.querySelectorAll('#itemDetailPage .smartOfflineAction').length") == "2"
        }
    }

    @Test fun rendersClassicCardsReactListAndEpisodeDetailsWithoutScheduling() {
        show()
        assertEquals("true", evaluate("document.body.innerText.includes('Downloaded · Watched · Sync pending')"))
        assertEquals("true", evaluate("document.body.innerText.includes('Downloaded · Watched · Cleanup eligible')"))
        assertEquals("true", evaluate("document.body.innerText.includes('Downloading 42%')"))
        assertEquals("true", evaluate("document.body.innerText.includes('Selected for automatic downloads')"))
        assertEquals("0", evaluate("document.querySelectorAll('#foreign .smartOfflineNative').length"))
        assertEquals("true", evaluate("window.__fixtureRequests.every(r => r.action === 'snapshot')"))
        assertEquals("true", evaluate("window.SmartOfflinePlayback.canPlay({Id: '$EPISODE', ServerId: '$SERVER'})"))
        screenshot("11-web-context-fixture")
    }

    @Test fun genuineTouchOpensContextOrPlaysLocalWhileSyntheticClickDoesNothing() {
        show()
        evaluate("document.querySelector('#show .smartOfflineAction').click()")
        assertEquals("0", evaluate("window.__fixtureRequests.filter(r => r.action === 'manage').length"))
        touch("#show .smartOfflineAction")
        compose.waitUntil(5_000) { evaluate("window.__fixtureRequests.filter(r => r.action === 'manage').length") == "1" }
        assertEquals("\"$SERIES\"", evaluate("window.__fixtureRequests.find(r => r.action === 'manage').seriesId"))
        touch("#episode .smartOfflineAction")
        compose.waitUntil(5_000) { evaluate("window.__fixtureRequests.filter(r => r.action === 'play').length") == "1" }
        assertEquals("\"$EPISODE\"", evaluate("window.__fixtureRequests.find(r => r.action === 'play').itemId"))
        screenshot("12-web-context-tapped-fixture")
    }

    private fun evaluate(script: String): String {
        val complete = CountDownLatch(1)
        var result = "null"
        instrumentation.runOnMainSync { web.evaluateJavascript(script) { result = it; complete.countDown() } }
        assertTrue("WebView evaluation timed out", complete.await(10, TimeUnit.SECONDS))
        return result
    }

    private fun touch(selector: String) {
        val raw = evaluate(
            "(function(){const b=document.querySelector('$selector');b.scrollIntoView({block:'center'});" +
                "const r=b.getBoundingClientRect();return JSON.stringify({x:(r.left+r.width/2)*devicePixelRatio," +
                "y:(r.top+r.height/2)*devicePixelRatio});})()",
        )
        val point = JSONObject(JSONTokener(raw).nextValue() as String)
        waitForVisualState()
        val location = IntArray(2)
        instrumentation.runOnMainSync { web.getLocationOnScreen(location) }
        val x = location[0] + point.getDouble("x").toFloat()
        val y = location[1] + point.getDouble("y").toFloat()
        val now = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, x, y, 0)
            try {
                assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        waitForVisualState()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "smart-offline-qa")
        val file = File(directory, "$name.png")
        file.parentFile?.mkdirs()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun waitForVisualState() {
        val complete = CountDownLatch(1)
        instrumentation.runOnMainSync {
            web.postVisualStateCallback(1, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) { complete.countDown() }
            })
        }
        assertTrue("WebView rendering timed out", complete.await(10, TimeUnit.SECONDS))
    }

    @After fun close() {
        if (::web.isInitialized) instrumentation.runOnMainSync { web.destroy() }
    }

    private fun html(plugin: String): String = """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>
        body{font:14px sans-serif;background:#101820;color:white;margin:12px}h1{font-size:19px}h2{font-size:16px}
        .fixture{font-size:12px;color:#cbd5df}.row{display:flex;gap:8px}.card{width:48%;background:#263544}
        .cardImageContainer{position:relative;display:block;height:105px;background:linear-gradient(135deg,#225365,#46617d)}
        .name{padding:7px}.emby-button{background:#11758b;border:0;border-radius:4px;color:white;padding:8px}
        .listItem{margin:12px 0;padding:8px;background:#263544}.listItemImage{position:relative;height:62px;background:#33596b}
        .mainDetailButtons{background:#263544;padding:8px}.hide{display:none}
        </style></head><body><h1>Contextual offline downloads</h1>
        <p class="fixture">SYNTHETIC LOCAL TEST · No server or media</p>
        <div class="row">
        <div id="show" class="card" data-id="$SERIES" data-type="Series" data-serverid="$SERVER">
        <a class="cardImageContainer" href="#ignored"></a><div class="name">Fixture series</div></div>
        <div id="episode" class="card" data-id="$EPISODE" data-type="Episode" data-serverid="$SERVER">
        <a class="cardImageContainer" href="#ignored"></a><div class="name">S1:E1 · Complete local copy</div></div>
        </div>
        <div class="listItem" data-id="$PARTIAL" data-type="Episode" data-serverid="$SERVER">
        <div class="listItemImage"></div><span>React list fixture · S1:E2</span></div>
        <div class="listItem" data-id="$CLEANUP" data-type="Episode" data-serverid="$SERVER">
        <span>Watched local copy · No pending sync</span></div>
        <div id="foreign" class="card hide" data-id="$EPISODE" data-type="Episode" data-serverid="$FOREIGN"></div>
        <div id="itemDetailPage" class="itemDetailPage"><h2>Episode detail fixture</h2><div class="mainDetailButtons"></div></div>
        <script>
        $plugin
        window.__fixtureRequests=[];
        window.SmartOfflineBridge={postMessage(raw){const r=JSON.parse(raw);window.__fixtureRequests.push(r);
          const items=(r.itemIds||[]).map(id=>({id,selected:id==='$SERIES',
            phase:id==='$EPISODE'||id==='$CLEANUP'?'COMPLETE':id==='$PARTIAL'?'DOWNLOADING':'',progress:.42,
            watched:id==='$EPISODE'||id==='$CLEANUP',pendingSync:id==='$EPISODE',cleanupEligible:id==='$CLEANUP',
            canPlayLocal:id==='$EPISODE'||id==='$CLEANUP'}));
          this.onmessage({data:JSON.stringify({requestId:r.requestId,items})});}};
        const api={getCurrentUserId:()=>'$USER',serverInfo:()=>({Id:'$SERVER'}),
          getItem:()=>Promise.resolve({Id:'$EPISODE',ServerId:'$SERVER',SeriesId:'$SERIES',Type:'Episode'})};
        location.hash='/details?id=$EPISODE&serverId=$SERVER';
        window.__fixturePlugin=new SmartOfflinePlugin({events:{on(){}},ServerConnections:{currentApiClient:()=>api}});
        window.__fixtureReady=true;
        </script></body></html>
    """.trimIndent()

    private companion object {
        const val SERVER = "11111111-1111-1111-1111-111111111111"
        const val USER = "22222222-2222-2222-2222-222222222222"
        const val FOREIGN = "33333333-3333-3333-3333-333333333333"
        const val EPISODE = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        const val SERIES = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        const val PARTIAL = "cccccccc-cccc-cccc-cccc-cccccccccccc"
        const val CLEANUP = "dddddddd-dddd-dddd-dddd-dddddddddddd"
    }
}
