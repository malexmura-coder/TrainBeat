package io.github.malexmuracoder.trainbeat

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import io.github.malexmuracoder.trainbeat.ui.theme.TrainBeatTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the media keys should press.
 *
 * A speaker's play button has to do exactly what the PLAY button does, and that
 * logic needs a dozen pieces of screen state that only exist deep inside the
 * layout. Rather than duplicate it - and then have the two drift apart - the screen
 * hands its own actions to this holder, and the key handler presses them.
 */
private class KeyActions {
    var play: () -> Unit = {}
    var stop: () -> Unit = {}
}

/**
 * The activity a composable is running in, or null if it is a preview.
 *
 * Wanted for one question only, and an important one: is this composition going
 * away because the user left the screen, or because the system is destroying the
 * activity while a loop is meant to keep playing? The lifecycle answers it and
 * nothing else does.
 */
private fun Context.hostActivity(): ComponentActivity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is ComponentActivity) return c
        c = c.baseContext
    }
    return null
}

/** Where we are. Four entries from the home screen, plus the player. */
sealed class Route {
    object Home : Route()
    object Folder : Route()
    object Library : Route()
    object Folders : Route()
    object Scan : Route()
    object Trim : Route()
    object Record : Route()
    object Favourites : Route()
    object Manual : Route()
    data class Play(val uri: String, val name: String) : Route()
}

class MainActivity : ComponentActivity() {

    /**
     * The player is NOT owned here. It lives in Playback, for the length of the
     * process: an Activity dies on rotation and whenever the system decides, while
     * a practice loop is supposed to play straight through all of that.
     */
    private val player = Playback.player

    /**
     * A file handed in from outside, waiting to be dealt with.
     *
     * Held by the activity and not by the composition, because it can arrive at two
     * quite different moments - at a cold start, and through onNewIntent when the
     * app is already open - and both have to end up in the same place.
     */
    private val arrival = mutableStateOf<Arrival?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only on a real start. After a configuration change the system hands the
        // same intent back, and turning the phone sideways does not mean "open that
        // file again, and ask me again whether to keep it".
        if (savedInstanceState == null) {
            arrival.value = Incoming.from(this, intent)
            consume(intent)
        }
        setContent {
            TrainBeatTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot(player, arrival)
                }
            }
        }
    }

    /**
     * A file arriving while the app is already open.
     *
     * This exists because of singleTask: with one instance guaranteed, a second
     * file does not build a second screen, it is delivered here. Without this
     * override the tap would simply bring the old screen forward and nothing would
     * open, which looks exactly like the app ignoring the file.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Incoming.from(this, intent)?.let { arrival.value = it }
        consume(intent)
    }

    /**
     * An intent is opened once.
     *
     * After setIntent the VIEW intent stays the activity's intent for as long as
     * the task lives, so coming back to the app from Recents would offer the same
     * borrowed file again - by then usually a file we have lost the right to read.
     * Blanking the action costs nothing and says "this one has been dealt with".
     */
    private fun consume(intent: Intent) {
        intent.action = Intent.ACTION_MAIN
        intent.data = null
    }

    /**
     * Deliberately does NOT stop the audio. Looking away from the phone - at the
     * music, at a chord chart in another app - is the normal case while practising,
     * and it used to kill the loop. The foreground service keeps it alive, and its
     * notification is how you stop it from outside.
     */
    override fun onStop() {
        super.onStop()
    }

    /**
     * A real teardown, not a screen turning off: nothing left to play into.
     *
     * isFinishing is the whole test. The system destroying this activity to reclaim
     * memory, while the phone is in another app and a loop is playing, is NOT
     * finishing - and must not silence anything, because surviving that is the
     * point of the foreground service. Being swiped away or backed out of is, and
     * then everything goes: the sound, the focus, the service and its notification.
     */
    override fun onDestroy() {
        if (isFinishing) Playback.silence(this)
        super.onDestroy()
    }
}

