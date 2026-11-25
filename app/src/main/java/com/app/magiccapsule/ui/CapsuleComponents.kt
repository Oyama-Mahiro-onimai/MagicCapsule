package com.app.magiccapsule.ui

import android.graphics.Color as AndroidColor
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

    // 外部切换编辑目标或点击预设色块时同步 HSV
    LaunchedEffect(hexValue) {
        val c = CapsuleConfig.parseColorSafe(hexValue, fallbackColor)
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(c, hsv)
        if (hsv[1] > 0.01f) hue = hsv[0]
        sat = hsv[1]
        value = hsv[2]
    }

    fun emitColor(h: Float, s: Float, v: Float) {
        val argb = AndroidColor.HSVToColor(255, floatArrayOf(h, s, v))
        onHexChange(CapsuleConfig.toArgbHex(argb))
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // 2D 饱和度 × 亮度调色板
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(168.dp)
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(168.dp)
                    .pointerInput(hue) {
                        detectTapGestures { offset ->
                            val newS = (offset.x / size.width).coerceIn(0f, 1f)
                            val newV = (1f - offset.y / size.height).coerceIn(0f, 1f)
                            sat = newS
                            value = newV
                            emitColor(hue, newS, newV)
                        }
                    }
                    .pointerInput(hue) {
                        detectDragGestures { change, _ ->
                            change.consume()
                            val newS = (change.position.x / size.width).coerceIn(0f, 1f)
                            val newV = (1f - change.position.y / size.height).coerceIn(0f, 1f)
                            sat = newS
                            value = newV
                            emitColor(hue, newS, newV)
                        }
                    }
            ) {
                val hueColor = Color.hsv(hue.coerceIn(0f, 360f), 1f, 1f)
                drawRect(
                    brush = Brush.horizontalGradient(listOf(Color.White, hueColor))
                )
                drawRect(
                    brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black))
                )
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
                    .background(Color(parsedInt))
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            )
            OutlinedTextField(
                value = hexValue,
                onValueChange = onHexChange,
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
                        .clickable { onHexChange(swatch) }
                )
            }
        }
    }
}

@Composable
fun LiveCardPreview(config: CapsuleConfig) {
    val cardBg = Color(config.resolveCardBgColor())
    val titleColor = Color(CapsuleConfig.parseColorSafe(config.titleColorHex, AndroidColor.parseColor("#FFECFDF5")))
    val detailColor = Color(CapsuleConfig.parseColorSafe(config.detailColorHex, AndroidColor.parseColor("#FF6EE7B7")))
    val btnBg = Color(CapsuleConfig.parseColorSafe(config.btnBgHex, AndroidColor.parseColor("#FF065F46")))
    val accentColor = Color(CapsuleConfig.parseColorSafe(config.accentColorHex, AndroidColor.parseColor("#FF34D399")))

    val title = "${config.businessName} · ${config.mainText}"
    val detail = if (config.showTimestamp) "${config.subText} · 19:30:00 (#1)" else config.subText

    // 模拟系统毛玻璃壁纸底衬，方便实时观察背景透明度与灰度效果
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        Color(0xFF9EA5AD),
                        Color(0xFFC5CCD3),
                        Color(0xFF8C949C)
                    )
                )
            )
            .padding(2.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            color = cardBg,
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalAlignment = if (config.cardLayoutStyle == CapsuleConfig.LAYOUT_HERO) {
                    Alignment.CenterHorizontally
                } else {
                    Alignment.Start
                }
            ) {
                if (config.showAccentBar) {
                    Box(
                        modifier = Modifier
                            .width(36.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(accentColor)
                    )
                    Spacer(Modifier.height(4.dp))
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (config.cardLayoutStyle == CapsuleConfig.LAYOUT_COMPACT_ROW) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 10.dp)
                            ) {
                                Text(
                                    text = title,
                                    color = titleColor,
                                    fontSize = config.titleSizeSp.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = detail,
                                    color = detailColor,
                                    fontSize = config.detailSizeSp.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (config.showActions) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(btnBg)
                                        .padding(horizontal = 14.dp, vertical = 7.dp)
                                ) { Text("刷新", color = accentColor, fontSize = config.btnSizeSp.sp) }
                                Spacer(Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(btnBg)
                                        .padding(horizontal = 14.dp, vertical = 7.dp)
                                ) { Text("停止", color = Color(0xFFFF6B6B), fontSize = config.btnSizeSp.sp) }
                            }
                        }
                    } else {
                        val align = if (config.cardLayoutStyle == CapsuleConfig.LAYOUT_HERO) TextAlign.Center else TextAlign.Start
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = if (config.cardLayoutStyle == CapsuleConfig.LAYOUT_HERO) {
                                Alignment.CenterHorizontally
                            } else {
                                Alignment.Start
                            }
                        ) {
                            Text(
                                text = title,
                                color = titleColor,
                                fontSize = config.titleSizeSp.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = align,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = detail,
                                color = detailColor,
                                fontSize = config.detailSizeSp.sp,
                                maxLines = if (config.cardLayoutStyle == CapsuleConfig.LAYOUT_MINIMAL) 2 else 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = align,
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (config.cardLayoutStyle != CapsuleConfig.LAYOUT_MINIMAL && config.showActions) {
                                Spacer(Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(btnBg)
                                            .padding(vertical = 3.dp),
                                        contentAlignment = Alignment.Center
                                    ) { Text("刷新", color = accentColor, fontSize = config.btnSizeSp.sp) }
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(btnBg)
                                            .padding(vertical = 3.dp),
                                        contentAlignment = Alignment.Center
                                    ) { Text("停止", color = Color(0xFFFF6B6B), fontSize = config.btnSizeSp.sp) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
