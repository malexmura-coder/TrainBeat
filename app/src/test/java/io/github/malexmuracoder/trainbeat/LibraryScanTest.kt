package io.github.malexmuracoder.trainbeat

import org.junit.Test
import java.io.File

/**
 * Desktop dry run of the library scan, before any of this touches the phone:
 *
 *   ./gradlew :app:testDebugUnitTest --tests "*LibraryScanTest*" --rerun-tasks
 *
 * The folder is read from scan-folder.txt in the project root - edit that file
 * once instead of typing a long command - or from -Dtrainbeat.dir if you prefer --rerun-tasks
 *
 * It lists the folder, times the first few files to estimate the total, scans
 * everything and prints name, tempo and key candidates.
 *
 * Only WAV files can be read here: MP3 decoding needs MediaCodec, which exists
 * on Android and not on a desktop JVM. MP3s are counted and skipped.
 */
class LibraryScanTest {

    @Test
    fun scanFolder() {
        val dir = ToolInput.path("trainbeat.dir", "scan-folder.txt")
        if (dir.isNullOrBlank()) {
            println("[scan] " + ToolInput.hint("scan-folder.txt", "trainbeat.dir"))
            return
        }
        val root = File(dir)
        require(root.isDirectory) { "not a folder: $dir" }

        val wavs = ArrayList<File>()
        var mp3 = 0
        root.walkTopDown().forEach { f ->
            if (!f.isFile) return@forEach
            val n = f.name.lowercase()
            when {
                n.endsWith(".wav") || n.endsWith(".wave") || n.endsWith(".bwf") -> wavs.add(f)
                n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".flac") || n.endsWith(".ogg") -> mp3++
            }
        }
        wavs.sortBy { it.name.lowercase() }

        println("[scan] folder     : ${root.absolutePath}")
        println("[scan] wav found  : ${wavs.size}")
        if (mp3 > 0) println("[scan] skipped    : $mp3 compressed files (need Android to decode)")
        if (wavs.isEmpty()) return

        // estimate from the first few files instead of guessing
        val probe = minOf(3, wavs.size)
        var probeMs = 0L
        val warm = ArrayList<String>()
        for (i in 0 until probe) {
            val t0 = System.currentTimeMillis()
            warm.add(describe(wavs[i]))
            probeMs += System.currentTimeMillis() - t0
        }
        val perFile = probeMs.toDouble() / probe
        val estSec = perFile * wavs.size / 1000.0
        println(
            "[scan] estimate   : %.1f s per file, about %.0f s (%.1f min) for %d files"
                .format(perFile / 1000.0, estSec, estSec / 60.0, wavs.size)
        )
        println()
        println("%-52s %-34s %s".format("FILE", "TEMPO", "KEY"))
        println("-".repeat(120))

        val t0 = System.currentTimeMillis()
        var ok = 0
        var failed = 0
        for ((i, f) in wavs.withIndex()) {
            val line = if (i < probe) warm[i] else describe(f)
            println(line)
            if (line.contains("ERROR")) failed++ else ok++
        }
        val took = (System.currentTimeMillis() - t0) / 1000.0
        println("-".repeat(120))
        println("[scan] done       : $ok analysed, $failed failed, %.1f s".format(took))
    }

    private fun describe(f: File): String {
        return try {
            val clip = f.inputStream().use { WavReader.read(it) }
            val r = Analyzer.analyze(clip.samples, clip.channels, clip.sampleRate)
            "%-52s %-34s %s".format(cut(f.name, 52), Analyzer.tempoLabel(r), Analyzer.keyLabel(r))
        } catch (e: Throwable) {
            "%-52s ERROR  %s".format(cut(f.name, 52), e.message ?: e.javaClass.simpleName)
        }
    }

    private fun cut(s: String, n: Int) = if (s.length <= n) s else s.substring(0, n - 1) + "~"
}
