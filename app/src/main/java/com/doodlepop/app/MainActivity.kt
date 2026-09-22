package com.doodlepop.app

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.doodlepop.app.databinding.ActivityMainBinding
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import com.google.android.material.chip.Chip
import com.google.android.material.slider.Slider
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var interstitial: InterstitialAdManager
    private lateinit var consent: ConsentManager
    private var bannerAdView: AdView? = null
    private var adsInitialized = false

    /** The picked photo, after transforms. Filter pipeline runs against this. */
    private var baseBitmap: Bitmap? = null

    /** The rendered preview (base → filter → adjust → vignette). */
    private var renderedBitmap: Bitmap? = null

    private var state = EditState()
    private var renderJob: Job? = null

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) loadBitmap(uri) else toast(getString(R.string.msg_no_image))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupTabs()
        setupFilterChips()
        setupSliders()
        setupTransformButtons()
        setupPreviewCompare()

        // AdMob: gather consent before SDK initialize, then load ads if allowed.
        consent = ConsentManager(this)
        consent.gatherConsent { canRequestAds -> if (canRequestAds) initializeAds() }
        if (consent.canRequestAds) initializeAds()

        setBusy(false)
    }

    // -----------------------------------------------------------------------
    // Wiring
    // -----------------------------------------------------------------------

    private fun setupToolbar() {
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_pick -> { launchPicker(); true }
                R.id.menu_save -> { saveWithAd(); true }
                R.id.menu_share -> { shareCurrent(); true }
                R.id.menu_reset -> { resetAllEdits(); true }
                R.id.menu_about -> {
                    startActivity(Intent(this, AboutActivity::class.java)); true
                }
                else -> false
            }
        }
    }

    private fun setupTabs() {
        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showPanel(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        showPanel(0)
    }

    private fun showPanel(index: Int) {
        binding.filtersPanel.visibility = if (index == 0) View.VISIBLE else View.GONE
        binding.adjustPanel.visibility = if (index == 1) View.VISIBLE else View.GONE
        binding.transformPanel.visibility = if (index == 2) View.VISIBLE else View.GONE
    }

    private fun setupFilterChips() {
        for (preset in ImageFilters.Preset.values()) {
            val chip = Chip(this).apply {
                text = preset.displayName
                isCheckable = true
                isClickable = true
                isChecked = preset == ImageFilters.Preset.ORIGINAL
                tag = preset
                setOnClickListener {
                    state = state.copy(preset = preset)
                    requestRender()
                }
            }
            binding.filterChips.addView(chip)
        }
    }

    private fun setupSliders() {
        bindSlider(
            include = binding.sliderIntensity.root,
            label = getString(R.string.slider_intensity),
            min = 0f, max = 100f, value = state.intensity.toFloat(),
            onChange = { state = state.copy(intensity = it); requestRender() }
        )
        bindSlider(
            include = binding.sliderBrightness.root,
            label = getString(R.string.slider_brightness),
            min = -100f, max = 100f, value = state.brightness.toFloat(),
            onChange = { state = state.copy(brightness = it); requestRender() }
        )
        bindSlider(
            include = binding.sliderContrast.root,
            label = getString(R.string.slider_contrast),
            min = -100f, max = 100f, value = state.contrast.toFloat(),
            onChange = { state = state.copy(contrast = it); requestRender() }
        )
        bindSlider(
            include = binding.sliderSaturation.root,
            label = getString(R.string.slider_saturation),
            min = -100f, max = 100f, value = state.saturation.toFloat(),
            onChange = { state = state.copy(saturation = it); requestRender() }
        )
        bindSlider(
            include = binding.sliderVignette.root,
            label = getString(R.string.slider_vignette),
            min = 0f, max = 100f, value = state.vignette.toFloat(),
            onChange = { state = state.copy(vignette = it); requestRender() }
        )
    }

    private fun bindSlider(
        include: View,
        label: String,
        min: Float,
        max: Float,
        value: Float,
        onChange: (Int) -> Unit,
    ) {
        val labelView = include.findViewById<TextView>(R.id.sliderLabel)
        val valueView = include.findViewById<TextView>(R.id.sliderValue)
        val slider = include.findViewById<Slider>(R.id.slider)
        labelView.text = label
        slider.valueFrom = min
        slider.valueTo = max
        slider.value = value.coerceIn(min, max)
        valueView.text = slider.value.toInt().toString()
        slider.addOnChangeListener { _, v, fromUser ->
            valueView.text = v.toInt().toString()
            if (fromUser) onChange(v.toInt())
        }
    }

    private fun setupTransformButtons() {
        binding.btnRotateLeft.setOnClickListener { applyTransform { Transforms.rotate(it, -90f) } }
        binding.btnRotateRight.setOnClickListener { applyTransform { Transforms.rotate(it, 90f) } }
        binding.btnFlipH.setOnClickListener { applyTransform(Transforms::flipHorizontal) }
        binding.btnFlipV.setOnClickListener { applyTransform(Transforms::flipVertical) }
    }

    private fun setupPreviewCompare() {
        // Long-press: peek the original (untouched base) so the user can confirm the edit was worth it.
        binding.imagePreview.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.postDelayed(showOriginal, 300)
                    false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(showOriginal)
                    if (binding.compareLabel.visibility == View.VISIBLE) {
                        renderedBitmap?.let { binding.imagePreview.setImageBitmap(it) }
                        binding.compareLabel.visibility = View.GONE
                    }
                    v.performClick()
                    true
                }
                else -> false
            }
        }
    }

    private val showOriginal = Runnable {
        baseBitmap?.let {
            binding.imagePreview.setImageBitmap(it)
            binding.compareLabel.visibility = View.VISIBLE
        }
    }

    // -----------------------------------------------------------------------
    // Actions
    // -----------------------------------------------------------------------

    private fun launchPicker() {
        pickImage.launch(PickVisualMediaRequest(
            ActivityResultContracts.PickVisualMedia.ImageOnly
        ))
    }

    private fun loadBitmap(uri: Uri) {
        lifecycleScope.launch {
            setBusy(true)
            val bmp = withContext(Dispatchers.IO) {
                contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it)?.let { d -> ImageFilters.fit(d) }
                }
            }
            if (bmp == null) {
                toast(getString(R.string.msg_load_failed)); setBusy(false); return@launch
            }
            baseBitmap = bmp
            state = EditState()
            resetUiToState()
            requestRender()
        }
    }

    private fun applyTransform(op: (Bitmap) -> Bitmap) {
        val src = baseBitmap ?: run { toast(getString(R.string.msg_pick_first)); return }
        lifecycleScope.launch {
            setBusy(true)
            val next = withContext(Dispatchers.Default) { op(src) }
            baseBitmap = next
            requestRender()
        }
    }

    private fun saveWithAd() {
        if (renderedBitmap == null) { toast(getString(R.string.msg_pick_first)); return }
        if (::interstitial.isInitialized) {
            interstitial.showIfReady(this) { saveCurrent() }
        } else saveCurrent()
    }

    private fun saveCurrent() {
        val bmp = renderedBitmap ?: return
        lifecycleScope.launch {
            setBusy(true)
            val uri = withContext(Dispatchers.IO) { writeToGallery(bmp) }
            setBusy(false)
            if (uri != null) {
                toast(getString(R.string.msg_saved))
                offerShare(uri)
            } else {
                toast(getString(R.string.msg_save_failed))
            }
        }
    }

    private fun shareCurrent() {
        // If nothing is rendered yet but a base exists, share that; otherwise nag.
        val bmp = renderedBitmap ?: baseBitmap ?: run {
            toast(getString(R.string.msg_pick_first)); return
        }
        lifecycleScope.launch {
            setBusy(true)
            val uri = withContext(Dispatchers.IO) { writeToGallery(bmp) }
            setBusy(false)
            if (uri != null) offerShare(uri) else toast(getString(R.string.msg_save_failed))
        }
    }

    private fun offerShare(uri: Uri) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.msg_share_chooser)))
    }

    private fun resetAllEdits() {
        if (baseBitmap == null) { toast(getString(R.string.msg_pick_first)); return }
        state = EditState()
        resetUiToState()
        requestRender()
    }

    /**
     * Re-sync chip selection + slider positions when state is reset programmatically
     * (e.g. picking a new image or hitting Reset).
     */
    private fun resetUiToState() {
        for (i in 0 until binding.filterChips.childCount) {
            val chip = binding.filterChips.getChildAt(i) as? Chip ?: continue
            chip.isChecked = chip.tag == state.preset
        }
        setSliderValue(binding.sliderIntensity.root, state.intensity)
        setSliderValue(binding.sliderBrightness.root, state.brightness)
        setSliderValue(binding.sliderContrast.root, state.contrast)
        setSliderValue(binding.sliderSaturation.root, state.saturation)
        setSliderValue(binding.sliderVignette.root, state.vignette)
    }

    private fun setSliderValue(include: View, value: Int) {
        val slider = include.findViewById<Slider>(R.id.slider)
        val valueView = include.findViewById<TextView>(R.id.sliderValue)
        slider.value = value.toFloat().coerceIn(slider.valueFrom, slider.valueTo)
        valueView.text = value.toString()
    }

    // -----------------------------------------------------------------------
    // Render pipeline
    // -----------------------------------------------------------------------

    /**
     * Debounced re-render: slider drags fire dozens of events per second; we
     * coalesce them so we only render the latest state, not every interim value.
     */
    private fun requestRender() {
        val base = baseBitmap ?: return
        renderJob?.cancel()
        renderJob = lifecycleScope.launch {
            delay(30)  // coalesce burst events from slider drags
            setBusy(true)
            val snapshot = state
            val result = withContext(Dispatchers.Default) {
                EditPipeline.render(base, snapshot)
            }
            // Only commit if state hasn't moved on under us.
            if (snapshot == state) {
                renderedBitmap = result
                binding.imagePreview.setImageBitmap(result)
            }
            setBusy(false)
        }
    }

    // -----------------------------------------------------------------------
    // I/O
    // -----------------------------------------------------------------------

    private fun writeToGallery(bmp: Bitmap): Uri? {
        val name = "doodlepop_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Doodlepop")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = contentResolver
        val collection =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        val uri = resolver.insert(collection, values) ?: return null
        return runCatching {
            resolver.openOutputStream(uri)?.use { os: OutputStream ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 92, os)
            } ?: return@runCatching null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            uri
        }.getOrNull()
    }

    // -----------------------------------------------------------------------
    // Ad lifecycle + busy UI
    // -----------------------------------------------------------------------

    @Synchronized
    private fun initializeAds() {
        if (adsInitialized) return
        adsInitialized = true
        MobileAds.initialize(this) {}
        // Build the AdView in code so adSize + adUnitId both come from the
        // same source. Mixing XML adSize with code-set adUnitId made the SDK
        // throw at loadAd; this is Google's documented programmatic pattern.
        val ad = AdView(this).apply {
            setAdSize(AdSize.BANNER)
            adUnitId = BuildConfig.ADMOB_BANNER_UNIT_ID
        }
        bannerAdView = ad
        binding.adContainer.removeAllViews()
        binding.adContainer.addView(ad)
        ad.loadAd(AdRequest.Builder().build())

        interstitial = InterstitialAdManager(this, BuildConfig.ADMOB_INTERSTITIAL_UNIT_ID)
        interstitial.preload()
    }

    private fun setBusy(busy: Boolean) {
        binding.progress.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onPause() {
        bannerAdView?.pause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        bannerAdView?.resume()
    }

    override fun onDestroy() {
        bannerAdView?.destroy()
        bannerAdView = null
        super.onDestroy()
    }
}
