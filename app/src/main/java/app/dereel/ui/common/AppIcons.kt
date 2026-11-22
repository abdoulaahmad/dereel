package app.dereel.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * A few glyphs the core Material icon set lacks, drawn for Dereel on the same 24 dp grid. They are
 * always shown next to a text label, so they carry no meaning by themselves (NFR-03).
 */
object AppIcons {
    /** Protection: a plain shield. */
    val Shield: ImageVector by lazy {
        glyph("Shield", "M12 2 L4 5 V11 C4 16.1 7.4 20.7 12 22 C16.6 20.7 20 16.1 20 11 V5 Z")
    }

    /** Sleep schedule: a crescent moon. */
    val Moon: ImageVector by lazy {
        glyph("Moon", "M12 3 A9 9 0 1 0 21 12 A7 7 0 0 1 12 3 Z")
    }

    /** Live timer: a stopwatch. */
    val Timer: ImageVector by lazy {
        glyph(
            "Timer",
            "M12 6 A8 8 0 1 0 12 22 A8 8 0 1 0 12 6 Z M12 8 A6 6 0 1 1 12 20 A6 6 0 1 1 12 8 Z " +
                "M11 10 H13 V15 H11 Z M9.5 2 H14.5 V4 H9.5 Z",
            evenOdd = true,
        )
    }

    /** Temporary access: an hourglass. */
    val Hourglass: ImageVector by lazy {
        glyph("Hourglass", "M6 2 H18 V7 L14 12 L18 17 V22 H6 V17 L10 12 L6 7 Z")
    }

    private fun glyph(name: String, pathData: String, evenOdd: Boolean = false): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(
                pathData = addPathNodes(pathData),
                fill = SolidColor(Color.Black),
                pathFillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
            )
            .build()
}