@Composable
private fun AppRoot(player: LoopPlayer, arrival: State<Arrival?>) {
    val ctx = LocalContext.current
    // Normally the home page. But a loop can outlive the screen that started it -
    // the system destroys an activity that has been out of sight for long enough,
    // and the foreground service keeps the music going - and rebuilding onto the
    // home page with sound coming out of the phone leaves the user holding a
    // control that has no visible relation to what they can hear. So if something
    // is playing, that is where we are.
    var route by remember {
        mutableStateOf<Route>(
            Playback.nowPlaying?.let { Route.Play(it.uri, it.name) } ?: Route.Home
        )
    }
    val back = { route = Route.Home }

    /**
     * A file sent in from outside, not yet answered for.
     *
     * Saveable, and as two plain strings because that is what a Bundle can hold.
     * The activity is rebuilt on any configuration change - a rotation, dark mode
     * coming on at sunset - and the file that arrived is not re-read when that
     * happens, on purpose. Without this the question would disappear mid-answer and
     * take the file with it: no dialog, no music, and nothing said.
     */
    var offeredUri by rememberSaveable { mutableStateOf<String?>(null) }
    var offeredName by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(arrival.value) {
        val a = arrival.value ?: return@LaunchedEffect
        // A file arriving replaces whatever was playing, because that is plainly
        // what the tap meant. Stopping here rather than on the way into the player
        // means the old loop is silent while the question is being answered, which
        // is what anybody would expect after asking for something else.
        player.stop()
        Playback.ended(ctx)
        offeredUri = a.uri
        offeredName = a.name
    }

    when (val r = route) {
        is Route.Home -> HomeScreen(
            onFolder = { route = Route.Folder },
            onLibrary = { route = Route.Library },
            onFavourites = { route = Route.Favourites },
            onFolders = { route = Route.Folders },
            onScan = { route = Route.Scan },
            onTrim = { route = Route.Trim },
            onRecord = { route = Route.Record },
            onManual = { route = Route.Manual }
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
        is Route.Record -> RecordScreen(player, onBack = back)
        is Route.Favourites -> FavouritesScreen(
            onBack = back,
            onOpen = { uri, name -> route = Route.Play(uri, name) }
        )
        is Route.Manual -> ManualScreen(onBack = back)
        is Route.Play -> PlayerScreen(
            player, r.uri, r.name,
            onBack = back,
            onOpen = { uri, name -> route = Route.Play(uri, name) }
        )
    }

    // Over whatever is on screen, and before the player: the question has to be
    // answered first because its answer decides WHICH file gets played - the
    // borrowed one, or the copy that will still be there next week.
    val pending = offeredUri
    if (pending != null) {
        KeepACopy(Arrival(pending, offeredName)) { chosen ->
            offeredUri = null
            History.add(ctx, HistoryItem(chosen.uri, chosen.name))
            route = Route.Play(chosen.uri, chosen.name)
        }
    }
}

/**
 * The offer to keep a file that arrived from another app.
 *
 * It asks rather than deciding, and it asks BEFORE playing, because the answer
 * changes what is played: keep it and the copy is what plays and what is
 * remembered, so the entry in Recent Tracks points at something permanent.
 *
 * With no recordings folder chosen there is nothing to ask, and the dialog becomes
 * a statement. Saying it out loud matters more than it looks: the alternative is
 * an app that silently remembers a file it will not be able to open tomorrow, and
 * the user finds out weeks later with no idea why.
 */
@Composable
private fun KeepACopy(arrival: Arrival, onDone: (Arrival) -> Unit) {
    val ctx = LocalContext.current
    val io = rememberCoroutineScope()
    val folder = remember { if (RecordFolder.isSet(ctx)) RecordFolder.label(ctx) else null }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        // dismissing is not cancelling: the file was opened on purpose, so the
        // quiet answer is the one that plays it and keeps nothing
        onDismissRequest = { if (!saving) onDone(arrival) },
        title = {
            Text(
                when {
                    failed != null -> "The copy failed"
                    folder == null -> "Nowhere to keep it"
                    else -> "Keep a copy?"
                },
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column {
                Text(arrival.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Text(
                    when {
                        failed != null ->
                            "$failed" + "\n\n" + "It can still be played now."
                        folder == null ->
                            "A file opened from another app is borrowed: the " +
                                "permission to read it is withdrawn sooner or later, " +
                                "and certainly when the phone restarts. A copy would " +
                                "be yours, but no recordings folder has been chosen, " +
                                "so there is nowhere to put one.\n\n" +
                                "It will play. The entry left in Recent Tracks will " +
                                "one day open on nothing."
                        else ->
                            "A file opened from another app is borrowed: the " +
                                "permission to read it is withdrawn sooner or later, " +
                                "and certainly when the phone restarts.\n\n" +
                                "A copy in $folder is yours, and it is the copy that " +
                                "would be played and remembered."
                    },
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            if (folder != null && failed == null) {
                TextButton(
                    enabled = !saving,
                    onClick = {
                        saving = true
                        io.launch {
                            val kept = withContext(Dispatchers.IO) {
                                try {
                                    Incoming.copyInto(ctx, arrival)
                                } catch (e: Throwable) {
                                    failed = e.message ?: e.javaClass.simpleName
                                    null
                                }
                            }
                            saving = false
                            if (kept != null) onDone(kept)
                        }
                    }
                ) { Text(if (saving) "Copying" else "Keep a copy") }
            } else {
                TextButton(onClick = { onDone(arrival) }) { Text("Play it") }
            }
        },
        dismissButton = {
            if (folder != null && failed == null) {
                TextButton(enabled = !saving, onClick = { onDone(arrival) }) {
                    Text("Just play it")
                }
            }
        }
    )
}

@Composable
private fun HomeScreen(
    onFolder: () -> Unit,
    onLibrary: () -> Unit,
    onFavourites: () -> Unit,
    onFolders: () -> Unit,
    onScan: () -> Unit,
    onTrim: () -> Unit,
    onRecord: () -> Unit,
    onManual: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Text("TrainBeat", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(18.dp))
        HomeButton("Play File From Folder", onFolder)
        HomeButton("Play File From Library", onLibrary)
        HomeButton("Play From Favourites", onFavourites)
        HomeButton("Folders", onFolders)
        HomeButton("Scan Library", onScan)
        HomeButton("Make Loop From File", onTrim)
        HomeButton("Record a Loop", onRecord)
        HomeButton("Manual", onManual)

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

        // the empty space under the buttons, put to use: the icon and whose app it is
        Spacer(Modifier.weight(1f))
        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFF3E6D0)),          // the enamel-sign cream
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_fg),
                    contentDescription = "TrainBeat",
                    modifier = Modifier.fillMaxSize()
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "A. Mura, 2026",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HomeButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(52.dp).padding(vertical = 3.dp)
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
        // no page title anywhere: you know which button you pressed to get here
        Spacer(Modifier.height(8.dp))

        Button(
            onClick = { picker.launch(arrayOf("*/*")) },
            modifier = Modifier.fillMaxWidth().height(60.dp)
        ) { Text("Select a File", fontSize = 17.sp) }

        Spacer(Modifier.height(24.dp))
        Text(
            if (history.isEmpty()) "No recent tracks" else "Recent Tracks",
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

/**
 * The favourite toggle: a filled star or a hollow one.
 *
 * Drawn as a character rather than an icon so it needs no drawable, no vector
 * asset and no icons dependency - and so it inherits the text colour in both
 * themes without being told.
 *
 * The touch area is sized deliberately. In a library row the whole row is already
 * clickable and opens the track, so the star needs enough of its own area to be hit
 * on purpose and not so much that it swallows taps meant for the row.
 */
@Composable
fun Star(on: Boolean, onToggle: () -> Unit) {
    Text(
        if (on) "\u2605" else "\u2606",
        fontSize = 22.sp,
        color = if (on) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clickable { onToggle() }
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

@Composable
fun BackLink(onBack: () -> Unit) {
    TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) { Text("< Home", fontSize = 18.sp) }
}

@Composable
private fun PlayerScreen(
    player: LoopPlayer,
    uri: String,
    name: String,
    onBack: () -> Unit,
    onOpen: (String, String) -> Unit
) {
    val ctx = LocalContext.current
    val view = LocalView.current

    val io = rememberCoroutineScope()
    val isMidi = remember(name) { MidiFile.isMidi(name) }

    // NOT keyed on uri, and that is the whole point. With remember(uri) a new track
    // gets a NEW slot while the old slot - holding ninety six megabytes of the
    // previous track - stays alive until the recomposition finishes, which is
    // exactly while the new track is being decoded. One slot, emptied on purpose
    // before the next decode begins, means one track in memory at a time.
    var clip by remember { mutableStateOf<AudioClip?>(null) }
    var stream by remember { mutableStateOf<StreamClip?>(null) }
    var decoded by remember(uri) { mutableStateOf(0) }
    var score by remember { mutableStateOf<Score?>(null) }
    var midiInfo by remember(uri) { mutableStateOf<MidiAnalysis.Result?>(null) }
    var sfLabel by remember { mutableStateOf(SoundFontStore.label(ctx)) }
    var sfSet by remember { mutableStateOf(SoundFontStore.isSet(ctx)) }
    var sfSummary by remember { mutableStateOf<String?>(null) }
    var error by remember(uri) { mutableStateOf<String?>(null) }
    var loading by remember(uri) { mutableStateOf(true) }
    var known by remember(uri) { mutableStateOf<TrackRecord?>(null) }
    var size by remember(uri) { mutableStateOf(0L) }
    var keySel by remember(uri) { mutableStateOf<Int?>(null) }

    /**
     * This very loop is already playing, because the system destroyed the screen
     * and the service kept the music going. It has to be stopped to read the file
     * again - the same track twice in memory is what a phone cannot do - so what
     * survives the rebuild is the place it had got to, not the sound itself.
     *
     * Negative means there was nothing playing, which is not the same as playing
     * from the start: one has to be resumed and the other must not be.
     */
    val resumeAt = remember(uri) {
        val n = Playback.nowPlaying
        if (n != null && n.uri == uri && player.isPlaying) n.positionMs else -1L
    }
    var autoResume by remember(uri) { mutableStateOf(resumeAt >= 0L) }

    /**
     * Where in the piece the loop is, and where the next Play will come in.
     *
     * ONE value for both, which is what makes stopping into pausing: nothing is
     * saved and restored, the cursor simply IS the entry point, and pressing Play
     * comes in wherever it was left. Dragging it and pressing Play, or dragging it
     * while the music runs, are then the same act with the same code behind it.
     */
    var positionMs by remember(uri) { mutableStateOf(resumeAt.coerceAtLeast(0L)) }

    // The three controls start from what this track was last played at, not from
    // the defaults: practising means coming back tomorrow to the same loop at the
    // same speed, and re-entering all three every time is friction on the one
    // gesture that gets repeated a hundred times.
    val saved = remember(uri) { LibraryFolders.prefs(ctx).get(uri) }
    var speed by remember(uri) { mutableStateOf(saved?.speed ?: Ramp.SPEED_DEFAULT) }
    var pitch by remember(uri) { mutableStateOf(saved?.semitones ?: 0) }
    var train by remember(uri) { mutableStateOf(saved?.train ?: 0f) }
    var starred by remember(uri) { mutableStateOf(saved?.favourite ?: false) }

    // written when they settle, not on every tap of a stepper
    LaunchedEffect(uri, speed, pitch, train) {
        delay(600)
        LibraryFolders.prefs(ctx).remember(uri, name, speed, pitch, train)
    }

    /**
     * How long the piece is, whichever of the three kinds of thing it is.
     *
     * A score has to be in here as well as audio, and that is the point: a MIDI
     * file is the one case where a position is exact rather than measured, and
     * leaving it out is what made the cursor unavailable on the material it suits
     * best. For a track still being decoded this is what the container SAYS it
     * comes to, not what has arrived, so the bar does not stretch as it fills.
     */
    fun trackMillis(): Long {
        val sc = score
        val c = clip
        val st = stream
        return when {
            sc != null -> (sc.durationSec * 1000.0).toLong()
            c != null -> c.durationMs
            st != null -> st.expectedMs
            else -> 0L
        }
    }

    var playing by remember { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(false) }
    var liveSpeed by remember { mutableStateOf(1.0f) }
    var liveLoop by remember { mutableStateOf(0) }
    var trainer by remember { mutableStateOf<TrainRenderer?>(null) }
    // never remembered between sessions, and never on by default
    var hq by remember { mutableStateOf(false) }

    val sfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked: Uri? ->
        if (picked != null) {
            SoundFontStore.remember(ctx, picked, displayName(ctx, picked))
            sfLabel = SoundFontStore.label(ctx)
            sfSet = true
            sfSummary = null
            error = null
            io.launch { sfSummary = withContext(Dispatchers.IO) { SoundFontStore.summary(ctx) } }
        }
    }

    LaunchedEffect(uri) {
        loading = true
        error = null
        // Let go of the previous track BEFORE asking for the next one. The decoder
        // is about to allocate as much again, and on a phone that is the difference
        // between playing and OutOfMemory. This holds even when the track being
        // loaded is the one already playing: two copies of the same file is still
        // two copies, so the loop is stopped here and picked up again below, at the
        // place it had reached.
        player.stop()
        playing = false
        trainer?.close()            // holds prepared buffers of its own
        trainer = null
        stream?.cancelled = true    // stop the decoder before dropping its buffer
        clip = null
        stream = null
        score = null
        // rendered buffers belong to the file that is being left behind
        Renderer.clearCache()
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
            val manual = found.second?.manualKey
            keySel = if (manual != null) Analyzer.keyIndex(manual).takeIf { it >= 0 } else null
            if (isMidi) {
                val parsedScore = withContext(Dispatchers.IO) { MidiFile.loadScore(ctx, parsed) }
                score = parsedScore
                midiInfo = MidiAnalysis.analyse(parsedScore)
                if (SoundFontStore.isSet(ctx)) {
                    sfSummary = withContext(Dispatchers.IO) { SoundFontStore.summary(ctx) }
                }
            } else {
                // compressed audio decodes while it plays; our own WAV reader is
                // fast enough on the short loops it exists for
                val st = withContext(Dispatchers.IO) { AudioLoader.loadStreaming(ctx, parsed) }
                if (st != null) {
                    stream = st
                } else {
                    clip = withContext(Dispatchers.IO) {
                        AudioLoader.load(ctx, parsed, trimSilence = false)
                    }
                }
            }
        } catch (e: Throwable) {
            error = e.message ?: e.javaClass.simpleName
        }
        loading = false
    }

    // follow the decoder without holding anything up
    LaunchedEffect(stream) {
        val st = stream ?: return@LaunchedEffect
        while (!st.complete) {
            decoded = Math.round(st.progress * 100)
            delay(200)
        }
        decoded = 100
        val err = st.error
        if (err != null) error = err else clip = st.toClip()
    }

    LaunchedEffect(playing) {
        while (playing) {
            liveLoop = player.loopsCompleted
            liveSpeed = if (hq || isMidi) Ramp.speedAt(speed, train, liveLoop) else player.currentSpeed
            // Ten times a second is enough for a bar that is at most a few hundred
            // pixels wide, and cheap: three volatile reads and a division.
            val loopFrames = player.loopFrames
            val total = trackMillis()
            if (loopFrames > 0 && total > 0L) {
                positionMs = Cursor.millisAt(player.framePosition, loopFrames, total)
                Playback.moved(positionMs)
            }
            if (!player.isPlaying) {
                // it ended by itself - a decode that failed, a device that took the
                // track away - and the notification must not outlive the sound
                playing = false
                Playback.ended(ctx)
            }
            delay(100)
        }
    }

    DisposableEffect(playing) {
        view.keepScreenOn = playing
        onDispose { view.keepScreenOn = false }
    }

    val host = remember(ctx) { ctx.hostActivity() }
    DisposableEffect(Unit) {
        onDispose {
            // LEAVING the screen and BEING DESTROYED are not the same thing, and
            // this is the one place in the app where the difference is audible.
            //
            // Walking back to the home page should stop the loop and give the
            // memory back. The system destroying the activity while the phone is in
            // another app should not: that is the case the foreground service and
            // the notification exist for, and stopping there would break the one
            // promise the app makes about practising - that you can look away.
            //
            // The lifecycle tells them apart. On a move between screens the
            // activity is still resumed; on a teardown it has already passed
            // through stopped on its way out.
            val leaving = host == null ||
                host.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (leaving) {
                player.stop()
                trainer?.close()
                stream?.cancelled = true
                Renderer.clearCache()
                Playback.ended(ctx)
            }
        }
    }

    /** One repetition of the loop at this speed, ready to be written to the track. */
    fun buffer(atSpeed: Float): ShortArray {
        val sc = score
        val c = clip
        return when {
            sc != null -> {
                val sf = SoundFontStore.soundFont(ctx)
                if (sf.effectsOnly) {
                    throw IllegalStateException(
                        "\"${sf.name}\" has no instruments, only sound effects. " +
                            "Choose the large General MIDI .sf2.")
                }
                Renderer.midiCached(atSpeed, pitch, size) ?: run {
                    val res = Renderer.forMidi(sc, sf, 44100, atSpeed, pitch, size)
                    Renderer.storeMidi(atSpeed, pitch, size, res.samples)
                    res.samples
                }
            }
            c != null -> Renderer.forAudio(c, atSpeed, pitch, size)
            else -> ShortArray(0)
        }
    }

    /**
     * Stop the sound and keep the place. There is no other kind of stopping here.
     *
     * positionMs is deliberately left alone, and that single omission is the whole
     * of "stop is really a pause": the cursor stays where the music was, and the
     * next Play reads it as the entry point. It is also what makes a lost audio
     * focus survivable - a message arrives, the loop pauses, and Play carries on
     * from the bar you were on rather than from the top.
     */
    val pause = {
        player.stop()
        trainer?.close()
        trainer = null
        playing = false
        Playback.abandonFocus(ctx)
        Playback.ended(ctx)
    }

    /**
     * What the speaker's buttons mean while this screen is open.
     *
     * play/pause is the loop. next and previous walk the favourites, which is what
     * turns a practice session hands-free: start, stop, move to the next exercise,
     * without ever putting the instrument down. A speaker that sends no next/previous
     * simply never calls those.
     */
    // keyed on uri: it holds the play action, which captures the clip. Left
    // unkeyed it kept the previous track reachable from the Playback singleton.
    val actions = remember(uri) { KeyActions() }
    val keys = remember(uri, name) {
        object : Playback.Handler {
            override fun onPlayPause() { actions.play() }
            override fun onStop() { actions.stop() }
            override fun onSkip(forward: Boolean): Boolean {
                val next = LibraryFolders.prefs(ctx).neighbourFavourite(uri, forward) ?: return false
                if (next.uri == uri) return false
                actions.stop()
                onOpen(next.uri, next.name)
                return true
            }
            override val busy: Boolean get() = player.isPlaying
            override val title: String get() = name
            override val backgroundable: Boolean get() = true
        }
    }
    DisposableEffect(keys) {
        Playback.takeCharge(ctx, keys)
        onDispose { Playback.release(keys) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        TextButton(
            onClick = { pause(); onBack() },
            contentPadding = PaddingValues(0.dp)
        ) { Text("< Home", fontSize = 18.sp) }

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                name,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                modifier = Modifier.weight(1f)
            )
            Star(starred) {
                starred = LibraryFolders.prefs(ctx).toggleFavourite(uri, name)
            }
        }
        Spacer(Modifier.height(4.dp))

        val c = clip
        val sc = score
        val st = stream
        val lengthMs = trackMillis()
        val hqPossible = sc == null && lengthMs in 1..AudioClip.LOOP_MAX_MS
        val ready = c != null || sc != null ||
            (st != null && st.frames > st.sampleRate / 2)

        when {
            loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
                Text(if (isMidi) "  Reading" else "  Opening", fontSize = 14.sp)
            }

            error != null -> Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier
                        .padding(12.dp)
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text("Cannot play this file", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    SelectionContainer {
                        Text(error ?: "", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                    if (isMidi && !sfSet) {
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { sfPicker.launch(arrayOf("*/*")) }) { Text("Choose .sf2") }
                    }
                }
            }

            sc != null && !sfSet -> Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("MIDI needs a SoundFont", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("A score says which notes, not how they sound.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { sfPicker.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Choose .sf2", fontSize = 15.sp) }
                }
            }

            ready -> {
                if (sc != null) MidiCard(sc, midiInfo, known, sfSummary ?: sfLabel)
                else if (c != null) InfoCard(c, known)
                else if (st != null) StreamCard(st, known)
                Spacer(Modifier.height(4.dp))

                Stepper(
                    label = "Speed",
                    value = Ramp.speedLabel(speed),
                    enabled = !playing && !preparing,
                    onMinus = { speed = (speed - Ramp.SPEED_STEP).coerceAtLeast(Ramp.SPEED_MIN) },
                    onPlus = { speed = (speed + Ramp.SPEED_STEP).coerceAtMost(Ramp.SPEED_MAX) }
                )
                Stepper(
                    label = "Transpose",
                    value = Ramp.pitchLabel(pitch),
                    enabled = !playing && !preparing,
                    onMinus = { if (pitch > Ramp.PITCH_MIN) pitch-- },
                    onPlus = { if (pitch < Ramp.PITCH_MAX) pitch++ }
                )
                Stepper(
                    label = "Train",
                    value = Ramp.trainLabel(train),
                    enabled = !playing && !preparing,
                    onMinus = { train = (train - Ramp.TRAIN_STEP).coerceAtLeast(0f) },
                    onPlus = { train = (train + Ramp.TRAIN_STEP).coerceAtMost(Ramp.TRAIN_MAX) }
                )

                if (sc == null) {
                    if (hqPossible) {
                        TextButton(
                            onClick = {
                                if (!playing && !preparing) { hq = !hq; Renderer.clearCache() }
                            },
                            contentPadding = PaddingValues(0.dp)
                        ) { Text(if (hq) "Hi-Fi: On" else "Hi-Fi: Off", fontSize = 13.sp) }
                    } else {
                        Text(
                            "Too long, Hi-Fi not available",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))
                // one definition of "Press play", used by the button on screen and
                // by the play key on a Bluetooth speaker or a pedal
                val togglePlay: () -> Unit = {
                        if (playing) {
                            pause()
                        } else if (!preparing) {
                            // ask for the right to make noise, and stop cleanly if it
                            // is taken away: without this a notification sound can
                            // kill the loop with no way back
                            Playback.requestFocus(ctx) { pause() }
                            // hqPossible already says there is no score, so asking
                            // again here only made the compiler point out that it
                            // knew. What is left is the real condition: Hi-Fi was
                            // asked for, it is allowed, and the file is decoded -
                            // there is nothing to prepare from a track still arriving.
                            val useHq = hq && hqPossible && c != null
                            if (sc == null && !useHq) {
                                // nothing to prepare: the device does tempo and key
                                try {
                                    if (c != null) {
                                        player.startLive(
                                            c, speed, pitch, train,
                                            startAt = Cursor.sampleAt(
                                                Cursor.frameAt(positionMs, c.frames, c.durationMs),
                                                c.channels, c.samples.size
                                            )
                                        )
                                    } else if (st != null) {
                                        // A seek past the decoder is allowed here and
                                        // simply waits: the decoder runs many times
                                        // faster than the music, so the wait is short
                                        // and it is a truer answer than starting over.
                                        val chans = st.channels.coerceAtLeast(1)
                                        player.startStream(
                                            st, speed, pitch, train,
                                            startAt = Cursor.sampleAt(
                                                Cursor.frameAt(
                                                    positionMs, st.expectedSamples / chans, st.expectedMs),
                                                chans, st.expectedSamples
                                            )
                                        )
                                    }
                                    liveSpeed = speed
                                    liveLoop = 0
                                    playing = true
                                    Playback.began(ctx, uri, name)
                                } catch (e: Throwable) {
                                    error = e.message ?: e.javaClass.simpleName
                                }
                            } else {
                                preparing = true
                                io.launch {
                                    try {
                                        val first = withContext(Dispatchers.Default) { buffer(speed) }
                                        if (first.isEmpty()) throw Exception(
                                            "This SoundFont cannot play these notes")
                                        val rate = if (sc != null) 44100 else c!!.sampleRate
                                        val chans = if (sc != null) 2 else c!!.channels
                                        val tr = if (train > 0f)
                                            TrainRenderer(speed, train) { s -> buffer(s) } else null
                                        trainer = tr
                                        // The entry point is measured against the
                                        // RENDERED material, which at half speed is
                                        // twice as long as the file. Measuring it
                                        // against the file instead is the mistake
                                        // that lands you at the wrong bar - half way
                                        // in when you asked for the end.
                                        player.start(
                                            rate, chans, first,
                                            startAt = Cursor.sampleAt(
                                                Cursor.frameAt(
                                                    positionMs, first.size / chans, trackMillis()),
                                                chans, first.size
                                            )
                                        ) { loop -> tr?.nextFor(loop) }
                                        liveSpeed = speed
                                        liveLoop = 0
                                        playing = true
                                        Playback.began(ctx, uri, name)
                                    } catch (e: Throwable) {
                                        error = e.message ?: e.javaClass.simpleName
                                    } finally {
                                        preparing = false
                                    }
                                }
                            }
                        }
                        Playback.changed(ctx)
                }
                SideEffect {
                    actions.play = togglePlay
                    actions.stop = { pause() }
                }

                /**
                 * The loop was already playing when this screen was built, because
                 * the system had destroyed the previous one. It has been stopped to
                 * read the file again; carry on from where it was.
                 */
                LaunchedEffect(Unit) {
                    if (autoResume) {
                        autoResume = false
                        togglePlay()
                    }
                }

                PositionBar(
                    positionMs = positionMs,
                    lengthMs = lengthMs,
                    enabled = !preparing,
                    onSeek = { ms ->
                        positionMs = ms
                        Playback.moved(ms)
                        // Seeking while it plays is a pause and a fresh entry, which
                        // is a gap of a moment - and, with Hi-Fi on, of a rendering.
                        // The alternative is a second playing path with its own
                        // seam, for a gesture nobody makes twice a bar.
                        if (playing) { pause(); togglePlay() }
                    }
                )

                Button(
                    onClick = togglePlay,
                    enabled = !preparing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (playing) "Pause" else if (preparing) "Rendering" else "Play",
                        fontSize = 22.sp, fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    when {
                        preparing -> "Rendering"
                        playing -> "${Ramp.speedLabel(liveSpeed)}   loop ${liveLoop + 1}" +
                            (trainer?.let { if (it.misses > 2) "   behind" else "" } ?: "")
                        Renderer.isPlain(speed, pitch) -> "Ready"
                        else -> "Ready: ${Ramp.speedLabel(speed)}"
                    } + (if (st != null && !st.complete) "   ·   Decoding $decoded%" else ""),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Correcting the key belongs here: this is where you hear that the
                // detected one is wrong. It is saved in the library, keyed by name
                // and size, so it survives and the list shows it as certain.
                Spacer(Modifier.height(6.dp))
                KeyDropdown(
                    label = null,
                    value = keySel?.let { Analyzer.KEY_NAMES[it] } ?: "Set Key by Hand",
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
                        // nothing to save once the shown key IS the saved one
                        enabled = keySel != null &&
                            Analyzer.KEY_NAMES.getOrNull(keySel!!) != known?.manualKey,
                        modifier = Modifier.weight(1f)
                    ) { Text("Save Key") }
                    Spacer(Modifier.width(10.dp))
                    // the same size and the same weight as its neighbour: two halves
                    // of one decision, each dark only when it has nothing to do
                    Button(
                        onClick = {
                            io.launch {
                                val saved = withContext(Dispatchers.IO) {
                                    LibraryFolders.saveManualKey(ctx, known, name, size, uri, null)
                                }
                                known = saved
                                keySel = null
                            }
                        },
                        enabled = known?.manualKey != null,
                        modifier = Modifier.weight(1f)
                    ) { Text("Clear") }
                }
            }

            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
                Text("  Decoding $decoded%", fontSize = 14.sp)
            }
        }
    }
}

