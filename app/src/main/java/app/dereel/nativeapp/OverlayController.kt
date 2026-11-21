package app.dereel.nativeapp

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import app.dereel.R
import app.dereel.core.nativeapp.Bounds

/**
 * Accessibility overlays for native enforcement (FR-07, FR-08, UX-10):
 * - a short banner explaining an automatic Back action;
 * - a cover over the short-video region, with a manual exit, when Back did not leave the surface;
 * - a small timer while short videos are allowed, if the user keeps the live timer on (S-02).
 *
 * TYPE_ACCESSIBILITY_OVERLAY windows need no extra permission and are owned by the service.
 * Every overlay is removed when its target is gone, the app leaves the foreground, the screen
 * turns off or the service stops.
 */
class OverlayController(private val service: AccessibilityService) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val audioManager = service.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var cover: View? = null
    private var banner: View? = null
    private var timer: TextView? = null
    private var audioFocus: AudioFocusRequest? = null
    private val removeBannerRunnable = Runnable { removeBanner() }

    val coverShown: Boolean get() = cover != null

    fun showBanner(title: String, body: String) {
        removeBanner()
        val view = card(service, title, body, buttons = listOf(service.getString(R.string.action_dismiss) to { removeBanner() }))
        val params = baseParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP
            y = dp(56)
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            format = PixelFormat.TRANSLUCENT
        }
        if (add(view, params)) {
            banner = view
            handler.postDelayed(removeBannerRunnable, BANNER_MS)
        }
    }

    fun showCover(
        region: Bounds,
        title: String,
        body: String,
        onGoBack: () -> Unit,
        temporaryAccess: (() -> Unit)?,
    ) {
        val existing = cover
        if (existing != null) {
            updateCover(region)
            return
        }
        val buttons = buildList<Pair<String, () -> Unit>> {
            add(service.getString(R.string.action_go_back) to onGoBack)
            temporaryAccess?.let { add(service.getString(R.string.action_allow_five_minutes) to it) }
        }
        val view = card(service, title, body, buttons, fillScreen = true)
        if (add(view, coverParams(region))) {
            cover = view
            requestAudioFocus()
        }
    }

    fun updateCover(region: Bounds) {
        val view = cover ?: return
        try {
            windowManager.updateViewLayout(view, coverParams(region))
        } catch (e: IllegalArgumentException) {
            cover = null
        }
    }

    fun removeCover() {
        cover?.let { remove(it) }
        cover = null
        abandonAudioFocus()
    }

    fun removeBanner() {
        handler.removeCallbacks(removeBannerRunnable)
        banner?.let { remove(it) }
        banner = null
    }

    /**
     * Shows or updates the live timer. It cannot be touched, so it never gets in the way of the
     * app underneath, and it is hidden from screen readers because it changes every second.
     */
    fun showTimer(text: String) {
        val existing = timer
        if (existing != null) {
            if (existing.text.toString() != text) existing.text = text
            return
        }
        val view = TextView(service).apply {
            this.text = text
            setTextColor(service.getColor(R.color.overlay_on_surface))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                setColor(service.getColor(R.color.overlay_surface))
                cornerRadius = dp(16).toFloat()
                alpha = 235
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val params = baseParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(44)
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            format = PixelFormat.TRANSLUCENT
        }
        if (add(view, params)) timer = view
    }

    fun removeTimer() {
        timer?.let { remove(it) }
        timer = null
    }

    fun removeAll() {
        removeCover()
        removeBanner()
        removeTimer()
    }

    // --- views -----------------------------------------------------------------------------

    private fun baseParams(width: Int, height: Int) = WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.OPAQUE,
    )

    private fun coverParams(region: Bounds) = baseParams(region.width, region.height).apply {
        gravity = Gravity.TOP or Gravity.START
        x = region.left
        y = region.top
        flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
    }

    private fun card(
        context: Context,
        title: String,
        body: String,
        buttons: List<Pair<String, () -> Unit>>,
        fillScreen: Boolean = false,
    ): View {
        val surface = context.getColor(R.color.overlay_surface)
        val onSurface = context.getColor(R.color.overlay_on_surface)
        val accent = context.getColor(R.color.overlay_accent)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (fillScreen) Gravity.CENTER else Gravity.START
            setPadding(dp(24), dp(20), dp(24), dp(20))
            background = GradientDrawable().apply {
                setColor(surface)
                cornerRadius = if (fillScreen) 0f else dp(16).toFloat()
            }
            if (!fillScreen) {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            // TalkBack announces the pane and its live content when the overlay appears (NFR-02).
            ViewCompat.setAccessibilityPaneTitle(this, title)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            addView(TextView(context).apply {
                text = title
                setTextColor(onSurface)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = if (fillScreen) Gravity.CENTER_HORIZONTAL else Gravity.START
                isFocusable = true
                ViewCompat.setAccessibilityHeading(this, true)
            })
            addView(TextView(context).apply {
                text = body
                setTextColor(onSurface)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                gravity = if (fillScreen) Gravity.CENTER_HORIZONTAL else Gravity.START
                setPadding(0, dp(8), 0, dp(16))
            })
            buttons.forEachIndexed { index, (label, action) ->
                addView(TextView(context).apply {
                    text = label
                    val primary = index == 0
                    setTextColor(if (primary) surface else accent)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    minHeight = dp(48)
                    minWidth = dp(160)
                    setPadding(dp(20), dp(12), dp(20), dp(12))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(24).toFloat()
                        if (primary) setColor(accent) else setStroke(dp(1), accent)
                    }
                    isClickable = true
                    isFocusable = true
                    contentDescription = label
                    setOnClickListener { action() }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        topMargin = dp(8)
                        gravity = if (fillScreen) Gravity.CENTER_HORIZONTAL else Gravity.END
                    }
                })
            }
        }
    }

    private fun add(view: View, params: WindowManager.LayoutParams): Boolean = try {
        windowManager.addView(view, params)
        true
    } catch (e: RuntimeException) {
        false
    }

    private fun remove(view: View) {
        try {
            windowManager.removeView(view)
        } catch (e: IllegalArgumentException) {
            // Already removed.
        }
    }

    /**
     * Covered short videos may still play sound. Taking transient audio focus asks well-behaved
     * apps to pause until the cover is removed.
     */
    private fun requestAudioFocus() {
        if (audioFocus != null) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build()
        if (audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) audioFocus = request
    }

    private fun abandonAudioFocus() {
        audioFocus?.let { audioManager.abandonAudioFocusRequest(it) }
        audioFocus = null
    }

    private fun dp(value: Int): Int = (value * service.resources.displayMetrics.density).toInt()

    companion object {
        private const val BANNER_MS = 4_500L
    }
}
