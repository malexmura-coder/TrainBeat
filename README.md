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

**Speed and key.** By default the device applies them as the loop plays: nothing is
prepared, so pressing play is instant, and it is good enough for the semitone or two
most practice actually uses. They are chosen with the loop stopped. For short loops a
`hi-fi` switch prepares the material instead, offline: a phase vocoder with phase
locking and a phase reset on onsets for the tempo, band-limited sinc resampling
for the pitch. It costs a couple of seconds and keeps the drums intact.

**Train mode.** The tempo climbs by a fixed step every repetition, so you can start
under tempo and let the piece take you up to it. The next repetition is rendered
while the current one plays and swapped in exactly at the seam.

**One cursor, two jobs.** The bar under the controls shows where the loop is and is
also where the next Play comes in, so stopping is pausing and there is nothing to
save or restore. It reads the same on all four roads - live, prepared, a track still
being decoded, a score - because the position is stated as a ratio between the
material actually being fed and the length of the original, which is the one form
that survives a loop having been rendered to a different length.

**MIDI.** A `.mid` file is a score, not a sound: changing its tempo is a
multiplication and transposing it is an addition, so both are exact and free of
artefacts. TrainBeat reads the file, plays it with a SoundFont of your choosing,
and reads tempo, metre and key from the file itself instead of estimating them.
The drum channel is never transposed.

**Reading a score.** A MIDI file can be shown as a staff instead of the info card, one
part at a time, with a cursor that follows the music. It is a reading aid for
practising, not an engraver: the rhythm is put on a grid of sixteenth notes, each
staff carries one line of music, and a piano part gets the grand staff. The number
of bars shown follows the width of the screen, the cursor walks through them and the
staff turns the page as the last visible bar begins, and the size of the staff can be
set (it starts out larger on a tablet). Tapping the staff or tablature shows it alone on the whole screen, on black
(a tap there is play / pause; the back gesture or "< Back" leaves). A MusicXML file (`.musicxml`, `.mxl`, `.xml`)
opens the same way, with repeats and voltas unrolled (a repeated section is played, and shown, twice, each ending on its own pass; D.C., D.S. and coda are not followed); a part that the file writes for a
fretboard, with string and fret on every note, is shown as a tablature instead of a
staff. Guitar Pro 3, 4 and 5 files (`.gp3`, `.gp4`, `.gp5`) open the same way,
with the tablature the file itself holds (Guitar Pro 6 and 7 are another, packed format and are
refused with a message; export them as `.gp5` or MusicXML). A tablature is never guessed: a MIDI file does not say which string a note is
on, so it is only shown when the file does.

**Files from anywhere.** Anything that can send an audio file or a score can send
it here - a download, an attachment, a file manager - and it opens straight in the
player. Such a file is borrowed, since the permission to read it belongs to the
sender and is withdrawn sooner or later, so the app offers to keep a copy and says
plainly when there is nowhere to put one.

**A mixer for scores.** An instrument of a MIDI file can be switched off, which is
exact rather than approximate: the notes of that channel are simply not played, so
nothing is filtered and nothing is subtracted. Opening the mixer stops the loop, on
purpose - the rendering has to be made again, and a silence the user asked for is
better than one that happens to them. The mixer also holds an optional metronome, a click
on every beat that follows the tempo and the metre of the score; it starts switched off.

**Library.** Grant a few folders and the app scans them once, storing tempo and key
per file. Audio is analysed (spectral flux and an autocorrelated onset envelope for
tempo, fused chromagrams against Sha'ath profiles for key); a MIDI file is simply
read. Any key can be corrected by hand, and the correction is remembered.

**Loop trimmer.** Cuts a loop out of a longer track: on audio by snapping to
zero crossings and searching for the best matching end, on a score by cutting on
bar lines, which is exact by construction. It writes a `.wav`, or a `.mid` that
stays adjustable for ever.

## The SoundFont

The app carries a small one, Chaos Bank V1.9 (1999) by Seong-Jin Yun, so MIDI plays
on a fresh install. It is distributed by rKhive under CC0 1.0
([notice](https://rkhive.com/legal.html)); the file itself carries only the author's
name in its metadata. A `.sf2` of your own, chosen once under **Folders**, takes
precedence and is remembered; "Use built-in" goes back. For a fuller General MIDI
sound, [FluidR3 GM](https://member.keymusician.com/Member/FluidR3_GM/index.html)
is a common free choice — take the large "GM" file, not the small "GS" one, which
holds only sound effects.

## Built-in examples

A few example loops (MIDI, MP3, WAV, Guitar Pro) come with the app, in `assets/builtin/`.
They are copied once to the app's private storage, apart from the library folders,
listed in the library with the tempo and key the author gives in `assets/builtin/loops.tsv`
(nothing is measured at start; a score is only parsed), and can be removed there (and
restored under **Folders**). Change a loop, change its line, raise `BuiltIn.VERSION`.

## Building

No dependencies beyond AndroidX and Compose. No native code, no Google Play
Services, no network access of any kind.

```
./gradlew testDebugUnitTest    # unit tests on a plain JVM
./gradlew installDebug
```

`minSdk` 24, `targetSdk` 36.

## Licence

GPL-3.0-or-later. See [LICENSE](LICENSE).

The music notation font is Bravura, Copyright (c) 2015 Steinberg Media Technologies
GmbH, used unmodified under the SIL Open Font License 1.1
([app/src/main/assets/fonts/OFL-Bravura.txt](app/src/main/assets/fonts/OFL-Bravura.txt)).

The built-in SoundFont, Chaos Bank V1.9 by Seong-Jin Yun, is under CC0 1.0 as stated by
its distributor rKhive (https://rkhive.com/legal.html).

Copyright (C) 2026 Alessandro Mura
