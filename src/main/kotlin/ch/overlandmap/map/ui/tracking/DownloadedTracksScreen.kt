package ch.overlandmap.map.ui.tracking

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.provider.OpenableColumns
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.R
import ch.overlandmap.map.geo.GeoBounds
import ch.overlandmap.map.tracking.*
import ch.overlandmap.map.ui.VerticalSplit
import ch.overlandmap.map.ui.home.SidebarFocus
import kotlinx.coroutines.*
import java.net.URI
import java.text.DateFormat
import java.util.Date

/** Root → website → batch; each level owns the map's visible content and initial extent. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadedTracksScreen(onBack: () -> Unit, onOpenTrack: (String) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as OverlandApp
    val library = app.downloadLibrary
    val websites by library.websites.collectAsState()
    val folders by library.folders.collectAsState()
    val all by app.trackRecorder.recordings.collectAsState()
    val scope = rememberCoroutineScope()
    val savedLocation = remember { app.userPreferences.importLocationNow() }
    var websiteId by rememberSaveable { mutableStateOf(savedLocation.first) }
    var folderId by rememberSaveable { mutableStateOf(savedLocation.second) }
    var browser by remember { mutableStateOf<String?>(null) }
    var askAddress by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var archiveDocuments by remember { mutableStateOf<List<GpxDownloadParser.Document>?>(null) }
    var pending by remember { mutableStateOf<List<RecordedTrack>?>(null) }
    var name by remember { mutableStateOf("") }
    var conflict by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var color by remember { mutableStateOf(false) }
    var selectedWaypoint by remember { mutableStateOf<Pair<RecordedTrack, RecordedWaypoint>?>(null) }
    var selectedTrack by remember { mutableStateOf<RecordedTrack?>(null) }
    val website = websites.firstOrNull { it.id == websiteId }
    val folder = folders.firstOrNull { it.id == folderId }
    val tracks = all.filter { it.isImported && when {
        folderId != null -> it.trackFolderId == folderId
        websiteId != null -> it.downloadWebsiteId == websiteId
        else -> true
    } }
    val childFolders = folders.filter { it.websiteId == websiteId }
    val legacy = tracks.filter { it.trackFolderId == null && it.downloadWebsiteId == websiteId }
    val extent = when {
        folderId != null -> folder?.bounds
        websiteId != null -> website?.bounds
        else -> GeoBounds.containing(tracks.flatMap { it.coordinates + it.waypoints.map { w -> w.coordinate } })
    }
    var focus by remember { mutableStateOf<SidebarFocus?>(null) }
    var token by remember { mutableIntStateOf(0) }
    fun zoom(bounds: GeoBounds?) { focus = SidebarFocus(bounds = bounds, token = ++token) }
    fun back() { when { folderId != null -> folderId = null; websiteId != null -> websiteId = null; else -> onBack() } }
    BackHandler(onBack = ::back)
    LaunchedEffect(Unit) {
        // Seeding also loads the saved library; only validate after disk data is available.
        withContext(Dispatchers.IO) { library.seedWebsites() }
        val savedFolder = library.folders.value.firstOrNull { it.id == folderId }
        if (savedFolder != null) websiteId = savedFolder.websiteId
        else folderId = null
        if (websiteId != null && library.websites.value.none { it.id == websiteId }) websiteId = null
    }
    LaunchedEffect(websiteId, folderId) {
        app.userPreferences.setImportLocation(websiteId, folderId)
    }
    // Data arrives asynchronously from disk; frame once the selected collection has geometry.
    var framed by remember(websiteId, folderId) { mutableStateOf(false) }
    LaunchedEffect(websiteId, folderId, extent) { if (!framed && extent != null) { zoom(extent); framed = true } }
    val unknown = stringResource(R.string.import_content_unknown)
    fun importFile(uri: android.net.Uri) {
        scope.launch {
            busy = true
            try {
                val result = withContext(Dispatchers.IO) {
                    val fileName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "Import.gpx"
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readLimited(GpxDownloadParser.MAX_BYTES + 1) } ?: error("No content")
                    val docs = GpxDownloadParser.parseDownload(bytes, fileName).filter { it.tracks.isNotEmpty() }
                    docs
                }
                when (result.size) {
                    0 -> error = unknown
                    1 -> { name = result.first().fileName.substringBeforeLast('.'); pending = result.first().tracks }
                    else -> archiveDocuments = result
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { error = unknown } finally { busy = false }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::importFile)
    }
    val sharedFile by app.pendingGpxShare.collectAsState()
    LaunchedEffect(sharedFile) {
        val shared = sharedFile ?: return@LaunchedEffect
        app.pendingGpxShare.value = null
        // External shares start a new local import, independent of the collection being viewed.
        browser = null
        websiteId = null
        folderId = null
        archiveDocuments = null
        pending = null
        conflict = false
        importFile(shared.second)
    }
    fun commit(replace: Boolean) {
        val parsed = pending ?: return
        scope.launch {
            busy = true
            try { withContext(Dispatchers.IO) { library.commit(websiteId, name, parsed, replace) }; pending = null; conflict = false; framed = false }
            catch (e: CancellationException) { throw e } catch (_: Exception) { error = unknown } finally { busy = false }
        }
    }
    if (browser != null) { ImportBrowser(browser!!, onClose = { browser = null; framed = false }); return }
    Scaffold(topBar = {
        TopAppBar(title = { Text(folder?.name ?: website?.name ?: stringResource(R.string.imported)) },
            navigationIcon = { IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            actions = {
                IconButton(enabled = extent != null, onClick = { zoom(extent) }) { Icon(Icons.Filled.ZoomIn, stringResource(R.string.zoom_to_object)) }
                IconButton(onClick = { if (website != null) browser = website.url else askAddress = true }) { Icon(Icons.Filled.Language, stringResource(R.string.import_from_web)) }
                IconButton(onClick = { picker.launch(arrayOf("*/*")) }) { Icon(Icons.Filled.FileOpen, stringResource(R.string.import_from_disk)) }
                if (website != null || folder != null) Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, null) }
                    DropdownMenu(menu, { menu = false }) {
                        if (folder != null) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { name = folder.name; rename = true; menu = false })
                            DropdownMenuItem(text = { Text(stringResource(R.string.color)) }, onClick = { color = true; menu = false })
                        }
                        DropdownMenuItem(text = { Text(stringResource(R.string.delete_all)) }, onClick = { delete = true; menu = false })
                        if (folder != null) DropdownMenuItem(text = { Text(DateFormat.getDateTimeInstance().format(Date(folder.downloadDate))) }, enabled = false, onClick = {})
                    }
                }
            })
    }) { padding ->
        VerticalSplit(
            modifier = Modifier.padding(padding).fillMaxSize(),
            splitKey = "downloadedTracks",
            top = {
                Box(Modifier.fillMaxSize()) {
                    TrackMap(tracks.filter { it.isDisplayed }, focus, { id, wid -> all.firstOrNull { it.id == id }?.let { t -> t.waypoints.firstOrNull { it.id == wid }?.let { selectedWaypoint = t to it } } },
                        Modifier.fillMaxSize(), onTrackTapped = { id -> selectedTrack = all.firstOrNull { it.id == id } })
                    if (busy) CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
            },
            bottom = {
                LazyColumn(Modifier.fillMaxSize()) {
                    if (website != null) item {
                        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                            ElevatedButton(onClick = { browser = website.url }) {
                                Icon(Icons.Filled.Language, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.import_from_web))
                            }
                        }
                    }
                    if (websiteId == null && folderId == null) items(websites, key = { it.id }) { w ->
                        ListItem(headlineContent = { Text(w.name) }, supportingContent = { Text(w.url) }, leadingContent = { Icon(Icons.Filled.Language, null) }, modifier = Modifier.clickable { websiteId = w.id })
                    }
                    if (folderId == null) items(childFolders, key = { it.id }) { f ->
                        val swipe = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
                            if (value != SwipeToDismissBoxValue.Settled) { scope.launch(Dispatchers.IO) { library.deleteFolder(f.id) }; true } else false
                        })
                        SwipeToDismissBox(state = swipe, backgroundContent = { Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.CenterEnd) { Icon(Icons.Filled.Delete, stringResource(R.string.delete)) } }) {
                            ListItem(headlineContent = { Text(f.name) }, leadingContent = { Icon(Icons.Filled.Folder, null) }, modifier = Modifier.clickable { folderId = f.id })
                        }
                    }
                    items(if (folderId != null) tracks else legacy, key = { it.id }) { t ->
                        TrackRow(t, false, { onOpenTrack(t.id) }, { app.trackRecorder.setDisplayed(t.id, true); zoom(t.trackBounds ?: t.bounds) })
                    }
                }
            },
        )
    }
    if (askAddress) AlertDialog(onDismissRequest = { askAddress = false }, title = { Text(stringResource(R.string.website_address)) },
        text = { OutlinedTextField(address, { address = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = {
            val url = if (address.contains("://")) address.trim() else "https://${address.trim()}"
            val uri = runCatching { URI(url) }.getOrNull()
            if (uri?.scheme in listOf("http", "https") && !uri?.host.isNullOrEmpty()) { browser = url; askAddress = false } else error = unknown
        }) { Text(stringResource(R.string.import_from_web)) } }, dismissButton = { TextButton(onClick = { askAddress = false }) { Text(stringResource(R.string.cancel)) } })
    archiveDocuments?.let { documents ->
        GpxFileSelectionDialog(documents, onDismiss = { archiveDocuments = null }, onContinue = { selection ->
            archiveDocuments = null
            name = selection.folderName
            pending = selection.tracks
        })
    }
    if (pending != null && !conflict) AlertDialog(onDismissRequest = { pending = null }, title = { Text(stringResource(R.string.track_folder_name)) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true) }, confirmButton = { TextButton(enabled = name.isNotBlank() && !busy, onClick = {
            if (library.existingFolder(websiteId, name) != null) conflict = true else commit(false)
        }) { Text(stringResource(R.string.save)) } }, dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.cancel)) } })
    if (conflict) AlertDialog(onDismissRequest = { conflict = false }, title = { Text(stringResource(R.string.folder_already_exists)) },
        confirmButton = { TextButton(enabled = !busy, onClick = { commit(false) }) { Text(stringResource(R.string.merge)) } }, dismissButton = { Row {
            TextButton(enabled = !busy, onClick = { commit(true) }) { Text(stringResource(R.string.replace)) }
            TextButton(onClick = { conflict = false }) { Text(stringResource(R.string.cancel)) }
        } })
    if (rename && folder != null) AlertDialog(onDismissRequest = { rename = false }, title = { Text(stringResource(R.string.rename)) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true) }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
            if (library.existingFolder(folder.websiteId, name)?.let { it.id != folder.id } == true) { error = context.getString(R.string.folder_already_exists) }
            else scope.launch { withContext(Dispatchers.IO) { library.rename(folder, name) }; rename = false }
        }) { Text(stringResource(R.string.save)) } })
    if (color) TrackColorPickerDialog(tracks.firstOrNull()?.colorHex ?: RecordedTrack.DEFAULT_COLOR, { hex -> tracks.forEach { app.trackRecorder.updateColor(it.id, hex) } }, { color = false })
    if (delete) AlertDialog(onDismissRequest = { delete = false }, text = { Text(stringResource(R.string.delete_all_confirm)) },
        confirmButton = { TextButton(onClick = {
            val deletingFolder = folderId
            val deletingWebsite = websiteId
            scope.launch(Dispatchers.IO) { if (deletingFolder != null) library.clearFolder(deletingFolder) else deletingWebsite?.let(library::deleteWebsiteContent) }
            delete = false
        }) { Text(stringResource(R.string.delete_all)) } }, dismissButton = { TextButton(onClick = { delete = false }) { Text(stringResource(R.string.cancel)) } })
    error?.let { text -> AlertDialog(onDismissRequest = { error = null }, text = { Text(text) }, confirmButton = { TextButton(onClick = { error = null }) { Text(stringResource(R.string.ok)) } }) }
    selectedWaypoint?.let { (t, w) -> RecordedWaypointSheet(t, w, { selectedWaypoint = null }, { focus = SidebarFocus(lat = w.latitude, lon = w.longitude, token = ++token); selectedWaypoint = null }) }
    selectedTrack?.let { t -> RecordedTrackSheet(t, { selectedTrack = null }, { selectedTrack = null; onOpenTrack(t.id) }, { selectedTrack = null; zoom(t.bounds) }) }
}
