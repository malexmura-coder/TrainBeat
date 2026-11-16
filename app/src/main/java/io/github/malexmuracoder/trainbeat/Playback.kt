package io.github.malexmuracoder.trainbeat

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.view.KeyEvent

/**
 * One place that outlives every screen: the player, the media session, and who is
 * currently in charge of the buttons.
 *
 * Why a singleton and not a field of the Activity. The Activity dies on rotation,
 * on a configuration change, and whenever the system feels like it, while a loop is
 * supposed to keep playing through all of that. And the media session has to exist
 * before the screen is opened and after it is closed, or the speaker's play button
 * has nowhere to arrive.
 *
 * THE HANDLER is what makes one physical button do the right thing. AVRCP - what a
 * Bluetooth speaker or a pedal speaks - has no "record" command; there is only
 * play/pause. So the meaning is taken from context instead: the screen that is open
 * registers what play/pause should do, and the recorder registers something
 * different from the player. One button, no modes to remember.
 *
 * Android delivers media keys to the most recently ACTIVE session, which is why the
 * session is activated when a screen takes charge rather than being left on: an app
 * that keeps a session active while doing nothing steals the button from whatever
 * the user is actually listening to.
 *
 * WHAT OWNS THE SOUND is this object, and not the handler. The distinction is not
 * academic: a handler is registered by a screen and released when that screen goes,
 * while the loop is meant to outlive every screen. Stopping used to be routed
 * through the handler like everything else, so a loop that was still playing with no
 * screen alive could not be stopped at all - not from the notification, not from a
 * speaker, not from anywhere. Silencing therefore lives here and works with no
 * handler at all; the handler is asked as well, when there is one, only so that a
 * screen which IS open follows what happened.
 */
object Playback {

    /** The one player in the process. */
    val player = LoopPlayer()

    /**
     * What the buttons mean right now. Registered by whichever screen is in charge,
     * cleared when it leaves.
     */
    interface Handler {
        /** The one key every speaker and pedal sends. */
        fun onPlayPause()

        /** Explicit stop, from the notification or from a speaker that sends it. */
        fun onStop()

        /** Next or previous favourite. Returns false if there is nowhere to go. */
        fun onSkip(forward: Boolean): Boolean

        /** Is something actually happening - for the notification and the state. */
        val busy: Boolean

        /** What to show in the notification. */
        val title: String

        /**
         * May this survive the screen going off?
         *
         * True only for playback. The recorder deliberately says no: keeping a
         * foreground service of type mediaPlayback alive while the microphone is
         * open would be both a lie about what the service is doing and a way to
         * leave a recording running in a pocket.
         */
        val backgroundable: Boolean get() = false
    }

    @Volatile private var handler: Handler? = null
    @Volatile private var session: MediaSession? = null
    @Volatile private var focus: Any? = null            // AudioFocusRequest, API 26+
    @Volatile private var appContext: Context? = null

    val current: Handler? get() = handler

    /**
     * Has the notification permission been asked for in this process already.
     *
     * Android 13 and later asks nothing by itself: without the permission the
     * playback notification is simply not shown. The player asks once, when a loop
     * starts, and not again until the process is rebuilt - a question repeated on
     * every Play is a nag, and the system itself stops showing the dialog after
     * two refusals.
     */
    @Volatile var notificationAsked: Boolean = false

    /**
     * The track the loop is on, kept here rather than in the screen that started it.
     *
     * A screen can be destroyed by the system while the loop plays on, and the next
     * one is built from nothing and would otherwise open on the home page with music
     * coming out of the phone and no obvious relation between the two. This is the
     * thread back: a name and a document, and where the loop had got to.
     *
     * A NAME AND A URI, deliberately, and not the clip or the screen. Those are
     * megabytes and this object lives as long as the process.
     */
    class Now(val uri: String, val name: String) {
        @Volatile var positionMs: Long = 0L
    }

    @Volatile var nowPlaying: Now? = null
        private set

    val title: String get() = nowPlaying?.name ?: handler?.title ?: "TrainBeat"

    /**
     * Is this process making a sound, whoever asked for it.
     *
     * The player is asked FIRST and the handler second. That order is the fix: the
     * player is the thing that is actually making the noise, and asking only the
     * handler meant that a loop with no screen behind it counted as silence - which
     * is how the notification used to disappear while the music carried on.
     */
    val audible: Boolean get() = player.isPlaying || handler?.busy == true

    /** A loop has started on this track. */
    fun began(ctx: Context, uri: String, name: String) {
        nowPlaying = Now(uri, name)
        changed(ctx)
    }

    /** Where it has got to, so a rebuilt screen can come back to the same place. */
    fun moved(millis: Long) {
        nowPlaying?.positionMs = millis
    }

    /** It has stopped, by a pause or by leaving. */
    fun ended(ctx: Context) {
        nowPlaying = null
        changed(ctx)
    }

    /**
     * Silence, from anywhere, with or without a screen alive.
     *
     * This is what the Stop on the notification presses, and it must work when
     * there is nothing else left: the notification is the last control the user has
     * once the app is out of sight, and a Stop that quietly does nothing is worse
     * than no Stop at all.
     */
    fun silence(ctx: Context) {
        handler?.onStop()               // a screen that is open follows along
        player.stop()
        nowPlaying = null
        abandonFocus(ctx)
        publishState()
        PlaybackService.stop(ctx)
    }

