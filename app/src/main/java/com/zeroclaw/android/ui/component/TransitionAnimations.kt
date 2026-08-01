/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin

/**
 * Smooth screen fade transition - ideal for sequential screen changes.
 */
val SmoothScreenFadeTransition: Pair<EnterTransition, ExitTransition>
    get() = Pair(
        fadeIn(animationSpec = androidx.compose.animation.core.tween(400)) +
                scaleIn(
                    initialScale = 0.98f,
                    animationSpec = androidx.compose.animation.core.tween(400)
                ),
        fadeOut(animationSpec = androidx.compose.animation.core.tween(400)) +
                scaleOut(
                    targetScale = 0.95f,
                    animationSpec = androidx.compose.animation.core.tween(400)
                )
    )

/**
 * Smooth slide-in from left transition - for forward navigation.
 */
val SmoothSlideLeftTransition: Pair<EnterTransition, ExitTransition>
    get() = Pair(
        slideInHorizontally(
            initialOffsetX = { -it },
            animationSpec = androidx.compose.animation.core.tween(
                400,
                easing = androidx.compose.animation.core.FastOutSlowInEasing
            )
        ) + fadeIn(animationSpec = androidx.compose.animation.core.tween(400)),
        slideOutHorizontally(
            targetOffsetX = { -it },
            animationSpec = androidx.compose.animation.core.tween(
                400,
                easing = androidx.compose.animation.core.FastOutSlowInEasing
            )
        ) + fadeOut(animationSpec = androidx.compose.animation.core.tween(400))
    )

/**
 * Smooth slide-in from right transition - for back navigation.
 */
val SmoothSlideRightTransition: Pair<EnterTransition, ExitTransition>
    get() = Pair(
        slideInHorizontally(
            initialOffsetX = { it },
            animationSpec = androidx.compose.animation.core.tween(
                400,
                easing = androidx.compose.animation.core.FastOutSlowInEasing
            )
        ) + fadeIn(animationSpec = androidx.compose.animation.core.tween(400)),
        slideOutHorizontally(
            targetOffsetX = { it },
            animationSpec = androidx.compose.animation.core.tween(
                400,
                easing = androidx.compose.animation.core.FastOutSlowInEasing
            )
        ) + fadeOut(animationSpec = androidx.compose.animation.core.tween(400))
    )

/**
 * Smooth slide-up transition - for bottom sheets and modals.
 */
val SmoothSlideUpTransition: Pair<EnterTransition, ExitTransition>
    get() = Pair(
        slideInVertically(
            initialOffsetY = { it },
            animationSpec = androidx.compose.animation.core.tween(
                400,
                easing = androidx.compose.animation.core.FastOutSlowInEasing
            )
        ) + fadeIn(animationSpec = androidx.compose.animation.core.tween(400)),
        slideOutVertically(
            targetOffsetY = { it },
            animationSpec = androidx.compose.animation.core.tween(
                400,
                easing = androidx.compose.animation.core.FastOutSlowInEasing
            )
        ) + fadeOut(animationSpec = androidx.compose.animation.core.tween(400))
    )

/**
 * Smooth slide-down transition - for dismissing modals.
 */
val SmoothSlideDownTransition: Pair<EnterTransition, ExitTransition>
    get() = Pair(
        slideInVertically(
            initialOffsetY = { -it },
            animationSpec = androidx.compose.animation.core.tween(
                400,
                easing = androidx.compose.animation.core.FastOutSlowInEasing
            )
        ) + fadeIn(animationSpec = androidx.compose.animation.core.tween(400)),
        slideOutVertically(
            targetOffsetY = { -it },
            animationSpec = androidx.compose.animation.core.tween(
                400,
                easing = androidx.compose.animation.core.FastOutSlowInEasing
            )
        ) + fadeOut(animationSpec = androidx.compose.animation.core.tween(400))
    )

/**
 * Premium zoom transition - scales up content while fading in.
 */
val PremiumZoomTransition: Pair<EnterTransition, ExitTransition>
    get() = Pair(
        scaleIn(
            initialScale = 0.85f,
            transformOrigin = TransformOrigin.Center,
            animationSpec = androidx.compose.animation.core.tween(
                500,
                easing = androidx.compose.animation.core.FastOutSlowInEasing
            )
        ) + fadeIn(animationSpec = androidx.compose.animation.core.tween(500)),
        scaleOut(
            targetScale = 0.85f,
            transformOrigin = TransformOrigin.Center,
            animationSpec = androidx.compose.animation.core.tween(
                500,
                easing = androidx.compose.animation.core.FastOutSlowInEasing
            )
        ) + fadeOut(animationSpec = androidx.compose.animation.core.tween(500))
    )

/**
 * Animated content transition - smooth swap between content.
 */
@Composable
fun <S> SmoothAnimatedContent(
    targetState: S,
    modifier: Modifier = Modifier,
    contentKey: ((targetState: S) -> Any)? = null,
    content: @Composable (targetState: S) -> Unit,
) {
    Box(modifier = modifier.animateContentSize()) {
        content(targetState)
    }
}

/**
 * Animated visibility with default premium animations.
 */
@Composable
fun PremiumAnimatedVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    enter: EnterTransition = fadeIn(animationSpec = androidx.compose.animation.core.tween(400)) +
            scaleIn(
                initialScale = 0.95f,
                animationSpec = androidx.compose.animation.core.tween(400)
            ),
    exit: ExitTransition = fadeOut(animationSpec = androidx.compose.animation.core.tween(300)) +
            scaleOut(
                targetScale = 0.95f,
                animationSpec = androidx.compose.animation.core.tween(300)
            ),
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = enter,
        exit = exit,
        content = { content() }
    )
}

/**
 * Content size animation - smoothly animates size changes.
 */
@Composable
fun SmoothSizeTransition(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier.animateContentSize(
            animationSpec = androidx.compose.animation.core.tween(
                300,
                easing = androidx.compose.animation.core.FastOutSlowInEasing
            )
        )
    ) {
        content()
    }
}

/**
 * Crossfade transition - smooth fade between two states.
 */
@Composable
fun <T> SmoothCrossfade(
    targetState: T,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    androidx.compose.animation.Crossfade(
        targetState = targetState,
        modifier = modifier,
        animationSpec = androidx.compose.animation.core.tween(
            400,
            easing = androidx.compose.animation.core.FastOutSlowInEasing
        ),
        content = content
    )
}

/**
 * List item animation - staggered fade-in for list items.
 */
@Composable
fun StaggeredListItemAnimation(
    index: Int,
    totalItems: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val staggerDuration = 50
    PremiumAnimatedVisibility(
        visible = true,
        modifier = modifier,
        enter = fadeIn(
            animationSpec = androidx.compose.animation.core.tween(
                300,
                delayMillis = index * staggerDuration,
                easing = androidx.compose.animation.core.FastOutSlowInEasing
            )
        ) + slideInVertically(
            initialOffsetY = { 30 },
            animationSpec = androidx.compose.animation.core.tween(
                300,
                delayMillis = index * staggerDuration,
                easing = androidx.compose.animation.core.FastOutSlowInEasing
            )
        ),
        content = content
    )
}

/**
 * Page transition - smooth page fade with optional direction indicator.
 */
@Composable
fun SmoothPageTransition(
    isForward: Boolean = true,
    content: @Composable () -> Unit
) {
    PremiumAnimatedVisibility(
        visible = true,
        enter = if (isForward) SmoothScreenFadeTransition.first else SmoothSlideRightTransition.first,
        exit = if (isForward) SmoothScreenFadeTransition.second else SmoothSlideRightTransition.second,
        content = content
    )
}
