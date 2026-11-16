package io.github.malexmuracoder.trainbeat

/**
 * SoundFont 2 reader.
 *
 * The app ships one small SoundFont and the user may pick another .sf2 with the
 * system picker, the same way library folders are granted. A General MIDI bank is
 * 30 to 150 MB, so the file is NEVER read whole. What is read at open time is only the index -
 * presets, instruments, zones, sample headers, a few hundred kilobytes - and the
 * sample data is pulled out on demand, for the handful of instruments a backing
 * track actually uses.
 *
 * Random access is expressed as a ByteSource so the same code works on a plain
 * file (tests and bench) and on a content uri (the app).
 */

interface ByteSource {
    val size: Long
    fun read(pos: Long, dst: ByteArray, off: Int, len: Int)
    fun close() {}
}

class UnsupportedSoundFont(message: String) : Exception(message)

/** One sample-playing region, with everything already resolved to usable units. */
class Region(
    val sampleStart: Int,
    val sampleEnd: Int,
    val loopStart: Int,
    val loopEnd: Int,
    val sampleRate: Int,
    val rootKey: Int,
    val correctionCents: Int,
    val fineTuneCents: Int,
    val coarseTuneSemis: Int,
    val scaleTuning: Int,
    val loopMode: Int,
    val attenuationCb: Int,
    val pan: Int,
    val delaySec: Double,
    val attackSec: Double,
    val holdSec: Double,
    val decaySec: Double,
    val sustainCb: Int,
    val releaseSec: Double,
    val filterCents: Int,
    val filterQCb: Int,
    val keyLo: Int,
    val keyHi: Int,
    val velLo: Int,
    val velHi: Int,
    val fixedKey: Int,
    val fixedVel: Int,
    val exclusiveClass: Int
) {
    val loops: Boolean get() = (loopMode == 1 || loopMode == 3) && loopEnd > loopStart + 8
}

class Preset(val bank: Int, val program: Int, val name: String, internal val zones: List<Zone>)

class Zone internal constructor(
    internal val gens: IntArray,
    internal val instrument: Int,
    internal val sampleId: Int
)

