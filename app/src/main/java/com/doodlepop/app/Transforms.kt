package com.doodlepop.app

import android.graphics.Bitmap
import android.graphics.Matrix

/**
 * Lossless transforms: rotate by 90° and horizontal/vertical flips.
 *
 * Lossless because we use a Matrix on Bitmap.createBitmap — no pixel
 * resampling, just a coordinate remap.
 */
object Transforms {

    fun rotate(src: Bitmap, degrees: Float): Bitmap {
        if (degrees == 0f) return src
        val m = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    fun flipHorizontal(src: Bitmap): Bitmap = transformWithMatrix(src) { it.preScale(-1f, 1f) }

    fun flipVertical(src: Bitmap): Bitmap = transformWithMatrix(src) { it.preScale(1f, -1f) }

    private inline fun transformWithMatrix(src: Bitmap, block: (Matrix) -> Unit): Bitmap {
        val m = Matrix()
        block(m)
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }
}
