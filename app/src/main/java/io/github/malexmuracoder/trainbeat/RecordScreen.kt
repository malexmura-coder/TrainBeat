package io.github.malexmuracoder.trainbeat

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the speaker's play key presses while the recorder is on screen. */
private class RecActions {
    var rec: () -> Unit = {}
}

/**
 * "Record a loop": microphone in, WAV out.
 *
 * Deliberately bare - one big button and a clock. What it does NOT try to be is a
 * pedal looper: nobody can play a guitar and hit a phone screen on the downbeat,
 * so the recording will have some silence at each end and the honest thing is to
 * say so on the screen rather than pretend otherwise. The trimmer is where that
 * gets cut, and the file carries a mark so the trimmer knows to leave the beat
 * grid switched off when it opens one of these.
 *
 * Stopping does not save. The take stays in memory and can be listened to, kept or
 * thrown away - because most takes are bad ones, and a folder full of bad takes is
 * worse than no recorder at all.
 *
 * The dead air at the ends can be cut here too, and that cut is reversible: the
 * switch stores two offsets and applies them on save, so nothing is ever destroyed
 * and turning it off restores the whole take instantly.
 */
@Composable
fun RecordScreen(player: LoopPlayer, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val io = rememberCoroutineScope()

    val recorder = remember { Recorder() }
    var folderSet by remember { mutableStateOf(RecordFolder.isSet(ctx)) }
    var folderLabel by remember { mutableStateOf(RecordFolder.label(ctx)) }
    var recording by remember { mutableStateOf(false) }
    var elapsed by remember { mutableStateOf(0.0) }
    var peak by remember { mutableStateOf(0f) }
    var message by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    // the take waiting to be judged, and the reversible trim on it
    var take by remember { mutableStateOf<Recorder.Result?>(null) }
    var bounds by remember { mutableStateOf<Silence.Bounds?>(null) }
    var trim by remember { mutableStateOf(false) }
    var playingBack by remember { mutableStateOf(false) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree: Uri? ->
        if (tree != null) {
            RecordFolder.remember(ctx, tree)
            folderSet = RecordFolder.isSet(ctx)
            folderLabel = RecordFolder.label(ctx)
        }
    }

    var hasMic by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasMic = granted }

    // the folder is asked on entry, as in the trimmer: by the time REC is pressed
    // there is always somewhere to put the result
    LaunchedEffect(Unit) {
        if (!RecordFolder.isSet(ctx)) folderPicker.launch(null)
    }

    LaunchedEffect(recording) {
        while (recording) {
            elapsed = recorder.seconds
            peak = recorder.peak
            // the recorder can also stop by itself - the five minute limit, or the
            // microphone being taken away - and that take deserves the same
            // treatment as one stopped by hand
            if (!recorder.isRecording) {
                recorder.error?.let { message = it }
                val r = recorder.stop()
                recording = false
                if (r != null && r.frames > 0) {
                    take = r
                    trim = false
                    bounds = withContext(Dispatchers.Default) {
                        Silence.detect(r.samples, r.channels, r.sampleRate)
                    }
                }
            }
            delay(100)
        }
    }

    /** What would be saved, and what plays back: the take, trimmed or whole. */
    fun selected(t: Recorder.Result): ShortArray {
        val b = bounds
        return if (trim && b != null && b.usable)
            LoopTrim.slice(t.samples, t.channels, b.start, b.end)
        else t.samples
    }

    // playing back loops, so a short phrase can be judged more than once without
    // reaching for the screen again
    LaunchedEffect(playingBack, trim) {
        val t = take
        if (!playingBack || t == null) return@LaunchedEffect
        delay(120)
        try {
            player.start(t.sampleRate, t.channels, selected(t))
        } catch (e: Throwable) {
            message = e.message ?: "Cannot play"
            playingBack = false
        }
    }

    val recActions = remember { RecActions() }
    val keys = remember {
        object : Playback.Handler {
            override fun onPlayPause() { recActions.rec() }
            override fun onStop() { recActions.rec() }
            override fun onSkip(forward: Boolean): Boolean = false
            override val busy: Boolean get() = recording || playingBack
            override val title: String get() = "Recording"
        }
    }
    DisposableEffect(keys) {
        Playback.takeCharge(ctx, keys)
        onDispose { Playback.release(keys) }
    }

    DisposableEffect(recording, playingBack) {
        view.keepScreenOn = recording || playingBack
        onDispose { view.keepScreenOn = false }
    }
    DisposableEffect(Unit) { onDispose { recorder.stop(); player.stop() } }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        TextButton(
            onClick = { recorder.stop(); player.stop(); recording = false; playingBack = false; onBack() },
            contentPadding = PaddingValues(0.dp)
        ) { Text("< Home", fontSize = 18.sp) }

        Spacer(Modifier.height(4.dp))
        Text(
            "Tapping is not playing: expect some silence at both ends.\n" +
                "Trim it afterwards with Make Loop From File.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(14.dp))

        if (!folderSet) {
            Text(
                "First choose where recordings go.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { folderPicker.launch(null) },
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("Choose Folder", fontSize = 16.sp) }
            return@Column
        }

        if (!hasMic) {
            Text(
                "The microphone permission is needed to record.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { micPermission.launch(Manifest.permission.RECORD_AUDIO) },
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("Allow the Microphone", fontSize = 16.sp) }
            return@Column
        }

        // the same gesture the speaker's play key performs: AVRCP has no record
        // command, so play/pause means REC here, where the recorder is on screen
        val toggleRec: () -> Unit = {
                if (recording) {
                    val result = recorder.stop()
                    recording = false
                    if (result == null || result.frames <= 0) {
                        message = "Nothing was recorded"
                    } else {
                        message = ""
                        take = result
                        trim = false
                        bounds = null
                        io.launch {
                            bounds = withContext(Dispatchers.Default) {
                                Silence.detect(result.samples, result.channels, result.sampleRate)
                            }
                        }
                    }
                } else {
                    try {
                        message = ""
                        player.stop()
                        playingBack = false
                        take = null
                        bounds = null
                        recorder.start()
                        elapsed = 0.0
                        recording = true
                    } catch (e: Throwable) {
                        message = e.message ?: e.javaClass.simpleName
                    }
                }
                Playback.changed(ctx)
        }
        SideEffect { recActions.rec = toggleRec }
        Button(
            onClick = toggleRec,
            enabled = !saving,
            modifier = Modifier.fillMaxWidth().height(72.dp)
        ) {
            Text(
                if (recording) "Stop" else "Rec",
                fontSize = 28.sp, fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "%d:%02d.%d".format(
                    (elapsed / 60).toInt(), (elapsed % 60).toInt(), ((elapsed * 10) % 10).toInt()
                ),
                fontSize = 22.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.width(10.dp))
            Text(
                if (recording) "Peak %3d%%".format(Math.round(peak * 100)) else "",
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        val t = take
        if (t != null && !recording) {
            val b = bounds
            val kept = if (trim && b != null && b.usable) b.frames else t.frames
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        if (playingBack) { player.stop(); playingBack = false }
                        else playingBack = true
                    },
                    enabled = !saving,
                    modifier = Modifier.weight(1f).height(52.dp)
                ) {
                    Text(
                        if (playingBack) "Stop" else "Play",
                        fontSize = 18.sp, fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        player.stop()
                        playingBack = false
                        saving = true
                        val samples = selected(t)
                        io.launch {
                            message = withContext(Dispatchers.IO) {
                                try {
                                    val fileName = recorder.fileName(System.currentTimeMillis())
                                    val out = RecordFolder.createWav(ctx, fileName)
                                    ctx.contentResolver.openOutputStream(out)?.use { st ->
                                        WavWriter.write(
                                            st, samples, t.sampleRate,
                                            t.channels, AudioClip.RECORDER_TAG
                                        )
                                    } ?: return@withContext "could not open $fileName for writing"
                                    "saved %s · %.1f s".format(
                                        fileName, samples.size.toDouble() / t.channels / t.sampleRate)
                                } catch (e: Throwable) {
                                    "save failed: ${e.message ?: e.javaClass.simpleName}"
                                }
                            }
                            saving = false
                            if (message.startsWith("saved")) { take = null; bounds = null; trim = false }
                        }
                    },
                    enabled = !saving,
                    modifier = Modifier.weight(1f).height(52.dp)
                ) {
                    Text(
                        if (saving) "Saving" else "Save",
                        fontSize = 18.sp, fontWeight = FontWeight.Bold
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().height(26.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Discard",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        player.stop(); playingBack = false
                        take = null; bounds = null; trim = false; elapsed = 0.0
                    }
                )
                Spacer(Modifier.width(14.dp))
                if (b == null) {
                    Text(
                        "Checking...",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (b.usable) {
                    Text(
                        if (trim) "Trim Silence: On" else "Trim Silence: Off",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { trim = !trim }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        Silence.describe(b, t.frames, t.sampleRate),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        "Nothing to trim",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                "take %.1f s · %s".format(kept.toDouble() / t.sampleRate, t.sourceName),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "saving into: $folderLabel",
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (message.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    message,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }
    }
}
