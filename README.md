# TrainBeat

An Android practice player. It opens an audio file or a MIDI score, loops it
without a gap, and lets you slow it down, transpose it, or ramp the tempo up one
repetition at a time.

It exists because the loop players on the store either could not open plain WAV
files, or inserted a hole at the seam — which is fatal when what you are looping
is one bar you intend to play over for twenty minutes.

## What it does

**Gapless looping.** A single `AudioTrack` is fed by a wrap-around feeder: at the
end of the material the next sample written is the first one again, inside the
same write cycle. The audio stream never stops, so the seam is sample-exact. This
is proved by unit tests, not by ear.

**Speed and key.** By default the device applies them while playing — instant, and
good enough for the semitone or two most practice actually uses. For short loops a
`hi-fi` switch prepares the material instead, offline: a phase vocoder with phase
locking and a phase reset on onsets for the tempo, band-limited sinc resampling
for the pitch. It costs a couple of seconds and keeps the drums intact.

**Train mode.** The tempo climbs by a fixed step every repetition, so you can start
under tempo and let the piece take you up to it. The next repetition is rendered
while the current one plays and swapped in exactly at the seam.

**MIDI.** A `.mid` file is a score, not a sound: changing its tempo is a
multiplication and transposing it is an addition, so both are exact and free of
artefacts. TrainBeat reads the file, plays it with a SoundFont of your choosing,
and reads tempo, metre and key from the file itself instead of estimating them.
The drum channel is never transposed.

**Library.** Grant a few folders and the app scans them once, storing tempo and key
per file. Audio is analysed (spectral flux and an autocorrelated onset envelope for
tempo, fused chromagrams against Sha'ath profiles for key); a MIDI file is simply
read. Any key can be corrected by hand, and the correction is remembered.

**Loop trimmer.** Cuts a loop out of a longer track: on audio by snapping to
zero crossings and searching for the best matching end, on a score by cutting on
bar lines, which is exact by construction. It writes a `.wav`, or a `.mid` that
stays adjustable for ever.

## The SoundFont

The app ships none, on purpose. A General MIDI bank is 30 to 150 MB and belongs to
whoever made it. To play MIDI, choose a `.sf2` file once under **Folders** and it is
remembered. [FluidR3 GM](https://member.keymusician.com/Member/FluidR3_GM/index.html)
is a common free choice — take the large "GM" file, not the small "GS" one, which
holds only sound effects.

## Building

No dependencies beyond AndroidX and Compose. No native code, no Google Play
Services, no network access of any kind.

```
./gradlew testDebugUnitTest    # 125 unit tests on a plain JVM
./gradlew installDebug
```

`minSdk` 24, `targetSdk` 36.

## Licence

GPL-3.0-or-later. See [LICENSE](LICENSE).

Copyright (C) 2026 Alessandro Mura
