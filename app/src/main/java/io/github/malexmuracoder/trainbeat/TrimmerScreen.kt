package io.github.malexmuracoder.trainbeat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.geometry.Offset
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
 * "Make loop from file": cuts a seamless loop out of a track, or out of a score.
 *
 * On AUDIO the cut is placed by ear and by the reading in BEATS AND BARS, and the
 * fine adjustment moves by one beat, because a slider across four minutes cannot
 * place a cut.
 *
 * There IS a drawing, added for jobs it does better than the ear. On a short take it
 * says at a glance WHICH END the dead air is at, which the ear only answers by
 * playing the whole loop twice and counting. And it follows the selection rather
 * than showing the whole file: on a ten minute track a picture of everything gives
 * a four bar selection two pixels, which is no picture at all. It stays small and
 * deliberately not interactive - the cursors are placed better by the controls
 * below it.
 *
 * On MIDI there is no seam to find: a score has bar lines and a cut on one is exact
 * by construction, which is why this side has no AUTO TRIM and no snap switch. But
 * whole bars alone were not enough. Pieces open with a fill or a pickup, so the
 * chord sequence starts INSIDE a bar, and a step of one bar could never reach it -
 * hence the step menu, and hence positions kept in ticks, where a third of a bar is
 * an exact number. It can save either a .mid, which stays adjustable in tempo and
 * key forever, or a .wav, which plays anywhere without a SoundFont.
 *
 * The destination folder is asked BEFORE anything else, once, and remembered.
 */
