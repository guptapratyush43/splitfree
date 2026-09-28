package com.splitfree.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Vibrations played on the motor directly, so they feel the same on every
 * phone regardless of the system "touch feedback" setting.
 */
object Haptics {
    /** Light tick: pills, chips, cards, switches, tabs. */
    fun tick(context: Context) = play(
        context,
        if (Build.VERSION.SDK_INT >= 29) VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
        else VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE)
    )

    /** Firm click: main buttons. */
    fun press(context: Context) = play(
        context,
        if (Build.VERSION.SDK_INT >= 29) VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
        else VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE)
    )

    /** Strong double thump: an expense or payment was saved. */
    fun success(context: Context) = play(
        context, VibrationEffect.createWaveform(longArrayOf(0, 45, 70, 60), intArrayOf(0, 255, 0, 255), -1)
    )

    /** Sharp buzz before something is deleted or removed. */
    fun warn(context: Context) = play(
        context,
        if (Build.VERSION.SDK_INT >= 29) VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
        else VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE)
    )

    private fun play(context: Context, effect: VibrationEffect) {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java).defaultVibrator
            else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
            if (vibrator.hasVibrator()) vibrator.vibrate(effect)
        }
    }
}
