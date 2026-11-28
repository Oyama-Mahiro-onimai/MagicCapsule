package com.app.magiccapsule.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.app.magiccapsule.R
import com.app.magiccapsule.model.CapsuleConfig
import com.app.magiccapsule.service.LrcCapsuleLyricEngine

/**
 * 弹性灵动胶囊（迷你胶囊 Pill）Compose 布局：
 * - 使用 animateContentSize() 根据当前歌词/文案长度平滑伸缩宽度
 * - 严格限制最大宽度 (186.dp) 与单行省略，避免极端长文本覆盖状态栏左右图标
 */
@Composable
fun ElasticCapsulePill(
    title: String,
    lyricOrSubText: String,
    accentColor: Color,
    lyricTextSizePx: Float = 30f,
    containerColor: Color = Color(0xEE11181C),
    contentColor: Color = Color.White,
    applyStatusBarPadding: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val displayText = remember(title, lyricOrSubText) {
        val raw = lyricOrSubText.ifBlank { title }
        raw.replace('\n', ' ').replace('\r', ' ').replace(Regex("\\s+"), " ").trim()
    }
    val density = LocalDensity.current
    val textSp = with(density) { lyricTextSizePx.coerceIn(16f, 56f).toSp() }
    val baseModifier = if (applyStatusBarPadding) {
        modifier.statusBarsPadding()
    } else {
        modifier
    }

    Surface(
        shape = RoundedCornerShape(999.dp),
        color = containerColor,
        shadowElevation = 6.dp,
        modifier = baseModifier
            .widthIn(min = 88.dp)
            .border(1.dp, accentColor.copy(alpha = 0.35f), RoundedCornerShape(999.dp))
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
            .let { m -> if (onClick != null) m.clickable(onClick = onClick) else m }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_px_play),
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(11.dp)
                )
            }
            Text(
                text = displayText,
                color = contentColor,
                fontSize = textSp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 弹性悬浮横幅（Banner）Compose 布局：
 * - 使用 animateContentSize() 根据多行歌词长度自动弹性伸缩高度
 * - 通过 LrcCapsuleLyricEngine.formatLyricForDisplay() 将超过 30 字的歌词按标点/词边界智能换行（最多 3 行）
 * - 内置 statusBarsPadding() 安全避让与 heightIn(max = 216.dp) 高度上限，即使 100 字极端长句也不会遮挡状态栏
 */
@Composable
fun ElasticFloatingBanner(
    config: CapsuleConfig,
    title: String,
    lyricOrDetail: String,
    applyStatusBarPadding: Boolean = true,
    showActions: Boolean = config.showActions,
    modifier: Modifier = Modifier,
    onPrimaryAction: (() -> Unit)? = null,
    onSecondaryAction: (() -> Unit)? = null
) {
    val bgColor = Color(config.resolveCardBgColor())
    val titleColor = Color(CapsuleConfig.parseColorSafe(config.titleColorHex, android.graphics.Color.WHITE))
    val detailColor = Color(CapsuleConfig.parseColorSafe(config.detailColorHex, 0xFFB8D8CC.toInt()))
    val accentColor = Color(CapsuleConfig.parseColorSafe(config.accentColorHex, 0xFF34D399.toInt()))
    val btnBg = Color(CapsuleConfig.parseColorSafe(config.btnBgHex, 0xFF065F46.toInt()))

    val density = LocalDensity.current
    val bannerLyricSp = with(density) { config.bannerLyricTextSizePx.coerceIn(20f, 72f).toSp() }
    val bannerLineHeightSp = with(density) { (config.bannerLyricTextSizePx.coerceIn(20f, 72f) + 10f).toSp() }
    val formattedLyric = remember(lyricOrDetail) {
        LrcCapsuleLyricEngine.formatLyricForDisplay(
            rawLine = lyricOrDetail,
            maxCharsPerLine = 30,
            maxLines = 3
        )
    }

    val safeContainerModifier = if (applyStatusBarPadding) {
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(top = 4.dp)
    } else {
        modifier.fillMaxWidth()
    }

    Box(
        modifier = safeContainerModifier
            .heightIn(min = 68.dp, max = 216.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(bgColor)
            .border(1.dp, accentColor.copy(alpha = 0.28f), RoundedCornerShape(22.dp))
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
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
                Spacer(Modifier.height(6.dp))
            }

            val textAlign = if (config.cardLayoutStyle == CapsuleConfig.LAYOUT_HERO) {
                TextAlign.Center
            } else {
                TextAlign.Start
            }

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
                            text = formattedLyric,
                            color = detailColor,
                            fontSize = bannerLyricSp,
                            lineHeight = bannerLineHeightSp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (showActions) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(btnBg)
                                    .let { m -> if (onPrimaryAction != null) m.clickable { onPrimaryAction() } else m }
                                    .padding(horizontal = 14.dp, vertical = 7.dp)
                            ) { Text("刷新", color = accentColor, fontSize = config.btnSizeSp.sp) }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(btnBg)
                                    .let { m -> if (onSecondaryAction != null) m.clickable { onSecondaryAction() } else m }
                                    .padding(horizontal = 14.dp, vertical = 7.dp)
                            ) { Text("停止", color = Color(0xFFFF6B6B), fontSize = config.btnSizeSp.sp) }
                        }
                    }
                }
            } else {
                Text(
                    text = title,
                    color = titleColor,
                    fontSize = config.titleSizeSp.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = textAlign,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = formattedLyric,
                    color = detailColor,
                    fontSize = bannerLyricSp,
                    lineHeight = bannerLineHeightSp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = textAlign,
                    modifier = Modifier.fillMaxWidth()
                )
                if (config.cardLayoutStyle != CapsuleConfig.LAYOUT_MINIMAL && showActions) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(btnBg)
                                .let { m -> if (onPrimaryAction != null) m.clickable { onPrimaryAction() } else m }
                                .padding(vertical = 5.dp),
                            contentAlignment = Alignment.Center
                        ) { Text("刷新", color = accentColor, fontSize = config.btnSizeSp.sp) }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(btnBg)
                                .let { m -> if (onSecondaryAction != null) m.clickable { onSecondaryAction() } else m }
                                .padding(vertical = 5.dp),
                            contentAlignment = Alignment.Center
                        ) { Text("停止", color = Color(0xFFFF6B6B), fontSize = config.btnSizeSp.sp) }
                    }
                }
            }
        }
    }
}
