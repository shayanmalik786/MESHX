package com.meshx.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshx.app.core.UiState
import com.meshx.app.mesh.LinkScore
import com.meshx.app.model.BROADCAST_ID
import com.meshx.app.model.LinkState
import com.meshx.app.qr.QrCode
import com.meshx.app.ui.theme.Mesh
import com.meshx.app.ui.theme.MeshIcons
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------------- text helpers

fun clockLabel(time: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(time))

fun agoLabel(time: Long, now: Long): String {
    if (time <= 0) return "never"
    val seconds = ((now - time) / 1000).coerceAtLeast(0)
    return when {
        seconds < 10 -> "just now"
        seconds < 60 -> "$seconds s ago"
        seconds < 3600 -> "${seconds / 60} min ago"
        seconds < 86_400 -> "${seconds / 3600} h ago"
        else -> "${seconds / 86_400} d ago"
    }
}

fun uptimeLabel(seconds: Long): String = when {
    seconds < 60 -> "$seconds s"
    seconds < 3600 -> "${seconds / 60} min"
    seconds < 86_400 -> "${seconds / 3600} h ${seconds % 3600 / 60} min"
    else -> "${seconds / 86_400} d ${seconds % 86_400 / 3600} h"
}

fun bytesLabel(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f kB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}

fun initials(name: String): String {
    val words = name.trim().split(' ').filter { it.isNotEmpty() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(2).uppercase()
        else -> (words[0].take(1) + words[1].take(1)).uppercase()
    }
}

fun hopsLabel(hops: Int): String = if (hops == 1) "1 hop" else "$hops hops"

// ---------------------------------------------------------------------- building blocks

