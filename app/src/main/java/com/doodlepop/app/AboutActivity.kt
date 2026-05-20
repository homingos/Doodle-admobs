package com.doodlepop.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.doodlepop.app.databinding.ActivityAboutBinding
import com.google.android.ump.UserMessagingPlatform

/**
 * Surfaces the privacy policy link and a "reset ad consent" action that AdMob
 * policy requires apps to expose (so users can change their mind after their
 * first consent decision).
 */
class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnPrivacy.setOnClickListener {
            openUrl(getString(R.string.privacy_policy_url))
        }

        binding.btnResetConsent.setOnClickListener {
            UserMessagingPlatform.getConsentInformation(this).reset()
            recreate()
        }

        binding.versionText.text = getString(R.string.version_label, BuildConfig.VERSION_NAME)
    }

    private fun openUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }
}