/**
 * The cursor: where the loop is, and where the next Play will come in.
 *
 * Held stateless on purpose. The position belongs to the screen, because it is the
 * same number that the entry point is read from, and a bar that owned a position of
 * its own would be a second copy of it - which is how a cursor and a player come to
 * disagree about where the music is.
 *
 * What IS kept here is the finger. While a drag is in progress the bar shows the
 * finger rather than the music, or the two fight each other ten times a second and
 * the handle will not move; the position is only handed over when the finger lifts.
 *
 * A time is shown at both ends rather than under the handle, because the useful
 * reading is not where the handle is but how far in and how much is left.
 */
@Composable
private fun PositionBar(
    positionMs: Long,
    lengthMs: Long,
    enabled: Boolean,
    onSeek: (Long) -> Unit
) {
    var dragging by remember { mutableStateOf(false) }
    var dragMs by remember { mutableStateOf(0f) }
    val known = lengthMs > 0L
    val shown = if (dragging) dragMs.toLong() else positionMs

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = if (known) shown.coerceIn(0L, lengthMs).toFloat() else 0f,
            valueRange = 0f..(if (known) lengthMs.toFloat() else 1f),
            enabled = enabled && known,
            onValueChange = { v -> dragging = true; dragMs = v },
            onValueChangeFinished = {
                dragging = false
                onSeek(dragMs.toLong().coerceIn(0L, lengthMs))
            }
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                if (known) Cursor.label(shown) else "-:--",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                if (known) Cursor.label(lengthMs) else "-:--",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** While a track is still decoding, the little that is known about it. */
@Composable
private fun StreamCard(st: StreamClip, known: TrackRecord?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Line("Format", st.info.container + " · " + st.info.encoding)
            Line("Sample rate", "${st.sampleRate} Hz, ${if (st.channels == 1) "mono" else "${st.channels} channels"}")
            Line("Duration", String.format("%.1f s", st.expectedMs / 1000.0))
            Line("Tempo", known?.tempoLabel() ?: "Not analysed")
            Line("Key", known?.keyLabel() ?: "Not analysed")
        }
    }
}

