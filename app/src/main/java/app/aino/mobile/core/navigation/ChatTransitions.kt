package app.aino.mobile.core.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.navigation.NavBackStackEntry

/*
 * Signal conversation transitions (res/anim): the thread slides in from the
 * end (`slide_from_end`, 200 ms decelerate) over the list, which shrinks and
 * dims (`fade_scale_out`, 1 → 0.85 scale, 1 → 0.6 alpha, 150 ms); back
 * reverses both (`slide_to_end`, `fade_scale_in`). Slide directions follow
 * the layout direction, so RTL enters from the left.
 */
private typealias NavTransitionScope = AnimatedContentTransitionScope<NavBackStackEntry>

/** Screens pushed over the chat list with the slide-from-end transition. */
internal val CHAT_PUSHED_ROUTES = setOf(AinoDestination.ChatThread.route, CHAT_NEW_GROUP_ROUTE)

private const val SLIDE_MS = 200
private const val FADE_SCALE_MS = 150
private const val SCALE = 0.85f
private const val ALPHA = 0.6f

internal fun NavTransitionScope.signalSlideFromEnd(): EnterTransition =
    slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(SLIDE_MS, easing = LinearOutSlowInEasing))

internal fun NavTransitionScope.signalSlideToEnd(): ExitTransition =
    slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(SLIDE_MS, easing = LinearOutSlowInEasing))

internal fun signalFadeScaleOut(): ExitTransition =
    scaleOut(tween(FADE_SCALE_MS), targetScale = SCALE) + fadeOut(tween(FADE_SCALE_MS), targetAlpha = ALPHA)

internal fun signalFadeScaleIn(): EnterTransition =
    scaleIn(tween(FADE_SCALE_MS), initialScale = SCALE) + fadeIn(tween(FADE_SCALE_MS), initialAlpha = ALPHA)
