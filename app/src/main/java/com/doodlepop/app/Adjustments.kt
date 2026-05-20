package com.doodlepop.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint

/**
 * Brightness / contrast / saturation as a single ColorMatrix pipeline.
 *
 * Why one composite matrix: stacking three filters as separate passes would
 * triple the bitmap allocations and Canvas draws per slider tick. Composing
 * into a single ColorMatrix means the GPU does one shader pass.
 */
object Adjustments {

    /**
     * @param brightness  -100..+100  (0 = no change). Shifts each channel.
     * @param contrast    -100..+100  (0 = no change). Scales around mid-gray.
     * @param saturation  -100..+100  (0 = no change; -100 = grayscale; +100 = doubled).
     */
    fun apply(
        src: Bitmap,
        brightness: Int = 0,
        contrast: Int = 0,
        saturation: Int = 0,
    ): Bitmap {
        if (brightness == 0 && contrast == 0 && saturation == 0) return src

        val m = ColorMatrix()

        if (saturation != 0) {
            val s = 1f + saturation / 100f  // -100 → 0; 0 → 1; +100 → 2
            m.postConcat(ColorMatrix().apply { setSaturation(s.coerceAtLeast(0f)) })
        }

        if (contrast != 0) {
            val c = 1f + contrast / 100f    // 0 → 1; +100 → 2; -100 → 0
            val t = (1f - c) * 128f          // shift to keep mid-gray at mid-gray
            m.postConcat(ColorMatrix(floatArrayOf(
                c, 0f, 0f, 0f, t,
                0f, c, 0f, 0f, t,
                0f, 0f, c, 0f, t,
                0f, 0f, 0f, 1f, 0f
            )))
        }

        if (brightness != 0) {
            val b = brightness * 2.55f       // -100..+100 → -255..+255
            m.postConcat(ColorMatrix(floatArrayOf(
                1f, 0f, 0f, 0f, b,
                0f, 1f, 0f, 0f, b,
                0f, 0f, 1f, 0f, b,
                0f, 0f, 0f, 1f, 0f
            )))
        }

        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply {
            colorFilter = ColorMatrixColorFilter(m)
        })
        return out
    }

    /**
     * Blend two same-size bitmaps: result = base*(1-t) + top*t.
     *
     * Used to implement the filter-intensity slider — render the filter at
     * 100%, then blend that against the original at the slider's t value.
     */
    fun blend(base: Bitmap, top: Bitmap, t: Float): Bitmap {
        val clampedT = t.coerceIn(0f, 1f)
        if (clampedT <= 0f) return base
        if (clampedT >= 1f) return top
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val paint = Paint().apply { alpha = (clampedT * 255f).toInt() }
        canvas.drawBitmap(top, 0f, 0f, paint)
        return out
    }
}
