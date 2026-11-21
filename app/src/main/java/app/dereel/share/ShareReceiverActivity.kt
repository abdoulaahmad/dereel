package app.dereel.share

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import app.dereel.R
import app.dereel.browser.BrowserActivity
import app.dereel.container
import app.dereel.core.web.SharedLinkParser
import app.dereel.ui.common.platformNameRes
import kotlinx.coroutines.launch

/**
 * Receives links shared to Dereel (INT-01). The scheme, host and path are validated before a
 * platform is selected. The link then opens in Dereel's browser, where a shared reel can use the
 * single shared-video exception (FR-20) and every other route follows normal policy.
 */
class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = if (intent?.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT) else null
        val link = SharedLinkParser.parse(text)
        if (link == null) {
            Toast.makeText(this, R.string.share_unsupported, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        lifecycleScope.launch {
            container.awaitReady()
            val config = container.settings.configs.value[link.platform]
            if (config?.selected != true) {
                val name = getString(platformNameRes(link.platform))
                Toast.makeText(this@ShareReceiverActivity, getString(R.string.share_platform_not_added, name), Toast.LENGTH_LONG).show()
            } else {
                startActivity(BrowserActivity.intent(this@ShareReceiverActivity, link.platform, link.url, viaSharedLink = true))
            }
            finish()
        }
    }
}
