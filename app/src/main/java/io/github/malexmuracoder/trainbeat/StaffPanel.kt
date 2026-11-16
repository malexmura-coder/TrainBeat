package io.github.malexmuracoder.trainbeat

import android.content.Context
import android.graphics.Typeface
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The player's alternative to the info card: one part of the score, on a staff.
 *
 * It sits in the same place and answers to the same controls, so there is nothing
 * to learn - the position bar, play and the steppers drive it exactly as they drive
 * the sound. What it adds is the choice of WHICH part to read, one at a time, because
 * the point is to read along with the one you are practising and not to see the
 * whole score.
 *
 * The SIZE of the staff is the user's to set, and remembered for the device, not for
 * the track: it depends on the screen in the hand and not on the music. Until it is
 * set it follows the screen (see StaffScale), so a tablet starts out with a staff
 * that is already larger.
 *
 * A part that the file writes for a fretboard (MusicXML with string and fret) is shown
 * as a TABLATURE instead, from what the file says; any other part is a staff.
 *
 * Transposition is followed: the staff shows what is heard, not what the file says.
 * The channel mixer is not: a part that has been switched off can still be read,
 * which is the whole use of reading a part you are about to play yourself.
 */
@Composable
fun StaffPanel(
    score: Score,
    semitones: Int,
    positionMs: Long,
    channel: Int,
    onChannel: (Int) -> Unit,
    /** A tap on the picture asks for it full screen. */
    onOpenFull: () -> Unit = {}
) {
    val ctx = LocalContext.current
    val parts = remember(score) { Instruments.partsOf(score).filter { !it.drums } }
    if (parts.isEmpty()) {
        Text(
            "Nothing to show: the drums are not written on a staff.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    val part = parts.firstOrNull { it.channel == channel } ?: parts.first()

    // null means "not chosen": the size then follows the screen
    var chosen by remember { mutableStateOf(StaffPrefs.get(ctx)) }
    val auto = StaffScale.autoFor(LocalConfiguration.current.smallestScreenWidthDp.toFloat())
    val scale = chosen ?: auto

    Stepper(
        label = if (chosen == null) "Staff size (automatic)" else "Staff size",
        value = StaffScale.label(scale),
        enabled = true,
        buttonSize = 43.dp,
        onMinus = { chosen = StaffScale.stepped(scale, -1).also { StaffPrefs.set(ctx, it) } },
        onPlus = { chosen = StaffScale.stepped(scale, +1).also { StaffPrefs.set(ctx, it) } }
    )
    if (chosen != null) {
        TextButton(
            onClick = { chosen = null; StaffPrefs.set(ctx, null) },
            modifier = Modifier.height(28.dp),
            contentPadding = PaddingValues(0.dp)
        ) { Text("Automatic size", fontSize = 13.sp) }
    }

    Card(modifier = Modifier.fillMaxWidth().padding(top = 3.dp).clickable(onClick = onOpenFull)) {
        StaffDisplay(
            score = score,
            semitones = semitones,
            positionMs = positionMs,
            channel = part.channel,
            scale = scale,
            ink = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * The picture itself - a staff or a tablature, whichever the part is - without any
 * control around it. The player's panel and the full-screen view both show it, so
 * that what is read is drawn by one piece of code.
 *
 * [fitHeight], when given, is the room there is: the picture is made smaller if it
 * would not fit in it, never larger than [scale] says.
 */
@Composable
fun StaffDisplay(
    score: Score,
    semitones: Int,
    positionMs: Long,
    channel: Int,
    scale: Float,
    ink: Color,
    fitHeight: Dp? = null
) {
    val ctx = LocalContext.current
    // the music font ships inside the app; if it somehow cannot be loaded the panel
    // says so rather than drawing a page of empty boxes
    val typeface = remember {
        try {
            Typeface.createFromAsset(ctx.assets, "fonts/Bravura.otf")
        } catch (_: Exception) {
            null
        }
    }
    val parts = remember(score) { Instruments.partsOf(score).filter { !it.drums } }
    if (parts.isEmpty()) return
    val part = parts.firstOrNull { it.channel == channel } ?: parts.first()
    val tabbed = score.tab?.partFor(part.channel) != null

    // Engraving walks every note of the part, so it is done off the main thread and
    // only again when the part or the transposition changes - not at every frame.
    val engraved by produceState<Notation.Engraved?>(null, score, part.channel, semitones, tabbed) {
        value = null
        if (!tabbed) {
            value = withContext(Dispatchers.Default) {
                Notation.engrave(score, part.channel, semitones)
            }
        }
    }
    // null while it is being worked out; Done(null) when there is nothing to show
    val tabEngraved by produceState<Done<TabLayout.Engraved>?>(null, score, part.channel, semitones, tabbed) {
        value = null
        val tab = score.tab
        if (tabbed && tab != null) {
            value = Done(withContext(Dispatchers.Default) { TabLayout.engrave(tab, part.channel, semitones) })
        }
    }

    val e = engraved
    val t = tabEngraved
    val tick = Notation.tickAt(score, positionMs / 1000.0)
    val cursor = Color.Red      // the cursor is red, whatever the theme
    when {
        typeface == null -> Text(
            "The music font could not be loaded.",
            fontSize = 12.sp,
            color = ink,
            modifier = Modifier.padding(12.dp)
        )

        tabbed && t != null && t.value == null -> Text(
            "Nothing to show: no note of this part can be played on its strings.",
            fontSize = 12.sp,
            color = ink,
            modifier = Modifier.padding(12.dp)
        )

        tabbed && t != null -> TabView(
            engraved = t.value!!,
            tick = tick,
            typeface = typeface,
            ink = ink,
            accent = cursor,
            scale = StaffScale.fitted(scale, TabGeometry.heightInSpaces(t.value!!.strings), fitHeight?.value)
        )

        tabbed || e == null -> Box(
            modifier = Modifier.fillMaxWidth().height(80.dp),
            contentAlignment = Alignment.Center
        ) { CircularProgressIndicator(modifier = Modifier.size(20.dp)) }

        else -> StaffView(
            engraved = e,
            tick = tick,
            typeface = typeface,
            ink = ink,
            accent = cursor,
            scale = StaffScale.fitted(scale, StaffLayout.heightInSpaces(e.clefs.size), fitHeight?.value)
        )
    }
}

/**
 * Which part to read, as a line of text to click, in the manner of the mixer's. It
 * lives outside the panel so that the player can put it on the mixer's own line.
 */
@Composable
fun StaffPartPicker(
    score: Score,
    channel: Int,
    onChannel: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val parts = remember(score) { Instruments.partsOf(score).filter { !it.drums } }
    if (parts.isEmpty()) return
    val part = parts.firstOrNull { it.channel == channel } ?: parts.first()
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        TextButton(
            onClick = { open = true },
            modifier = Modifier.height(30.dp),
            contentPadding = PaddingValues(0.dp)
        ) {
            Text(part.label, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            parts.forEach { p ->
                DropdownMenuItem(
                    text = { Text(p.label, fontSize = 15.sp) },
                    onClick = { open = false; onChannel(p.channel) }
                )
            }
        }
    }
}

/** A result that may legitimately be nothing, as opposed to one not yet computed. */
private class Done<T>(val value: T?)

/**
 * The chosen staff size, kept on the device. Absent means "follow the screen".
 *
 * Its own small file, like the other remembered things in this app, and a single
 * float: there is nothing here that deserves more.
 */
object StaffPrefs {
    private const val PREFS = "trainbeat_staff"
    private const val KEY = "scale"

    fun get(ctx: Context): Float? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (p.contains(KEY)) StaffScale.clamp(p.getFloat(KEY, 1f)) else null
    }

    fun set(ctx: Context, scale: Float?) {
        val e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (scale == null) e.remove(KEY) else e.putFloat(KEY, StaffScale.clamp(scale))
        e.apply()
    }
}
