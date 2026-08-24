package io.github.malexmuracoder.trainbeat

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/**
 * The sampler. Turns a score plus a SoundFont into audio.
 *
 * Written for OFFLINE rendering, and that is what makes it simple: there is no
 * deadline, no ring buffer, no voice stealing panic. A loop is rendered once, in
 * one pass, and then played back untouched at the native rate. Tempo and key have
 * already been applied to the score, so nothing here stretches or resamples audio
 * for musical reasons - the only resampling is the one every sampler does, playing
 * a recorded note at a different pitch.
 *
 * Two passes on purpose: the first builds the voices, the second renders them. The
 * exclusive class (the open hi-hat that has to stop when the closed one hits) can
 * only be resolved once the following notes are known.
 */
object Synth {

    const val MAX_SECONDS = 300.0
    private const val SILENCE_CB = 1000.0                    // -100 dB: voice is over

    class Result(
        val samples: ShortArray,
        val channels: Int,
        val sampleRate: Int,
        val voices: Int,
        val peak: Double,
        val appliedGain: Double,
        val missingPresets: List<String>
    ) {
        val frames: Int get() = samples.size / channels

        /** Nothing was found to play it with: the caller has to say so, not shrug. */
        val silent: Boolean get() = voices == 0
    }

    private class Voice(
        val region: Region,
        val channel: Int,
        val startSec: Double,
        var offSec: Double,
        var cutSec: Double,
        val key: Int,
        val velocity: Int,
        val channelGain: Double,
        val channelPan: Double
    )

    /**
     * Renders the score. Speed is a plain time scale: 0.8 means slower, and since
     * it is applied to the score and not to audio, the pitch does not move and
     * nothing is smeared.
     */
    fun render(
        score: Score,
        sf: SoundFont,
        sampleRate: Int = 44100,
        speed: Double = 1.0,
        cancelled: () -> Boolean = { false },
        progress: (Double) -> Unit = {},
        /**
         * The result is meant to LOOP, so what is still ringing when the score ends
         * is folded back onto the beginning instead of being left as a tail. A tail
         * would be a hole in the loop; folded, the last chord decays across the seam
         * the way it would if the band simply played the passage again.
         */
        wrapTail: Boolean = true
    ): Result {
        val sp = if (speed <= 0.0) 1.0 else speed
        val durSec = score.durationSec / sp
        if (durSec > MAX_SECONDS) {
            throw IllegalArgumentException(
                "the selection is %.0f s long: choose a shorter loop (limit %.0f s)".format(durSec, MAX_SECONDS))
        }

        // ---------- pass one: voices ----------
        val missing = LinkedHashSet<String>()
        val voices = ArrayList<Voice>(score.notes.size + 16)
        val lastOfClass = HashMap<Int, Voice>()
        val programs = ProgramTrack(score)
        val volumes = ControllerTrack(score, 7, 100)
        val expression = ControllerTrack(score, 11, 127)
        val pans = ControllerTrack(score, 10, 64)

        for (n in score.notes) {
            val drums = n.channel == Score.DRUM_CHANNEL
            val pc = programs.at(n.tick, n.channel)
            val preset = sf.select(pc.bank, pc.program, drums)
            if (preset == null) {
                missing.add(
                    if (drums) "no drum kit in this SoundFont"
                    else "no instrument for program ${pc.program}")
                continue
            }
            val regions = sf.regionsFor(preset, n.key, n.velocity)
            if (regions.isEmpty()) {
                missing.add("${preset.name}: no sample for key ${n.key}")
                continue
            }
            val startSec = score.secondsAt(n.tick) / sp
            val offSec = score.secondsAt(n.endTick) / sp
            val cv = volumes.at(n.tick, n.channel) / 127.0
            val ce = expression.at(n.tick, n.channel) / 127.0
            val cp = (pans.at(n.tick, n.channel) - 64) / 63.0
            for (r in regions) {
                val v = Voice(r, n.channel, startSec, offSec, Double.MAX_VALUE, n.key, n.velocity,
                    cv * cv * ce * ce, cp.coerceIn(-1.0, 1.0))
                if (r.exclusiveClass > 0) {
                    val id = n.channel * 1000 + r.exclusiveClass
                    lastOfClass[id]?.let { prev -> if (prev.cutSec > startSec) prev.cutSec = startSec }
                    lastOfClass[id] = v
                }
                voices.add(v)
            }
        }

        // ---------- pass two: audio ----------
        val loopFrames = Math.round(durSec * sampleRate).toInt().coerceAtLeast(1)
        val tail = (1.5 * sampleRate).toInt()                 // room for the last release
        val frames = loopFrames + tail
        val left = FloatArray(frames)
        val right = FloatArray(frames)
        var done = 0
        for (v in voices) {
            if (cancelled()) break
            renderVoice(v, sf, sampleRate, left, right)
            done++
            if (done % 64 == 0) progress(done.toDouble() / voices.size)
        }

        val outFrames = if (wrapTail) loopFrames else frames
        if (wrapTail) {
            for (i in loopFrames until frames) {
                val k = (i - loopFrames) % loopFrames
                left[k] += left[i]
                right[k] += right[i]
            }
        }

        var peak = 0.0
        for (i in 0 until outFrames) {
            val a = abs(left[i]); if (a > peak) peak = a.toDouble()
            val b = abs(right[i]); if (b > peak) peak = b.toDouble()
        }
        // A backing track with twenty voices will always exceed full scale. Rather
        // than clip it, the whole render is scaled to -1 dBFS - one gain applied to
        // everything, so the balance between instruments is untouched.
        val gain = if (peak > 0.891) 0.891 / peak else 1.0
        val out = ShortArray(outFrames * 2)
        var j = 0
        for (i in 0 until outFrames) {
            out[j++] = clip(left[i].toDouble() * gain)
            out[j++] = clip(right[i].toDouble() * gain)
        }
        return Result(out, 2, sampleRate, voices.size, peak, gain, missing.toList())
    }

