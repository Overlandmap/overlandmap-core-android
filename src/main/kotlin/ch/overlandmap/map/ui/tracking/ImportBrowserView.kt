package ch.overlandmap.map.ui.tracking

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceResponse
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import ch.overlandmap.map.tracking.GpxDownloadParser
import org.json.JSONObject
import ch.overlandmap.map.tracking.readLimited
import java.net.HttpURLConnection
import java.net.URL
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/** Real child WebViews preserve window.opener, postMessage and login flows in popup windows. */
@SuppressLint("SetJavaScriptEnabled")
internal class ImportBrowserView(
    context: Context,
    private val onNavigation: (url: String?, canGoBack: Boolean, canGoForward: Boolean, progress: Int) -> Unit,
    private val onDownload: (url: String, agent: String, disposition: String?, mime: String?, source: String) -> Unit,
    private val onFile: (bytes: ByteArray, name: String, source: String) -> Unit,
    private val onTransferStarted: () -> Unit,
    private val onBlob: (base64: String, name: String, source: String) -> Unit,
    private val onDownloadError: () -> Unit,
    private val onChooseFile: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Boolean,
) : FrameLayout(context) {
    private val windows = mutableListOf<WebView>()
    private val committedPages = mutableMapOf<WebView, String>()
    private val suggestedNames = mutableMapOf<WebView, String>()
    private val chromiumPostUrls = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val active: WebView? get() = windows.lastOrNull()
    private var initialAddress = ""
    private var disposed = false

    fun open(address: String) {
        initialAddress = address
        createWindow().loadUrl(address)
    }

    private fun notifyNavigation() {
        val view = active ?: return
        onNavigation(view.url, view.canGoBack() || windows.size > 1, view.canGoForward(), view.progress)
    }

    private fun sourcePage(view: WebView): String =
        committedPages[view] ?: view.url?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            ?: windows.asReversed().mapNotNull { it.url }.firstOrNull { it.startsWith("https://") || it.startsWith("http://") }
            ?: initialAddress

    private fun closeWindow(view: WebView) {
        if (view !in windows) return
        windows.remove(view)
        committedPages.remove(view)
        suggestedNames.remove(view)
        removeView(view)
        view.stopLoading()
        view.removeJavascriptInterface(BLOB_BRIDGE)
        view.destroy()
        active?.visibility = View.VISIBLE
        notifyNavigation()
    }

    /** Returns false when the browser's owner should close the whole browser. */
    fun goBack(): Boolean {
        val view = active ?: return false
        return when {
            view.canGoBack() -> { view.goBack(); true }
            windows.size > 1 -> { closeWindow(view); true }
            else -> false
        }
    }

    fun goForward() { active?.goForward() }
    fun closePopup(): Boolean {
        if (windows.size <= 1) return false
        active?.let(::closeWindow)
        return true
    }

    fun dispose() {
        disposed = true
        windows.toList().asReversed().forEach(::closeWindow)
    }

    private fun finishFile(view: WebView, bytes: ByteArray, name: String) {
        if (disposed) { return }
        val source = if (view in windows) sourcePage(view) else active?.let(::sourcePage) ?: initialAddress
        val fileName = suggestedNames.remove(view)?.takeIf { it.isNotBlank() } ?: name
        // target=_blank downloads create a window with no committed document.
        if (view in windows && windows.size > 1 && committedPages[view] == null) closeWindow(view)
        onFile(bytes, fileName, source)
    }

    private fun noNavigation() = WebResourceResponse("text/plain", "UTF-8", 204, "No Content", emptyMap(), ByteArrayInputStream(byteArrayOf()))

    /** Inspect navigation responses once, and hand the original file body directly to the parser. */
    private fun interceptNavigation(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        if (!request.isForMainFrame) return null
        if (request.method == "POST") chromiumPostUrls.add(request.url.toString())
        if (request.method != "GET" || request.url.scheme !in listOf("http", "https")) {
            return null
        }
        var connection: HttpURLConnection? = null
        var readingFile = false
        try {
            val original = URL(request.url.toString())
            var current = original
            for (redirect in 0..10) {
                require(current.protocol in listOf("http", "https"))
                val c = current.openConnection() as HttpURLConnection
                connection = c
                c.instanceFollowRedirects = false
                c.connectTimeout = 20000; c.readTimeout = 30000
                request.requestHeaders.forEach { (key, value) ->
                    if (!key.equals("Cookie", true) && !key.equals("Host", true) &&
                        (!key.equals("Authorization", true) || current.protocol == original.protocol && current.authority == original.authority)) {
                        c.setRequestProperty(key, value)
                    }
                }
                c.setRequestProperty("Accept-Encoding", "identity")
                val cookies = CookieManager.getInstance().getCookie(current.toString())
                cookies?.let { c.setRequestProperty("Cookie", it) }
                val status = c.responseCode
                c.headerFields.filterKeys { it?.equals("Set-Cookie", true) == true }.values.flatten().forEach {
                    CookieManager.getInstance().setCookie(current.toString(), it)
                }
                if (status in listOf(301, 302, 303, 307, 308)) {
                    val location = c.getHeaderField("Location") ?: error("Missing redirect")
                    current = URL(current, location)
                    c.disconnect()
                    continue
                }
                if (status !in 200..299) {
                    c.disconnect(); return null
                }
                val mime = c.contentType?.substringBefore(';')?.trim()?.lowercase()
                val disposition = c.getHeaderField("Content-Disposition")
                val compressed = c.contentEncoding?.lowercase()
                if (compressed !in setOf(null, "identity", "gzip", "deflate")) {
                    c.disconnect(); return null
                }
                val raw = c.inputStream
                val stream = BufferedInputStream(when (compressed) {
                    "gzip" -> GZIPInputStream(raw)
                    "deflate" -> InflaterInputStream(raw)
                    else -> raw
                })
                var file = isTrackFileResponse(mime, disposition)
                // Some servers return GPX as plain XML or binary data with no filename header.
                // Check the actual body; a .gpx URL returning a login page remains a webpage.
                if (!file) {
                    stream.mark(4097)
                    val prefix = stream.readLimited(4096)
                    stream.reset()
                    file = looksLikeTrackFile(prefix)
                }
                if (file) {
                    readingFile = true
                    mainHandler.post { if (!disposed) onTransferStarted() }
                    val name = android.webkit.URLUtil.guessFileName(current.toString(), disposition, mime)
                    val bytes = stream.use { it.readLimited(GpxDownloadParser.MAX_BYTES + 1) }
                    c.disconnect()
                    require(bytes.size <= GpxDownloadParser.MAX_BYTES)
                    mainHandler.post { finishFile(view, bytes, name) }
                    return noNavigation()
                }
                if (current != original) {
                    // Normal redirected pages must have their real origin and URL in Chromium.
                    stream.close(); c.disconnect()
                    val destination = current.toString()
                    mainHandler.post { if (!disposed && view in windows) view.loadUrl(destination) }
                    return noNavigation()
                }
                val headers = c.headerFields.filterKeys {
                    it != null && !it.equals("Set-Cookie", true) && !it.equals("Content-Encoding", true) && !it.equals("Content-Length", true)
                }.map { (key, value) -> key!! to value.joinToString(", ") }.toMap()
                val charset = Regex("charset=([^; ]+)", RegexOption.IGNORE_CASE).find(c.contentType.orEmpty())?.groupValues?.get(1)?.trim('"')
                val body = object : java.io.FilterInputStream(stream) {
                    override fun close() { try { super.close() } finally { c.disconnect() } }
                }
                return WebResourceResponse(mime ?: "application/octet-stream", charset, status, "OK", headers, body)
            }
            error("Too many redirects")
        } catch (error: Exception) {
            connection?.disconnect()
            if (readingFile) {
                mainHandler.post { if (!disposed) onDownloadError() }
                return noNavigation()
            }
            return null
        }
    }

    private fun installDownloadHook(view: WebView) {
        // Chromium's download-attribute path skips navigation interception. Turn that
        // user action into a navigation so its first response body reaches the importer.
        view.evaluateJavascript("""
            (function() {
                if (window.__overlandTrackDownloads) return;
                window.__overlandTrackDownloads = true;
                var originalSubmit = HTMLFormElement.prototype.submit;
                function downloadPost(form, submitter) {
                    var action = submitter && submitter.getAttribute('formaction') || form.action || location.href;
                    var method = submitter && submitter.getAttribute('formmethod') || form.method || 'get';
                    var url = new URL(action, document.baseURI);
                    // Limit the hook to file-export forms; leave login, checkout and other POSTs alone.
                    var fileForm = /downloadtofile|(?:download|export)(?:[/.]|$)|[.](?:gpx|zip)$/i.test(url.pathname);
                    return String(method).toUpperCase() === 'POST' && /^https?:$/i.test(url.protocol) && fileForm ? url.href : null;
                }
                function capturePost(form, submitter, url) {
                    if (window.__overlandPostBusy) return;
                    window.__overlandPostBusy = true;
                    $BLOB_BRIDGE.postStarted(url);
                    Promise.resolve().then(function() {
                        var data = new FormData(form);
                        if (submitter && submitter.name && !submitter.disabled) data.append(submitter.name, submitter.value);
                        var encoding = submitter && submitter.getAttribute('formenctype') || form.enctype;
                        var headers = {};
                        var body = data;
                        if (encoding !== 'multipart/form-data') {
                            if (encoding === 'text/plain') {
                                body = Array.from(data.entries()).map(function(pair) { return pair[0] + '=' + pair[1]; }).join('\r\n');
                                headers['Content-Type'] = 'text/plain;charset=UTF-8';
                            } else {
                                body = new URLSearchParams();
                                data.forEach(function(value, key) { body.append(key, value instanceof File ? value.name : value); });
                            }
                        }
                        // Chromium supplies the original cookies, Origin and Referer. Hidden fields,
                        // selected format, CSRF token and clicked submit-button value remain in the POST.
                        return fetch(url, {method:'POST', body:body, headers:headers, credentials:'include', redirect:'follow'})
                            .then(function(response) {
                                var mime = response.headers.get('Content-Type') || '';
                                var disposition = response.headers.get('Content-Disposition') || '';
                                if (!response.ok) throw new Error('POST download returned HTTP ' + response.status);
                                var match = /filename[*]\s*=\s*([^;]+)/i.exec(disposition);
                                var name = '';
                                if (match) {
                                    name = match[1].trim().replace(/^"|"$/g, '').replace(/^[^']*'[^']*'/, '');
                                    try { name = decodeURIComponent(name); } catch (ignored) {}
                                }
                                if (!name) {
                                    match = /filename\s*=\s*(?:"([^"]*)"|([^;]*))/i.exec(disposition);
                                    name = match ? (match[1] || match[2] || '').trim() : '';
                                    if (!name) name = 'download.gpx';
                                }
                                return response.blob().then(function(blob) {
                                    if (blob.size > ${GpxDownloadParser.MAX_BYTES}) throw new Error('POST download exceeds size limit');
                                    return new Promise(function(resolve, reject) {
                                        var reader = new FileReader();
                                        reader.onload = function() {
                                            $BLOB_BRIDGE.receive(String(reader.result).split(',')[1], name, blob.type || mime);
                                            resolve();
                                        };
                                        reader.onerror = function() { reject(reader.error || new Error('FileReader failed')); };
                                        reader.readAsDataURL(blob);
                                    });
                                });
                            });
                    }).catch(function(error) {
                        $BLOB_BRIDGE.failed('POST download: ' + String(error && error.message || error));
                    }).finally(function() { window.__overlandPostBusy = false; });
                }
                document.addEventListener('submit', function(event) {
                    var form = event.target;
                    if (!(form instanceof HTMLFormElement) || event.defaultPrevented) return;
                    var url = downloadPost(form, event.submitter);
                    if (!url) return;
                    event.preventDefault();
                    capturePost(form, event.submitter, url);
                });
                HTMLFormElement.prototype.submit = function() {
                    var url = downloadPost(this, null);
                    if (url) capturePost(this, null, url);
                    else originalSubmit.apply(this, arguments);
                };
                document.addEventListener('click', function(event) {
                    var link = event.target.closest && event.target.closest('a[download]');
                    if (!link || event.defaultPrevented || !/^https?:/i.test(link.href)) return;
                    event.preventDefault();
                    $BLOB_BRIDGE.openDownload(link.href, link.download || '');
                });
            })();
        """.trimIndent(), null)
    }

    private fun createWindow(): WebView {
        val view = WebView(context)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            allowFileAccess = false
            allowContentAccess = false
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
        }
        view.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                if (view === active) notifyNavigation()
            }
            override fun onPageCommitVisible(view: WebView, url: String?) {
                if (url?.startsWith("http") == true) {
                    committedPages[view] = url
                    suggestedNames.remove(view)
                    installDownloadHook(view)
                }
            }
            override fun onPageFinished(view: WebView, url: String?) {
                CookieManager.getInstance().flush()
                installDownloadHook(view)
                if (view === active) notifyNavigation()
            }
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                if (view === active) notifyNavigation()
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.scheme in listOf("http", "https")) {
                    return false
                }
                if (uri.scheme in listOf("about", "data", "blob")) return false
                if (request.isForMainFrame && request.hasGesture() && uri.scheme in listOf("mailto", "tel", "geo")) {
                    try { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: ActivityNotFoundException) { }
                }
                return true
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                interceptNavigation(view, request)
            // Scripts, styles, fetches and other subresources continue through Chromium unchanged.
        }
        view.webChromeClient = object : WebChromeClient() {
            // Installing a WebChromeClient also enables the standard JS alert/confirm/prompt dialogs.
            override fun onCreateWindow(parent: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                if (disposed || windows.size >= 8) return false
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                transport.webView = createWindow()
                resultMsg.sendToTarget()
                return true
            }
            override fun onCloseWindow(window: WebView) { if (windows.size > 1) closeWindow(window) }
            override fun onProgressChanged(view: WebView, newProgress: Int) { if (view === active) notifyNavigation() }
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean =
                onChooseFile(callback, params)
        }
        view.addJavascriptInterface(object {
            @JavascriptInterface fun postStarted(url: String) {
                mainHandler.post { if (!disposed && view in windows) onTransferStarted() }
            }

            @JavascriptInterface fun openDownload(url: String, name: String) {
                mainHandler.post {
                    if (!disposed && view in windows && Uri.parse(url).scheme in listOf("http", "https")) {
                        suggestedNames[view] = name.take(255)
                        view.loadUrl(url, mapOf("Referer" to sourcePage(view)))
                    }
                }
            }

            /** Only stages an import for review; never exposes files, credentials or automatic writes. */
            @JavascriptInterface fun receive(base64: String, name: String, mime: String) {
                mainHandler.post {
                    if (!disposed && view in windows) {
                        if (base64.length <= MAX_BASE64_LENGTH) {
                            val source = sourcePage(view)
                            if (windows.size > 1 && committedPages[view] == null) closeWindow(view)
                            onBlob(base64, name.take(255), source)
                        } else { onDownloadError() }
                    }
                }
            }
            @JavascriptInterface fun failed(reason: String) {
                mainHandler.post { if (!disposed) onDownloadError() }
            }
        }, BLOB_BRIDGE)
        view.setDownloadListener { url, agent, disposition, mime, length ->
            if (url.startsWith("blob:")) {
                val fileName = android.webkit.URLUtil.guessFileName(url, disposition, mime)
                // Generated files live inside Chromium and cannot be fetched by HttpURLConnection.
                view.evaluateJavascript("""
                    (function() {
                        var link = Array.from(document.querySelectorAll('a[download]')).find(function(a) { return a.href === ${JSONObject.quote(url)}; });
                        var name = link && link.download || ${JSONObject.quote(fileName)};
                        fetch(${JSONObject.quote(url)}).then(function(response) { return response.blob(); }).then(function(blob) {
                            if (blob.size > ${GpxDownloadParser.MAX_BYTES}) throw new Error('File too large');
                            var reader = new FileReader();
                            reader.onload = function() { $BLOB_BRIDGE.receive(String(reader.result).split(',')[1], name, blob.type); };
                            reader.onerror = function() { $BLOB_BRIDGE.failed('FileReader: ' + (reader.error && reader.error.message || 'unknown error')); };
                            reader.readAsDataURL(blob);
                        }).catch(function(error) { $BLOB_BRIDGE.failed('Blob fetch/read: ' + String(error && error.message || error)); });
                    })();
                """.trimIndent(), null)
            } else if (chromiumPostUrls.remove(url)) {
                // WebView's callback does not expose a POST body. A GET here would lose the
                // form data and can consume/deny the original download (as on Wikiloc).
                onDownloadError()
            } else {
                val source = sourcePage(view)
                val userAgent = agent ?: view.settings.userAgentString
                if (windows.size > 1 && committedPages[view] == null) closeWindow(view)
                onDownload(url, userAgent, disposition, mime, source)
            }
        }
        active?.visibility = View.GONE
        windows += view
        addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        notifyNavigation()
        return view
    }

    private companion object {
        const val BLOB_BRIDGE = "OverlandImportBlob"
        const val MAX_BASE64_LENGTH = (GpxDownloadParser.MAX_BYTES + 2) / 3 * 4
    }
}

