package io.github.malexmuracoder.trainbeat

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * The staff or the tablature alone, on the whole screen, on black.
 *
 * Nothing else is shown: no title, no buttons, no steppers, and the system bars are
 * hidden. A tap anywhere is play or pause ([onTap]); leaving is the back gesture or
 * the "< Back" at the top left, which does the same.
 *
 * Upright, the picture is centred and the screen is black above and below it. On
 * its side the picture is as tall as the screen allows, so it fills it. In both cases
 * it starts at [StaffScale.FULLSCREEN_ZOOM] times the size the panel has, and is made
 * smaller only if it would not fit the height (see [StaffScale.fitted]).
 *
 * The ink is white and the cursor red whatever the theme, because the ground is black.
 */
@Composable
fun StaffFullScreen(
    score: Score,
    semitones: Int,
    positionMs: Long,
    channel: Int,
    onTap: () -> Unit,
    onClose: () -> Unit
) {
    BackHandler(onBack = onClose)

    // the size the panel has: the one the user set, or the one the screen suggests
    val ctx = LocalContext.current
    val panelScale = StaffPrefs.get(ctx)
        ?: StaffScale.autoFor(LocalConfiguration.current.smallestScreenWidthDp.toFloat())

    // the system bars go while this is shown and come back when it is not
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTap
            )
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().safeDrawingPadding(),
            contentAlignment = Alignment.Center
        ) {
            // the "< Back" line is above the picture in the band that is black anyway;
            // leaving the same room below keeps the picture in the middle
            StaffDisplay(
                score = score,
                semitones = semitones,
                positionMs = positionMs,
                channel = channel,
                scale = panelScale * StaffScale.FULLSCREEN_ZOOM,
                ink = Color.White,
                fitHeight = maxHeight - BAND * 2
            )
            TextButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.TopStart).height(BAND),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
            ) { Text("< Back", fontSize = 18.sp, color = Color.White) }
        }
    }
}

private val BAND = 40.dp

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
