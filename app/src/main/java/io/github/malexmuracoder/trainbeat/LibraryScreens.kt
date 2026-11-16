package io.github.malexmuracoder.trainbeat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Folders": the folders the app remembers.
 *
 * Two different things live here on purpose, in two separate blocks: the library
 * folders, which are many and read-only and feed the scan, and the loop
 * destination, which is one and needs write permission. It is the only screen
 * about remembered folders and persistent grants, so it is where the destination
 * can be changed or forgotten - the trimmer page has to just work.
 */
@Composable
fun FoldersScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var folders by remember { mutableStateOf(LibraryFolders.load(ctx)) }
    var counts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var outSet by remember { mutableStateOf(OutputFolder.isSet(ctx)) }
    var outLabel by remember { mutableStateOf(OutputFolder.label(ctx)) }
    var outMsg by remember { mutableStateOf("") }
    var recSet by remember { mutableStateOf(RecordFolder.isSet(ctx)) }
    var recLabel by remember { mutableStateOf(RecordFolder.label(ctx)) }
    var recMsg by remember { mutableStateOf("") }
    var sfSet by remember { mutableStateOf(SoundFontStore.uri(ctx) != null) }
    var sfLabel by remember { mutableStateOf(SoundFontStore.label(ctx)) }
    var sfMsg by remember { mutableStateOf("") }
    var exMsg by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            folders = LibraryFolders.add(ctx, uri.toString())
        }
    }

    val outPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            OutputFolder.remember(ctx, uri)
            outSet = OutputFolder.isSet(ctx)
            outLabel = OutputFolder.label(ctx)
            outMsg = ""
        }
    }

    val recPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree: Uri? ->
        if (tree != null) {
            RecordFolder.remember(ctx, tree)
            recSet = RecordFolder.isSet(ctx)
            recLabel = RecordFolder.label(ctx)
            recMsg = ""
        }
    }

    val sfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked: Uri? ->
        if (picked != null) {
            SoundFontStore.remember(ctx, picked, displayName(ctx, picked))
            sfSet = true
            sfLabel = SoundFontStore.label(ctx)
            sfMsg = ""
        }
    }

    LaunchedEffect(folders) {
        counts = withContext(Dispatchers.IO) {
            folders.associateWith { DocTree.listAudio(ctx, Uri.parse(it)).size }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)
    ) {
        BackLink(onBack)
        Spacer(Modifier.height(4.dp))
        Text(
            "Granting a folder makes every file inside it readable, now and in the " +
                "future. The system picker allows one folder per go.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(18.dp))
        Text("Library Folders", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        Text(
            "Read only. Their contents are what the scan analyses.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))

        Button(
            onClick = { treePicker.launch(null) },
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text("Add Folder", fontSize = 16.sp) }

        Spacer(Modifier.height(12.dp))
        if (folders.isEmpty()) {
            Text("No folder selected yet.", fontSize = 14.sp)
        } else {
            // a plain loop, not a LazyColumn: the list is short and the screen scrolls
            folders.forEach { f ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(vertical = 10.dp)) {
                            Text(DocTree.folderLabel(f), fontSize = 15.sp, maxLines = 2)
                            val n = counts[f]
                            Text(
                                if (n == null) "Counting..." else "$n audio files",
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { folders = LibraryFolders.remove(ctx, f) }) {
                            Text("Remove")
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text("Loop Destination", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        Text(
            "Read and write. This is where \"Make loop from file\" saves. Clearing it " +
                "only forgets the folder: the loops already saved are not touched.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    outLabel,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2
                )
                Spacer(Modifier.height(6.dp))
                if (!outSet) {
                    Button(
                        onClick = { outPicker.launch(null) },
                        modifier = Modifier.fillMaxWidth().height(46.dp)
                    ) { Text("Choose Destination", fontSize = 15.sp) }
                } else {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { outPicker.launch(null) },
                            modifier = Modifier.weight(1f).height(44.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) { Text("Change", fontSize = 13.sp) }
                        Spacer(Modifier.width(6.dp))
                        Button(
                            onClick = {
                                val made = OutputFolder.createSubfolder(ctx)
                                outLabel = OutputFolder.label(ctx)
                                outMsg = if (made != null) "created and selected \"$made\""
                                else "Could not create the subfolder"
                            },
                            modifier = Modifier.weight(1f).height(44.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) { Text("Subfolder", fontSize = 13.sp) }
                        Spacer(Modifier.width(6.dp))
                        Button(
                            onClick = {
                                OutputFolder.clear(ctx)
                                outSet = false
                                outLabel = OutputFolder.label(ctx)
                                outMsg = "Destination forgotten, no file removed"
                            },
                            modifier = Modifier.weight(1f).height(44.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) { Text("Clear", fontSize = 13.sp) }
                    }
                }
                if (outMsg.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(outMsg, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text("Recordings", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        Text(
            "Read and write. Where REC saves. Clearing it only forgets the folder: " +
                "the recordings stay.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(recLabel, fontSize = 14.sp, fontFamily = FontFamily.Monospace, maxLines = 2)
                Spacer(Modifier.height(6.dp))
                if (!recSet) {
                    Button(
                        onClick = { recPicker.launch(null) },
                        modifier = Modifier.fillMaxWidth().height(46.dp)
                    ) { Text("Choose Folder", fontSize = 15.sp) }
                } else {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { recPicker.launch(null) },
                            modifier = Modifier.weight(1f).height(44.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) { Text("Change", fontSize = 13.sp) }
                        Spacer(Modifier.width(6.dp))
                        Button(
                            onClick = {
                                val made = RecordFolder.createSubfolder(ctx)
                                recLabel = RecordFolder.label(ctx)
                                recMsg = if (made != null) "created and selected \"$made\""
                                else "Could not create the subfolder"
                            },
                            modifier = Modifier.weight(1f).height(44.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) { Text("Subfolder", fontSize = 13.sp) }
                        Spacer(Modifier.width(6.dp))
                        Button(
                            onClick = {
                                RecordFolder.clear(ctx)
                                recSet = false
                                recLabel = RecordFolder.label(ctx)
                                recMsg = "Folder forgotten, no file removed"
                            },
                            modifier = Modifier.weight(1f).height(44.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) { Text("Clear", fontSize = 13.sp) }
                    }
                }
                if (recMsg.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(recMsg, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text("SoundFont for MIDI", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        Text(
            "Read only. A MIDI file says which notes to play, not how they sound: this " +
                "is the bank of sounds used to hear it. The app has a small one built in; " +
                "choose your own .sf2 for a better sound, it takes precedence.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(sfLabel, fontSize = 14.sp, fontFamily = FontFamily.Monospace, maxLines = 2)
                Spacer(Modifier.height(6.dp))
                if (!sfSet) {
                    Button(
                        onClick = { sfPicker.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth().height(46.dp)
                    ) { Text("Choose your own .sf2", fontSize = 15.sp) }
                } else {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { sfPicker.launch(arrayOf("*/*")) },
                            modifier = Modifier.weight(1f).height(44.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) { Text("Change", fontSize = 13.sp) }
                        Spacer(Modifier.width(6.dp))
                        Button(
                            onClick = {
                                SoundFontStore.clear(ctx)
                                sfSet = false
                                sfLabel = SoundFontStore.label(ctx)
                                sfMsg = "Back to the built-in SoundFont, no file removed"
                            },
                            modifier = Modifier.weight(1f).height(44.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) { Text("Use built-in", fontSize = 13.sp) }
                    }
                }
                if (sfMsg.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(sfMsg, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text("Built-in examples", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        Text(
            "The app comes with a few example loops and a SoundFont, kept in its own " +
                "private space, apart from your folders. They are in the library until " +
                "you remove them there. This brings back the ones you removed.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Button(
                    onClick = {
                        exMsg = "Restoring..."
                        scope.launch {
                            val n = withContext(Dispatchers.IO) { BuiltIn.restore(ctx) }
                            exMsg = if (n == 0) "Nothing to restore" else "Restored $n example(s)"
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(46.dp)
                ) { Text("Restore examples", fontSize = 15.sp) }
                if (exMsg.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(exMsg, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        Spacer(Modifier.height(20.dp))
    }
}

/** "Scan Library": count, estimate, run, and let it run in the background. */
@Composable
fun ScanScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var found by remember { mutableStateOf(-1) }
    var alreadyKnown by remember { mutableStateOf(0) }
    var todo by remember { mutableStateOf(0) }
    var notifDenied by remember { mutableStateOf(false) }

    // ScanState lives in a service and is not Compose state, so it is copied into
    // observable variables by a poller; otherwise the screen would never redraw
    var running by remember { mutableStateOf(ScanState.running) }
    var done by remember { mutableStateOf(0) }
    var skipped by remember { mutableStateOf(0) }
    var failed by remember { mutableStateOf(0) }
    var total by remember { mutableStateOf(0) }
    var current by remember { mutableStateOf("") }
    var msPerFile by remember { mutableStateOf(0.0) }
    var eta by remember { mutableStateOf(-1.0) }
    var message by remember { mutableStateOf(ScanState.lastMessage) }

    val askNotif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notifDenied = !granted
        ScanService.start(ctx)
    }

    LaunchedEffect(Unit) {
        val res = withContext(Dispatchers.IO) {
            val entries = LibraryFolders.load(ctx)
                .flatMap { DocTree.listAudio(ctx, Uri.parse(it)) }
                .plus(BuiltIn.entries(ctx))
                .distinctBy { TrackRecord.cacheKeyOf(it.name, it.size) }
            val known = LibraryFolders.store(ctx).loadMap()
            val analysed = entries.count {
                val rec = known[TrackRecord.cacheKeyOf(it.name, it.size)]
                rec != null && rec.error == null
            }
            Triple(entries.size, analysed, entries.size - analysed)
        }
        found = res.first
        alreadyKnown = res.second
        todo = res.third
    }

    // poll the shared state: the scan lives in a service, not in this screen
    LaunchedEffect(Unit) {
        while (true) {
            running = ScanState.running
            done = ScanState.done
            skipped = ScanState.skipped
            failed = ScanState.failed
            total = ScanState.total
            current = ScanState.current
            msPerFile = ScanState.msPerFile
            eta = ScanState.etaSeconds()
            message = ScanState.lastMessage
            delay(400)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        BackLink(onBack)
        Spacer(Modifier.height(8.dp))

        if (found < 0) {
            Text("Listing folders...", fontSize = 14.sp)
        } else if (running) {
            val doneAll = done + skipped
            val frac = if (total > 0) doneAll.toFloat() / total else 0f
            Text("Analysing $doneAll of $total", fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            Line("Current", current.ifEmpty { "-" })
            Line("Per File", if (msPerFile > 0) "%.1f s".format(msPerFile / 1000.0) else "Measuring...")
            Line("Remaining", if (eta < 0) "Measuring..." else "%.0f s (%.1f min)".format(eta, eta / 60.0))
            Line("Failed", failed.toString())
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { ScanService.cancel(ctx) },
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("Cancel Scan") }
            if (notifDenied) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Progress notification hidden: you denied notifications. The scan " +
                        "keeps running - follow it here.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Line("Audio Files Found", found.toString())
            Line("Already Analysed", alreadyKnown.toString())
            Line("To Analyse", todo.toString())
            Spacer(Modifier.height(6.dp))
            Text(
                if (todo == 0) "Nothing to do: every file is already in the library."
                else "Rough guess: about ${"%.0f".format(todo * 1.5 / 60.0)} minutes. The real " +
                    "figure is measured on the first files and shown while it runs.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            if (todo > 0) {
                Button(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                            != PackageManager.PERMISSION_GRANTED
                        ) {
                            askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            ScanService.start(ctx)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(60.dp)
                ) { Text("Start Scan", fontSize = 17.sp) }
            }
            if (message.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(message, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "The scan runs in the background, so the screen can go off. If it gets " +
                    "interrupted, exclude TrainBeat from battery optimisation and run it " +
                    "again: results are saved file by file, so it resumes where it stopped.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** "Play File From Library": search, tempo range, key ranking. */
@Composable
fun LibraryScreen(onBack: () -> Unit, onOpen: (String, String) -> Unit) {
    val ctx = LocalContext.current
    var records by remember { mutableStateOf<List<TrackRecord>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    // held as a set of uris rather than read per row: a LazyColumn asks its rows
    // again on every scroll, and re-reading the file each time would be a disk hit
    // per visible row per frame
    var starred by remember { mutableStateOf<Set<String>>(emptySet()) }

    var search by remember { mutableStateOf("") }
    var keyIdx by remember { mutableStateOf(-1) }        // -1 = any
    var bpmMinIdx by remember { mutableStateOf(0) }      // 0 = any, else 40 + 5*i
    var bpmMaxIdx by remember { mutableStateOf(0) }
    var halfDouble by remember { mutableStateOf(false) }
    // riga in correzione: cacheKey del brano, piu' l'indice scelto
    var editing by remember { mutableStateOf<String?>(null) }
    var editIdx by remember { mutableStateOf(0) }
    val io = rememberCoroutineScope()

    fun persist(rec: TrackRecord) {
        io.launch {
            withContext(Dispatchers.IO) { LibraryFolders.store(ctx).append(rec) }
        }
        records = records.map { if (it.cacheKey == rec.cacheKey) rec else it }
    }

    fun removeBuiltIn(rec: TrackRecord) {
        records = records.filter { it.cacheKey != rec.cacheKey }
        starred = starred - rec.uri
        io.launch { withContext(Dispatchers.IO) { BuiltIn.remove(ctx, rec) } }
    }

    LaunchedEffect(Unit) {
        records = withContext(Dispatchers.IO) { LibraryFolders.store(ctx).loadAll() }
        starred = withContext(Dispatchers.IO) {
            LibraryFolders.prefs(ctx).favourites().map { it.uri }.toSet()
        }
        loaded = true
    }

    val key = if (keyIdx < 0) null else Analyzer.KEY_NAMES[keyIdx]
    val bpmMin = if (bpmMinIdx <= 0) null else (40 + 5 * bpmMinIdx).toFloat()
    val bpmMax = if (bpmMaxIdx <= 0) null else (40 + 5 * bpmMaxIdx).toFloat()
    val rows = LibraryQuery.run(records, search, bpmMin, bpmMax, halfDouble, key)
    val cut = if (key == null) -1 else LibraryQuery.uncertainFrom(rows)

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        BackLink(onBack)

        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            label = { Text("Search by Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        // 24 named keys are a list to pick from: dropdown, with "Any" first
        KeyDropdown(
            label = "Key",
            value = key ?: "Any",
            options = listOf("Any") + Analyzer.KEY_NAMES.toList(),
            onPick = { keyIdx = it - 1 }
        )

        Spacer(Modifier.height(8.dp))
        Text(
            "Tempo",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
            Box(modifier = Modifier.weight(1f)) {
                MiniStepper(
                    label = "from",
                    value = if (bpmMin == null) "Any" else "${bpmMin.toInt()}",
                    onMinus = { if (bpmMinIdx > 0) bpmMinIdx-- },
                    onPlus = { if (bpmMinIdx < 40) bpmMinIdx++ }
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                MiniStepper(
                    label = "to",
                    value = if (bpmMax == null) "Any" else "${bpmMax.toInt()}",
                    onMinus = { if (bpmMaxIdx > 0) bpmMaxIdx-- },
                    onPlus = { if (bpmMaxIdx < 40) bpmMaxIdx++ }
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { halfDouble = !halfDouble }, contentPadding = PaddingValues(0.dp)) {
                Text(if (halfDouble) "Half/Double: On" else "Half/Double: Off", fontSize = 12.sp)
            }
            Spacer(Modifier.weight(1f))
            Text(
                "${rows.size} of ${records.size}",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(6.dp))

        if (!loaded) {
            Text("Loading library...", fontSize = 14.sp)
        } else if (records.isEmpty()) {
            Text(
                "The library is empty. Choose the folders in \"Select library\", then run " +
                    "\"Scan library\".",
                fontSize = 14.sp
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(rows.size) { i ->
                    if (i == cut) {
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text(
                                "--- less likely below this line ---",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    val row = rows[i]
                    val r = row.record
                    TrackRow(
                        record = r,
                        starred = starred.contains(r.uri),
                        onOpen = { onOpen(r.uri, r.name) },
                        onStar = {
                            val now = LibraryFolders.prefs(ctx).toggleFavourite(r.uri, r.name)
                            starred = if (now) starred + r.uri else starred - r.uri
                        },
                        editing = editing == r.cacheKey,
                        onEdit = {
                            editing = if (editing == r.cacheKey) null else r.cacheKey
                            editIdx = r.effectiveKeyIndex
                        },
                        editIdx = editIdx,
                        onEditIdx = { editIdx = it },
                        onSaveKey = { persist(r.copy(manualKey = Analyzer.KEY_NAMES[editIdx])); editing = null },
                        onCancelEdit = { editing = null },
                        onClearKey = { persist(r.copy(manualKey = null)) },
                        filterKey = key,
                        onSetFilterKey = { persist(r.copy(manualKey = key)) },
                        score = if (key != null) row.score else null,
                        onRemove = if (BuiltIn.isBuiltIn(ctx, r.uri)) ({ removeBuiltIn(r) }) else null
                    )
                }
            }
        }
    }
}

/**
 * One track, as a row. Used by the library and by the favourites, deliberately the
 * same composable in both.
 *
 * Two lists showing the same thing had to look and behave the same, and the way to
 * guarantee that is not discipline, it is having one definition. What differs
 * between the two screens is what the screen is FOR - the library is a search
 * result, the favourites are a hand-kept shelf - and that difference lives in the
 * screens, not in here.
 *
 * The key is what opens the correction, not the name: a badly misread track never
 * shows up under the right key's filter, so reaching it through the filter alone
 * was a dead end.
 */
@Composable
fun TrackRow(
    record: TrackRecord,
    starred: Boolean,
    onOpen: () -> Unit,
    onStar: () -> Unit,
    editing: Boolean,
    onEdit: () -> Unit,
    editIdx: Int,
    onEditIdx: (Int) -> Unit,
    onSaveKey: () -> Unit,
    onCancelEdit: () -> Unit,
    onClearKey: () -> Unit,
    /** The key being filtered on, when there is one: enables the one-tap "set". */
    filterKey: String? = null,
    onSetFilterKey: () -> Unit = {},
    /** Match score, shown only when a key filter is narrowing the list. */
    score: Float? = null,
    /** Extra lines under the row - the favourites use it for what is remembered. */
    extra: String? = null,
    /** A dead pointer: shown greyed, and tapping means forget rather than open. */
    missing: Boolean = false,
    /** Only for the examples that come with the app: takes the track out of the library. */
    onRemove: (() -> Unit)? = null
) {
    val r = record
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clickable { onOpen() }
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    r.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    modifier = Modifier.weight(1f),
                    color = if (missing) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface
                )
                if (score != null) {
                    Text(
                        "${Math.round(score * 100)}%",
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                // its own touch area inside a row that is itself clickable: tapping
                // the star must not start the track
                Star(starred) { onStar() }
            }

            if (missing) {
                Text(
                    "Missing - tap to forget",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }

            if (r.isMidi) {
                Text(
                    "MIDI · " + r.sourceLabel(),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                r.tempoLabel(),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    r.keyLabel(),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).clickable { onEdit() }
                )
                // with a key filter on, one tap states "this track is in that key"
                // without opening anything
                if (filterKey != null && r.manualKey != filterKey) {
                    TextButton(
                        onClick = onSetFilterKey,
                        contentPadding = PaddingValues(horizontal = 6.dp)
                    ) { Text("Set $filterKey", fontSize = 11.sp) }
                }
                if (r.manualKey != null) {
                    TextButton(
                        onClick = onClearKey,
                        contentPadding = PaddingValues(horizontal = 6.dp)
                    ) { Text("Clear", fontSize = 11.sp) }
                }
                if (onRemove != null) {
                    TextButton(
                        onClick = onRemove,
                        contentPadding = PaddingValues(horizontal = 6.dp)
                    ) { Text("Remove", fontSize = 11.sp) }
                }
            }

            if (extra != null && extra.isNotEmpty()) {
                Text(
                    extra,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (editing) {
                KeyDropdown(
                    label = "Set Key by Hand",
                    value = Analyzer.KEY_NAMES[editIdx],
                    options = Analyzer.KEY_NAMES.toList(),
                    onPick = onEditIdx
                )
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = onSaveKey,
                        modifier = Modifier.weight(1f)
                    ) { Text("Save", fontSize = 13.sp) }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onCancelEdit) { Text("Cancel", fontSize = 13.sp) }
                }
            }
        }
    }
}
