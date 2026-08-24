package io.github.malexmuracoder.trainbeat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.malexmuracoder.trainbeat.ui.theme.TrainBeatTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where we are. Four entries from the home screen, plus the player. */
sealed class Route {
    object Home : Route()
    object Folder : Route()
    object Library : Route()
    object Folders : Route()
    object Scan : Route()
    object Trim : Route()
    data class Play(val uri: String, val name: String) : Route()
}

class MainActivity : ComponentActivity() {

    private val player = LoopPlayer()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TrainBeatTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot(player)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        player.stop()
    }

    override fun onDestroy() {
        player.stop()
        super.onDestroy()
    }
}

@Composable
private fun AppRoot(player: LoopPlayer) {
    var route by remember { mutableStateOf<Route>(Route.Home) }
    val back = { route = Route.Home }

    when (val r = route) {
        is Route.Home -> HomeScreen(
            onFolder = { route = Route.Folder },
            onLibrary = { route = Route.Library },
            onFolders = { route = Route.Folders },
            onScan = { route = Route.Scan },
            onTrim = { route = Route.Trim }
        )
        is Route.Folder -> FolderScreen(
            onBack = back,
            onOpen = { uri, name -> route = Route.Play(uri, name) }
        )
        is Route.Library -> LibraryScreen(
            onBack = back,
            onOpen = { uri, name -> route = Route.Play(uri, name) }
        )
        is Route.Folders -> FoldersScreen(onBack = back)
        is Route.Scan -> ScanScreen(onBack = back)
        is Route.Trim -> TrimmerScreen(player, onBack = back)
        is Route.Play -> PlayerScreen(player, r.uri, r.name, onBack = back)
    }
}

@Composable
private fun HomeScreen(
    onFolder: () -> Unit,
    onLibrary: () -> Unit,
    onFolders: () -> Unit,
    onScan: () -> Unit,
    onTrim: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Text("TrainBeat", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(28.dp))
        HomeButton("Play file from folder", onFolder)
        HomeButton("Play file from library", onLibrary)
        HomeButton("Folders", onFolders)
        HomeButton("Scan library", onScan)
        HomeButton("Make loop from file", onTrim)

        // the scan runs in a service: poll it so the line keeps up
        var scanLine by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            while (true) {
                scanLine = if (ScanState.running)
                    "scan running: ${ScanState.done + ScanState.skipped} of ${ScanState.total}"
                else ""
                delay(500)
            }
        }
        if (scanLine.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text(
                scanLine,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HomeButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(64.dp).padding(vertical = 4.dp)
    ) { Text(label, fontSize = 17.sp) }
}

