package app.dereel.browser

import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.dereel.R
import app.dereel.core.model.OpeningMethod
import app.dereel.core.model.Platform
import app.dereel.core.web.BlockNotice
import app.dereel.core.web.BlockOutcome
import app.dereel.core.web.BrowserCommand
import app.dereel.ui.BlockMessages
import app.dereel.ui.common.platformName

enum class BrowserError { OFFLINE, PAGE_FAILED, TLS, REDIRECT_LOOP, RENDERER_GONE, WEBVIEW_UNAVAILABLE }

enum class BrowserSnack { APP_LINK_BLOCKED, DOWNLOAD_UNSUPPORTED, UPLOAD_UNSUPPORTED, NO_APP_FOR_LINK }

@Stable
class BrowserUiState {
    var webViewReady by mutableStateOf(false)
    var loading by mutableStateOf(true)
    var progress by mutableFloatStateOf(0f)
    var canGoBack by mutableStateOf(false)
    var notice by mutableStateOf<BlockNotice?>(null)
    var fullBlock by mutableStateOf<BlockNotice?>(null)
    var external by mutableStateOf<BrowserCommand.OfferExternal?>(null)
    var error by mutableStateOf<BrowserError?>(null)
    var snack by mutableStateOf<BrowserSnack?>(null)
    var rulesActive by mutableStateOf(false)
    var temporaryAccessOffered by mutableStateOf(false)

    /** Live timer text while a short video is allowed, or null (S-02). */
    var timerText by mutableStateOf<String?>(null)
}

