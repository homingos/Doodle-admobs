package com.doodlepop.app

import android.graphics.Bitmap

/**
 * Immutable edit state: every UI change produces a new copy. The pipeline
 * always re-derives the visible bitmap from the [transformedBase] so quality
 * doesn't degrade across consecutive edits (vs. compounding lossy operations).
 *
 * Render order:  base → filter at intensity → adjustments → vignette
 */
data class EditState(
    val preset: ImageFilters.Preset = ImageFilters.Preset.ORIGINAL,
    val intensity: Int = 100,        // 0..100
    val brightness: Int = 0,         // -100..+100
    val contrast: Int = 0,           // -100..+100
    val saturation: Int = 0,         // -100..+100
    val vignette: Int = 0,           // 0..100; 0 = off
    // Transforms live outside the live state because they mutate the base
    // bitmap itself (rotate/flip change dimensions and aren't part of the
    // re-renderable pipeline).
) {
    fun resetAdjustments() = copy(
        intensity = 100,
        brightness = 0,
        contrast = 0,
        saturation = 0,
        vignette = 0,
    )
}

object EditPipeline {

    /**
     * Render [base] with the supplied [state]. Returns a brand-new bitmap;
     * the caller owns it and is responsible for recycling old results.
     */
    fun render(base: Bitmap, state: EditState): Bitmap {
        // Step 1: apply filter at requested intensity (blend if < 100%).
        val filtered = if (state.preset == ImageFilters.Preset.ORIGINAL) {
            base
        } else {
            val full = ImageFilters.apply(base, state.preset)
            if (state.intensity >= 100) full
            else Adjustments.blend(base, full, state.intensity / 100f)
        }

        // Step 2: brightness / contrast / saturation as one matrix pass.
        val adjusted = Adjustments.apply(
            filtered,
            brightness = state.brightness,
            contrast = state.contrast,
            saturation = state.saturation,
        )

        // Step 3: vignette on top (radial shadow).
        return if (state.vignette > 0) {
            ImageFilters.vignette(adjusted, strength = (state.vignette * 2.55f).toInt())
        } else adjusted
    }
}