/** The original flow: system picker plus the recent list. */
@Composable
private fun FolderScreen(onBack: () -> Unit, onOpen: (String, String) -> Unit) {
    val ctx = LocalContext.current
    var history by remember { mutableStateOf(History.load(ctx)) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            // make the grant permanent: no dialog, and the history survives a reboot
            try {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            val name = displayName(ctx, uri)
            history = History.add(ctx, HistoryItem(uri.toString(), name))
            onOpen(uri.toString(), name)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        BackLink(onBack)
        Text("Play file from folder", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        Button(
            onClick = { picker.launch(arrayOf("*/*")) },
            modifier = Modifier.fillMaxWidth().height(60.dp)
        ) { Text("Select a file", fontSize = 17.sp) }

        Spacer(Modifier.height(24.dp))
        Text(
            if (history.isEmpty()) "No recent tracks" else "Recent tracks",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        if (history.isEmpty()) {
            Text(
                "Files you open stay here: the last ${History.MAX}.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(history) { item ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { onOpen(item.uri, item.name) }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                item.name,
                                fontSize = 15.sp,
                                maxLines = 2,
                                modifier = Modifier.weight(1f).padding(vertical = 10.dp)
                            )
                            TextButton(onClick = { history = History.remove(ctx, item.uri) }) {
                                Text("Remove")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BackLink(onBack: () -> Unit) {
    TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) { Text("< Home", fontSize = 16.sp) }
}

@Composable
private fun PlayerScreen(player: LoopPlayer, uri: String, name: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current

    val io = rememberCoroutineScope()

    var clip by remember(uri) { mutableStateOf<AudioClip?>(null) }
    var error by remember(uri) { mutableStateOf<String?>(null) }
    var loading by remember(uri) { mutableStateOf(true) }
    var known by remember(uri) { mutableStateOf<TrackRecord?>(null) }
    var size by remember(uri) { mutableStateOf(0L) }
    var keySel by remember(uri) { mutableStateOf<Int?>(null) }

    // a new file resets the three controls to their defaults
    var speed by remember(uri) { mutableStateOf(Ramp.SPEED_DEFAULT) }
    var pitch by remember(uri) { mutableStateOf(0) }
    var train by remember(uri) { mutableStateOf(0f) }

    var playing by remember { mutableStateOf(false) }
    var liveSpeed by remember { mutableStateOf(1.0f) }
    var liveLoop by remember { mutableStateOf(0) }

    LaunchedEffect(uri) {
        loading = true
        error = null
        clip = null
        try {
            val parsed = Uri.parse(uri)
            // tempo and key are never computed here: they are read from the
            // library cache if the scan has already seen this file
            val found = withContext(Dispatchers.IO) {
                val sz = fileSize(ctx, parsed)
                Pair(sz, LibraryFolders.store(ctx).loadMap()[TrackRecord.cacheKeyOf(name, sz)])
            }
            size = found.first
            known = found.second
            // only a key already set by hand pre-fills the picker: otherwise it
            // reads "set key by hand", so nothing pretends to be your choice
            val manual = found.second?.manualKey
            keySel = if (manual != null) Analyzer.keyIndex(manual).takeIf { it >= 0 } else null
            clip = withContext(Dispatchers.IO) {
                AudioLoader.load(ctx, parsed, trimSilence = false)
            }
        } catch (e: Throwable) {
            error = e.message ?: e.javaClass.simpleName
        }
        loading = false
    }

    LaunchedEffect(playing) {
        while (playing) {
            liveSpeed = player.currentSpeed
            liveLoop = player.currentLoop
            delay(100)
        }
    }

    DisposableEffect(playing) {
        view.keepScreenOn = playing
        onDispose { view.keepScreenOn = false }
    }

    DisposableEffect(Unit) { onDispose { player.stop() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        TextButton(
            onClick = { player.stop(); playing = false; onBack() },
            contentPadding = PaddingValues(0.dp)
        ) { Text("< Home", fontSize = 16.sp) }

        Text(name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 3)
        Spacer(Modifier.height(6.dp))

        val c = clip
        when {
            loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Text("  Decoding...", fontSize = 14.sp)
            }

            error != null -> Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier
                        .padding(14.dp)
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text("Cannot open this file", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.height(6.dp))
                    SelectionContainer {
                        Text(error ?: "", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }

            c != null -> {
                InfoCard(c, known)
                Spacer(Modifier.height(8.dp))

                Stepper(
                    label = "Speed",
                    value = Ramp.speedLabel(speed),
                    enabled = !playing,
                    onMinus = { speed = (speed - Ramp.SPEED_STEP).coerceAtLeast(Ramp.SPEED_MIN) },
                    onPlus = { speed = (speed + Ramp.SPEED_STEP).coerceAtMost(Ramp.SPEED_MAX) }
                )
                Stepper(
                    label = "Transpose",
                    value = Ramp.pitchLabel(pitch),
                    enabled = !playing,
                    onMinus = { if (pitch > Ramp.PITCH_MIN) pitch-- },
                    onPlus = { if (pitch < Ramp.PITCH_MAX) pitch++ }
                )
                Stepper(
                    label = "Train",
                    value = Ramp.trainLabel(train),
                    enabled = !playing,
                    onMinus = { train = (train - Ramp.TRAIN_STEP).coerceAtLeast(0f) },
                    onPlus = { train = (train + Ramp.TRAIN_STEP).coerceAtMost(Ramp.TRAIN_MAX) }
                )

                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = {
                        if (playing) {
                            player.stop()
                            playing = false
                        } else {
                            try {
                                player.start(c, speed, pitch, train)
                                liveSpeed = speed
                                liveLoop = 0
                                playing = true
                            } catch (e: Throwable) {
                                error = e.message ?: e.javaClass.simpleName
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(54.dp)
                ) {
                    Text(if (playing) "STOP" else "PLAY", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    if (playing)
                        "playing: ${Ramp.speedLabel(liveSpeed)}   loop ${liveLoop + 1}"
                    else
                        "ready: ${Ramp.speedLabel(speed)}" +
                            (if (train > 0f) ", then ${Ramp.trainLabel(train)} up to 200%" else ""),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Correcting the key belongs here: this is where you hear that the
                // detected one is wrong. It is saved in the library, keyed by name
                // and size, so it survives and the list shows it as certain.
                // The detected key is not repeated: it is already in the info card.
                Spacer(Modifier.height(8.dp))
                KeyDropdown(
                    label = null,
                    value = keySel?.let { Analyzer.KEY_NAMES[it] } ?: "set key by hand",
                    options = Analyzer.KEY_NAMES.toList(),
                    onPick = { keySel = it }
                )
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            val chosen = keySel?.let { Analyzer.KEY_NAMES[it] } ?: return@Button
                            io.launch {
                                val saved = withContext(Dispatchers.IO) {
                                    LibraryFolders.saveManualKey(ctx, known, name, size, uri, chosen)
                                }
                                known = saved
                            }
                        },
                        enabled = keySel != null,
                        modifier = Modifier.weight(1f)
                    ) { Text("Save key") }
                    if (known?.manualKey != null) {
                        Spacer(Modifier.width(10.dp))
                        TextButton(onClick = {
                            io.launch {
                                val saved = withContext(Dispatchers.IO) {
                                    LibraryFolders.saveManualKey(ctx, known, name, size, uri, null)
                                }
                                known = saved
                                keySel = null
                            }
                        }) { Text("Clear") }
                    }
                }
            }
        }
    }
}

/** minus - value - plus: the same control for speed, transpose and train. */
@Composable
fun Stepper(
    label: String,
    value: String,
    enabled: Boolean,
    onMinus: () -> Unit,
    onPlus: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(
            onClick = onMinus,
            enabled = enabled,
            modifier = Modifier.size(56.dp),
            contentPadding = PaddingValues(0.dp)
        ) { Text("-", fontSize = 22.sp, fontWeight = FontWeight.Bold) }

        Column(
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }

        OutlinedButton(
            onClick = onPlus,
            enabled = enabled,
            modifier = Modifier.size(56.dp),
            contentPadding = PaddingValues(0.dp)
        ) { Text("+", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
    }
}

/**
 * Key picker as a dropdown: 24 named values are a list to choose from, not a
 * quantity to nudge - which is why plus/minus felt wrong here while it stays
 * right for speed, transpose and train.
 */
@Composable
fun KeyDropdown(
    label: String?,
    value: String,
    options: List<String>,
    onPick: (Int) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        // with no label the button itself carries the wording, which saves a line
        if (label != null) {
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            OutlinedButton(
                onClick = { open = true },
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) { Text(value, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEachIndexed { i, opt ->
                    DropdownMenuItem(
                        text = { Text(opt, fontSize = 15.sp) },
                        onClick = { open = false; onPick(i) }
                    )
                }
            }
        }
    }
}

/** Narrow minus-value-plus, for two of them side by side. */
@Composable
fun MiniStepper(
    label: String,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        OutlinedButton(
            onClick = onMinus,
            modifier = Modifier.size(44.dp),
            contentPadding = PaddingValues(0.dp)
        ) { Text("-", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        Column(
            modifier = Modifier.padding(horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        OutlinedButton(
            onClick = onPlus,
            modifier = Modifier.size(44.dp),
            contentPadding = PaddingValues(0.dp)
        ) { Text("+", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun InfoCard(c: AudioClip, known: TrackRecord?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            val secs = c.durationMs / 1000.0
            Line("Format", formatLine(c))
            Line("Sample rate", "${c.sampleRate} Hz, ${if (c.channels == 1) "mono" else "${c.channels} channels"}")
            Line("Duration", String.format("%.2f s (%d frames, %.1f MB)", secs, c.frames, c.megabytes))
            Line("Tempo", known?.tempoLabel() ?: "not analysed")
            Line("Key", known?.keyLabel() ?: "not analysed")
            if (c.info.notes.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                c.info.notes.forEach {
                    Text("- $it", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun Line(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp), horizontalArrangement = Arrangement.Start) {
        Text("$label: ", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
    }
}

/** Encoding, container and which code path read the file, on one line. */
private fun formatLine(c: AudioClip): String {
    val decoder = if (c.info.path.contains("MediaCodec")) "(${c.info.path})" else c.info.path
    return c.info.encoding + " · " + c.info.container + " · " + decoder
}

fun displayName(ctx: Context, uri: Uri): String {
    try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
            if (cur.moveToFirst() && cur.columnCount > 0) {
                val n = cur.getString(0)
                if (!n.isNullOrBlank()) return n
            }
        }
    } catch (_: Exception) {
    }
    return uri.lastPathSegment ?: uri.toString()
}

fun fileSize(ctx: Context, uri: Uri): Long {
    try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cur ->
            if (cur.moveToFirst() && cur.columnCount > 0 && !cur.isNull(0)) return cur.getLong(0)
        }
    } catch (_: Exception) {
    }
    return 0L
}
