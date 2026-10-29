package ch.overlandmap.map.ui.tracking

import android.annotation.SuppressLint
import android.webkit.*
import android.content.ActivityNotFoundException
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.text.style.TextOverflow
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.res.stringResource
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.R
import ch.overlandmap.map.tracking.GpxDownloadParser
import ch.overlandmap.map.tracking.RecordedTrack
import ch.overlandmap.map.tracking.readLimited
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import android.webkit.URLUtil

/** WebView keeps login cookies; file responses are fetched with those same cookies and user agent. */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ImportBrowser(address: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val library = (context.applicationContext as OverlandApp).downloadLibrary
    val scope = rememberCoroutineScope()
    var browser by remember { mutableStateOf<ImportBrowserView?>(null) }
    var pageUrl by remember { mutableStateOf(address) }
    var progress by remember { mutableIntStateOf(100) }
    var fileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
        fileCallback = null
    }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var archiveDocuments by remember { mutableStateOf<List<GpxDownloadParser.Document>?>(null) }
    var pending by remember { mutableStateOf<List<RecordedTrack>?>(null) }
    var source by remember { mutableStateOf(address) }
    var folderName by remember { mutableStateOf("") }
    var conflict by remember { mutableStateOf(false) }
    var back by remember { mutableStateOf(false) }
    var forward by remember { mutableStateOf(false) }
    val unknown = stringResource(R.string.import_content_unknown)
    val importedFormat = stringResource(R.string.import_tracks_count)
    val seen = remember { mutableSetOf<String>() }
    fun stageDocuments(documents: List<GpxDownloadParser.Document>) {
        when (documents.size) {
            0 -> { message = unknown }
            1 -> {
                folderName = documents.first().fileName.substringBeforeLast('.')
                pending = documents.first().tracks
            }
            else -> archiveDocuments = documents
        }
    }
    fun download(url: String, userAgent: String?, disposition: String?, mime: String?, sourceUrl: String) {
        if (busy || pending != null || archiveDocuments != null || !seen.add(url)) {
            return
        }
        busy = true
        source = sourceUrl
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    var current = URL(url)
                    var connection: HttpURLConnection? = null
                    try {
                        for (i in 0..10) {
                            require(current.protocol in listOf("http", "https"))
                            val c = current.openConnection() as HttpURLConnection
                            connection = c
                            c.instanceFollowRedirects = false; c.connectTimeout = 20000; c.readTimeout = 30000
                            userAgent?.let { c.setRequestProperty("User-Agent", it) }
                            CookieManager.getInstance().getCookie(current.toString())?.let { c.setRequestProperty("Cookie", it) }
                            c.setRequestProperty("Referer", source)
                            if (c.responseCode in listOf(301, 302, 303, 307, 308)) {
                                val next = c.getHeaderField("Location") ?: error("Missing redirect")
                                current = URL(current, next); c.disconnect(); continue
                            }
                            require(c.responseCode in 200..299)
                            require(c.contentLengthLong <= GpxDownloadParser.MAX_BYTES)
                            val name = URLUtil.guessFileName(current.toString(), c.getHeaderField("Content-Disposition") ?: disposition, c.contentType ?: mime)
                            val bytes = c.inputStream.use { it.readLimited(GpxDownloadParser.MAX_BYTES + 1) }
                            val docs = GpxDownloadParser.parseDownload(bytes, name).filter { it.tracks.isNotEmpty() }
                            return@withContext docs
                        }
                        error("Too many redirects")
                    } finally { connection?.disconnect() }
                }
                stageDocuments(result)
            } catch (e: CancellationException) { throw e }
            catch (error: Exception) { message = unknown }
            finally { busy = false; seen.remove(url) }
        }
    }
    fun commit(replace: Boolean) {
        val tracks = pending ?: return
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { library.commit(library.website(source).id, folderName, tracks, replace) }
                message = String.format(importedFormat, tracks.count { it.points.isNotEmpty() })
                pending = null; conflict = false
            } catch (e: CancellationException) { throw e }
            catch (error: Exception) { message = unknown }
            finally { busy = false }
        }
    }
    fun receiveContent(name: String, sourceUrl: String, loadBytes: () -> ByteArray) {
        if (pending != null || archiveDocuments != null) {
            busy = false; return
        }
        busy = true
        source = sourceUrl
        scope.launch {
            try {
                val docs = withContext(Dispatchers.IO) {
                    val bytes = loadBytes()
                    GpxDownloadParser.parseDownload(bytes, name).filter { it.tracks.isNotEmpty() }
                }
                stageDocuments(docs)
            } catch (e: CancellationException) { throw e }
            catch (error: Exception) { message = unknown }
            finally { busy = false }
        }
    }
    fun receiveFile(bytes: ByteArray, name: String, sourceUrl: String) {
        receiveContent(name, sourceUrl) { bytes }
    }
    fun receiveBlob(base64: String, name: String, sourceUrl: String) {
        if (pending != null || archiveDocuments != null) {
            return
        }
        // Decoding large browser downloads must not block Compose and dialog presentation.
        receiveContent(name, sourceUrl) { Base64.decode(base64, Base64.DEFAULT) }
    }
    BackHandler { if (browser?.goBack() != true) onClose() }
    DisposableEffect(Unit) { onDispose {
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        browser?.dispose()
    } }
    Scaffold(topBar = {
        TopAppBar(title = {
            Column {
                Text(message.ifEmpty { stringResource(R.string.import_from_web) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(Uri.parse(pageUrl).authority ?: pageUrl, style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }, actions = {
            IconButton(enabled = back, onClick = { browser?.goBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            IconButton(enabled = forward, onClick = { browser?.goForward() }) { Icon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.forward)) }
            IconButton(onClick = { if (browser?.closePopup() != true) onClose() }) { Icon(Icons.Filled.Close, stringResource(R.string.close)) }
        })
    }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
                ImportBrowserView(ctx,
                    onNavigation = { url, canBack, canForward, loading ->
                        pageUrl = url ?: address; back = canBack; forward = canForward; progress = loading
                    },
                    onDownload = ::download,
                    onBlob = ::receiveBlob,
                    onFile = ::receiveFile,
                    onTransferStarted = { busy = true },
                    onDownloadError = { busy = false; message = unknown },
                    onChooseFile = { callback, params ->
                        fileCallback?.onReceiveValue(null)
                        fileCallback = callback
                        try { filePicker.launch(params.createIntent()); true }
                        catch (_: ActivityNotFoundException) {
                            fileCallback = null
                            false
                        }
                    },
                ).also { browser = it; it.open(address) }
            })
            if (progress < 100) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            if (busy) Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.scrim.copy(alpha = .25f)) {
                Box(contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
    }
    archiveDocuments?.let { documents ->
        GpxFileSelectionDialog(documents, onDismiss = { archiveDocuments = null }, onContinue = { selection ->
            archiveDocuments = null
            folderName = selection.folderName
            pending = selection.tracks
        })
    }
    if (pending != null && !conflict) AlertDialog(onDismissRequest = { if (!busy) pending = null },
        title = { Text(stringResource(R.string.track_folder_name)) },
        text = { OutlinedTextField(folderName, { folderName = it }, singleLine = true, enabled = !busy) },
        confirmButton = { TextButton(enabled = folderName.isNotBlank() && !busy, onClick = {
            val website = library.website(source)
            if (library.existingFolder(website.id, folderName) != null) conflict = true else commit(false)
        }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = { pending = null }) { Text(stringResource(R.string.cancel)) } })
    if (conflict) AlertDialog(onDismissRequest = { conflict = false }, title = { Text(stringResource(R.string.folder_already_exists)) },
        text = { Text(folderName) },
        confirmButton = { TextButton(enabled = !busy, onClick = { commit(false) }) { Text(stringResource(R.string.merge)) } },
        dismissButton = { Row {
            TextButton(enabled = !busy, onClick = { commit(true) }) { Text(stringResource(R.string.replace)) }
            TextButton(enabled = !busy, onClick = { conflict = false }) { Text(stringResource(R.string.cancel)) }
        } })
}
