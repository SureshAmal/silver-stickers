package com.example.silversticker.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

object SilverMotion {
    const val Short1 = 50
    const val Short2 = 100
    const val Short3 = 150
    const val Short4 = 200
    const val Medium1 = 250
    const val Medium2 = 300
    const val Medium3 = 350
    const val Medium4 = 400
    const val Long1 = 450
    const val Long2 = 500
    const val Long3 = 550
    const val Long4 = 600

    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val StandardDecelerate = CubicBezierEasing(0f, 0f, 0f, 1f)
    val StandardAccelerate = CubicBezierEasing(0.3f, 0f, 1f, 1f)

    fun <T> emphasized(durationMillis: Int = Medium4) = tween<T>(
        durationMillis = durationMillis,
        easing = Emphasized
    )

    fun <T> emphasizedEnter(durationMillis: Int = Long1) = tween<T>(
        durationMillis = durationMillis,
        easing = EmphasizedDecelerate
    )

    fun <T> emphasizedExit(durationMillis: Int = Short4) = tween<T>(
        durationMillis = durationMillis,
        easing = EmphasizedAccelerate
    )

    fun <T> standard(durationMillis: Int = Medium2) = tween<T>(
        durationMillis = durationMillis,
        easing = Standard
    )

    fun <T> standardEnter(durationMillis: Int = Medium1) = tween<T>(
        durationMillis = durationMillis,
        easing = StandardDecelerate
    )

    fun <T> standardExit(durationMillis: Int = Short3) = tween<T>(
        durationMillis = durationMillis,
        easing = StandardAccelerate
    )

    fun <T> expressiveSpring() = spring<T>(
        dampingRatio = 0.86f,
        stiffness = Spring.StiffnessMediumLow
    )

    fun <T> spatialSpring() = spring<T>(
        dampingRatio = 0.78f,
        stiffness = Spring.StiffnessMediumLow
    )

    fun <T> pressSpring() = spring<T>(
        dampingRatio = 0.7f,
        stiffness = Spring.StiffnessMedium
    )

    fun <T> quickSpring() = spring<T>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium
    )
}
