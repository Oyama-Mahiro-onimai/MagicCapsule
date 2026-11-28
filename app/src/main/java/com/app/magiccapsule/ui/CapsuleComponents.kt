package com.app.magiccapsule.ui

import android.graphics.Color as AndroidColor
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.app.magiccapsule.R
import com.app.magiccapsule.model.CapsuleConfig
import com.app.magiccapsule.service.LrcCapsuleLyricEngine
import kotlin.math.roundToInt

/**
 * Google Pixel Material You 风格圆形图标徽章：
 * 柔和动态色调底盘 + Material Symbols Rounded 单色圆角矢量图标
 */
@Composable
fun PixelIconBadge(
    @DrawableRes iconRes: Int,
    contentDescription: String? = null,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    badgeSizeDp: Int = 40,
    iconSizeDp: Int = 20
) {
    Box(
        modifier = Modifier
            .size(badgeSizeDp.dp)
            .clip(CircleShape)
            .background(containerColor),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = contentDescription,
            tint = contentColor,
            modifier = Modifier.size(iconSizeDp.dp)
        )
    }
}

@Composable
fun StatusIconRow(
    @DrawableRes iconRes: Int,
    text: String,
    tint: Color = MaterialTheme.colorScheme.primary,
    textColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = textColor,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun SectionEntryCard(
    @DrawableRes iconRes: Int,
    title: String,
    subtitle: String,
    badge: String? = null,
    iconContainerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    iconContentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PixelIconBadge(
                iconRes = iconRes,
                contentDescription = title,
                containerColor = iconContainerColor,
                contentColor = iconContentColor
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (!badge.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            text = badge,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
                Icon(
                    painter = painterResource(id = R.drawable.ic_px_chevron_right),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun SpSizeControl(
    label: String,
    valueSp: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    val intVal = valueSp.roundToInt()
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("$label：$intVal sp", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = { onChange((intVal - 1f).coerceIn(range.start, range.endInclusive)) }
                ) { Text("-1sp") }
                OutlinedButton(
                    onClick = { onChange((intVal + 1f).coerceIn(range.start, range.endInclusive)) }
                ) { Text("+1sp") }
            }
        }
        Slider(
            value = valueSp,
            onValueChange = { onChange(it.roundToInt().toFloat()) },
            valueRange = range
        )
    }
}

@Composable
fun PxSizeControl(
    label: String,
    valuePx: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    val intVal = valuePx.roundToInt()
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("$label：$intVal px", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = { onChange((intVal - 2f).coerceIn(range.start, range.endInclusive)) }
                ) { Text("-2px") }
                OutlinedButton(
                    onClick = { onChange((intVal + 2f).coerceIn(range.start, range.endInclusive)) }
                ) { Text("+2px") }
            }
        }
        Slider(
            value = valuePx,
            onValueChange = { onChange(it.roundToInt().toFloat()) },
            valueRange = range
        )
    }
}

/**
 * 交互式 2D HSV 调色板 + 色相/饱和度/亮度滑块 + 快速色块 + #AARRGGBB 输入
 */
