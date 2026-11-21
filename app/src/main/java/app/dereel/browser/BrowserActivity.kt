package app.dereel.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.dereel.AppContainer
import app.dereel.BuildConfig
import app.dereel.container
import app.dereel.core.bridge.BridgeMessage
import app.dereel.core.bridge.BridgeProtocol
import app.dereel.core.coordinator.MethodKey
import app.dereel.core.coordinator.SurfaceObservation
import app.dereel.core.model.Capabilities
import app.dereel.core.model.OpeningMethod
import app.dereel.core.model.Platform
import app.dereel.core.model.PlatformConfig
import app.dereel.core.policy.PolicyDecision
import app.dereel.core.policy.SurfaceKind
import app.dereel.core.shared.SharedItemMatch
import app.dereel.core.web.BlockOutcome
import app.dereel.core.web.BrowserCommand
import app.dereel.core.web.BrowserNavigationController
import app.dereel.core.web.BrowserPolicyPort
import app.dereel.core.web.NavTarget
import app.dereel.core.web.PlatformWeb
import app.dereel.core.web.WebUrlClassifier
import app.dereel.ui.TimerText
import app.dereel.platform.SystemIntents
import app.dereel.ui.theme.DereelTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The filtered browser inside Dereel (FR-12 to FR-19). The WebView layer forwards navigation
 * events to [BrowserNavigationController]. That controller asks the shared coordinator for every
 * decision, so the browser keeps no allowance balance of its own (DATA-02).
 */
class BrowserActivity : ComponentActivity() {
    private lateinit var platform: Platform
    private lateinit var appContainer: AppContainer
    private lateinit var controller: BrowserNavigationController
    private lateinit var scripts: BrowserScripts

    private val ui = BrowserUiState()
    private var webView: WebView? = null
    private var scriptHandler: ScriptHandler? = null
    private var installedConfig: PageConfig? = null
    private var requestedUrl: String? = null
    private var viaSharedLink = false
    private var lastEntryNoticeAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val selected = Platform.fromId(intent.getStringExtra(EXTRA_PLATFORM))
        // Only platforms with an "inside Dereel" method have pages and rules to load.
        if (selected == null || !Capabilities.supports(selected, OpeningMethod.IN_DEREEL)) {
            finish()
            return
        }
        platform = selected
        appContainer = container
        scripts = BrowserScripts(this)
        // INT-01: accept only a URL that classifies as a page of this platform.
        requestedUrl = intent.getStringExtra(EXTRA_URL)
            ?.takeIf { WebUrlClassifier.classify(platform, it) is NavTarget.PlatformPage }
        viaSharedLink = requestedUrl != null && intent.getBooleanExtra(EXTRA_VIA_SHARED_LINK, false)
        controller = BrowserNavigationController(platform, policyPort(), SystemClock::elapsedRealtime)
        ui.temporaryAccessOffered = appContainer.settings.preferences.value?.temporaryAccessEnabled == true

        setContent {
            DereelTheme {
                BrowserScreen(platform = platform, state = ui, webView = { webView }, actions = actions)
            }
        }