/** What a score is, on one card: read from the file, not guessed at. */
@Composable
private fun MidiCard(score: Score, res: MidiAnalysis.Result?, known: TrackRecord?, sfLabel: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Line("Format", "MIDI score · " + (res?.let { MidiFile.describe(score, it) } ?: "Reading"))
            Line("Length", String.format("%.2f s (%d ticks, %d per quarter)",
                score.durationSec, score.endTick, score.ppq))
            Line("Tempo", res?.tempoLabel() ?: "-")
            val manual = known?.manualKey
            Line("Key", when {
                manual != null -> "$manual (set by you)"
                res == null -> "-"
                res.keySource == MidiAnalysis.Source.FROM_FILE ->
                    (res.keys.firstOrNull()?.name ?: "-") + " (written in the file)"
                else -> res.keys.take(3).joinToString("  ·  ") {
                    it.name + " " + Math.round(it.p * 100) + "%"
                } + "  (from the notes)"
            })
            Line("SoundFont", sfLabel)
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
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = onMinus,
            enabled = enabled,
            modifier = Modifier.size(48.dp),
            contentPadding = PaddingValues(0.dp)
        ) { Text("-", fontSize = 22.sp, fontWeight = FontWeight.Bold) }

        Column(
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }

        Button(
            onClick = onPlus,
            enabled = enabled,
            modifier = Modifier.size(48.dp),
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
    Column(modifier = Modifier.fillMaxWidth()) {
        // with no label the button itself carries the wording, which saves a line
        if (label != null) {
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            OutlinedButton(
                onClick = { open = true },
                modifier = Modifier.fillMaxWidth().height(44.dp)
            ) { Text(value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
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
    onPlus: () -> Unit,
    /**
     * When true the buttons keep firing while held. Only where long journeys are
     * normal - trimming a whole song one beat at a time - and deliberately NOT in
     * the player, where a step can start a rendering.
     */
    repeatOnHold: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        HoldButton("-", repeatOnHold, onMinus)
        Column(
            modifier = Modifier.padding(horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        HoldButton("+", repeatOnHold, onPlus)
    }
}

/**
 * A stepper button that can also auto-repeat while held.
 *
 * The tap still goes through the button's own onClick, so it keeps its ripple and
 * stays reachable by TalkBack, which produces a click with no press at all. The
 * repeat rides on the press state instead. Those two paths overlap in one case -
 * the release at the end of a hold also delivers a click - so a hold that has begun
 * repeating marks the click that is about to arrive as already spent.
 *
 * The first wait is the platform's own long-press timeout rather than a number of
 * ours: the phone already knows how long a tap lasts, and a tap must never
 * accidentally become a repeat.
 */
@Composable
private fun HoldButton(text: String, repeatOnHold: Boolean, onStep: () -> Unit) {
    if (!repeatOnHold) {
        Button(
            onClick = onStep,
            modifier = Modifier.size(44.dp),
            contentPadding = PaddingValues(0.dp)
        ) { Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        return
    }

    val step = rememberUpdatedState(onStep)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val longPress = LocalViewConfiguration.current.longPressTimeoutMillis
    var spentClick by remember { mutableStateOf(false) }

    LaunchedEffect(pressed) {
        if (!pressed) return@LaunchedEffect
        spentClick = false                  // a fresh press: the next click is a tap
        delay(longPress)
        spentClick = true                   // from here the release is not a tap
        var n = 0
        while (true) {
            step.value.invoke()
            delay(HoldRepeat.delayFor(n))
            n++
        }
    }

    Button(
        onClick = { if (spentClick) spentClick = false else step.value.invoke() },
        interactionSource = interaction,
        modifier = Modifier.size(44.dp),
        contentPadding = PaddingValues(0.dp)
    ) { Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun InfoCard(c: AudioClip, known: TrackRecord?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            val secs = c.durationMs / 1000.0
            Line("Format", formatLine(c))
            Line("Sample rate", "${c.sampleRate} Hz, ${if (c.channels == 1) "mono" else "${c.channels} channels"}")
            Line("Duration", String.format("%.2f s (%d frames, %.1f MB)", secs, c.frames, c.megabytes))
            Line("Tempo", known?.tempoLabel() ?: "Not analysed")
            Line("Key", known?.keyLabel() ?: "Not analysed")
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
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
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
