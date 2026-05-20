package com.doodlepop.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Stateless filter catalog. Each filter takes a source Bitmap and returns a new
 * one. Color-grade filters use `ColorMatrix` (fast, GPU-friendly on draw).
 *
 * Why pure-Kotlin: no OpenCV/RenderScript means tiny APK (~10 MB), zero native
 * setup, and Play Store review without "extra permissions justification."
 */
object ImageFilters {

    enum class Preset(val displayName: String) {
        ORIGINAL("Original"),
        BW("B&W"),
        SKETCH("Sketch"),
        BUBBLE("Bubble"),
        SEPIA("Sepia"),
        VINTAGE("Vintage"),
        WARM("Warm"),
        COOL("Cool"),
        VIVID("Vivid"),
        NOIR("Noir"),
        FADE("Fade"),
    }

    fun apply(src: Bitmap, preset: Preset): Bitmap = when (preset) {
        Preset.ORIGINAL -> src
        Preset.BW -> matrix(src, bwMatrix())
        Preset.SKETCH -> sketch(src)
        Preset.BUBBLE -> bubble(src)
        Preset.SEPIA -> matrix(src, sepiaMatrix())
        Preset.VINTAGE -> matrix(src, vintageMatrix())
        Preset.WARM -> matrix(src, warmMatrix())
        Preset.COOL -> matrix(src, coolMatrix())
        Preset.VIVID -> matrix(src, vividMatrix())
        Preset.NOIR -> matrix(src, noirMatrix())
        Preset.FADE -> matrix(src, fadeMatrix())
    }

    // -----------------------------------------------------------------------
    // ColorMatrix presets
    //
    // ColorMatrix layout (row-major 4×5):
    //   [ rR rG rB rA rOff,
    //     gR gG gB gA gOff,
    //     bR bG bB bA bOff,
    //     aR aG aB aA aOff ]
    // outR = rR*R + rG*G + rB*B + rA*A + rOff   (and similarly for G,B,A)
    // -----------------------------------------------------------------------

    private fun bwMatrix() = ColorMatrix().apply { setSaturation(0f) }

    private fun sepiaMatrix() = ColorMatrix(floatArrayOf(
        0.393f, 0.769f, 0.189f, 0f, 0f,
        0.349f, 0.686f, 0.168f, 0f, 0f,
        0.272f, 0.534f, 0.131f, 0f, 0f,
        0f,     0f,     0f,     1f, 0f
    ))

    private fun vintageMatrix(): ColorMatrix {
        // Faded contrast + warm shift + slight desaturation.
        val m = ColorMatrix(floatArrayOf(
            0.9f, 0.1f, 0.1f, 0f, 10f,
            0.1f, 0.85f, 0.05f, 0f, 5f,
            0.05f, 0.1f, 0.7f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        ))
        m.postConcat(ColorMatrix().apply { setSaturation(0.7f) })
        return m
    }

    private fun warmMatrix() = ColorMatrix(floatArrayOf(
        1.1f, 0f,   0f,   0f, 15f,
        0f,   1.0f, 0f,   0f, 5f,
        0f,   0f,   0.9f, 0f, 0f,
        0f,   0f,   0f,   1f, 0f
    ))

    private fun coolMatrix() = ColorMatrix(floatArrayOf(
        0.9f, 0f,   0f,   0f, 0f,
        0f,   1.0f, 0f,   0f, 5f,
        0f,   0f,   1.1f, 0f, 15f,
        0f,   0f,   0f,   1f, 0f
    ))

    private fun vividMatrix() = ColorMatrix().apply {
        setSaturation(1.5f)
        postConcat(ColorMatrix(floatArrayOf(
            1.15f, 0f, 0f, 0f, -10f,
            0f, 1.15f, 0f, 0f, -10f,
            0f, 0f, 1.15f, 0f, -10f,
            0f, 0f, 0f, 1f, 0f
        )))
    }

    private fun noirMatrix(): ColorMatrix {
        // High-contrast B&W with a moody crush in the shadows.
        val m = ColorMatrix().apply { setSaturation(0f) }
        m.postConcat(ColorMatrix(floatArrayOf(
            1.4f, 0f, 0f, 0f, -40f,
            0f, 1.4f, 0f, 0f, -40f,
            0f, 0f, 1.4f, 0f, -40f,
            0f, 0f, 0f, 1f, 0f
        )))
        return m
    }

