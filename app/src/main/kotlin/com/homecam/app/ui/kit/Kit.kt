package com.homecam.app.ui.kit

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.homecam.app.R
import com.homecam.app.ui.theme.Ethan
import com.homecam.app.ui.theme.EthanRadius

/** The gutter every screen shares. */
val Gutter = 16.dp

/** Touch needs 44dp even where the design system's desktop control is 36. */
val TouchHeight = 44.dp

@Composable
fun LucideIcon(
    @DrawableRes id: Int,
    label: String?,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    tint: Color = Ethan.colors.textMuted,
) {
    Icon(painterResource(id), contentDescription = label, modifier = modifier.size(size), tint = tint)
}

/** A sticky bar: where you are, the way back, and at most a couple of actions. */
@Composable
fun TopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    /** Mono for an identifier, sans for a sentence. */
    subtitleIsData: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().background(Ethan.colors.bgApp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = if (onBack == null) Gutter else 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconAction(R.drawable.lucide_arrow_left, "Back", onClick = onBack)
            }
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(title, style = Ethan.type.title2, color = Ethan.colors.textHeading, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(subtitle, style = if (subtitleIsData) Ethan.type.monoSmall else Ethan.type.caption, color = Ethan.colors.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            actions()
        }
        Rule()
    }
}

/** An icon-only control. The label is what a screen reader says and what a long press shows. */
@Composable
fun IconAction(
    @DrawableRes id: Int,
    label: String,
    modifier: Modifier = Modifier,
    tint: Color = Ethan.colors.textMuted,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(TouchHeight)
            .clip(RoundedCornerShape(EthanRadius.control))
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        LucideIcon(id, label, tint = if (enabled) tint else tint.copy(alpha = 0.45f))
    }
}

@Composable
fun Rule(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier, thickness = 1.dp, color = Ethan.colors.inkRule)
}

/** The uppercase mono micro label that names a section. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = Ethan.type.micro, color = Ethan.colors.textFaint, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = Ethan.type.micro, color = Ethan.colors.textFaint)
    }
}

/** Paper on paper: a hairline, a tight corner, and no shadow to speak of. Cards never nest. */
@Composable
fun PaperCard(
    modifier: Modifier = Modifier,
    padding: Dp = 12.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(EthanRadius.card)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Ethan.colors.surfaceCard)
            .border(1.dp, Ethan.colors.borderSubtle, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

/**
 * One line of label, value and status, with its metadata tucked directly under the label.
 * Used inside a [PaperCard] with zero padding, separated by [Rule]s.
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    meta: String? = null,
    metaMono: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 52.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = Ethan.type.bodyStrong, color = Ethan.colors.textHeading, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (meta != null) {
                Text(
                    meta,
                    style = if (metaMono) Ethan.type.monoSmall else Ethan.type.bodySmall,
                    color = Ethan.colors.textMuted,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

@Composable
fun Chevron() = LucideIcon(R.drawable.lucide_chevron_right, null, size = 18.dp, tint = Ethan.colors.textFaint)

@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, meta: String? = null) {
    ListRow(title, meta = meta, onClick = { onChange(!checked) }) {
        EthanSwitch(checked, onChange)
    }
}

/** The system's switch: a short pill whose knob slides in 140 ms. The row around it is the touch target. */
@Composable
fun EthanSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val offset by androidx.compose.animation.core.animateDpAsState(
        if (checked) 18.dp else 2.dp,
        androidx.compose.animation.core.tween(140),
        label = "knob",
    )
    Box(
        Modifier
            .size(width = 40.dp, height = 24.dp)
            .clip(CircleShape)
            .background(if (checked) Ethan.colors.accent else Ethan.colors.borderStrong)
            .toggleable(checked, role = Role.Switch, onValueChange = onChange),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.padding(start = offset).size(20.dp).clip(CircleShape).background(Ethan.colors.knob))
    }
}

enum class Tone { NEUTRAL, ACCENT, SUCCESS, WARNING, INFO, DANGER }

/** A pill that states a status. It rides its row; it never gets a line of its own. */
@Composable
fun Badge(text: String, tone: Tone = Tone.NEUTRAL, dot: Boolean = false, modifier: Modifier = Modifier) {
    val colors = Ethan.colors
    val (fill, ink, mark) = when (tone) {
        Tone.NEUTRAL -> Triple(colors.tintGreySoft, colors.textMuted, colors.borderStrong)
        Tone.ACCENT -> Triple(colors.accentQuiet, colors.onAccentSoft, colors.accent)
        Tone.SUCCESS -> Triple(colors.successSoft, colors.successText, colors.success)
        Tone.WARNING -> Triple(colors.warningSoft, colors.warningText, colors.warning)
        Tone.INFO -> Triple(colors.infoSoft, colors.infoText, colors.info)
        Tone.DANGER -> Triple(colors.dangerSoft, colors.textDanger, colors.danger)
    }
    Row(
        modifier.clip(CircleShape).background(fill).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(mark))
            Spacer(Modifier.width(5.dp))
        }
        Text(text, style = Ethan.type.caption, color = ink, maxLines = 1)
    }
}