@Composable
fun Card(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Mesh.colors
    val shape = RoundedCornerShape(16.dp)
    var m = modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.outline, shape)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Column(m.padding(padding), content = content)
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    danger: Boolean = false,
) {
    val c = Mesh.colors
    val fill = when {
        !enabled -> c.outline
        danger -> c.danger
        else -> c.primary
    }
    val ink = if (enabled) (if (danger) Color.White else c.onPrimary) else c.muted
    Row(
        modifier.clip(RoundedCornerShape(14.dp)).background(fill).clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = ink, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** A quieter button, for the second most important action. */
@Composable
fun SoftButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    danger: Boolean = false,
) {
    val c = Mesh.colors
    val ink = when {
        !enabled -> c.muted
        danger -> c.danger
        else -> c.primaryDeep
    }
    Row(
        modifier.clip(RoundedCornerShape(14.dp)).background(if (danger) c.surfaceAlt else c.primarySoft)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = ink, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun IconAction(icon: ImageVector, description: String, onClick: () -> Unit, tint: Color = Mesh.colors.ink) {
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
fun Chip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val c = Mesh.colors
    val shape = RoundedCornerShape(50)
    var m = modifier.clip(shape).background(if (selected) c.primary else c.surface)
        .border(1.dp, if (selected) c.primary else c.outline, shape)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Text(
        text, color = if (selected) c.onPrimary else c.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1,
        modifier = m.padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** A small coloured label, for example "3 hops" or "offline". */
@Composable
fun Tag(text: String, color: Color = Mesh.colors.primaryDeep, background: Color = Mesh.colors.primarySoft) {
    Text(
        text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(background).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

private val avatarColors = listOf(0xFF0F9D8A, 0xFF3B7DD8, 0xFF8A5BD6, 0xFFD9730D, 0xFFC2477B, 0xFF4C8C2B)

/** A round badge with initials. online = null hides the status dot. */
@Composable
fun Avatar(name: String, id: Int, size: Dp = 44.dp, online: Boolean? = null) {
    val c = Mesh.colors
    Box(Modifier.size(size)) {
        Box(
            Modifier.size(size).clip(CircleShape).background(Color(avatarColors[(id % avatarColors.size + avatarColors.size) % avatarColors.size])),
            contentAlignment = Alignment.Center,
        ) {
            if (id == BROADCAST_ID) {
                Icon(MeshIcons.Megaphone, null, tint = Color.White, modifier = Modifier.size(size * 0.48f))
            } else {
                Text(initials(name), color = Color.White, fontSize = (size.value * 0.36f).sp, fontWeight = FontWeight.SemiBold)
            }
        }
        if (online != null) {
            Box(
                Modifier.align(Alignment.BottomEnd).size(size * 0.3f).clip(CircleShape).background(c.surface).padding(2.dp)
                    .clip(CircleShape).background(if (online) c.success else c.muted),
            )
        }
    }
}

/** Four bars, like the signal indicator of a phone, coloured by the link score. */
@Composable
fun SignalBars(score: Int, modifier: Modifier = Modifier) {
    val c = Mesh.colors
    val lit = LinkScore.bars(score)
    val on = c.score(score)
    val off = c.outline
    Canvas(modifier.size(width = 22.dp, height = 16.dp)) {
        val gap = size.width * 0.1f
        val barWidth = (size.width - gap * 3) / 4
        for (i in 0 until 4) {
            val barHeight = size.height * (0.4f + 0.2f * i)
            drawRoundRect(
                color = if (i < lit) on else off,
                topLeft = Offset(i * (barWidth + gap), size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 3),
            )
        }
    }
}

@Composable
fun TopBar(
    title: String,
    subtitle: String = "",
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = Mesh.colors
    Row(
        Modifier.fillMaxWidth().height(60.dp).padding(start = if (onBack == null) 16.dp else 4.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) IconAction(MeshIcons.Back, "Back", onBack)
        Column(Modifier.weight(1f).padding(start = if (onBack == null) 0.dp else 4.dp)) {
            Text(
                title, color = c.ink, fontSize = if (onBack == null) 24.sp else 18.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotEmpty()) Text(subtitle, color = c.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}

/** Shows the connection at a glance. Sits in the top bar of every tab. */
@Composable
fun LinkPill(state: UiState, onClick: () -> Unit) {
    val c = Mesh.colors
    val (dot, text) = when (state.link.state) {
        LinkState.Connected -> c.success to "Node ${state.myId} · ${state.link.spec?.transport?.label ?: ""}"
        LinkState.Connecting -> c.warning to "Connecting"
        LinkState.Lost -> c.warning to "Reconnecting"
        LinkState.Idle -> c.danger to "Not connected"
    }
    Row(
        Modifier.padding(end = 10.dp).clip(RoundedCornerShape(50)).background(c.surface).border(1.dp, c.outline, RoundedCornerShape(50))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(7.dp))
        Text(text, color = c.ink, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    number: Boolean = false,
    password: Boolean = false,
    error: String = "",
    onDone: (() -> Unit)? = null,
) {
    val c = Mesh.colors
    var reveal by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Column(modifier) {
        if (label.isNotEmpty()) {
            Text(label, color = c.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 6.dp))
        }
        Row(
            Modifier.fillMaxWidth().clip(shape).background(c.surfaceAlt).border(1.dp, if (error.isNotEmpty()) c.danger else c.outline, shape)
                .padding(start = 14.dp, end = if (password) 2.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).padding(vertical = 13.dp)) {
                if (value.isEmpty()) Text(placeholder, color = c.muted, fontSize = 15.sp, maxLines = 1)
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = TextStyle(color = c.ink, fontSize = 15.sp),
                    cursorBrush = SolidColor(c.primary),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = when {
                            number -> KeyboardType.Number
                            password -> KeyboardType.Password
                            else -> KeyboardType.Text
                        },
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
                    visualTransformation = if (password && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
                )
            }
            if (password) {
                IconAction(if (reveal) MeshIcons.EyeOff else MeshIcons.Eye, if (reveal) "Hide" else "Show", { reveal = !reveal }, c.muted)
            }
        }
        if (error.isNotEmpty()) Text(error, color = c.danger, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
fun ProgressBar(progress: Float, modifier: Modifier = Modifier, color: Color = Mesh.colors.primary) {
    val track = Mesh.colors.outline
    Canvas(modifier.fillMaxWidth().height(8.dp)) {
        val radius = CornerRadius(size.height / 2)
        drawRoundRect(track, cornerRadius = radius)
        val width = size.width * progress.coerceIn(0f, 1f)
        if (width > 0f) drawRoundRect(color, size = Size(width.coerceAtLeast(size.height), size.height), cornerRadius = radius)
    }
}

/** A small line chart of values from 0 to 100, oldest on the left. */
@Composable
fun Sparkline(values: List<Int>, modifier: Modifier = Modifier, color: Color = Mesh.colors.primary) {
    val grid = Mesh.colors.outline
    Canvas(modifier.fillMaxWidth().height(56.dp)) {
        for (level in listOf(0f, 0.5f, 1f)) {
            val y = size.height * (1f - level)
            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
        if (values.size >= 2) {
            val step = size.width / (values.size - 1)
            val path = Path()
            values.forEachIndexed { i, value ->
                val x = i * step
                val y = size.height * (1f - value.coerceIn(0, 100) / 100f)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), color = Mesh.colors.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(start = 4.dp, top = 6.dp),
    )
}

/** One line in a settings card: icon, title, value and something on the right. */
@Composable
fun SettingRow(
    icon: ImageVector,
    title: String,
    value: String = "",
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val c = Mesh.colors
    var m = Modifier.fillMaxWidth()
    if (onClick != null) m = m.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)
    Row(m.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = c.primaryDeep, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (value.isNotEmpty()) Text(value, color = c.muted, fontSize = 12.sp)
        }
        if (trailing != null) {
            trailing()
        } else if (onClick != null) {
            Icon(MeshIcons.ChevronRight, null, tint = c.muted, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
fun MeshSwitch(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val c = Mesh.colors
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White, checkedTrackColor = c.primary,
            uncheckedThumbColor = c.muted, uncheckedTrackColor = c.surfaceAlt, uncheckedBorderColor = c.outline,
        ),
    )
}

@Composable
fun EmptyState(icon: ImageVector, title: String, text: String, button: String? = null, onClick: (() -> Unit)? = null) {
    val c = Mesh.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(c.primarySoft), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = c.primaryDeep, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(title, color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(text, color = c.muted, fontSize = 14.sp, textAlign = TextAlign.Center)
        if (button != null && onClick != null) {
            Spacer(Modifier.height(16.dp))
            PrimaryButton(button, onClick)
        }
    }
}

/** Round button that floats over a list. */
@Composable
fun Fab(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Mesh.colors
    Row(
        modifier.padding(16.dp).clip(RoundedCornerShape(18.dp)).background(c.primary).clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = c.onPrimary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = c.onPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Draws a QR code. It is always black on white so that every scanner can read it. */
@Composable
fun QrImage(payload: String, modifier: Modifier = Modifier) {
    val matrix = remember(payload) { QrCode.encode(payload) }
    Box(modifier.aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(16.dp)) {
        if (matrix == null) {
            Text("This text is too long for a QR code", color = Color.Black, modifier = Modifier.align(Alignment.Center))
        } else {
            Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
                val cell = size.width / matrix.size
                for (y in 0 until matrix.size) {
                    for (x in 0 until matrix.size) {
                        if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
                    }
                }
            }
        }
    }
}