    private fun clip(v: Double): Short {
        val x = v * 32767.0
        return when {
            x > 32767.0 -> 32767
            x < -32768.0 -> -32768
            else -> x.toInt().toShort()
        }
    }

    private fun renderVoice(v: Voice, sf: SoundFont, sr: Int, left: FloatArray, right: FloatArray) {
        val r = v.region
        val data = sf.samplesOf(r)
        if (data.size < 4) return

        val loopFrom = (r.loopStart - r.sampleStart).coerceIn(0, data.size - 1)
        val loopTo = (r.loopEnd - r.sampleStart).coerceIn(loopFrom + 1, data.size)
        val loops = r.loops && loopTo - loopFrom > 8

        // pitch: the sampler's own resampling, from the recorded root to the played key
        val key = if (r.fixedKey in 0..127) r.fixedKey else v.key
        val cents = (key - r.rootKey) * r.scaleTuning +
            r.coarseTuneSemis * 100 + r.fineTuneCents + r.correctionCents
        val step = 2.0.pow(cents / 1200.0) * r.sampleRate / sr

        val vel = if (r.fixedVel in 1..127) r.fixedVel else v.velocity
        // velocity to amplitude: the square is the usual reading of the default
        // velocity-to-attenuation modulator, and sounds right on real material
        val velGain = (vel / 127.0).pow(2.0)
        val amp = 10.0.pow(-r.attenuationCb / 200.0) * velGain * v.channelGain

        // constant-power pan: the region's own pan, then the channel's
        val p = ((r.pan / 500.0) + v.channelPan).coerceIn(-1.0, 1.0)
        val ang = (p + 1.0) * 0.25 * Math.PI
        val gl = cos(ang)
        val gr = sin(ang)

        val startFrame = (v.startSec * sr).toInt().coerceAtLeast(0)
        // A one-shot percussion sample plays to its end: drum notes in real files
        // are a few ticks long, and honouring their note-off would cut every crash
        // cymbal in half. Only an exclusive class (closed hi-hat over open) stops it.
        val oneShot = !loops && v.channel == Score.DRUM_CHANNEL
        val offSec = if (oneShot) minOf(v.cutSec, Double.MAX_VALUE) else minOf(v.offSec, v.cutSec)
        val offFrame = if (offSec == Double.MAX_VALUE) Int.MAX_VALUE / 2
            else (offSec * sr).toInt().coerceAtLeast(startFrame + 1)
        val fastCut = v.cutSec < v.offSec && !oneShot         // stopped by an exclusive class
        val releaseSec = if (fastCut) minOf(r.releaseSec, 0.02) else r.releaseSec
        val span = if (offFrame == Int.MAX_VALUE / 2) data.size + sr
            else (offFrame - startFrame) + ((releaseSec + 0.05) * sr).toInt()
        val maxFrames = span.coerceAtMost(left.size - startFrame)
        if (maxFrames <= 0) return

        // volume envelope, in the units the format uses: attack is linear in
        // amplitude, decay and release are linear in decibels
        val delayF = (r.delaySec * sr).toInt()
        val attackF = (r.attackSec * sr).toInt()
        val holdF = (r.holdSec * sr).toInt()
        val decayF = (r.decaySec * sr).toInt().coerceAtLeast(1)
        val sustainCb = r.sustainCb.toDouble()
        val releaseF = (releaseSec * sr).toInt().coerceAtLeast(1)
        val offRel = offFrame - startFrame

        // optional lowpass, as many presets rely on it for their character
        val fc = 8.176 * 2.0.pow(r.filterCents / 1200.0)
        val useFilter = fc < sr * 0.45 && r.filterCents < 13500
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2f = 0.0
        var a1 = 0.0; var a2 = 0.0; var b0 = 1.0; var b1 = 0.0; var b2 = 0.0
        if (useFilter) {
            val q = 10.0.pow(r.filterQCb / 200.0).coerceIn(0.5, 8.0)
            val w = 2.0 * Math.PI * fc / sr
            val alpha = sin(w) / (2.0 * q)
            val cw = cos(w)
            val a0 = 1.0 + alpha
            b0 = (1.0 - cw) / 2.0 / a0
            b1 = (1.0 - cw) / a0
            b2 = b0
            a1 = -2.0 * cw / a0
            a2 = (1.0 - alpha) / a0
        }

        // The envelope is stepped, not recomputed: an exponential in decibels is a
        // geometric sequence in amplitude, so one multiplication per frame replaces
        // a pow() per frame. On a four-bar loop that is the difference between one
        // second of rendering and four.
        val sustainLevel = 10.0.pow(-sustainCb / 200.0)
        val decayFactor = if (sustainCb <= 0.0) 1.0 else sustainLevel.pow(1.0 / decayF)
        val releaseFactor = 10.0.pow(-SILENCE_CB / (200.0 * releaseF))
        val attackStep = if (attackF > 0) 1.0 / attackF else 1.0
        val loopSpan = loopTo - loopFrom
        val lastSafe = data.size - 2

        var env = 0.0
        var pos = 0.0
        var i = 0
        var stage = 0                                        // 0 delay 1 attack 2 hold 3 decay 4 release
        while (i < maxFrames) {
            // ---- envelope, one step ----
            if (i >= offRel && stage < 4) {
                stage = 4
            }
            when (stage) {
                0 -> {
                    if (i >= delayF) { stage = 1 } else { env = 0.0 }
                }
                1 -> {
                    env += attackStep
                    if (env >= 1.0) { env = 1.0; stage = 2 }
                }
                2 -> {
                    if (i >= delayF + attackF + holdF) stage = 3
                }
                3 -> {
                    env *= decayFactor
                    if (env < sustainLevel) env = sustainLevel
                }
                else -> {
                    env *= releaseFactor
                    if (env < 1e-5) break
                }
            }
            if (stage == 1 && env <= 0.0) env = attackStep

            // ---- sample lookup, cubic over the four neighbours ----
            var ip = pos.toInt()
            if (loops) {
                if (ip >= loopTo) {
                    pos = loopFrom + ((pos - loopFrom) % loopSpan)
                    ip = pos.toInt()
                }
            } else if (ip >= lastSafe) {
                break
            }
            val frac = pos - ip

            val i0 = if (ip > 0) ip - 1 else 0
            var i2 = ip + 1
            var i3 = ip + 2
            if (loops) {
                if (i2 >= loopTo) i2 = loopFrom + (i2 - loopFrom) % loopSpan
                if (i3 >= loopTo) i3 = loopFrom + (i3 - loopFrom) % loopSpan
            } else {
                if (i2 > lastSafe) i2 = lastSafe
                if (i3 > lastSafe) i3 = lastSafe
            }
            val y0 = data[i0] * INV
            val y1s = data[ip] * INV
            val y2 = data[i2] * INV
            val y3 = data[i3] * INV
            val c1 = 0.5 * (y2 - y0)
            val c2 = y0 - 2.5 * y1s + 2.0 * y2 - 0.5 * y3
            val c3 = 0.5 * (y3 - y0) + 1.5 * (y1s - y2)
            val s = ((c3 * frac + c2) * frac + c1) * frac + y1s

            val filtered: Double
            if (useFilter) {
                val y = b0 * s + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2f
                x2 = x1; x1 = s
                y2f = y1; y1 = y
                filtered = y
            } else {
                filtered = s
            }

            val value = filtered * env * amp
            val k = startFrame + i
            if (k >= left.size) break
            left[k] += (value * gl).toFloat()
            right[k] += (value * gr).toFloat()

            pos += step
            i++
        }
    }

    private const val INV = 1.0 / 32768.0

    /** Program and bank in force on a channel at a tick. */
    private class ProgramTrack(score: Score) {
        class State(val program: Int, val bank: Int)
        private val byChannel = Array(16) { ArrayList<Pair<Int, State>>() }
        init {
            for (p in score.programs) byChannel[p.channel].add(Pair(p.tick, State(p.program, p.bank)))
        }
        fun at(tick: Int, channel: Int): State {
            var s = State(0, 0)
            for ((t, v) in byChannel[channel]) {
                if (t > tick) break
                s = v
            }
            return s
        }
    }

    /** One controller's value per channel at a tick. */
    private class ControllerTrack(score: Score, number: Int, private val default: Int) {
        private val byChannel = Array(16) { ArrayList<Pair<Int, Int>>() }
        init {
            for (c in score.controllers) if (c.number == number) byChannel[c.channel].add(Pair(c.tick, c.value))
        }
        fun at(tick: Int, channel: Int): Int {
            var v = default
            for ((t, x) in byChannel[channel]) {
                if (t > tick) break
                v = x
            }
            return v
        }
    }
}