        lifecycleScope.launch {
            appContainer.awaitReady()
            val created = createWebView()
            if (created == null) {
                ui.error = BrowserError.WEBVIEW_UNAVAILABLE
                return@launch
            }
            webView = created
            ui.webViewReady = true
            execute(controller.start(requestedUrl, viaSharedLink))
            observePolicy()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::controller.isInitialized) return
        webView?.onResume()
        ui.temporaryAccessOffered = appContainer.settings.preferences.value?.temporaryAccessEnabled == true
        execute(controller.onForeground(webView?.let(::previousEntryUrl)))
        pushPageConfig()
    }

    override fun onPause() {
        if (::controller.isInitialized) {
            // INT-03: leaving or closing the browser ends or checkpoints the viewing session.
            controller.onBackground()
            webView?.onPause()
            appContainer.usage.checkpoint()
        }
        super.onPause()
    }

    override fun onDestroy() {
        if (::controller.isInitialized) controller.onClosed()
        destroyWebView()
        super.onDestroy()
    }

    // --- policy -----------------------------------------------------------------------------

    private fun policyPort() = object : BrowserPolicyPort {
        override fun evaluate(surface: SurfaceKind, sharedItem: SharedItemMatch?): PolicyDecision =
            appContainer.coordinator.evaluate(platform, OpeningMethod.IN_DEREEL, SurfaceObservation(surface, sharedItem))

        override fun report(surface: SurfaceKind, sharedItem: SharedItemMatch?): PolicyDecision =
            appContainer.coordinator.report(platform, OpeningMethod.IN_DEREEL, SurfaceObservation(surface, sharedItem))

        override fun clear() = appContainer.coordinator.clear(platform, OpeningMethod.IN_DEREEL)
    }

    /** POL-06, FR-27: re-apply policy when settings, grants or the shared balance change. */
    private fun observePolicy() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    appContainer.coordinator.decisions
                        .map { it[MethodKey(platform, OpeningMethod.IN_DEREEL)]?.javaClass }
                        .distinctUntilChanged()
                        .collect {
                            execute(controller.onPolicyChanged(webView?.let(::previousEntryUrl)))
                            pushPageConfig()
                        }
                }
                launch {
                    appContainer.settings.configs.map { it[platform] }.distinctUntilChanged().collect { pushPageConfig() }
                }
                launch {
                    appContainer.grants.active.map { it[platform] }.distinctUntilChanged().collect { pushPageConfig() }
                }
                // S-02: the live timer, refreshed once a second while this screen is in front.
                launch {
                    while (true) {
                        ui.timerText = timerText()
                        delay(1_000)
                    }
                }
            }
        }
    }

    private fun timerText(): String? {
        if (appContainer.settings.preferences.value?.liveTimer != true) return null
        if (controller.currentRoute?.isShortVideo != true) return null
        val observation = SurfaceObservation(SurfaceKind.SHORT_VIDEO, controller.activeSharedItem)
        val decision = appContainer.coordinator.evaluate(platform, OpeningMethod.IN_DEREEL, observation) as? PolicyDecision.Allow
            ?: return null
        return TimerText.forDecision(this, decision, appContainer.usage.usedTodayMs(platform))
    }

    private fun currentPageConfig(): PageConfig {
        val config = appContainer.settings.configs.value[platform] ?: PlatformConfig.defaultFor(platform)
        return PageConfig(
            denied = controller.shortVideoDeniedNow(),
            sharedAllowed = config.allowSharedVideos && Capabilities.of(platform, OpeningMethod.IN_DEREEL).sharedItemException,
            controls = config.browserControls,
        )
    }

    private fun pushPageConfig() {
        val view = webView ?: return
        val config = currentPageConfig()
        if (config != installedConfig) installDocumentStartScript(view, config)
        view.evaluateJavascript(scripts.applyScript(config), null)
    }

    // --- commands --------------------------------------------------------------------------

    private fun execute(commands: List<BrowserCommand>) {
        val view = webView
        for (command in commands) {
            when (command) {
                BrowserCommand.GoBack ->
                    if (view?.canGoBack() == true) view.goBack() else view?.loadUrl(PlatformWeb.entryUrl(platform))
                is BrowserCommand.Load -> {
                    ui.fullBlock = null
                    view?.loadUrl(command.url)
                }
                is BrowserCommand.ShowBlocked ->
                    if (command.notice.outcome == BlockOutcome.NOTHING_LOADED) ui.fullBlock = command.notice else ui.notice = command.notice
                is BrowserCommand.OfferExternal -> ui.external = command
                BrowserCommand.AppLinkBlocked -> ui.snack = BrowserSnack.APP_LINK_BLOCKED
                is BrowserCommand.ShowError -> ui.error = BrowserError.REDIRECT_LOOP
            }
        }
    }

    private val actions = object : BrowserActions {
        override fun close() = finish()

        override fun back() {
            val view = webView
            when {
                ui.notice != null -> ui.notice = null
                ui.external != null -> ui.external = null
                view?.canGoBack() == true && ui.error == null && ui.fullBlock == null -> view.goBack()
                else -> finish()
            }
        }

        override fun refresh() {
            ui.error = null
            webView?.reload()
        }

        override fun dismissNotice() {
            ui.notice = null
        }

        override fun goHome() {
            ui.error = null
            ui.notice = null
            execute(listOf(BrowserCommand.Load(PlatformWeb.entryUrl(platform))))
        }

        override fun continueAfterLoop() {
            ui.error = null
            execute(controller.resetLoopGuard())
        }

        override fun openExternal(url: String) {
            ui.external = null
            if (!SystemIntents.openExternalWeb(this@BrowserActivity, url)) ui.snack = BrowserSnack.NO_APP_FOR_LINK
        }

        override fun cancelExternal() {
            ui.external = null
        }

        override fun allowTemporaryAccess() {
            appContainer.grants.request(platform)
            ui.notice = null
            val blocked = ui.fullBlock
            if (blocked != null) {
                ui.fullBlock = null
                execute(listOf(BrowserCommand.Load(requestedUrl ?: PlatformWeb.entryUrl(platform))))
            }
        }

        override fun updateWebView() {
            SystemIntents.openWebViewUpdate(this@BrowserActivity)
        }

        override fun snackShown() {
            ui.snack = null
        }
    }

    // --- WebView ---------------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView? {
        val view = try {
            WebView(this)
        } catch (e: RuntimeException) {
            // MissingWebViewPackageException and similar: WebView is missing or disabled (FR-37).
            return null
        }
        view.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        with(view.settings) {
            javaScriptEnabled = true // Required by both platforms' web apps.
            domStorageEnabled = true
            allowFileAccess = false // SEC-05
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setGeolocationEnabled(false)
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
            safeBrowsingEnabled = true
            // FR-12 user-agent strategy: the default WebView user agent, unchanged (Decision D-08).
        }
        @Suppress("DEPRECATION")
        with(view.settings) {
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
        }
        CookieManager.getInstance().setAcceptCookie(true)
        // A-18 (revised after device testing): Instagram's login CAPTCHA runs in an embedded frame
        // from another site and needs its own cookies, as in Chrome. Main-frame navigation stays
        // limited to platform and sign-in pages, so this only affects frames those pages embed.
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        view.webViewClient = Client()
        view.webChromeClient = Chrome()
        view.setDownloadListener { _, _, _, _, _ -> ui.snack = BrowserSnack.DOWNLOAD_UNSUPPORTED }
        // SEC-03: remote debugging only in debug builds.
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        installBridge(view)
        installDocumentStartScript(view, currentPageConfig())
        return view
    }

    /** SEC-04: messages are accepted only from the platform's own top-level origin, and validated. */
    private fun installBridge(view: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        WebViewCompat.addWebMessageListener(view, BRIDGE_NAME, PlatformWeb.bridgeOrigins(platform)) { _, message, sourceOrigin, isMainFrame, _ ->
            if (!BridgeProtocol.acceptsSource(platform, sourceOrigin.toString(), isMainFrame)) return@addWebMessageListener
            when (BridgeProtocol.parse(message.data)) {
                is BridgeMessage.RulesReady -> ui.rulesActive = true
                BridgeMessage.BlockedEntryTapped -> {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastEntryNoticeAt >= ENTRY_NOTICE_INTERVAL_MS) {
                        lastEntryNoticeAt = now
                        execute(controller.onBlockedEntryTapped())
                    }
                }
                null -> Unit
            }
        }
    }

    private fun installDocumentStartScript(view: WebView, config: PageConfig) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        removeDocumentStartScript()
        scriptHandler = WebViewCompat.addDocumentStartJavaScript(view, scripts.documentScript(platform, config), PlatformWeb.bridgeOrigins(platform))
        installedConfig = config
    }

    private fun removeDocumentStartScript() {
        val handler = scriptHandler ?: return
        scriptHandler = null
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) handler.remove()
    }

    /** Older WebViews without document-start scripts get the rules after the page starts. */
    private fun injectFallback(view: WebView, url: String) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        val origin = url.toUri().let { "${it.scheme}://${it.host}" }
        if (origin in PlatformWeb.bridgeOrigins(platform)) view.evaluateJavascript(scripts.documentScript(platform, currentPageConfig()), null)
    }

    private fun previousEntryUrl(view: WebView): String? {
        val history = view.copyBackForwardList()
        val index = history.currentIndex - 1
        return if (index >= 0) history.getItemAtIndex(index)?.url else null
    }

    private fun destroyWebView() {
        val view = webView ?: return
        webView = null
        ui.webViewReady = false
        removeDocumentStartScript()
        (view.parent as? ViewGroup)?.removeView(view)
        view.stopLoading()
        view.destroy()
    }

    private fun isOnline(): Boolean {
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val result = controller.onNavigationRequest(request.url.toString(), request.isForMainFrame, request.hasGesture())
            if (BuildConfig.DEBUG) {
                // Site name and decision only: never the path, query or page content (FR-32, SEC-06).
                val frame = if (request.isForMainFrame) "request" else "frame"
                Log.d(LOG_TAG, "$frame ${request.url.scheme}://${request.url.host.orEmpty()} block=${result.block} -> ${result.commands.map { it::class.simpleName }}")
            }
            execute(result.commands)
            return result.block
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
            if (BuildConfig.DEBUG) Log.d(LOG_TAG, "http ${errorResponse.statusCode} ${request.url.host.orEmpty()} main=${request.isForMainFrame}")
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            ui.loading = true
            if (ui.error == BrowserError.OFFLINE || ui.error == BrowserError.PAGE_FAILED) ui.error = null
            if (BuildConfig.DEBUG) {
                val target = WebUrlClassifier.classify(platform, url)
                val kind = (target as? NavTarget.PlatformPage)?.route?.kind?.name ?: target::class.simpleName
                Log.d(LOG_TAG, "page ${url.toUri().host} $kind")
            }
            val commands = controller.onRouteCommitted(url, previousEntryUrl = null, inPage = false)
            if (commands.isNotEmpty()) view.stopLoading()
            execute(commands)
            injectFallback(view, url)
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            ui.canGoBack = view.canGoBack()
            execute(controller.onRouteCommitted(url, previousEntryUrl(view), inPage = true))
        }

        override fun onPageFinished(view: WebView, url: String) {
            ui.loading = false
            ui.canGoBack = view.canGoBack()
            injectFallback(view, url)
            pushPageConfig()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (BuildConfig.DEBUG) {
                Log.d(LOG_TAG, "error ${error.errorCode} ${error.description} ${request.url.scheme}://${request.url.host.orEmpty()} main=${request.isForMainFrame}")
            }
            if (!request.isForMainFrame) return
            // DATA-04: a network error is neither usage nor successful filtering.
            controller.onMainFrameError()
            ui.loading = false
            ui.error = if (isOnline()) BrowserError.PAGE_FAILED else BrowserError.OFFLINE
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            // SEC-05: never bypass certificate errors.
            handler.cancel()
            if (view.url == null || error.url == view.url) {
                controller.onMainFrameError()
                ui.loading = false
                ui.error = BrowserError.TLS
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            controller.onMainFrameError()
            ui.error = BrowserError.RENDERER_GONE
            destroyWebView()
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            ui.progress = newProgress / 100f
        }

        // Camera, microphone, location and uploads are not part of the first release.
        override fun onPermissionRequest(request: PermissionRequest) = request.deny()

        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
            callback?.invoke(origin, false, false)
        }

        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?,
        ): Boolean {
            filePathCallback?.onReceiveValue(null)
            ui.snack = BrowserSnack.UPLOAD_UNSUPPORTED
            return true
        }
    }

    companion object {
        private const val EXTRA_PLATFORM = "platform"
        private const val EXTRA_URL = "url"
        private const val EXTRA_VIA_SHARED_LINK = "via_shared_link"
        private const val BRIDGE_NAME = "dereelBridge"
        private const val LOG_TAG = "DereelBrowser"
        private const val ENTRY_NOTICE_INTERVAL_MS = 2_000L

        fun intent(context: Context, platform: Platform, url: String? = null, viaSharedLink: Boolean = false): Intent =
            Intent(context, BrowserActivity::class.java)
                .putExtra(EXTRA_PLATFORM, platform.id)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_VIA_SHARED_LINK, viaSharedLink)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
