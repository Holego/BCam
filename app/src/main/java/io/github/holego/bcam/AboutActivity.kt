package io.github.holego.bcam

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import io.github.holego.bcam.databinding.ActivityAboutBinding

/**
 * What the app is, what it does not do with your data, and how to reach the author.
 */
class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        binding.version.text = getString(R.string.about_version, BuildConfig.VERSION_NAME)

        binding.rowPrivacyPolicy.setOnClickListener { open(PRIVACY_POLICY_URL) }
        binding.rowGithub.setOnClickListener { open(GITHUB_URL) }
        binding.rowJabber.setOnClickListener { openJabber() }

        binding.back.setOnClickListener { finish() }
    }

    private fun open(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            copyToClipboard(url)
        }
    }

    /**
     * Tries a real XMPP client first; if the user has none, the address is copied instead
     * so the button still does something useful.
     */
    private fun openJabber() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("xmpp:" + JABBER_ID)))
        } catch (e: ActivityNotFoundException) {
            copyToClipboard(JABBER_ID)
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) {
            Toast.makeText(this, R.string.about_no_browser, Toast.LENGTH_SHORT).show()
            return
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
        Toast.makeText(this, R.string.about_copied, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val GITHUB_URL = "https://github.com/Holego"

        /** Also entered as the privacy policy URL in the Play Console. */
        private const val PRIVACY_POLICY_URL = "https://github.com/Holego/BCam/blob/main/PRIVACY.md"
        private const val JABBER_ID = "Jopirat@jabber.fr"
    }
}