/** A filter or a choice among several: selected is the soft accent fill with its ring. */
@Composable
fun Tag(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Ethan.colors
    Box(
        modifier
            .heightIn(min = 30.dp)
            .clip(CircleShape)
            .background(if (selected) colors.accentQuiet else colors.surfaceCard)
            .border(1.dp, if (selected) colors.accentRing else colors.borderDefault, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Ethan.type.bodySmall, color = if (selected) colors.onAccentSoft else colors.textBody, maxLines = 1)
    }
}

/** Touch pills on a sunken track; the selected one is filled with the accent. */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val colors = Ethan.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(EthanRadius.control + 3.dp))
            .background(colors.bgSunken)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { (value, label) ->
            val on = value == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(EthanRadius.control))
                    .background(if (on) colors.accent else Color.Transparent)
                    .clickable { onSelect(value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = Ethan.type.label, color = if (on) colors.onAccent else colors.textMuted, maxLines = 1)
            }
        }
    }
}

private val ButtonPadding = PaddingValues(horizontal = 16.dp)

/** The one filled accent action a view gets. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, @DrawableRes icon: Int? = null) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = TouchHeight),
        shape = RoundedCornerShape(EthanRadius.control),
        contentPadding = ButtonPadding,
        colors = ButtonDefaults.buttonColors(
            containerColor = Ethan.colors.accent, contentColor = Ethan.colors.onAccent,
            disabledContainerColor = Ethan.colors.accent.copy(alpha = 0.45f), disabledContentColor = Ethan.colors.onAccent,
        ),
    ) { ButtonLabel(text, icon, Ethan.colors.onAccent) }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, @DrawableRes icon: Int? = null) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = TouchHeight),
        shape = RoundedCornerShape(EthanRadius.control),
        contentPadding = ButtonPadding,
        border = BorderStroke(1.dp, Ethan.colors.borderDefault),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Ethan.colors.surfaceCard, contentColor = Ethan.colors.textHeading),
    ) { ButtonLabel(text, icon, Ethan.colors.textMuted) }
}

/** Destructive, and so deliberately not the brand colour. */
@Composable
fun DangerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, filled: Boolean = true, @DrawableRes icon: Int? = null) {
    val colors = Ethan.colors
    Button(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = TouchHeight),
        shape = RoundedCornerShape(EthanRadius.control),
        contentPadding = ButtonPadding,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (filled) colors.danger else colors.dangerSoft,
            contentColor = if (filled) colors.onDanger else colors.textDanger,
        ),
    ) { ButtonLabel(text, icon, if (filled) colors.onDanger else colors.textDanger) }
}

/** An inline action in the accent's text colour. */
@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = TouchHeight),
        shape = RoundedCornerShape(EthanRadius.control),
        colors = ButtonDefaults.textButtonColors(contentColor = Ethan.colors.textAccent),
    ) { Text(text, style = Ethan.type.label) }
}

@Composable
private fun ButtonLabel(text: String, @DrawableRes icon: Int?, iconTint: Color) {
    if (icon != null) {
        LucideIcon(icon, null, size = 16.dp, tint = iconTint)
        Spacer(Modifier.width(8.dp))
    }
    Text(text, style = Ethan.type.label, maxLines = 1)
}

/** One line, and at most one sentence under it. */
@Composable
fun EmptyState(
    line: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    @DrawableRes icon: Int? = null,
    busy: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Ethan.colors.accent)
            Spacer(Modifier.height(14.dp))
        } else if (icon != null) {
            LucideIcon(icon, null, size = 26.dp, tint = Ethan.colors.textFaint)
            Spacer(Modifier.height(12.dp))
        }
        Text(line, style = Ethan.type.title3, color = Ethan.colors.textHeading, textAlign = TextAlign.Center)
        if (detail != null) {
            Spacer(Modifier.height(4.dp))
            Text(detail, style = Ethan.type.bodySmall, color = Ethan.colors.textMuted, textAlign = TextAlign.Center)
        }
        if (action != null) {
            Spacer(Modifier.height(18.dp))
            action()
        }
    }
}