interface BrowserActions {
    fun close()
    fun back()
    fun refresh()
    fun dismissNotice()
    fun goHome()
    fun continueAfterLoop()
    fun openExternal(url: String)
    fun cancelExternal()
    fun allowTemporaryAccess()
    fun updateWebView()
    fun snackShown()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(platform: Platform, state: BrowserUiState, webView: () -> WebView?, actions: BrowserActions) {
    val snackbar = remember { SnackbarHostState() }
    val snackText = state.snack?.let { snackMessage(it) }
    LaunchedEffect(state.snack) {
        if (snackText != null) {
            snackbar.showSnackbar(snackText)
            actions.snackShown()
        }
    }
    BackHandler { actions.back() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = actions::close) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.browser_close))
                    }
                },
                title = {
                    Column {
                        Text(platformName(platform), style = MaterialTheme.typography.titleMedium)
                        Text(
                            state.timerText ?: stringResource(R.string.browser_subtitle),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (state.timerText != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = actions::back, enabled = state.canGoBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.browser_back))
                    }
                    IconButton(onClick = actions::refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.browser_refresh))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val view = webView()
            if (state.webViewReady && view != null) {
                AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
            }
            if (state.loading && state.error == null && state.fullBlock == null) {
                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
            state.fullBlock?.let { FullBlock(platform, it, state.temporaryAccessOffered, actions) }
            state.error?.let { ErrorState(platform, it, actions) }
            state.notice?.let {
                NoticeCard(platform, it, state.temporaryAccessOffered, actions, Modifier.align(Alignment.BottomCenter))
            }
        }
    }

    state.external?.let { offer ->
        AlertDialog(
            onDismissRequest = actions::cancelExternal,
            title = { Text(stringResource(R.string.external_title)) },
            text = { Text(stringResource(R.string.external_body, offer.host)) },
            confirmButton = { TextButton(onClick = { actions.openExternal(offer.url) }) { Text(stringResource(R.string.external_open)) } },
            dismissButton = { TextButton(onClick = actions::cancelExternal) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun NoticeCard(
    platform: Platform,
    notice: BlockNotice,
    offerGrant: Boolean,
    actions: BrowserActions,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val outcome = when (notice.outcome) {
        BlockOutcome.WENT_BACK -> stringResource(R.string.browser_went_back)
        BlockOutcome.RETURNED_TO_CONVERSATION -> stringResource(R.string.browser_returned_to_conversation)
        else -> null
    }
    val body = listOfNotNull(outcome, BlockMessages.body(context, platform, notice.reason, OpeningMethod.IN_DEREEL)).joinToString(" ")
    Card(
        modifier = modifier.padding(16.dp).widthIn(max = 560.dp).fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                BlockMessages.title(context, platform, notice.reason, notice.retryInMs),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(body, style = MaterialTheme.typography.bodyMedium)
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                // The user is already on a valid page in every outcome, so the exit is to continue.
                Button(onClick = actions::dismissNotice) { Text(stringResource(R.string.action_ok)) }
                if (offerGrant) {
                    TextButton(onClick = actions::allowTemporaryAccess) { Text(stringResource(R.string.action_allow_five_minutes)) }
                }
            }
        }
    }
}

@Composable
private fun FullBlock(platform: Platform, notice: BlockNotice, offerGrant: Boolean, actions: BrowserActions) {
    val context = LocalContext.current
    CenteredMessage(
        title = BlockMessages.title(context, platform, notice.reason, notice.retryInMs),
        body = BlockMessages.body(context, platform, notice.reason, OpeningMethod.IN_DEREEL),
        primary = stringResource(R.string.browser_go_home, platformName(platform)) to actions::goHome,
        secondary = stringResource(R.string.action_close) to actions::close,
        tertiary = if (offerGrant) stringResource(R.string.action_allow_five_minutes) to actions::allowTemporaryAccess else null,
    )
}

@Composable
private fun ErrorState(platform: Platform, error: BrowserError, actions: BrowserActions) {
    val name = platformName(platform)
    val close = stringResource(R.string.action_close) to actions::close
    when (error) {
        BrowserError.OFFLINE -> CenteredMessage(
            stringResource(R.string.error_offline_title),
            stringResource(R.string.error_offline_body, name),
            stringResource(R.string.action_try_again) to actions::refresh,
            close,
        )
        BrowserError.PAGE_FAILED -> CenteredMessage(
            stringResource(R.string.error_page_title),
            stringResource(R.string.error_page_body),
            stringResource(R.string.action_try_again) to actions::refresh,
            close,
        )
        BrowserError.TLS -> CenteredMessage(
            stringResource(R.string.error_tls_title),
            stringResource(R.string.error_tls_body),
            stringResource(R.string.action_try_again) to actions::refresh,
            close,
        )
        BrowserError.REDIRECT_LOOP -> CenteredMessage(
            stringResource(R.string.error_loop_title),
            stringResource(R.string.error_loop_body, name),
            stringResource(R.string.browser_go_home, name) to actions::continueAfterLoop,
            close,
        )
        BrowserError.RENDERER_GONE -> CenteredMessage(
            stringResource(R.string.error_renderer_title),
            stringResource(R.string.error_renderer_body),
            close,
            null,
        )
        BrowserError.WEBVIEW_UNAVAILABLE -> CenteredMessage(
            stringResource(R.string.error_webview_title),
            stringResource(R.string.error_webview_body, name),
            stringResource(R.string.action_update_webview) to actions::updateWebView,
            close,
        )
    }
}

@Composable
private fun CenteredMessage(
    title: String,
    body: String,
    primary: Pair<String, () -> Unit>,
    secondary: Pair<String, () -> Unit>?,
    tertiary: Pair<String, () -> Unit>? = null,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
            Text(body, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            Button(onClick = primary.second, modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp)) { Text(primary.first) }
            secondary?.let { OutlinedButton(onClick = it.second, modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp)) { Text(it.first) } }
            tertiary?.let { TextButton(onClick = it.second) { Text(it.first) } }
        }
    }
}

@Composable
private fun snackMessage(snack: BrowserSnack): String = stringResource(
    when (snack) {
        BrowserSnack.APP_LINK_BLOCKED -> R.string.snack_app_link_blocked
        BrowserSnack.DOWNLOAD_UNSUPPORTED -> R.string.snack_download_unsupported
        BrowserSnack.UPLOAD_UNSUPPORTED -> R.string.snack_upload_unsupported
        BrowserSnack.NO_APP_FOR_LINK -> R.string.snack_no_app_for_link
    },
)
