package io.github.malexmuracoder.trainbeat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Make loop from file": cuts a seamless loop out of a track.
 *
 * There is no waveform on purpose - you judge with your ears, and the loop keeps
 * playing while you move the cursors. What replaces the drawing is the reading
 * in BEATS AND BARS: seeing "8.00 bars" tells you the selection is right in a way
 * that a picture of the waveform never would.
 *
 * Fine adjustment moves by one beat, not by milliseconds, because a slider across
 * four minutes cannot place a cut and a beat is the unit that matters musically.
 *
 * The destination folder is asked BEFORE anything else, once, and remembered. It
 * used to be asked by the SAVE button, which then returned without writing: the
 * first press was swallowed by the folder chooser and only the second one saved.
 */
@Composable
fun TrimmerScreen(player: LoopPlayer, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val io = rememberCoroutineScope()

    var uri by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var clip by remember { mutableStateOf<AudioClip?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var bpm by remember { mutableStateOf(0f) }

    var startFrame by remember { mutableStateOf(0) }
    var endFrame by remember { mutableStateOf(0) }
    var snap by remember { mutableStateOf(true) }
    var playing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var folderSet by remember { mutableStateOf(OutputFolder.isSet(ctx)) }
    var outLabel by remember { mutableStateOf(OutputFolder.label(ctx)) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked: Uri? ->
        if (picked != null) {
            uri = picked.toString()
            name = displayName(ctx, picked)
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree: Uri? ->
        if (tree != null) {
            OutputFolder.remember(ctx, tree)
            folderSet = OutputFolder.isSet(ctx)
            outLabel = OutputFolder.label(ctx)
        }
    }

    // asked on entry, not on save: from here on the destination always exists
    LaunchedEffect(Unit) {
        if (!OutputFolder.isSet(ctx)) folderPicker.launch(null)
    }

    LaunchedEffect(uri) {
        val u = uri ?: return@LaunchedEffect
        loading = true
        error = null
        clip = null
        message = ""
        try {
            val c = withContext(Dispatchers.IO) { AudioLoader.load(ctx, Uri.parse(u), trimSilence = false) }
            clip = c
            startFrame = 0
            endFrame = c.frames
            // tempo from a central window: on a whole song the loop constraint
            // does not hold, so the estimate is left free
            bpm = withContext(Dispatchers.IO) {
                val w = Math.min(c.frames, c.sampleRate * 40)
                val from = ((c.frames - w) / 2) * c.channels
                val part = c.samples.copyOfRange(from, from + w * c.channels)
                Analyzer.analyze(part, c.channels, c.sampleRate, loopExact = false).bpm
            }
        } catch (e: Throwable) {
            error = e.message ?: e.javaClass.simpleName
        }
        loading = false
    }

    val c = clip
    val framesPerBeat = if (c != null && bpm > 0f) (60.0 * c.sampleRate / bpm).toInt() else 0

    // audible feedback replaces the drawing: while playing, any change to the
    // selection restarts the loop, with a short pause so dragging does not thrash
    LaunchedEffect(startFrame, endFrame, playing) {
        if (!playing || c == null) return@LaunchedEffect
        delay(150)
        val cut = LoopTrim.slice(c.samples, c.channels, startFrame, endFrame)
        if (cut.size >= c.channels * 64) {
            try {
                player.start(AudioClip(cut, c.sampleRate, c.channels, c.info), 1f, 0, 0f)
            } catch (e: Throwable) {
                message = e.message ?: "cannot play"
                playing = false
            }
        }
    }

    DisposableEffect(playing) {
        view.keepScreenOn = playing
        onDispose { view.keepScreenOn = false }
    }
    DisposableEffect(Unit) { onDispose { player.stop() } }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        TextButton(onClick = { player.stop(); playing = false; onBack() }, contentPadding = PaddingValues(0.dp)) {
            Text("< Home", fontSize = 16.sp)
        }
        Text("Make loop from file", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))

        if (!folderSet) {
            Text(
                "First, choose the folder where the loops will be written. It is asked " +
                    "once and remembered, so saving later is a single tap.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { folderPicker.launch(null) },
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("Choose destination folder", fontSize = 16.sp) }
            return@Column
        }

        Button(
            onClick = { player.stop(); playing = false; picker.launch(arrayOf("*/*")) },
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text(if (uri == null) "Select a file" else "Select another file", fontSize = 16.sp) }

        if (uri != null) {
            Spacer(Modifier.height(6.dp))
            Text(name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
        }

        when {
            loading -> {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Text("  Decoding...", fontSize = 14.sp)
                }
            }

            error != null -> {
                Spacer(Modifier.height(12.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Cannot open this file", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(error ?: "", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }

            c != null -> {
                val lenFrames = (endFrame - startFrame).coerceAtLeast(0)
                val beats = if (framesPerBeat > 0) lenFrames.toDouble() / framesPerBeat else 0.0

                Spacer(Modifier.height(8.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Line("Track", String.format("%s  ·  %d Hz  ·  %s", timeOf(c.frames, c.sampleRate),
                            c.sampleRate, if (c.channels == 1) "mono" else "${c.channels} ch"))
                        Line("Tempo", if (bpm > 0f) String.format("%.1f BPM", bpm) else "not detected")
                        Line(
                            "Start / End",
                            timeOf(startFrame, c.sampleRate) + "  →  " + timeOf(endFrame, c.sampleRate)
                        )
                        Line(
                            "Loop",
                            if (framesPerBeat > 0)
                                String.format("%s   %.2f beats   %.2f bars",
                                    timeOf(lenFrames, c.sampleRate), beats, beats / 4.0)
                            else timeOf(lenFrames, c.sampleRate)
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))
                Slider(
                    value = if (c.frames > 0) startFrame.toFloat() / c.frames else 0f,
                    onValueChange = { v ->
                        val f = (v * c.frames).toInt()
                        startFrame = alignFrame(f, framesPerBeat, snap)
                        // pushing, not blocking: the end moves along and keeps a bar of room
                        val minLen = if (framesPerBeat > 0) framesPerBeat * 4 else c.sampleRate
                        if (endFrame < startFrame + minLen) {
                            endFrame = Math.min(c.frames, startFrame + minLen)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(28.dp)
                )
                MiniStepper(
                    label = if (framesPerBeat > 0) "start, one beat" else "start, 10 ms",
                    value = timeOf(startFrame, c.sampleRate),
                    onMinus = { startFrame = nudge(startFrame, -1, framesPerBeat, c.sampleRate, 0, endFrame) },
                    onPlus = { startFrame = nudge(startFrame, 1, framesPerBeat, c.sampleRate, 0, endFrame) }
                )

                Spacer(Modifier.height(2.dp))
                Slider(
                    value = if (c.frames > 0) endFrame.toFloat() / c.frames else 1f,
                    onValueChange = { v ->
                        val f = (v * c.frames).toInt()
                        val minLen = if (framesPerBeat > 0) framesPerBeat * 4 else c.sampleRate
                        endFrame = Math.max(startFrame + minLen, alignFrame(f, framesPerBeat, snap))
                            .coerceAtMost(c.frames)
                    },
                    modifier = Modifier.fillMaxWidth().height(28.dp)
                )
                MiniStepper(
                    label = if (framesPerBeat > 0) "end, one beat" else "end, 10 ms",
                    value = timeOf(endFrame, c.sampleRate),
                    onMinus = { endFrame = nudge(endFrame, -1, framesPerBeat, c.sampleRate, startFrame, c.frames) },
                    onPlus = { endFrame = nudge(endFrame, 1, framesPerBeat, c.sampleRate, startFrame, c.frames) }
                )

                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { snap = !snap }, contentPadding = PaddingValues(0.dp)) {
                        Text(if (snap) "snap to beat: on" else "snap to beat: off", fontSize = 12.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (framesPerBeat > 0) "beat = ${framesPerBeat} frames" else "no tempo",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = {
                        if (playing) {
                            player.stop()
                            playing = false
                        } else {
                            playing = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text(if (playing) "STOP" else "PLAY LOOP", fontSize = 20.sp, fontWeight = FontWeight.Bold) }

                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            val t = LoopTrim.refine(c.samples, c.channels, c.sampleRate, startFrame, endFrame)
                            startFrame = t.start
                            endFrame = t.end
                            message = "auto trim: start %+d, end %+d frames".format(t.movedStart, t.movedEnd)
                        },
                        modifier = Modifier.weight(1f).height(44.dp)
                    ) { Text("AUTO TRIM") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val cut = LoopTrim.slice(c.samples, c.channels, startFrame, endFrame)
                            val base = name.substringBeforeLast('.', name)
                            val barsText = if (framesPerBeat > 0)
                                "_%dbars".format(Math.round(beats / 4.0)) else ""
                            val fileName = base + barsText + "_loop.wav"
                            io.launch {
                                val res = withContext(Dispatchers.IO) {
                                    try {
                                        val out = OutputFolder.createWav(ctx, fileName)
                                        ctx.contentResolver.openOutputStream(out)?.use { st ->
                                            WavWriter.write(st, cut, c.sampleRate, c.channels)
                                        } ?: return@withContext "could not open $fileName for writing"
                                        "saved as $fileName in ${OutputFolder.label(ctx)}"
                                    } catch (e: Throwable) {
                                        "save failed: ${e.message ?: e.javaClass.simpleName}"
                                    }
                                }
                                message = res
                            }
                        },
                        modifier = Modifier.weight(1f).height(44.dp)
                    ) { Text("SAVE") }
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    "saving into: $outLabel",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (message.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(message, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }

            else -> {
                Spacer(Modifier.height(14.dp))
                Text(
                    "Pick a track and cut a loop out of it. The selection keeps playing " +
                        "while you move the cursors, and the length is shown in bars, so you " +
                        "can hear and read whether it is right.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "A whole song is decoded into memory, so a track is fine and an " +
                        "hour-long set is not.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "saving into: $outLabel",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** mm:ss.mmm */
private fun timeOf(frames: Int, sampleRate: Int): String {
    if (sampleRate <= 0) return "-"
    val ms = frames * 1000L / sampleRate
    val m = ms / 60000
    val s = (ms % 60000) / 1000
    val r = ms % 1000
    return String.format("%d:%02d.%03d", m, s, r)
}

/** Rounds a frame onto the beat grid when snapping is on. */
private fun alignFrame(frame: Int, framesPerBeat: Int, snap: Boolean): Int {
    if (!snap || framesPerBeat <= 0) return Math.max(0, frame)
    return Math.max(0, Math.round(frame.toDouble() / framesPerBeat).toInt() * framesPerBeat)
}

/** One beat when the tempo is known, 10 ms otherwise. */
private fun nudge(frame: Int, dir: Int, framesPerBeat: Int, sampleRate: Int, lo: Int, hi: Int): Int {
    val step = if (framesPerBeat > 0) framesPerBeat else sampleRate / 100
    return (frame + dir * step).coerceIn(lo, hi)
}