    private fun fadeMatrix() = ColorMatrix(floatArrayOf(
        0.8f, 0f,   0f,   0f, 40f,
        0f,   0.8f, 0f,   0f, 40f,
        0f,   0f,   0.8f, 0f, 40f,
        0f,   0f,   0f,   1f, 0f
    ))

    private fun matrix(src: Bitmap, m: ColorMatrix): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(m) }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    // -----------------------------------------------------------------------
    // Multi-pass effects (not pure ColorMatrix)
    // -----------------------------------------------------------------------

    /**
     * Pencil-sketch: invert grayscale → blur → color-dodge blend with grayscale.
     */
    fun sketch(src: Bitmap): Bitmap {
        val gray = matrix(src, bwMatrix())
        val inverted = invert(gray)
        val blurred = boxBlur(inverted, radius = 8)
        return colorDodge(gray, blurred)
    }

    /**
     * Translucent circles colored from the underlying pixels — "photo through bubbles."
     */
    fun bubble(src: Bitmap, bubbleCount: Int = 80, seed: Long = 42L): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val rng = Random(seed)
        val w = out.width; val h = out.height
        val maxR = (min(w, h) / 6f).coerceAtLeast(20f)
        val minR = (min(w, h) / 30f).coerceAtLeast(8f)

        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val highlight = Paint(Paint.ANTI_ALIAS_FLAG)
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.argb(140, 255, 255, 255)
        }

        repeat(bubbleCount) {
            val cx = rng.nextFloat() * w
            val cy = rng.nextFloat() * h
            val r = minR + rng.nextFloat() * (maxR - minR)
            val sx = cx.toInt().coerceIn(0, w - 1)
            val sy = cy.toInt().coerceIn(0, h - 1)
            val px = src.getPixel(sx, sy)

            fill.color = Color.argb(
                100,
                lighten(Color.red(px)),
                lighten(Color.green(px)),
                lighten(Color.blue(px))
            )
            canvas.drawCircle(cx, cy, r, fill)
            canvas.drawCircle(cx, cy, r, outline)
            highlight.color = Color.argb(180, 255, 255, 255)
            canvas.drawCircle(cx - r * 0.35f, cy - r * 0.35f, r * 0.18f, highlight)
        }
        return out
    }

    /**
     * Radial vignette overlay: dark feathered edges, untouched center.
     * Strength is the alpha intensity of the outer ring (0..255).
     */
    fun vignette(src: Bitmap, strength: Int = 160): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val w = out.width.toFloat()
        val h = out.height.toFloat()
        val cx = w / 2f; val cy = h / 2f
        val radius = max(w, h) * 0.75f
        val s = strength.coerceIn(0, 255)
        val shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(Color.TRANSPARENT, Color.argb(s, 0, 0, 0)),
            floatArrayOf(0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader }
        canvas.drawRect(0f, 0f, w, h, paint)
        return out
    }

    private fun lighten(channel: Int): Int = min(255, channel + 60)

    private fun invert(src: Bitmap): Bitmap = matrix(src, ColorMatrix(floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f
    )))

    private fun colorDodge(base: Bitmap, blend: Bitmap): Bitmap {
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val paint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.LIGHTEN)
        }
        canvas.drawBitmap(blend, 0f, 0f, paint)
        return out
    }

    private fun boxBlur(src: Bitmap, radius: Int): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)
        val out = IntArray(w * h); val tmp = IntArray(w * h)

        for (y in 0 until h) for (x in 0 until w) {
            var r = 0; var g = 0; var b = 0; var n = 0
            val x0 = max(0, x - radius); val x1 = min(w - 1, x + radius)
            for (xx in x0..x1) {
                val c = px[y * w + xx]
                r += (c ushr 16) and 0xFF; g += (c ushr 8) and 0xFF; b += c and 0xFF; n++
            }
            tmp[y * w + x] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
        }
        for (x in 0 until w) for (y in 0 until h) {
            var r = 0; var g = 0; var b = 0; var n = 0
            val y0 = max(0, y - radius); val y1 = min(h - 1, y + radius)
            for (yy in y0..y1) {
                val c = tmp[yy * w + x]
                r += (c ushr 16) and 0xFF; g += (c ushr 8) and 0xFF; b += c and 0xFF; n++
            }
            out[y * w + x] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(out, 0, w, 0, 0, w, h)
        }
    }

    fun fit(src: Bitmap, maxEdge: Int = 1600): Bitmap {
        val longest = max(src.width, src.height)
        if (longest <= maxEdge) return src
        val scale = maxEdge.toFloat() / longest
        return Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
    }
}
