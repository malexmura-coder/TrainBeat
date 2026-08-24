package io.github.malexmuracoder.trainbeat

import org.junit.Test
import java.io.File

/**
 * Desktop tool for trying real files, no phone involved:
 *
 *   ./gradlew :app:testDebugUnitTest --tests "*LoopRenderTest*" \
 *       -Dlooper.wav="/path/to/backing.wav" -Dlooper.loops=4
 *
 * It decodes the WAV with the same code that runs on the phone, prints the
 * format it found, measures the amplitude step at the seam, and writes a
 * "<name>.loop.wav" holding N laps glued together. Open that file on the Mac
 * and hear - and see, in an editor - whether the seam is clean.
 */
class LoopRenderTest {

    @Test
    fun renderLoop() {
        val path = ToolInput.path("looper.wav", "render-file.txt")
        if (path.isNullOrBlank()) {
            println("[render] " + ToolInput.hint("render-file.txt", "looper.wav"))
            return
        }
        val src = File(path)
        require(src.isFile) { "file not found: $path" }
        val loops = (System.getProperty("looper.loops") ?: "3").toIntOrNull() ?: 3

        val clip = src.inputStream().use { WavReader.read(it) }
        println("[render] file        : ${src.name}")
        println("[render] container   : ${clip.info.container}")
        println("[render] format      : ${clip.info.encoding}")
        println("[render] sample rate : ${clip.sampleRate} Hz, ${clip.channels} channels")
        println("[render] duration    : ${"%.3f".format(clip.durationMs / 1000.0)} s (${clip.frames} frames)")
        println("[render] in memory   : ${"%.1f".format(clip.megabytes)} MB")
        clip.info.notes.forEach { println("[render] note        : $it") }

        // amplitude step at the seam: this, not the code, is what produces
        // the audible click when the track starts over
        val ch = clip.channels
        val last = clip.frames - 1
        var maxJump = 0
        for (k in 0 until ch) {
            val a = clip.samples[last * ch + k].toInt()
            val b = clip.samples[k].toInt()
            val jump = kotlin.math.abs(b - a)
            if (jump > maxJump) maxJump = jump
            println("[render] channel $k   : last=$a first=$b step=$jump")
        }
        val pct = maxJump * 100.0 / 65535.0
        println("[render] max step    : ${"%.2f".format(pct)}% of full scale")
        println(
            when {
                pct < 0.5 -> "[render] verdict     : clean seam, you will hear nothing"
                pct < 3.0 -> "[render] verdict     : small step, likely inaudible under an instrument"
                else -> "[render] verdict     : large step, the file does not end where it begins"
            }
        )

        // numeric check: the stream produced is the track repeated, exactly
        val feeder = LoopFeeder(clip.samples)
        val out = ShortArray(clip.samples.size * loops)
        val chunk = ShortArray(8192)
        var w = 0
        while (w < out.size) {
            val n = feeder.fill(chunk, minOf(chunk.size, out.size - w))
            chunk.copyInto(out, w, 0, n)
            w += n
        }
        var mismatch = -1
        for (i in out.indices) {
            if (out[i] != clip.samples[i % clip.samples.size]) { mismatch = i; break }
        }
        println(
            if (mismatch < 0) "[render] continuity  : sample-exact over $loops laps (${out.size} samples)"
            else "[render] continuity  : ERROR at sample $mismatch"
        )
        require(mismatch < 0) { "the loop stream does not match the repeated track" }

        // NOT next to the original: the backing-track folder stays clean.
        // It lands in app/build/loop-render/, wiped by ./gradlew clean.
        val outDir = File(System.getProperty("looper.outdir") ?: "build/loop-render")
        outDir.mkdirs()
        val dest = File(outDir, src.nameWithoutExtension + ".loop.wav")
        WavTestUtil.writeWav16(dest, out, clip.sampleRate, clip.channels)
        println("[render] written     : ${dest.absolutePath}")
        println("[render] seams every : ${"%.3f".format(clip.durationMs / 1000.0)} s")
    }
}
