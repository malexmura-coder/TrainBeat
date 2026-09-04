package io.github.malexmuracoder.trainbeat

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The starred loops, in the order they were starred.
 *
 * Deliberately the SAME rows as the library, drawn by the same composable: two
 * lists of the same thing that looked different would be a small lie about the app.
 * What this screen does not have is the search, the tempo range and the key filter -
 * favourites are few and scrolled by eye, and filtering a shelf you curated by hand
 * would be filtering your own choices.
 *
 * Tempo and key come from the scan, matched by name and size. A favourite that was
 * never scanned - a file opened straight from a folder, a loop just cut, a recording
 * - has no tempo and no key to show, and says so rather than inventing them.
 *
 * A favourite is a pointer to a document, and pointers die: the file is moved or
 * renamed, or the folder grant is withdrawn. A dead one is shown greyed and marked
 * missing, and tapping it forgets it. Dropping them silently was rejected - a
 * favourite that vanishes without a word looks like the app lost it.
 */
@Composable
fun FavouritesScreen(onBack: () -> Unit, onOpen: (String, String) -> Unit) {
    val ctx = LocalContext.current
    val io = rememberCoroutineScope()

    class Row(
        val entry: TrackPrefs.Entry,
        val record: TrackRecord,
        val alive: Boolean
    )

    var rows by remember { mutableStateOf<List<Row>>(emptyList()) }
    var checked by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf<String?>(null) }
    var editIdx by remember { mutableStateOf(0) }

    // resolving a uri touches the provider, and the library is a file: not on the
    // main thread
    LaunchedEffect(reload) {
        val loaded = withContext(Dispatchers.IO) {
            val prefs = LibraryFolders.prefs(ctx)
            val known = LibraryFolders.store(ctx).loadAll().associateBy { it.uri }
            prefs.favourites().map { e ->
                Row(
                    entry = e,
                    // what the scan measured, or an empty record that will honestly
                    // report "not analysed"
                    record = known[e.uri] ?: TrackRecord(name = e.name, size = 0L, uri = e.uri),
                    alive = alive(ctx, e.uri)
                )
            }
        }
        rows = loaded
        checked = true
    }

    fun persist(rec: TrackRecord) {
        io.launch { withContext(Dispatchers.IO) { LibraryFolders.store(ctx).append(rec) } }
        rows = rows.map { if (it.record.uri == rec.uri) Row(it.entry, rec, it.alive) else it }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        BackLink(onBack)
        Spacer(Modifier.height(4.dp))

        if (!checked) {
            Text("Checking...", fontSize = 14.sp)
            return@Column
        }

        if (rows.isEmpty()) {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text("No favourites yet.", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Tap the star beside a name, in the player or in the library, to " +
                        "put a loop here.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(rows) { row ->
                val r = row.record
                TrackRow(
                    record = r,
                    starred = true,
                    onOpen = {
                        if (row.alive) {
                            onOpen(row.entry.uri, row.entry.name)
                        } else {
                            // the only thing left to do with a dead pointer
                            LibraryFolders.prefs(ctx).remove(row.entry.uri)
                            reload++
                        }
                    },
                    onStar = {
                        // on this screen the star can only mean "take it off the shelf"
                        LibraryFolders.prefs(ctx).toggleFavourite(row.entry.uri, row.entry.name)
                        reload++
                    },
                    editing = editing == r.cacheKey,
                    onEdit = {
                        editing = if (editing == r.cacheKey) null else r.cacheKey
                        editIdx = r.effectiveKeyIndex
                    },
                    editIdx = editIdx,
                    onEditIdx = { editIdx = it },
                    onSaveKey = {
                        persist(r.copy(manualKey = Analyzer.KEY_NAMES[editIdx]))
                        editing = null
                    },
                    onCancelEdit = { editing = null },
                    onClearKey = { persist(r.copy(manualKey = null)) },
                    extra = settingsLine(row.entry),
                    missing = !row.alive
                )
            }
        }
    }
}

/**
 * What is remembered about how to play it, or nothing if it is all default.
 *
 * Worth a line of its own here and not in the library: on a shelf of exercises it
 * tells you what speed you had got to, without opening anything.
 */
private fun settingsLine(e: TrackPrefs.Entry): String {
    val bits = ArrayList<String>(4)
    if (e.speed != 1.0f) bits.add(Ramp.speedLabel(e.speed))
    if (e.semitones != 0) bits.add(Ramp.pitchLabel(e.semitones))
    if (e.train > 0f) bits.add("train " + Ramp.trainLabel(e.train))
    // A silenced instrument changes what you will hear more than a semitone does,
    // so it belongs on the same line rather than being a surprise on opening.
    if (e.voices != Instruments.ALL) bits.add("mixer")
    return bits.joinToString("  ·  ")
}

/**
 * Does this document still exist and are we still allowed to read it?
 *
 * Opening the stream is the only honest test. A uri can look perfectly well formed
 * and still be dead - the permission was revoked, the sd card is out, the file was
 * renamed - and none of that shows until something tries to read it.
 */
private fun alive(ctx: Context, uri: String): Boolean =
    try {
        ctx.contentResolver.openInputStream(Uri.parse(uri))?.use { true } ?: false
    } catch (_: Throwable) {
        false
    }

/** The manual: one scrolling page, no links, no network, no images. */
@Composable
fun ManualScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    // read once when the page opens, not on every scroll
    val page = remember { Manual.load(ctx) }

    Column(
        modifier = Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        BackLink(onBack)
        Spacer(Modifier.height(4.dp))

        Text("TrainBeat", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(page.intro, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        page.sections.forEach { section ->
            Spacer(Modifier.height(14.dp))
            Text(section.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Text(section.body, fontSize = 13.sp, lineHeight = 18.sp)
        }

        Spacer(Modifier.height(20.dp))
    }
}
