package app.dereel.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import app.dereel.core.model.Platform
import androidx.compose.ui.unit.dp
import app.dereel.core.status.ProtectionState

/** Status shown with icon and text, never colour alone (NFR-03). */
@Composable
fun StatusChip(state: ProtectionState, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val (container, content, icon) = when (state) {
        ProtectionState.ACTIVE -> Triple(scheme.primaryContainer, scheme.onPrimaryContainer, Icons.Default.CheckCircle)
        ProtectionState.OFF -> Triple(scheme.surfaceVariant, scheme.onSurfaceVariant, Icons.Default.Clear)
        ProtectionState.PAUSED -> Triple(scheme.secondaryContainer, scheme.onSecondaryContainer, Icons.Default.Info)
        ProtectionState.NEEDS_SETUP -> Triple(scheme.tertiaryContainer, scheme.onTertiaryContainer, Icons.Default.Build)
        ProtectionState.NEEDS_ATTENTION -> Triple(scheme.errorContainer, scheme.onErrorContainer, Icons.Default.Warning)
        ProtectionState.UNAVAILABLE -> Triple(scheme.errorContainer, scheme.onErrorContainer, Icons.Default.Warning)
    }
    StatusPill(stateLabel(state), container, content, icon, modifier)
}

@Composable
fun StatusPill(label: String, container: Color, content: Color, icon: ImageVector?, modifier: Modifier = Modifier) {
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(50), modifier = modifier) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 24.dp, bottom = 8.dp).semantics { heading() },
    )
}

/** A whole-row switch: one 48 dp target announced as a switch with its label (NFR-02). */
@Composable
fun SwitchRow(
    title: String,
    description: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(RowLeadingGap))
        }
        Column(Modifier.weight(1f).padding(end = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * A row that opens something. [leading] is a decorative icon or logo, [status] a short state shown
 * before the arrow, and [trailing] replaces the arrow when the row does something other than open
 * a screen. [onClickLabel] tells a screen reader what a tap does when the title alone does not.
 */
@Composable
fun ClickRow(
    title: String,
    value: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    leading: (@Composable () -> Unit)? = null,
    status: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    titleColor: Color = Color.Unspecified,
    onClickLabel: String? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(RowLeadingGap))
        }
        Column(Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
            if (value != null) Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (status != null) {
            status()
            Spacer(Modifier.width(4.dp))
        }
        if (trailing != null) {
            trailing()
        } else {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A row that only tells you something: same shape as the others, nothing to tap. */
@Composable
fun InfoRow(title: String, description: String, leading: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(RowLeadingGap))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Size of a row's leading icon or logo, and the gap after it. Text in a card lines up on their sum. */
val RowLeadingSize = 40.dp
val RowLeadingGap = 16.dp

/** A row's leading icon on a soft round background. Decorative: the row's title says what it is. */
@Composable
fun RowIcon(icon: ImageVector, tint: Color = MaterialTheme.colorScheme.primary) {
    Box(
        Modifier.size(RowLeadingSize).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
fun BodyText(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

/** The page title at the top of each main screen. */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.headlineMedium,
        modifier = modifier.padding(vertical = 4.dp).semantics { heading() },
    )
}

/**
 * A platform's logo on a small tile (scope change S-04). Decorative: the platform is always named
 * in text beside it, so the logo is hidden from screen readers and nothing depends on it alone.
 */
@Composable
fun PlatformAvatar(platform: Platform, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    val logo = platformLogo(platform)
    val shape = RoundedCornerShape(percent = 28)
    // Logos keep their own colours, so the tile under them is plain: white in the light theme and
    // a raised surface in the dark one, unless the brand has a tile colour of its own.
    val neutral = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.surfaceContainerHigh else Color.White
    val outline = if (logo.tile == null) Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape) else Modifier
    Box(
        modifier.size(size).clip(shape).background(logo.tile ?: neutral).then(outline).clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(logo.drawable), contentDescription = null, modifier = Modifier.size(size * logo.scale))
    }
}

/** A titled group of related rows on one surface, so a screen reads as a few groups instead of one long list. */
@Composable
fun SectionCard(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp).semantics { heading() },
            )
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), content = content)
        }
    }
}

/** [inset] moves the start of the line to where the row's text starts, for rows with a leading icon. */
@Composable
fun RowDivider(inset: Dp = 0.dp) {
    HorizontalDivider(Modifier.padding(start = inset), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
}
