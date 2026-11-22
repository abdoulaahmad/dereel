package app.dereel.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.Color
import app.dereel.R
import app.dereel.core.model.Platform

/**
 * How one platform's logo is drawn: the drawable, the tile colour behind it when the brand has one
 * (otherwise a neutral tile is used), and how much of the tile the logo fills.
 *
 * The logos are the platforms' trademarks. They are shown only to identify the app a card or row
 * is about, unaltered, next to its name (scope change S-04, docs/THIRD_PARTY.md).
 */
data class PlatformLogo(@DrawableRes val drawable: Int, val tile: Color?, val scale: Float)

/** Exhaustive on purpose: a new platform does not compile until it has a logo. */
fun platformLogo(platform: Platform): PlatformLogo = when (platform) {
    Platform.INSTAGRAM -> PlatformLogo(R.drawable.ic_logo_instagram, tile = null, scale = 0.60f)
    Platform.YOUTUBE -> PlatformLogo(R.drawable.ic_logo_youtube, tile = null, scale = 0.66f)
    Platform.FACEBOOK -> PlatformLogo(R.drawable.ic_logo_facebook, tile = null, scale = 0.68f)
    // Snapchat's logo is the ghost on its yellow.
    Platform.SNAPCHAT -> PlatformLogo(R.drawable.ic_logo_snapchat, tile = Color(0xFFFFFC00), scale = 0.74f)
}