class SoundFont private constructor(
    private val src: ByteSource,
    private val smplOffset: Long,
    private val smplFrames: Int,
    val name: String,
    val presets: List<Preset>,
    private val instZones: List<List<Zone>>,
    private val samples: List<SampleHeader>
) {
    class SampleHeader(
        val name: String, val start: Int, val end: Int, val loopStart: Int, val loopEnd: Int,
        val rate: Int, val originalPitch: Int, val correction: Int, val type: Int
    )

    private val cache = HashMap<Int, ShortArray>()

    /** How many ordinary instruments the file carries: bank 0 is General MIDI. */
    val melodicPresets: Int by lazy { presets.count { it.bank == 0 } }

    /** How many drum kits: bank 128. */
    val drumKits: Int by lazy { presets.count { it.bank == 128 } }

    /**
     * True when the file has no ordinary instruments at all. Supplement files
     * exist - FluidR3 ships one of a few megabytes holding only sound effects -
     * and playing a piano part with them produces gunshots and sirens. Better to
     * say so than to make that noise.
     */
    val effectsOnly: Boolean get() = melodicPresets == 0

    fun summary(): String =
        if (effectsOnly)
            "$name: no melodic instruments (${presets.size} presets, effects only)"
        else
            "$name: $melodicPresets instruments, $drumKits drum kits"

    /**
     * Finds a preset the way a GM player has to: exact bank and program, then the
     * same program in bank 0, then the nearest program in bank 0. Bank 128 is the
     * drum bank; a file that asks for a kit we do not have gets kit 0.
     *
     * What it deliberately does NOT do any more is fall back to just any preset.
     * That is how an effects-only supplement came to answer for a whole band, and
     * a MIDI file played as sirens and gunshots with nothing in the interface
     * admitting anything was wrong. Returning null lets the caller say it.
     */
    fun select(bank: Int, program: Int, drums: Boolean): Preset? {
        if (drums) {
            presets.firstOrNull { it.bank == 128 && it.program == program }?.let { return it }
            presets.firstOrNull { it.bank == 128 && it.program == 0 }?.let { return it }
            return presets.firstOrNull { it.bank == 128 }
        }
        presets.firstOrNull { it.bank == bank && it.program == program }?.let { return it }
        presets.firstOrNull { it.bank == 0 && it.program == program }?.let { return it }
        // the nearest instrument in the General MIDI bank: within a family the
        // programs are neighbours, so this lands somewhere reasonable
        return presets.filter { it.bank == 0 }.minByOrNull { Math.abs(it.program - program) }
    }

    /** Every region of a preset that answers to this key and velocity. */
    fun regionsFor(preset: Preset, key: Int, vel: Int): List<Region> {
        val out = ArrayList<Region>(2)
        val presetGlobal = preset.zones.firstOrNull { it.instrument < 0 }?.gens
        for (pz in preset.zones) {
            if (pz.instrument < 0) continue
            val pgen = merge(presetGlobal, pz.gens)
            if (!inRange(pgen, GEN_KEY_RANGE, key, 0, 127)) continue
            if (!inRange(pgen, GEN_VEL_RANGE, vel, 0, 127)) continue
            val zones = instZones.getOrNull(pz.instrument) ?: continue
            val instGlobal = zones.firstOrNull { it.sampleId < 0 }?.gens
            for (iz in zones) {
                if (iz.sampleId < 0) continue
                val igen = merge(instGlobal, iz.gens)
                if (!inRange(igen, GEN_KEY_RANGE, key, 0, 127)) continue
                if (!inRange(igen, GEN_VEL_RANGE, vel, 0, 127)) continue
                val sh = samples.getOrNull(iz.sampleId) ?: continue
                out.add(build(igen, pgen, sh))
            }
        }
        return out
    }

    /**
     * The samples of one region, 16-bit mono, read from the file on first use and
     * kept so a repeated note does not read the disk again.
     */
    fun samplesOf(region: Region): ShortArray {
        val key = region.sampleStart
        cache[key]?.let { if (it.size >= region.sampleEnd - region.sampleStart) return it }
        val n = (region.sampleEnd - region.sampleStart).coerceAtLeast(0)
        if (n == 0) return ShortArray(0)
        val bytes = ByteArray(n * 2)
        val pos = smplOffset + region.sampleStart.toLong() * 2
        val avail = ((smplFrames.toLong() - region.sampleStart) * 2).coerceAtLeast(0)
        val len = minOf(bytes.size.toLong(), avail).toInt()
        if (len > 0) src.read(pos, bytes, 0, len)
        val out = ShortArray(n)
        var i = 0
        var j = 0
        while (j + 1 < len) {
            out[i++] = ((bytes[j].toInt() and 0xFF) or (bytes[j + 1].toInt() shl 8)).toShort()
            j += 2
        }
        cache[key] = out
        return out
    }

    fun clearCache() = cache.clear()

    fun close() = src.close()

    private fun build(igen: IntArray, pgen: IntArray, sh: SampleHeader): Region {
        // instrument generators are absolute, preset generators are added on top
        fun g(id: Int, default: Int): Int {
            val i = igen[id]
            val base = if (i == UNSET) default else i
            val p = pgen[id]
            return if (p == UNSET) base else base + p
        }

        val startOff = g(GEN_START_OFFSET, 0) + 32768 * g(GEN_START_COARSE, 0)
        val endOff = g(GEN_END_OFFSET, 0) + 32768 * g(GEN_END_COARSE, 0)
        val loopStartOff = g(GEN_LOOP_START_OFFSET, 0) + 32768 * g(GEN_LOOP_START_COARSE, 0)
        val loopEndOff = g(GEN_LOOP_END_OFFSET, 0) + 32768 * g(GEN_LOOP_END_COARSE, 0)

        val root = g(GEN_ROOT_KEY, -1).let { if (it in 0..127) it else sh.originalPitch }

        return Region(
            sampleStart = sh.start + startOff,
            sampleEnd = sh.end + endOff,
            loopStart = sh.loopStart + loopStartOff,
            loopEnd = sh.loopEnd + loopEndOff,
            sampleRate = if (sh.rate in 400..192000) sh.rate else 44100,
            rootKey = root,
            correctionCents = sh.correction,
            fineTuneCents = g(GEN_FINE_TUNE, 0),
            coarseTuneSemis = g(GEN_COARSE_TUNE, 0),
            scaleTuning = g(GEN_SCALE_TUNING, 100),
            loopMode = g(GEN_SAMPLE_MODES, 0) and 3,
            attenuationCb = g(GEN_ATTENUATION, 0).coerceIn(0, 1440),
            pan = g(GEN_PAN, 0).coerceIn(-500, 500),
            delaySec = tc(g(GEN_DELAY_VOL, -12000)),
            attackSec = tc(g(GEN_ATTACK_VOL, -12000)),
            holdSec = tc(g(GEN_HOLD_VOL, -12000)),
            decaySec = tc(g(GEN_DECAY_VOL, -12000)),
            sustainCb = g(GEN_SUSTAIN_VOL, 0).coerceIn(0, 1440),
            releaseSec = tc(g(GEN_RELEASE_VOL, -12000)),
            filterCents = g(GEN_FILTER_FC, 13500).coerceIn(1500, 13500),
            filterQCb = g(GEN_FILTER_Q, 0).coerceIn(0, 960),
            keyLo = igen[GEN_KEY_RANGE].let { if (it == UNSET) 0 else it and 0xFF },
            keyHi = igen[GEN_KEY_RANGE].let { if (it == UNSET) 127 else (it shr 8) and 0xFF },
            velLo = igen[GEN_VEL_RANGE].let { if (it == UNSET) 0 else it and 0xFF },
            velHi = igen[GEN_VEL_RANGE].let { if (it == UNSET) 127 else (it shr 8) and 0xFF },
            fixedKey = g(GEN_KEYNUM, -1),
            fixedVel = g(GEN_VELOCITY, -1),
            exclusiveClass = g(GEN_EXCLUSIVE, 0)
        )
    }

    private fun merge(global: IntArray?, local: IntArray): IntArray {
        if (global == null) return local
        val out = IntArray(GEN_COUNT) { UNSET }
        for (i in 0 until GEN_COUNT) out[i] = if (local[i] != UNSET) local[i] else global[i]
        return out
    }

    private fun inRange(gens: IntArray, id: Int, v: Int, lo: Int, hi: Int): Boolean {
        val r = gens[id]
        if (r == UNSET) return true
        val a = r and 0xFF
        val b = (r shr 8) and 0xFF
        return v >= maxOf(a, lo) && v <= minOf(b, hi)
    }

    companion object {
        internal const val UNSET = Int.MIN_VALUE
        internal const val GEN_COUNT = 61

        const val GEN_START_OFFSET = 0
        const val GEN_END_OFFSET = 1
        const val GEN_LOOP_START_OFFSET = 2
        const val GEN_LOOP_END_OFFSET = 3
        const val GEN_START_COARSE = 4
        const val GEN_FILTER_FC = 8
        const val GEN_FILTER_Q = 9
        const val GEN_END_COARSE = 12
        const val GEN_PAN = 17
        const val GEN_DELAY_VOL = 33
        const val GEN_ATTACK_VOL = 34
        const val GEN_HOLD_VOL = 35
        const val GEN_DECAY_VOL = 36
        const val GEN_SUSTAIN_VOL = 37
        const val GEN_RELEASE_VOL = 38
        const val GEN_INSTRUMENT = 41
        const val GEN_KEY_RANGE = 43
        const val GEN_VEL_RANGE = 44
        const val GEN_ATTENUATION = 48
        const val GEN_LOOP_START_COARSE = 45
        const val GEN_LOOP_END_COARSE = 50
        const val GEN_COARSE_TUNE = 51
        const val GEN_FINE_TUNE = 52
        const val GEN_SAMPLE_ID = 53
        const val GEN_SAMPLE_MODES = 54
        const val GEN_SCALE_TUNING = 56
        const val GEN_EXCLUSIVE = 57
        const val GEN_ROOT_KEY = 58
        const val GEN_KEYNUM = 46
        const val GEN_VELOCITY = 47

        /** timecents to seconds */
        internal fun tc(v: Int): Double =
            if (v <= -12000) 0.0 else Math.pow(2.0, v / 1200.0)

        fun open(src: ByteSource): SoundFont {
            val head = ByteArray(12)
            src.read(0, head, 0, 12)
            if (str(head, 0, 4) != "RIFF" || str(head, 8, 4) != "sfbk") {
                throw UnsupportedSoundFont("not a SoundFont: missing RIFF/sfbk header")
            }
            var pos = 12L
            val end = minOf(src.size, le32(head, 4).toLong() + 8)
            var name = "SoundFont"
            var smplOff = -1L
            var smplLen = 0L
            var pdta: ByteArray? = null

            val hdr = ByteArray(12)
            while (pos + 8 <= end) {
                src.read(pos, hdr, 0, 8)
                val id = str(hdr, 0, 4)
                val len = le32(hdr, 4).toLong() and 0xFFFFFFFFL
                if (id == "LIST") {
                    src.read(pos + 8, hdr, 0, 4)
                    when (str(hdr, 0, 4)) {
                        "INFO" -> {
                            val info = ByteArray(minOf(len, 65536L).toInt())
                            src.read(pos + 12, info, 0, info.size - 4)
                            name = findInam(info) ?: name
                        }
                        "sdta" -> {
                            var p = pos + 12
                            val stop = pos + 8 + len
                            while (p + 8 <= stop) {
                                src.read(p, hdr, 0, 8)
                                val cid = str(hdr, 0, 4)
                                val clen = le32(hdr, 4).toLong() and 0xFFFFFFFFL
                                if (cid == "smpl") {
                                    smplOff = p + 8
                                    smplLen = clen
                                }
                                p += 8 + clen + (clen and 1L)
                            }
                        }
                        "pdta" -> {
                            if (len > 64L * 1024 * 1024) throw UnsupportedSoundFont("index too large")
                            val b = ByteArray((len - 4).toInt())
                            src.read(pos + 12, b, 0, b.size)
                            pdta = b
                        }
                    }
                }
                pos += 8 + len + (len and 1L)
            }
            if (smplOff < 0) throw UnsupportedSoundFont("no sample data: sf3 files are compressed and not supported")
            val index = pdta ?: throw UnsupportedSoundFont("no pdta index in the file")
            return build(src, smplOff, (smplLen / 2).toInt(), name, index)
        }

        private fun build(src: ByteSource, smplOff: Long, frames: Int, name: String, pdta: ByteArray): SoundFont {
            val chunks = HashMap<String, Pair<Int, Int>>()
            var p = 0
            while (p + 8 <= pdta.size) {
                val id = str(pdta, p, 4)
                val len = le32(pdta, p + 4)
                chunks[id] = Pair(p + 8, len)
                p += 8 + len + (len and 1)
            }
            fun need(id: String): Pair<Int, Int> =
                chunks[id] ?: throw UnsupportedSoundFont("the index has no $id chunk")

            // sample headers
            val (shOff, shLen) = need("shdr")
            val samples = ArrayList<SampleHeader>()
            var i = shOff
            while (i + 46 <= shOff + shLen) {
                val nm = str(pdta, i, 20)
                if (nm.startsWith("EOS")) break
                samples.add(SampleHeader(
                    nm, le32(pdta, i + 20), le32(pdta, i + 24), le32(pdta, i + 28), le32(pdta, i + 32),
                    le32(pdta, i + 36), pdta[i + 40].toInt() and 0xFF, pdta[i + 41].toInt(),
                    le16(pdta, i + 44)))
                i += 46
            }

            // instruments and their zones
            val (instOff, instLen) = need("inst")
            val (ibagOff, _) = need("ibag")
            val (igenOff, igenLen) = need("igen")
            val instCount = instLen / 22
            val instBagStart = IntArray(instCount)
            val instNames = ArrayList<String>()
            for (k in 0 until instCount) {
                instNames.add(str(pdta, instOff + k * 22, 20))
                instBagStart[k] = le16(pdta, instOff + k * 22 + 20)
            }
            val instZones = ArrayList<List<Zone>>()
            for (k in 0 until instCount - 1) {              // last record is the terminal EOI
                val from = instBagStart[k]
                val to = instBagStart[k + 1]
                instZones.add(zonesOf(pdta, ibagOff, igenOff, igenLen, from, to, GEN_SAMPLE_ID))
            }

            // presets and their zones
            val (phdrOff, phdrLen) = need("phdr")
            val (pbagOff, _) = need("pbag")
            val (pgenOff, pgenLen) = need("pgen")
            val n = phdrLen / 38
            val presets = ArrayList<Preset>()
            for (k in 0 until n - 1) {
                val base = phdrOff + k * 38
                val pname = str(pdta, base, 20)
                val prog = le16(pdta, base + 20)
                val bank = le16(pdta, base + 22)
                val from = le16(pdta, base + 24)
                val to = le16(pdta, base + 24 + 38)
                val zones = zonesOf(pdta, pbagOff, pgenOff, pgenLen, from, to, GEN_INSTRUMENT)
                presets.add(Preset(bank, prog, pname, zones))
            }
            if (presets.isEmpty()) throw UnsupportedSoundFont("the file declares no presets")
            return SoundFont(src, smplOff, frames, name, presets, instZones, samples)
        }

        /** Reads one bag range into zones, resolving the terminal generator. */
        private fun zonesOf(
            b: ByteArray, bagOff: Int, genOff: Int, genLen: Int,
            fromBag: Int, toBag: Int, terminal: Int
        ): List<Zone> {
            val out = ArrayList<Zone>(toBag - fromBag)
            for (z in fromBag until toBag) {
                val gStart = le16(b, bagOff + z * 4)
                val gEnd = if (z + 1 <= toBag) le16(b, bagOff + (z + 1) * 4) else gStart
                val gens = IntArray(GEN_COUNT) { UNSET }
                var target = -1
                var g = gStart
                while (g < gEnd && genOff + g * 4 + 4 <= genOff + genLen) {
                    val op = le16(b, genOff + g * 4)
                    val amount = le16(b, genOff + g * 4 + 2)
                    if (op == terminal) {
                        target = amount
                    } else if (op in 0 until GEN_COUNT) {
                        gens[op] = signedForGenerator(op, amount)
                    }
                    g++
                }
                out.add(if (terminal == GEN_INSTRUMENT) Zone(gens, target, -1) else Zone(gens, -1, target))
            }
            return out
        }

        /** Ranges are two bytes, most others are signed 16-bit. */
        private fun signedForGenerator(op: Int, amount: Int): Int =
            when (op) {
                GEN_KEY_RANGE, GEN_VEL_RANGE -> amount
                GEN_SAMPLE_MODES, GEN_EXCLUSIVE, GEN_ROOT_KEY, GEN_KEYNUM, GEN_VELOCITY -> amount
                else -> if (amount >= 32768) amount - 65536 else amount
            }

        private fun findInam(info: ByteArray): String? {
            var p = 0
            while (p + 8 <= info.size) {
                val id = str(info, p, 4)
                val len = le32(info, p + 4)
                if (len < 0 || p + 8 + len > info.size) return null
                if (id == "INAM") return str(info, p + 8, len).trim()
                p += 8 + len + (len and 1)
            }
            return null
        }

        private fun str(b: ByteArray, off: Int, len: Int): String {
            val sb = StringBuilder()
            for (k in 0 until len) {
                if (off + k >= b.size) break
                val c = b[off + k].toInt() and 0xFF
                if (c == 0) break
                if (c >= 32) sb.append(c.toChar())
            }
            return sb.toString()
        }

        private fun le16(b: ByteArray, o: Int): Int =
            if (o + 1 < b.size) (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) else 0

        private fun le32(b: ByteArray, o: Int): Int =
            if (o + 3 < b.size)
                (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
                    ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
            else 0
    }
}