@Composable
fun InteractiveColorBoard(
    label: String,
    hexValue: String,
    fallbackColor: Int,
    onHexChange: (String) -> Unit
) {
    val parsedInt = CapsuleConfig.parseColorSafe(hexValue, fallbackColor)
    val initialHsv = remember(parsedInt) {
        FloatArray(3).also { AndroidColor.colorToHSV(parsedInt, it) }
    }
    var hue by remember { mutableFloatStateOf(initialHsv[0]) }
    var sat by remember { mutableFloatStateOf(initialHsv[1]) }
    var value by remember { mutableFloatStateOf(initialHsv[2]) }
    // 记录本组件刚发出的色值，避免拖动时 LaunchedEffect 反向把 8-bit RGB 量化回 HSV 导致跳变/卡顿
    var lastEmittedHex by remember { mutableStateOf<String?>(null) }
    val currentOnHexChange by rememberUpdatedState(onHexChange)

    // 仅当外部切换调色目标、点击快速色块或手动输入色号时同步 HSV
    LaunchedEffect(hexValue, label) {
        if (hexValue.equals(lastEmittedHex, ignoreCase = true)) return@LaunchedEffect
        val c = CapsuleConfig.parseColorSafe(hexValue, fallbackColor)
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(c, hsv)
        if (hsv[1] > 0.01f && hsv[2] > 0.01f) hue = hsv[0]
        sat = hsv[1]
        value = hsv[2]
    }

    fun emitColor(h: Float, s: Float, v: Float) {
        val ch = h.coerceIn(0f, 360f)
        val cs = s.coerceIn(0f, 1f)
        val cv = v.coerceIn(0f, 1f)
        val argb = AndroidColor.HSVToColor(255, floatArrayOf(ch, cs, cv))
        val hex = CapsuleConfig.toArgbHex(argb)
        lastEmittedHex = hex
        currentOnHexChange(hex)
    }

    val livePreviewColor = Color.hsv(
        hue.coerceIn(0f, 360f),
        sat.coerceIn(0f, 1f),
        value.coerceIn(0f, 1f)
    )

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // 2D 饱和度 × 亮度调色板（单 pointerInput(Unit) + awaitEachGesture，防止父列表抢手势与中途重启）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(168.dp)
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
        ) {
            // GPU 着色器缓存分层：渐变 Shader 仅在 hue 变化时通过 drawWithCache 编译缓存，拖动游标时零 Shader 重建
            val cachedHueColor = Color.hsv(hue.coerceIn(0f, 360f), 1f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(168.dp)
                    .drawWithCache {
                        val satBrush = Brush.horizontalGradient(listOf(Color.White, cachedHueColor))
                        val valBrush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black))
                        onDrawBehind {
                            drawRect(brush = satBrush)
                            drawRect(brush = valBrush)
                        }
                    }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            if (size.width > 0 && size.height > 0) {
                                val newS = (down.position.x / size.width).coerceIn(0f, 1f)
                                val newV = (1f - down.position.y / size.height).coerceIn(0f, 1f)
                                sat = newS
                                value = newV
                                emitColor(hue, newS, newV)
                            }
                            drag(down.id) { change ->
                                change.consume()
                                if (size.width > 0 && size.height > 0) {
                                    val newS = (change.position.x / size.width).coerceIn(0f, 1f)
                                    val newV = (1f - change.position.y / size.height).coerceIn(0f, 1f)
                                    sat = newS
                                    value = newV
                                    emitColor(hue, newS, newV)
                                }
                            }
                        }
                    }
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(168.dp)
                ) {
                    val cx = sat.coerceIn(0f, 1f) * size.width
                    val cy = (1f - value.coerceIn(0f, 1f)) * size.height
                    drawCircle(
                        color = Color.Black.copy(alpha = 0.45f),
                        radius = 11.dp.toPx(),
                        center = Offset(cx, cy),
                        style = Stroke(width = 3.dp.toPx())
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 9.dp.toPx(),
                        center = Offset(cx, cy),
                        style = Stroke(width = 2.5.dp.toPx())
                    )
                }
            }
        }

        // 彩虹色相滑块
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "色相 (H)：${hue.roundToInt()}°",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    Color.Red,
                                    Color.Yellow,
                                    Color.Green,
                                    Color.Cyan,
                                    Color.Blue,
                                    Color.Magenta,
                                    Color.Red
                                )
                            )
                        )
                )
                Slider(
                    value = hue,
                    onValueChange = {
                        hue = it
                        emitColor(it, sat, value)
                    },
                    valueRange = 0f..360f,
                    colors = SliderDefaults.colors(
                        activeTrackColor = Color.Transparent,
                        inactiveTrackColor = Color.Transparent,
                        activeTickColor = Color.Transparent,
                        inactiveTickColor = Color.Transparent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // 饱和度 & 明度滑块
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "饱和度：${(sat * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = sat,
                    onValueChange = {
                        sat = it
                        emitColor(hue, it, value)
                    },
                    valueRange = 0f..1f
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "亮度：${(value * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = value,
                    onValueChange = {
                        value = it
                        emitColor(hue, sat, it)
                    },
                    valueRange = 0f..1f
                )
            }
        }

        // 色号输入与预览圆点
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(livePreviewColor)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            )
            OutlinedTextField(
                value = hexValue,
                onValueChange = {
                    lastEmittedHex = null
                    currentOnHexChange(it)
                },
                label = { Text("$label (#AARRGGBB)") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }

        // 快速色块
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CapsuleConfig.QUICK_SWATCHES.forEach { swatch ->
                val swatchColor = Color(AndroidColor.parseColor(swatch))
                val isCurrent = hexValue.equals(swatch, ignoreCase = true)
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(swatchColor)
                        .border(
                            width = if (isCurrent) 2.5.dp else 1.dp,
                            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            shape = CircleShape
                        )
                        .clickable {
                            lastEmittedHex = null
                            currentOnHexChange(swatch)
                        }
                )
            }
        }
    }
}

@Composable
fun LiveCardPreview(config: CapsuleConfig) {
    val liveLyricEvent by LrcCapsuleLyricEngine.liveLyric.collectAsState()
    val accentColor = Color(CapsuleConfig.parseColorSafe(config.accentColorHex, 0xFF34D399.toInt()))
    val title = config.mainText.ifBlank { "主文案" }
    val detail = liveLyricEvent.line.ifBlank { config.subText.ifBlank { "副文案" } }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ElasticCapsulePill(
            title = title,
            lyricOrSubText = detail,
            accentColor = accentColor,
            lyricTextSizePx = config.capsuleLyricTextSizePx,
            applyStatusBarPadding = false
        )
        ElasticFloatingBanner(
            config = config,
            title = title,
            lyricOrDetail = detail,
            applyStatusBarPadding = false
        )
    }
}