/** On a phone a decision comes up from the bottom edge; dialogs are not used. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Sheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Ethan.colors.surfaceRaised,
        contentColor = Ethan.colors.textBody,
        scrimColor = Ethan.colors.scrim,
        shape = RoundedCornerShape(topStart = EthanRadius.sheet, topEnd = EthanRadius.sheet),
        dragHandle = {
            Box(
                Modifier.padding(top = 10.dp, bottom = 6.dp).size(width = 34.dp, height = 4.dp)
                    .clip(CircleShape).background(Ethan.colors.borderStrong),
            )
        },
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = Gutter, end = Gutter, bottom = 20.dp),
            content = content,
        )
    }
}

@Composable
fun ConfirmSheet(
    title: String,
    body: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = true,
) {
    Sheet(onDismiss) {
        Text(title, style = Ethan.type.title2, color = Ethan.colors.textHeading)
        Spacer(Modifier.height(6.dp))
        Text(body, style = Ethan.type.body, color = Ethan.colors.textBody)
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Cancel", onDismiss, Modifier.weight(1f))
            if (danger) DangerButton(confirm, { onDismiss(); onConfirm() }, Modifier.weight(1f))
            else PrimaryButton(confirm, { onDismiss(); onConfirm() }, Modifier.weight(1f))
        }
    }
}

data class TabItem<T>(val value: T, val label: String, @DrawableRes val icon: Int)

/** Icon and label together: four destinations leave room for both. */
@Composable
fun <T> BottomTabs(items: List<TabItem<T>>, selected: T, onSelect: (T) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Ethan.colors.surfaceCard)) {
        Rule()
        Row(Modifier.fillMaxWidth().height(58.dp)) {
            items.forEach { item ->
                val on = item.value == selected
                Column(
                    Modifier.weight(1f).fillMaxSize().clickable(role = Role.Tab) { onSelect(item.value) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    LucideIcon(item.icon, null, size = 20.dp, tint = if (on) Ethan.colors.textAccent else Ethan.colors.textFaint)
                    Spacer(Modifier.height(3.dp))
                    Text(item.label, style = Ethan.type.caption, color = if (on) Ethan.colors.textAccent else Ethan.colors.textFaint)
                }
            }
        }
    }
}

/** A value with its unit and what it measures, set as data. */
@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label.uppercase(), style = Ethan.type.micro, color = Ethan.colors.textFaint, maxLines = 1)
        Text(value, style = Ethan.type.mono, color = Ethan.colors.textHeading, maxLines = 1)
    }
}

/** A device with access: who, its fingerprint, and the one thing you can do about it. */
@Composable
fun DeviceRow(label: String, self: Boolean, fingerprint: String, detail: String, onRevoke: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        LucideIcon(R.drawable.lucide_smartphone, null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = Ethan.type.bodyStrong, color = Ethan.colors.textHeading, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(fingerprint, style = Ethan.type.monoSmall, color = Ethan.colors.textMuted)
                if (self) {
                    Spacer(Modifier.width(8.dp))
                    Badge("This phone", Tone.ACCENT)
                }
            }
            Text(detail, style = Ethan.type.caption, color = Ethan.colors.textFaint)
        }
        TextButton(onClick = onRevoke, colors = ButtonDefaults.textButtonColors(contentColor = Ethan.colors.textDanger)) {
            Text("Revoke", style = Ethan.type.label)
        }
    }
}

/** A hairline track and a small knob: the stock slider is three times the weight of anything near it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EthanSlider(
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onChangeFinished: (() -> Unit)? = null,
) {
    val colors = Ethan.colors
    androidx.compose.material3.Slider(
        value = value,
        onValueChange = onChange,
        onValueChangeFinished = onChangeFinished,
        modifier = modifier.height(TouchHeight),
        thumb = { Box(Modifier.size(16.dp).clip(CircleShape).background(colors.accent)) },
        track = { state ->
            val fraction = ((state.value - state.valueRange.start) / (state.valueRange.endInclusive - state.valueRange.start)).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(colors.borderDefault)) {
                Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(colors.accent))
            }
        },
    )
}

@Composable
@androidx.compose.runtime.ReadOnlyComposable
fun isLandscape(): Boolean =
    androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

/** Hides the system bars for as long as it is in the composition; a swipe brings them back briefly. */
@Composable
fun FullScreen() {
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(view) {
        val window = (view.context as? android.app.Activity)?.window
        val controller = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars()) }
    }
}

/** One line, no title, on the raised surface of the page it belongs to. It clears itself. */
@Composable
fun Toast(message: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(EthanRadius.control)
    Text(
        message,
        style = Ethan.type.bodySmall,
        color = Ethan.colors.textHeading,
        modifier = modifier
            .padding(12.dp)
            .clip(shape)
            .background(Ethan.colors.surfaceRaised)
            .border(1.dp, Ethan.colors.borderDefault, shape)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    )
}