@Composable
fun TrimmerScreen(player: LoopPlayer, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val io = rememberCoroutineScope()

    var uri by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var isMidi by remember { mutableStateOf(false) }

    var clip by remember { mutableStateOf<AudioClip?>(null) }
    var score by remember { mutableStateOf<Score?>(null) }
    var midiInfo by remember { mutableStateOf<MidiAnalysis.Result?>(null) }

    var loading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var bpm by remember { mutableStateOf(0f) }

    var startFrame by remember { mutableStateOf(0) }
    var endFrame by remember { mutableStateOf(0) }
    // In TICKS, not in bar numbers: a chord sequence that starts after a fill does
    // not start on a bar line, and with whole bars only that point is unreachable.
    var startTick by remember { mutableStateOf(0) }
    var endTick by remember { mutableStateOf(0) }
    var barStep by remember { mutableStateOf(BarStep.DEFAULT) }
    var stepOpen by remember { mutableStateOf(false) }
    var snap by remember { mutableStateOf(true) }
    var playing by remember { mutableStateOf(false) }
    // null = stopped, 0 = playing from the beginning, 1 = playing from before the seam
    var entry by remember { mutableStateOf<Int?>(null) }
    var message by remember { mutableStateOf("") }
    var folderSet by remember { mutableStateOf(OutputFolder.isSet(ctx)) }
    var outLabel by remember { mutableStateOf(OutputFolder.label(ctx)) }
    var sfSet by remember { mutableStateOf(SoundFontStore.isSet(ctx)) }
    // The drawn shape, and the window of the file it was drawn from.
    //
    // It used to be the whole file, computed once. On a ten minute track that made
    // one pixel column worth more than a second, so a four bar selection was two
    // pixels wide and the picture said nothing. Now it follows the selection, with
    // some context either side - which is also CHEAPER than before, because there
    // are fewer samples to walk.
    var shape by remember { mutableStateOf<Envelope.Shape?>(null) }
    var shapeFrom by remember { mutableStateOf(0) }
    var shapeTo by remember { mutableStateOf(0) }
    var grid by remember { mutableStateOf(Grid.OFF) }
    var gridOpen by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked: Uri? ->
        if (picked != null) {
            uri = picked.toString()
            name = displayName(ctx, picked)
            isMidi = MidiFile.isMidi(name)
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree: Uri? ->
        if (tree != null) {
            OutputFolder.remember(ctx, tree)
            folderSet = OutputFolder.isSet(ctx)
            outLabel = OutputFolder.label(ctx)
        }
    }

    val sfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked: Uri? ->
        if (picked != null) {
            SoundFontStore.remember(ctx, picked, displayName(ctx, picked))
            sfSet = true
            message = "SoundFont set"
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
        score = null
        midiInfo = null
        shape = null
        message = ""
        try {
            if (isMidi) {
                val sc = withContext(Dispatchers.IO) { MidiFile.loadScore(ctx, Uri.parse(u)) }
                val info = MidiAnalysis.analyse(sc)
                score = sc
                midiInfo = info
                bpm = info.bpm
                startTick = 0
                endTick = sc.ticksPerBar().coerceAtLeast(1) * minOf(4, maxOf(1, info.bars))
            } else {
                val c = withContext(Dispatchers.IO) {
                    AudioLoader.load(ctx, Uri.parse(u), trimSilence = false)
                }
                clip = c
                startFrame = 0
                endFrame = c.frames
                // a phrase played into the microphone has no measurable tempo, so
                // the beat grid would place cuts by a number nobody measured
                if (c.recorded || AudioClip.looksRecorded(name)) snap = false
                // tempo from a central window: on a whole song the loop constraint
                // does not hold, so the estimate is left free
                bpm = if (c.recorded || AudioClip.looksRecorded(name)) 0f
                else withContext(Dispatchers.IO) {
                    val w = Math.min(c.frames, c.sampleRate * 40)
                    val from = ((c.frames - w) / 2) * c.channels
                    val part = c.samples.copyOfRange(from, from + w * c.channels)
                    Analyzer.analyze(part, c.channels, c.sampleRate, loopExact = false).bpm
                }
            }
        } catch (e: Throwable) {
            error = e.message ?: e.javaClass.simpleName
        }
        loading = false
    }

    val c = clip
    val sc = score
    val framesPerBeat = if (c != null && bpm > 0f) (60.0 * c.sampleRate / bpm).toInt() else 0
    val ticksPerBar = sc?.ticksPerBar() ?: 0
    val totalTicks = sc?.endTick ?: 0
    val totalBars = if (sc != null && ticksPerBar > 0)
        Math.max(1, Math.ceil(sc.endTick.toDouble() / ticksPerBar).toInt()) else 0

    /** The score of the selected bars, ready to be played or written out. */
    fun slice(): Score? {
        val s = sc ?: return null
        if (ticksPerBar <= 0) return null
        return s.slice(startTick, endTick)
    }

    // Redrawn when the selection settles, not while it moves: the same pause the
    // loop restart uses, and for the same reason - holding a fine button down would
    // otherwise recompute the picture at every step.
    LaunchedEffect(clip, startFrame, endFrame) {
        val c0 = clip ?: return@LaunchedEffect
        delay(HoldRepeat.SLOW_MS + 100)
        val len = (endFrame - startFrame).coerceAtLeast(1)
        // a tenth of the selection either side, so the cuts are not stuck on the
        // edges of the picture and you can still see what comes before and after
        val pad = (len / 10).coerceAtLeast(1)
        val from = (startFrame - pad).coerceAtLeast(0)
        val to = (endFrame + pad).coerceAtMost(c0.frames)
        val part = withContext(Dispatchers.Default) {
            Envelope.of(
                c0.samples.copyOfRange(from * c0.channels, to * c0.channels),
                c0.channels, WAVE_COLUMNS
            )
        }
        shapeFrom = from
        shapeTo = to
        shape = part
    }

    // audible feedback replaces the drawing: while playing, any change to the
    // selection restarts the loop, with a pause so dragging does not thrash. That
    // pause must stay LONGER than the auto-repeat interval of the fine buttons,
    // otherwise holding one down restarts playback at every step and you hear
    // nothing but the first instant of the loop, over and over.
    LaunchedEffect(startFrame, endFrame, startTick, endTick, playing, entry) {
        if (!playing) return@LaunchedEffect
        delay(HoldRepeat.SLOW_MS + 100)
        try {
            if (sc != null) {
                val cut = slice() ?: return@LaunchedEffect
                // opening the SoundFont touches the disk: not on the main thread
                val res = withContext(Dispatchers.Default) {
                    Synth.render(cut, SoundFontStore.soundFont(ctx), 44100)
                }
                player.start(44100, 2, res.samples)
            } else if (c != null) {
                val cut = LoopTrim.slice(c.samples, c.channels, startFrame, endFrame)
                if (cut.size >= c.channels * 64) {
                    // the second button comes in that many frames BEFORE the end,
                    // so its entry offset is measured from the end; the first one
                    // starts at zero and must stay exactly zero
                    val loopFrames = cut.size / c.channels
                    val begin =
                        if (entry == 1) (loopFrames - leadInFrames(loopFrames, c.sampleRate)) * c.channels
                        else 0
                    player.start(c.sampleRate, c.channels, cut, begin)
                }
            }
        } catch (e: Throwable) {
            message = e.message ?: "Cannot play"
            playing = false
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
        // no page title: you know which button you pressed to get here
        TextButton(onClick = { player.stop(); playing = false; entry = null; onBack() }, contentPadding = PaddingValues(0.dp)) {
            Text("< Home", fontSize = 18.sp)
        }
        Spacer(Modifier.height(4.dp))

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
            ) { Text("Choose Destination Folder", fontSize = 16.sp) }
            return@Column
        }

        Button(
            onClick = { player.stop(); playing = false; entry = null; picker.launch(arrayOf("*/*")) },
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text(if (uri == null) "Select a File" else "Select Another File", fontSize = 16.sp) }

        if (uri != null) {
            Spacer(Modifier.height(6.dp))
            Text(name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
        }

        when {
            loading -> {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Text(if (isMidi) "  Reading the score..." else "  Decoding...", fontSize = 14.sp)
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

            // ---------------- a score: cut on bar lines ----------------
            sc != null -> {
                val info = midiInfo
                Spacer(Modifier.height(8.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Line("Score", info?.let { MidiFile.describe(sc, it) } ?: "-")
                        Line("Tempo", info?.tempoLabel() ?: "-")
                        Line("Bars", "$totalBars in the file")
                        Line(
                            "Selection",
                            BarStep.label(startTick, ticksPerBar, barStep) + "  ->  " +
                                BarStep.label(endTick, ticksPerBar, barStep) + "  ·  " +
                                fixed("%.2f s", tickSeconds(sc, startTick, endTick))
                        )
                    }
                }

                val step = BarStep.ticks(barStep, ticksPerBar)

                Spacer(Modifier.height(6.dp))
                Slider(
                    value = if (totalTicks > 0) startTick.toFloat() / totalTicks else 0f,
                    onValueChange = { v ->
                        val t = (v * totalTicks).toInt() / step * step
                        startTick = t.coerceIn(0, (totalTicks - step).coerceAtLeast(0))
                        if (endTick <= startTick) endTick = (startTick + step).coerceAtMost(totalTicks)
                    },
                    modifier = Modifier.fillMaxWidth().height(28.dp)
                )
                MiniStepper(
                    label = "start",
                    value = BarStep.label(startTick, ticksPerBar, barStep),
                    onMinus = { startTick = BarStep.nudge(startTick, -1, barStep, ticksPerBar, 0, endTick - step) },
                    onPlus = { startTick = BarStep.nudge(startTick, 1, barStep, ticksPerBar, 0, endTick - step) },
                    repeatOnHold = true
                )
                Spacer(Modifier.height(2.dp))
                Slider(
                    value = if (totalTicks > 0) endTick.toFloat() / totalTicks else 1f,
                    onValueChange = { v ->
                        val t = (v * totalTicks).toInt() / step * step
                        endTick = t.coerceIn(startTick + step, totalTicks)
                    },
                    modifier = Modifier.fillMaxWidth().height(28.dp)
                )
                MiniStepper(
                    label = "end",
                    value = BarStep.label(endTick, ticksPerBar, barStep),
                    onMinus = { endTick = BarStep.nudge(endTick, -1, barStep, ticksPerBar, startTick + step, totalTicks) },
                    onPlus = { endTick = BarStep.nudge(endTick, 1, barStep, ticksPerBar, startTick + step, totalTicks) },
                    repeatOnHold = true
                )

                // A whole bar is not enough. Pieces open with a fill or a pickup, so
                // the chord sequence starts inside a bar, and stepping by whole bars
                // could never reach it. In ticks these divisions are exact.
                Box {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Step: $barStep",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { stepOpen = true }
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            fixed("%.3f s", tickSeconds(sc, 0, step)),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(expanded = stepOpen, onDismissRequest = { stepOpen = false }) {
                        BarStep.OPTIONS.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option, fontSize = 14.sp) },
                                onClick = { barStep = option; stepOpen = false }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    "Cut on bar lines: exact by construction, nothing to trim.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (!sfSet) {
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { sfPicker.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth().height(46.dp)
                    ) { Text("Choose a SoundFont to Hear It", fontSize = 15.sp) }
                } else {
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            if (playing) { player.stop(); playing = false } else { playing = true }
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) { Text(if (playing) "Stop" else "Play Loop", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                }

                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            val cut = slice() ?: return@Button
                            val base = name.substringBeforeLast('.', name)
                            val fileName = "%s_bars%d-%d.mid".format(
                                base, BarStep.barNumber(startTick, ticksPerBar),
                                BarStep.barNumber(endTick - 1, ticksPerBar))
                            io.launch {
                                message = withContext(Dispatchers.IO) {
                                    try {
                                        val out = OutputFolder.createMidi(ctx, fileName)
                                        ctx.contentResolver.openOutputStream(out)?.use { st ->
                                            st.write(SmfWriter.write(cut))
                                        } ?: return@withContext "could not open $fileName for writing"
                                        "saved $fileName in ${OutputFolder.label(ctx)}"
                                    } catch (e: Throwable) {
                                        "save failed: ${e.message ?: e.javaClass.simpleName}"
                                    }
                                }
                            }
                        },
                        modifier = Modifier.weight(1f).height(44.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) { Text("Save .mid", fontSize = 13.sp) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val cut = slice() ?: return@Button
                            val base = name.substringBeforeLast('.', name)
                            val fileName = "%s_bars%d-%d_loop.wav".format(
                                base, BarStep.barNumber(startTick, ticksPerBar),
                                BarStep.barNumber(endTick - 1, ticksPerBar))
                            busy = true
                            io.launch {
                                message = withContext(Dispatchers.Default) {
                                    try {
                                        val sf = SoundFontStore.soundFont(ctx)
                                        val res = Synth.render(cut, sf, 44100)
                                        val out = OutputFolder.createWav(ctx, fileName)
                                        ctx.contentResolver.openOutputStream(out)?.use { st ->
                                            WavWriter.write(st, res.samples, res.sampleRate, res.channels)
                                        } ?: return@withContext "could not open $fileName for writing"
                                        "saved $fileName in ${OutputFolder.label(ctx)}"
                                    } catch (e: Throwable) {
                                        "save failed: ${e.message ?: e.javaClass.simpleName}"
                                    }
                                }
                                busy = false
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.weight(1f).height(44.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) { Text(if (busy) "Rendering" else "Save .wav", fontSize = 13.sp) }
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    "Saving into: $outLabel",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (message.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(message, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }

            // ---------------- audio: cut on beats, then look for the seam ----------------
            c != null -> {
                val lenFrames = (endFrame - startFrame).coerceAtLeast(0)
                val beats = if (framesPerBeat > 0) lenFrames.toDouble() / framesPerBeat else 0.0

                Spacer(Modifier.height(8.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Line("Track", String.format("%s  ·  %d Hz  ·  %s", timeOf(c.frames, c.sampleRate),
                            c.sampleRate, if (c.channels == 1) "mono" else "${c.channels} ch"))
                        if (c.recorded || AudioClip.looksRecorded(name)) {
                            Line("Source", "Recorded on this phone - snap to beat is off")
                        } else {
                            Line("Tempo", if (bpm > 0f) fixed("%.1f BPM", bpm) else "Not detected")
                        }
                        Line(
                            "Start / End",
                            timeOf(startFrame, c.sampleRate) + "  ->  " + timeOf(endFrame, c.sampleRate)
                        )
                        Line(
                            "Loop",
                            if (framesPerBeat > 0)
                                timeOf(lenFrames, c.sampleRate) +
                                    fixed("   %.2f beats   %.2f bars", beats, beats / 4.0)
                            else timeOf(lenFrames, c.sampleRate)
                        )
                    }
                }

                // Half the height of the card above, and it answers the one question
                // the card cannot: on a short take, WHICH END the dead air is at.
                // Judging that by ear means playing the whole thing twice.
                Spacer(Modifier.height(10.dp))
                Waveform(
                    shape = shape,
                    from = shapeFrom,
                    to = shapeTo,
                    startFrame = startFrame,
                    endFrame = endFrame,
                    grid = Grid.of(grid, startFrame, endFrame),
                    modifier = Modifier.fillMaxWidth().height(76.dp)
                )

                // one clickable line, not an ExposedDropdownMenuBox: that widget is
                // 56 dp tall on its own, for the same one word of text
                Box {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Grid: $grid",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { gridOpen = true }
                        )
                        Spacer(Modifier.weight(1f))
                        val div = Grid.divisionSeconds(grid, startFrame, endFrame, c.sampleRate)
                        Text(
                            if (div > 0) fixed("%.3f s each", div) else "",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(expanded = gridOpen, onDismissRequest = { gridOpen = false }) {
                        Grid.OPTIONS.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option, fontSize = 14.sp) },
                                onClick = { grid = option; gridOpen = false }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(2.dp))
                Slider(
                    value = if (c.frames > 0) startFrame.toFloat() / c.frames else 0f,
                    onValueChange = { v ->
                        val f = (v * c.frames).toInt()
                        startFrame = TrimStep.align(f, framesPerBeat, snap)
                        // pushing, not blocking: the end moves along and keeps a bar of room
                        val minLen = if (framesPerBeat > 0) framesPerBeat * 4 else c.sampleRate
                        if (endFrame < startFrame + minLen) {
                            endFrame = Math.min(c.frames, startFrame + minLen)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(28.dp)
                )
                MiniStepper(
                    label = TrimStep.label("start", framesPerBeat, snap),
                    value = timeOf(startFrame, c.sampleRate),
                    onMinus = {
                        startFrame = TrimStep.nudge(startFrame, -1, framesPerBeat, c.sampleRate, snap, 0, endFrame)
                    },
                    onPlus = {
                        startFrame = TrimStep.nudge(startFrame, 1, framesPerBeat, c.sampleRate, snap, 0, endFrame)
                    },
                    repeatOnHold = true
                )

                Spacer(Modifier.height(2.dp))
                Slider(
                    value = if (c.frames > 0) endFrame.toFloat() / c.frames else 1f,
                    onValueChange = { v ->
                        val f = (v * c.frames).toInt()
                        val minLen = if (framesPerBeat > 0) framesPerBeat * 4 else c.sampleRate
                        endFrame = Math.max(startFrame + minLen, TrimStep.align(f, framesPerBeat, snap))
                            .coerceAtMost(c.frames)
                    },
                    modifier = Modifier.fillMaxWidth().height(28.dp)
                )
                MiniStepper(
                    label = TrimStep.label("end", framesPerBeat, snap),
                    value = timeOf(endFrame, c.sampleRate),
                    onMinus = {
                        endFrame = TrimStep.nudge(endFrame, -1, framesPerBeat, c.sampleRate, snap, startFrame, c.frames)
                    },
                    onPlus = {
                        endFrame = TrimStep.nudge(endFrame, 1, framesPerBeat, c.sampleRate, snap, startFrame, c.frames)
                    },
                    repeatOnHold = true
                )

                // a plain clickable Text, not a TextButton: Material forces a 40 dp
                // minimum height on those, which is sixteen wasted dp for one line
                Row(
                    modifier = Modifier.fillMaxWidth().height(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (snap) "Snap to Beat: On" else "Snap to Beat: Off",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { snap = !snap }
                    )
                    Spacer(Modifier.weight(1f))
                    // the step of the fine buttons, in the same unit as the times
                    // above it. Frames are nobody's unit and made this look like it
                    // disagreed with the beat count.
                    Text(
                        if (snap && framesPerBeat > 0)
                            "Beat = " + fixed("%.3f s", framesPerBeat.toDouble() / c.sampleRate)
                        else "${TrimStep.MANUAL_MS} ms steps",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(2.dp))
                // Two ways into the same loop. Entering five seconds before the end
                // puts the seam under the ear straight away, instead of making you
                // wait through the whole selection to reach the one moment that
                // decides whether the cut is good.
                val lenFrames2 = (endFrame - startFrame).coerceAtLeast(1)
                val lead = leadInFrames(lenFrames2, c.sampleRate)
                Row(modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            if (playing && entry == 0) {
                                player.stop(); playing = false; entry = null
                            } else {
                                entry = 0; playing = true
                            }
                        },
                        modifier = Modifier.weight(1f).height(48.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        Text(
                            if (playing && entry == 0) "Stop" else "Play From 0:00",
                            fontSize = 15.sp, fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (playing && entry == 1) {
                                player.stop(); playing = false; entry = null
                            } else {
                                entry = 1; playing = true
                            }
                        },
                        modifier = Modifier.weight(1f).height(48.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        Text(
                            if (playing && entry == 1) "Stop"
                            else "Play From -%s".format(shortTime(lead, c.sampleRate)),
                            fontSize = 15.sp, fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    // A filled Button, not an outlined one. Outlined beside a filled
                    // one reads as disabled, and this was never disabled: refine()
                    // looks for a zero crossing and knows nothing about tempo, so it
                    // is just as useful on a phrase recorded off the radio as on an
                    // mp3 - the click at the join is the same click.
                    Button(
                        onClick = {
                            val t = LoopTrim.refine(c.samples, c.channels, c.sampleRate, startFrame, endFrame)
                            startFrame = t.start
                            endFrame = t.end
                            message = "auto trim: start %+d, end %+d frames".format(t.movedStart, t.movedEnd)
                        },
                        modifier = Modifier.weight(1f).height(44.dp)
                    ) { Text("Auto Trim") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val cut = LoopTrim.slice(c.samples, c.channels, startFrame, endFrame)
                            val base = name.substringBeforeLast('.', name)
                            // WHERE the cut is, not just how long it is. Two four bar
                            // loops from the same song - one from the verse, one from
                            // the chorus - used to want the same name, and the picker
                            // then handed back "(1)" and "(2)", which say nothing.
                            val fileName = base + LoopName.suffix(
                                startFrame, endFrame, framesPerBeat, c.sampleRate)
                            io.launch {
                                message = withContext(Dispatchers.IO) {
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
                            }
                        },
                        modifier = Modifier.weight(1f).height(44.dp)
                    ) { Text("Save .wav") }
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    "Saving into: $outLabel",
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
                    "Pick a track and cut a loop out of it. On audio the selection keeps " +
                        "playing while you move the cursors, and the length is shown in bars. " +
                        "On a MIDI file the cut lands on bar lines, so it is exact.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "A whole song is decoded into memory, so a track is fine and an " +
                        "hour-long set is not. A MIDI file costs nothing to read.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Saving into: $outLabel",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Seconds of the selected bars, following the score's own tempo map. */
private fun tickSeconds(score: Score, fromTick: Int, toTick: Int): Double {
    val a = score.secondsAt(fromTick)
    val b = score.secondsAt(toTick)
    return (b - a).coerceAtLeast(0.0)
}

/**
 * How far before the end the second button comes in: five seconds, or half the
 * loop when the loop is shorter than that, so it can never land before the start.
 */
private fun leadInFrames(loopFrames: Int, sampleRate: Int): Int {
    val five = sampleRate * 5
    return if (loopFrames <= five * 6 / 5) loopFrames / 2 else five
}

/** m:ss, for a button that has no room for more. */
private fun shortTime(frames: Int, sampleRate: Int): String {
    if (sampleRate <= 0) return "0:00"
    val s = frames / sampleRate
    return "%d:%02d".format(s / 60, s % 60)
}

/**
 * How many min/max pairs the picture is made of. Fixed rather than derived from the
 * canvas width: at 480 columns each one is two or three physical pixels wide on a
 * phone, which is finer than a finger can aim, and a constant keeps the shape from
 * being recomputed every time the layout is measured.
 */
private const val WAVE_COLUMNS = 480

/**
 * The track, drawn once, with the selection lit up inside it.
 *
 * Deliberately not interactive: the sliders and the fine buttons already place the
 * cuts, and a drag target spanning a whole track would place them worse.
 *
 * What is drawn is a WINDOW of the file - the selection plus a tenth of it either
 * side - so the shape is large enough to read and the cuts are not stuck against
 * the edges. The grid, when one is chosen, is drawn between the cursors, over the
 * shape.
 * This is here to be READ - the selected part is bright, the discarded part is
 * dim, and dead air is flat wherever it happens to be.
 *
 * Nothing is recomputed while the cursors move; only the two lines are redrawn.
 */
@Composable
private fun Waveform(
    shape: Envelope.Shape?,
    /** First frame of the file that the shape covers. */
    from: Int,
    /** One past the last frame of the file that the shape covers. */
    to: Int,
    startFrame: Int,
    endFrame: Int,
    grid: Grid.Lines,
    modifier: Modifier = Modifier
) {
    val inside = MaterialTheme.colorScheme.primary
    val outside = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
    val axis = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    val cursor = MaterialTheme.colorScheme.onSurface
    val strong = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    val weak = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)

    Canvas(modifier = modifier) {
        val mid = size.height / 2f
        drawLine(axis, Offset(0f, mid), Offset(size.width, mid), strokeWidth = 1f)

        val s = shape ?: return@Canvas                    // still being computed
        val n = s.columns
        val span = to - from
        if (n <= 0 || span <= 0) return@Canvas

        val colW = size.width / n
        // everything is measured inside the drawn window, not inside the whole file
        val col = { f: Int -> ((f - from).toLong() * n / span).toInt().coerceIn(0, n) }
        val a = col(startFrame)
        val b = col(endFrame)

        for (i in 0 until n) {
            val top = mid - s.max[i] * mid
            var bot = mid - s.min[i] * mid
            if (bot - top < 1f) bot = top + 1f            // silence is still a line
            drawLine(
                color = if (i in a until b) inside else outside,
                start = Offset(i * colW + colW / 2f, top),
                end = Offset(i * colW + colW / 2f, bot),
                strokeWidth = colW.coerceAtLeast(1f)
            )
        }

        // the grid goes OVER the shape - the whole question is whether a peak sits
        // on a line, and a line hidden behind the peak cannot answer it. Faint
        // triplet lines first, so a main line is never covered by one.
        val xOf = { f: Int -> col(f) * colW }
        for (f in grid.weak) drawLine(weak, Offset(xOf(f), 0f), Offset(xOf(f), size.height), strokeWidth = 1f)
        for (f in grid.strong) drawLine(strong, Offset(xOf(f), 0f), Offset(xOf(f), size.height), strokeWidth = 1f)

        // where the cuts are, drawn last so they are never hidden by anything
        drawLine(cursor, Offset(a * colW, 0f), Offset(a * colW, size.height), strokeWidth = 2f)
        drawLine(cursor, Offset(b * colW, 0f), Offset(b * colW, size.height), strokeWidth = 2f)
    }
}

/**
 * String.format with a FIXED locale.
 *
 * The default one follows the phone, and on an Italian phone "%.1f" prints 95,8 -
 * a decimal comma in a technical readout, which reads as a thousands separator to
 * everyone else and cannot be pasted anywhere useful.
 */
private fun fixed(pattern: String, vararg args: Any): String =
    String.format(java.util.Locale.US, pattern, *args)

/** mm:ss.mmm */
private fun timeOf(frames: Int, sampleRate: Int): String {
    if (sampleRate <= 0) return "-"
    val ms = frames * 1000L / sampleRate
    val m = ms / 60000
    val s = (ms % 60000) / 1000
    val r = ms % 1000
    return String.format("%d:%02d.%03d", m, s, r)
}
