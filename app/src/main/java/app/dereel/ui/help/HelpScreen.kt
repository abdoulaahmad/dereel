package app.dereel.ui.help

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.dereel.R
import app.dereel.browser.BrowserScripts
import app.dereel.core.model.OpeningMethod
import app.dereel.core.model.Platform
import app.dereel.core.nativeapp.NativeRules
import app.dereel.ui.common.BodyText
import app.dereel.ui.common.SectionHeader
import app.dereel.ui.common.methodLabel
import app.dereel.ui.common.platformName

/**
 * User-accessible compatibility and known gaps (NFR-11, FR-38). Nothing is shown as verified
 * until a compatibility record exists for it.
 */
private data class CompatibilityEntry(
    val platform: Platform,
    val method: OpeningMethod,
    val ruleVersion: String,
    val verifiedOn: String?,
    /** String resource IDs. */
    val gaps: List<Int>,
)

private val entries = listOf(
    CompatibilityEntry(
        Platform.INSTAGRAM, OpeningMethod.NATIVE_APP, NativeRules.instagram.version, null,
        listOf(R.string.gap_ig_native_scope, R.string.gap_ig_native_shared, R.string.gap_native_recs, R.string.gap_native_layout),
    ),
    CompatibilityEntry(
        Platform.YOUTUBE, OpeningMethod.NATIVE_APP, NativeRules.youtube.version, null,
        listOf(R.string.gap_yt_native_scope, R.string.gap_native_recs, R.string.gap_native_layout),
    ),
    CompatibilityEntry(
        Platform.INSTAGRAM, OpeningMethod.IN_DEREEL, webRules(Platform.INSTAGRAM), null,
        listOf(R.string.gap_ig_web_scope, R.string.gap_ig_web_filters, R.string.gap_web_one_login),
    ),
    CompatibilityEntry(
        Platform.YOUTUBE, OpeningMethod.IN_DEREEL, webRules(Platform.YOUTUBE), null,
        listOf(R.string.gap_yt_web_scope, R.string.gap_yt_web_signin, R.string.gap_yt_web_ads, R.string.gap_web_one_login),
    ),
    CompatibilityEntry(
        Platform.FACEBOOK, OpeningMethod.IN_DEREEL, webRules(Platform.FACEBOOK), null,
        listOf(
            R.string.gap_fb_web_scope,
            R.string.gap_fb_web_shortcuts,
            R.string.gap_fb_web_messages,
            R.string.gap_web_one_login,
        ),
    ),
    CompatibilityEntry(
        Platform.FACEBOOK, OpeningMethod.NATIVE_APP, NativeRules.facebookLite.version, null,
        listOf(R.string.gap_fb_lite_scope, R.string.gap_fb_regular_app, R.string.gap_native_recs, R.string.gap_native_layout),
    ),
    CompatibilityEntry(
        Platform.SNAPCHAT, OpeningMethod.NATIVE_APP, NativeRules.snapchat.version, null,
        listOf(R.string.gap_sc_native_scope, R.string.gap_sc_native_other, R.string.gap_native_layout, R.string.gap_sc_no_browser),
    ),
)

private fun webRules(platform: Platform): String = checkNotNull(BrowserScripts.ruleVersion(platform))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.help_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            },
            windowInsets = WindowInsets(0),
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BodyText(stringResource(R.string.help_intro))
            entries.forEach { entry ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "${platformName(entry.platform)} · ${methodLabel(entry.method, entry.platform)}",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.semantics { heading() },
                        )
                        BodyText(stringResource(R.string.help_rules_version, entry.ruleVersion))
                        Text(
                            entry.verifiedOn?.let { stringResource(R.string.help_verified_on, it) } ?: stringResource(R.string.help_not_verified),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        entry.gaps.forEach { Text("• " + stringResource(it), style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
            BodyText(stringResource(R.string.gap_general))
            BodyText(stringResource(R.string.gap_pip))
            BodyText(stringResource(R.string.gap_alert_limit))

            SectionHeader(stringResource(R.string.privacy_title))
            listOf(
                R.string.privacy_no_account,
                R.string.privacy_stored,
                R.string.privacy_never,
                R.string.privacy_accessibility,
                R.string.privacy_backup,
            ).forEach { Text("• " + stringResource(it), style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
