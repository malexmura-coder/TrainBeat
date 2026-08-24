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

    LaunchedEffect(folders) {
        counts = withContext(Dispatchers.IO) {
            folders.associateWith { DocTree.listAudio(ctx, Uri.parse(it)).size }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)
    ) {
        BackLink(onBack)
        Text("Folders", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Granting a folder makes every file inside it readable, now and in the " +
                "future. The system picker allows one folder per go.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(18.dp))
        Text("Library folders", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
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
        ) { Text("Add folder", fontSize = 16.sp) }

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
                                if (n == null) "counting..." else "$n audio files",
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
        Text("Loop destination", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
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
                    ) { Text("Choose destination", fontSize = 15.sp) }
                } else {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = { outPicker.launch(null) },
                            modifier = Modifier.weight(1f).height(44.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) { Text("Change", fontSize = 13.sp) }
                        Spacer(Modifier.width(6.dp))
                        OutlinedButton(
                            onClick = {
                                val made = OutputFolder.createSubfolder(ctx)
                                outLabel = OutputFolder.label(ctx)
                                outMsg = if (made != null) "created and selected \"$made\""
                                else "could not create the subfolder"
                            },
                            modifier = Modifier.weight(1f).height(44.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) { Text("Subfolder", fontSize = 13.sp) }
                        Spacer(Modifier.width(6.dp))
                        OutlinedButton(
                            onClick = {
                                OutputFolder.clear(ctx)
                                outSet = false
                                outLabel = OutputFolder.label(ctx)
                                outMsg = "destination forgotten, no file removed"
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
        Spacer(Modifier.height(20.dp))
    }
}

/** "Scan library": count, estimate, run, and let it run in the background. */
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
        Text("Scan library", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))

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
            Line("Per file", if (msPerFile > 0) "%.1f s".format(msPerFile / 1000.0) else "measuring...")
            Line("Remaining", if (eta < 0) "measuring..." else "%.0f s (%.1f min)".format(eta, eta / 60.0))
            Line("Failed", failed.toString())
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = { ScanService.cancel(ctx) },
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("Cancel scan") }
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
            Line("Audio files found", found.toString())
            Line("Already analysed", alreadyKnown.toString())
            Line("To analyse", todo.toString())
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
                ) { Text("Start scan", fontSize = 17.sp) }
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

/** "Play file from library": search, tempo range, key ranking. */
@Composable
fun LibraryScreen(onBack: () -> Unit, onOpen: (String, String) -> Unit) {
    val ctx = LocalContext.current
    var records by remember { mutableStateOf<List<TrackRecord>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

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

    LaunchedEffect(Unit) {
        records = withContext(Dispatchers.IO) { LibraryFolders.store(ctx).loadAll() }
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
            label = { Text("Search by name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        // 24 named keys are a list to pick from: dropdown, with "any" first
        KeyDropdown(
            label = "Key",
            value = key ?: "any",
            options = listOf("any") + Analyzer.KEY_NAMES.toList(),
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
                    value = if (bpmMin == null) "any" else "${bpmMin.toInt()}",
                    onMinus = { if (bpmMinIdx > 0) bpmMinIdx-- },
                    onPlus = { if (bpmMinIdx < 40) bpmMinIdx++ }
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                MiniStepper(
                    label = "to",
                    value = if (bpmMax == null) "any" else "${bpmMax.toInt()}",
                    onMinus = { if (bpmMaxIdx > 0) bpmMaxIdx-- },
                    onPlus = { if (bpmMaxIdx < 40) bpmMaxIdx++ }
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { halfDouble = !halfDouble }, contentPadding = PaddingValues(0.dp)) {
                Text(if (halfDouble) "half/double: on" else "half/double: off", fontSize = 12.sp)
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
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clickable { onOpen(r.uri, r.name) }
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    r.name,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 2,
                                    modifier = Modifier.weight(1f)
                                )
                                if (key != null) {
                                    Text(
                                        "${Math.round(row.score * 100)}%",
                                        fontSize = 14.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                            Text(
                                r.tempoLabel(),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // tapping the key - not the name - opens the
                                // correction: a badly misread track would never
                                // show up under the right key's filter, so the
                                // filter shortcut alone was a dead end
                                Text(
                                    r.keyLabel(),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            editing = if (editing == r.cacheKey) null else r.cacheKey
                                            editIdx = r.effectiveKeyIndex
                                        }
                                )
                                // with a key filter on, one tap states "this track
                                // is in that key" without opening anything
                                if (key != null && r.manualKey != key) {
                                    TextButton(
                                        onClick = { persist(r.copy(manualKey = key)) },
                                        contentPadding = PaddingValues(horizontal = 6.dp)
                                    ) { Text("set $key", fontSize = 11.sp) }
                                }
                                if (r.manualKey != null) {
                                    TextButton(
                                        onClick = { persist(r.copy(manualKey = null)) },
                                        contentPadding = PaddingValues(horizontal = 6.dp)
                                    ) { Text("clear", fontSize = 11.sp) }
                                }
                            }

                            if (editing == r.cacheKey) {
                                KeyDropdown(
                                    label = "Set the key by hand",
                                    value = Analyzer.KEY_NAMES[editIdx],
                                    options = Analyzer.KEY_NAMES.toList(),
                                    onPick = { editIdx = it }
                                )
                                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Button(
                                        onClick = {
                                            persist(r.copy(manualKey = Analyzer.KEY_NAMES[editIdx]))
                                            editing = null
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("Save", fontSize = 13.sp) }
                                    Spacer(Modifier.width(8.dp))
                                    TextButton(onClick = { editing = null }) { Text("Cancel", fontSize = 13.sp) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