    /**
     * A screen takes charge of the buttons. Idempotent, and the last caller wins:
     * moving from the player to the recorder hands the button over cleanly.
     */
    fun takeCharge(ctx: Context, h: Handler) {
        appContext = ctx.applicationContext
        handler = h
        ensureSession(ctx.applicationContext)
        session?.isActive = true
        publishState()
    }

    /**
     * A screen gives the buttons up. Only if it still holds them.
     *
     * Clearing the reference matters beyond tidiness: a Handler captures the screen
     * it came from, and this object lives as long as the process. Holding the last
     * screen's handler kept the last track's audio reachable, and the next one then
     * had no room to decode into.
     */
    fun release(h: Handler) {
        if (handler === h) {
            handler = null
            publishState()
        }
    }

    /**
     * Called whenever something starts or stops, so the state and notification follow.
     *
     * With a screen open the screen decides, because only it knows whether what is
     * happening may survive the phone going dark - the recorder says no on purpose.
     * With no screen open the only thing that can be happening is a loop, and if
     * there is one it keeps its notification: sound without a notification is
     * exactly the state this app must never be in.
     */
    fun changed(ctx: Context) {
        publishState()
        val h = handler
        val keep = if (h != null) h.busy && h.backgroundable else player.isPlaying
        if (keep) PlaybackService.start(ctx) else PlaybackService.stop(ctx)
    }

    // ---------------------------------------------------------------- session

    /**
     * The token the notification hangs its media controls on.
     *
     * A notification that carries it is what the system recognises as THE media
     * notification of this app: it is the one shown on the lock screen and in the
     * quick settings player, and on Android 13 and later it is exempt from the
     * notification permission. A plain notification with a Stop button is none of
     * those. The session is created here if no screen has done it yet, because the
     * service can be started with no screen alive.
     */
    fun sessionToken(ctx: Context): MediaSession.Token? {
        ensureSession(ctx.applicationContext)
        return session?.sessionToken
    }

    private fun ensureSession(ctx: Context) {
        if (session != null) return
        val s = MediaSession(ctx, "TrainBeat")
        s.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { handler?.onPlayPause() }
            override fun onPause() { handler?.onPlayPause() }
            override fun onStop() { handler?.onStop() }
            override fun onSkipToNext() { handler?.onSkip(true) }
            override fun onSkipToPrevious() { handler?.onSkip(false) }

            /**
             * Some devices send a raw key rather than a named action, and some send
             * PLAY and PAUSE as separate keys for what is one button on the speaker.
             * All of them mean the same thing here.
             */
            override fun onMediaButtonEvent(intent: Intent): Boolean {
                val ev: KeyEvent? =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    else @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT) as? KeyEvent
                if (ev == null || ev.action != KeyEvent.ACTION_DOWN) {
                    return super.onMediaButtonEvent(intent)
                }
                return when (ev.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY,
                    KeyEvent.KEYCODE_MEDIA_PAUSE,
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    KeyEvent.KEYCODE_HEADSETHOOK -> { handler?.onPlayPause(); true }
                    KeyEvent.KEYCODE_MEDIA_STOP -> { handler?.onStop(); true }
                    KeyEvent.KEYCODE_MEDIA_NEXT -> handler?.onSkip(true) ?: false
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> handler?.onSkip(false) ?: false
                    else -> super.onMediaButtonEvent(intent)
                }
            }
        })
        try {
            s.setSessionActivity(
                PendingIntent.getActivity(
                    ctx, 1, openApp(ctx),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        } catch (_: Exception) {
        }
        session = s
    }

    /**
     * The intent that comes BACK to the app rather than opening a second copy of it.
     *
     * This is the whole of a bug that looked like several. A plain intent at an
     * activity whose launch mode is the default starts a NEW instance on top of the
     * old one: a fresh screen on the home page, while the first screen - still
     * alive underneath, still holding the loop - carries on playing. From the new
     * screen there is no way to reach that loop, so the music could not be stopped
     * except by starting another one, which stops the first as a side effect. The
     * flags, and the single launch mode in the manifest, mean there is only ever one
     * screen and coming back finds it as it was left.
     */
    fun openApp(ctx: Context): Intent =
        Intent(ctx, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        )

    private fun publishState() {
        val s = session ?: return
        val busy = audible
        val actions = PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_STOP or
            PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS
        try {
            s.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(actions)
                    .setState(
                        if (busy) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                        1.0f
                    )
                    .build()
            )
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------ audio focus

    /**
     * Asks for the right to make noise, and gives it back afterwards.
     *
     * Without this the loop and a notification sound talk over each other, and on
     * some devices the loop is simply killed by whatever plays next with no way
     * back. Ducking is left to the system: a short quiet moment is far better than
     * a practice loop that stops because a message arrived.
     */
    fun requestFocus(ctx: Context, onLost: () -> Unit): Boolean {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return true
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> onLost()
                else -> {}      // DUCK: let the system lower us, do not stop
            }
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener(listener)
                    .build()
                focus = req
                am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                focus = listener
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN
                ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        } catch (_: Exception) {
            true                // never refuse to play because focus could not be asked
        }
    }

    fun abandonFocus(ctx: Context) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val f = focus ?: return
        focus = null
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && f is AudioFocusRequest) {
                am.abandonAudioFocusRequest(f)
            } else if (f is AudioManager.OnAudioFocusChangeListener) {
                @Suppress("DEPRECATION") am.abandonAudioFocus(f)
            }
        } catch (_: Exception) {
        }
    }
}
