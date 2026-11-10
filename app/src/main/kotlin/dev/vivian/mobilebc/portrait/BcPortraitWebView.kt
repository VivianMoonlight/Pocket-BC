package dev.vivian.mobilebc.portrait

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume

internal enum class PortraitStatus { Loading, Ready, Partial, Failed, Unsupported }

@SuppressLint("SetJavaScriptEnabled")
internal class BcPortraitWebView(context: Context, resources: BcPortraitResources) : WebView(context) {
    var status by mutableStateOf(PortraitStatus.Loading)
        private set
    var omittedItems by mutableStateOf<List<String>>(emptyList())
        private set
    private var payload: String? = null
    private var revision = 0
    private var pageReady = false
    private var disposed = false
    private var paused = true
    private var statusPort: WebMessagePort? = null
    val isReleased: Boolean get() = disposed

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(false)
            setSupportZoom(false)
        }
        // No JavascriptInterface is exposed. Native state travels only through JSON snapshots.
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                resources.intercept(request.url.toString(), request.method)

            override fun onPageFinished(view: WebView, url: String) {
                if (disposed || url != PortraitEntryUrl) return
                pageReady = true
                statusPort?.close()
                val ports = createWebMessageChannel()
                statusPort = ports[0]
                ports[0].setWebMessageCallback(object : WebMessagePort.WebMessageCallback() {
                    override fun onMessage(port: WebMessagePort, message: WebMessage) {
                        if (!disposed && port === statusPort) message.data?.let(::receiveStatus)
                    }
                })
                postWebMessage(WebMessage("mobilebc-portrait-status", arrayOf(ports[1])), Uri.parse(PortraitOrigin))
                deliver()
                evaluateJavascript("window.mobileBcPausePortrait?.($paused)", null)
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                status = PortraitStatus.Failed
                release()
                return true
            }
        }
        loadUrl(PortraitEntryUrl)
    }

    fun updatePayload(value: String?) {
        if (disposed) return
        if (value != payload || value == null) omittedItems = emptyList()
        if (value == null) {
            payload = null
            revision++
            status = PortraitStatus.Unsupported
            evaluateJavascript("window.mobileBcClearPortrait?.($revision)", null)
            return
        }
        if (value == payload) return
        revision++
        payload = value
        status = PortraitStatus.Loading
        if (pageReady) deliver()
    }

    private fun deliver() {
        val snapshot = payload ?: return
        // JSON is passed as a quoted string rather than interpolated into executable source.
        evaluateJavascript("window.mobileBcSetPortrait?.(JSON.parse(${JSONObject.quote(snapshot)}),$revision)", null)
    }

    fun setActive(active: Boolean) {
        if (disposed) return
        paused = !active
        if (active) onResume()
        evaluateJavascript("window.mobileBcPausePortrait?.($paused)", null)
        if (!active) onPause()
    }

    private fun receiveStatus(raw: String) {
        if (payload == null) return
        val result = runCatching { JSONObject(raw) }.getOrNull() ?: return
        if (result.optInt("revision", -1) != revision) return
        omittedItems = result.optJSONArray("omitted")?.let { items ->
            (0 until items.length()).mapNotNull { index ->
                items.optJSONObject(index)?.optString("name")?.takeIf { it.isNotBlank() }
            }.distinct()
        }.orEmpty()
        status = when (result.optString("state")) {
            "ready" -> PortraitStatus.Ready
            "partial" -> PortraitStatus.Partial
            "failed" -> PortraitStatus.Failed
            "unsupported" -> PortraitStatus.Unsupported
            else -> PortraitStatus.Loading
        }
    }

    suspend fun captureFrame(): String? {
        if (disposed || status != PortraitStatus.Ready) return null
        val requestedRevision = revision
        val raw = suspendCancellableCoroutine<String?> { continuation ->
            evaluateJavascript("window.mobileBcPortraitStatus?.revision===$requestedRevision && window.mobileBcPortraitStatus.state==='ready' ? document.getElementById('MainCanvas').toDataURL('image/png') : null") { value ->
                if (continuation.isActive) continuation.resume(value)
            }
        }
        return raw?.takeIf { !disposed && revision == requestedRevision && status == PortraitStatus.Ready }
    }

    suspend fun captureGeometry(): PortraitGeometry? {
        if (disposed || status != PortraitStatus.Ready && status != PortraitStatus.Partial) return null
        val requestedRevision = revision
        val raw = suspendCancellableCoroutine<String?> { continuation ->
            evaluateJavascript("""(() => {
                if (window.mobileBcPortraitStatus?.revision !== $requestedRevision || !['ready', 'partial'].includes(window.mobileBcPortraitStatus.state)) return null;
                const geometry = window.mobileBcPortraitGeometry?.();
                return geometry ? JSON.stringify(geometry) : null;
            })()""") { value -> if (continuation.isActive) continuation.resume(value) }
        }
        if (disposed || revision != requestedRevision || status != PortraitStatus.Ready && status != PortraitStatus.Partial || raw == null) return null
        return runCatching { PortraitGeometry.parse(org.json.JSONArray("[$raw]").getString(0)) }.getOrNull()
    }

    fun loadingTimedOut() {
        if (!disposed && status == PortraitStatus.Loading) status = PortraitStatus.Failed
    }

    fun release() {
        if (disposed) return
        disposed = true
        pageReady = false
        payload = null
        statusPort?.close()
        statusPort = null
        stopLoading()
        onPause()
        (parent as? ViewGroup)?.removeView(this)
        removeAllViews()
        destroy()
    }

    fun prepareForReuse(): Boolean {
        if (disposed || status == PortraitStatus.Failed) return false
        (parent as? ViewGroup)?.removeView(this)
        setActive(false)
        updatePayload(null)
        return true
    }
}
