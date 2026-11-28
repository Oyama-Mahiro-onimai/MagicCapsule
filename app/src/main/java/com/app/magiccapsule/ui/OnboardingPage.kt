package com.app.magiccapsule.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.app.magiccapsule.R
import com.app.magiccapsule.model.CapsuleConfig
import kotlinx.coroutines.delay

/**
 * 方案 A：冷启动品牌开屏页（平滑淡入缩放，约 800ms 后自动过渡进入引导页或主页）
 */
@Composable
fun LaunchSplashOverlay(
    isFrameworkSupported: Boolean,
    onFinished: () -> Unit
) {
    val alphaAnim = remember { Animatable(0f) }
    val scaleAnim = remember { Animatable(0.92f) }

    LaunchedEffect(Unit) {
        alphaAnim.animateTo(1f, animationSpec = tween(280, easing = FastOutSlowInEasing))
        scaleAnim.animateTo(1f, animationSpec = tween(220, easing = FastOutSlowInEasing))
        delay(550L)
        alphaAnim.animateTo(0f, animationSpec = tween(220, easing = FastOutSlowInEasing))
        onFinished()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .alpha(alphaAnim.value)
                .scale(scaleAnim.value),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(horizontal = 32.dp)
            ) {
                PixelIconBadge(
                    iconRes = R.drawable.ic_px_home_filled,
                    badgeSizeDp = 76,
                    iconSizeDp = 40,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "灵动胶囊助手",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (isFrameworkSupported) {
                        "荣耀原生灵动胶囊 · 多协议开源工具"
                    } else {
                        "多品牌灵动胶囊与顶层横幅 · 本地优先"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = "开源仓库 · Oyama-Mahiro-onimai/MagicCapsule",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

/**
 * 完整《用户协议》与《隐私政策及信息收集说明》阅读弹窗（开屏页与设置页共用）
 */
@Composable
fun LegalPolicyDialog(
    initialTab: Int = 0,
    onOpenGithub: () -> Unit,
    onDismiss: () -> Unit
) {
    var activeTab by remember(initialTab) { mutableIntStateOf(initialTab.coerceIn(0, 1)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (activeTab == 0) "用户协议" else "隐私政策与信息收集说明",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = activeTab == 0,
                        onClick = { activeTab = 0 },
                        label = { Text("用户协议") }
                    )
                    FilterChip(
                        selected = activeTab == 1,
                        onClick = { activeTab = 1 },
                        label = { Text("隐私与信息说明") }
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (activeTab == 0) {
                    Text(
                        text = "更新版本：v${CapsuleConfig.CURRENT_POLICY_VERSION}（2026-11）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "1. 软件性质与开源透明\n" +
                            "「灵动胶囊助手」（MagicCapsule）是一款面向 Android 12–15 设备的本地通知镜像、媒体控制与导航辅助工具。项目完整源码公开于 GitHub（${CapsuleConfig.GITHUB_REPO_URL}），供用户自由查阅与审计。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "2. 多品牌系统接口说明\n" +
                            "本应用依据设备环境自动适配荣耀 MagicOS 原生灵动胶囊、vivo OriginOS SuperX、小米 HyperOS 焦点通知及通用悬浮横幅。由于各厂商系统更新可能调整私有接口，部分增强特性不保证在所有系统版本永久有效。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "3. 导航安全与第三方 Key 免责\n" +
                            "胶囊导航及 360° 罗盘朝向指示仅作为辅助提示工具。驾车、骑行、步行时请严格遵守交通法规并以实际路况为准，切勿在行驶中分心操作手机。使用高德、腾讯、百度地图接口时，API Key 由用户自行申请并仅保存在本机，相关配额与服务条款遵循对应地图服务商规定。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "4. 权限控制与撤回\n" +
                            "所有权限均遵循按需申请原则，您可随时在系统设置中撤回任意权限，或卸载本应用彻底清除全部本地配置与缓存。",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Text(
                        text = "核心承诺：无自建服务器 · 无账号体系 · 无广告与统计 SDK · 源码完全开源可查",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "以下逐项说明本应用为何需要收集或读取各类信息、以及数据的存放与销毁方式：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "1. 通知内容、媒体信息与应用列表（通知使用权 / QUERY_ALL_PACKAGES）\n" +
                            "• 为何收集：用于读取其他应用发出的通知标题、正文、图标、专辑封面、播放进度与操作按钮，并将其渲染到灵动胶囊/顶层横幅；同时用于生成应用白名单。\n" +
                            "• 处理方式：100% 仅在设备本地内存中实时处理，绝不持久化上传，也不向任何第三方共享。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "2. 精确/粗略位置与指南针传感器（ACCESS_FINE_LOCATION / 旋转矢量与地磁传感器）\n" +
                            "• 为何收集：仅在您主动开启「胶囊导航」时，用于获取起点坐标、计算 360° 实时正确朝向（每 1° 更新指示点）以及与本地路线比对剩余距离和转向。\n" +
                            "• 处理方式：路线结果暂存于本机 cacheDir/nav_active_route.json 以支持弱网/离线导航；到达终点或点击结束导航时立即删除缓存文件；息屏瞬间自动注销 GPS 与指南针监听。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "3. 按需联网请求（INTERNET / ACCESS_NETWORK_STATE）\n" +
                            "• 为何联网：仅在您主动使用以下两项功能时直连外部公开接口，本应用自身不运营任何云端服务器：\n" +
                            "  ① 开启「LRC 实时歌词」时，仅发送当前歌曲名/歌手/时长向公开词库（lyrics.kugou.com 或 lrclib.net）查询 LRC 时间轴歌词，结果缓存于本地；\n" +
                            "  ② 启动「胶囊导航」时，仅发送起终点坐标向您选定的地图服务（高德 / 腾讯 / 百度 / OSRM）拉取一次路线。\n" +
                            "• 不上传 IMEI、OAID、手机号、通讯录或用户画像。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "4. 发送通知、悬浮窗与无障碍服务（POST_NOTIFICATIONS / SYSTEM_ALERT_WINDOW / 无障碍）\n" +
                            "• 为何申请：分别用于向状态栏发送胶囊通知，以及在系统界面顶层绘制通用迷你胶囊/自定义横幅。\n" +
                            "• 零读屏承诺：无障碍服务已在配置中显式声明 canRetrieveWindowContent=\"false\"，系统层面禁止读取屏幕文字、输入框内容或密码。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "5. Shizuku 增强与忽略电池优化（可选）\n" +
                            "• 为何申请：Shizuku 仅在您手动授权后用于写入 WRITE_SECURE_SETTINGS（屏蔽重复系统横幅）、只读查询温控/Doze 状态或发送酷狗锁词广播；电池白名单仅用于防止后台通知监听被系统强杀。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("我知道了")
            }
        },
        dismissButton = {
            TextButton(onClick = onOpenGithub) {
                Text("查看 GitHub 源码")
            }
        }
    )
}

@Composable
fun OnboardingPage(
    hasPostNotifications: Boolean,
    hasNotificationAccess: Boolean,
    hasLocationPermission: Boolean,
    hasOverlayPermission: Boolean,
    hasAccessibilityEnabled: Boolean,
    hasWriteSecureSettings: Boolean,
    shizukuGranted: Boolean,
    isIgnoringBatteryOpt: Boolean,
    isFrameworkSupported: Boolean,
    showLaunchSplash: Boolean,
    onSelectLaunchSplash: (Boolean) -> Unit,
    onRequestPostNotifications: () -> Unit,
    onOpenNotifAccess: () -> Unit,
    onRequestLocationPermission: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onRequestShizuku: () -> Unit,
    onRequestBatteryOpt: () -> Unit,
    onOpenGithub: () -> Unit,
    onRefreshStatus: () -> Unit,
    onComplete: () -> Unit
) {
    var agreedNotice by remember { mutableStateOf(false) }
    var dialogTab by remember { mutableStateOf<Int?>(null) }

    dialogTab?.let { tab ->
        LegalPolicyDialog(
            initialTab = tab,
            onOpenGithub = onOpenGithub,
            onDismiss = { dialogTab = null }
        )
    }

    Column(
        modifier = Modifier
            .widthIn(max = 600.dp)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 品牌欢迎头部
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PixelIconBadge(
                iconRes = R.drawable.ic_px_home_filled,
                badgeSizeDp = 50,
                iconSizeDp = 26,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "欢迎使用 灵动胶囊助手",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (isFrameworkSupported) {
                        "已检测到荣耀 framework-magic 接口 · 开始前请了解权限与信息用途"
                    } else {
                        "多品牌灵动胶囊与顶层横幅工具 · 开始前请了解权限与信息用途"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 信息收集与隐私说明卡片
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
            )
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StatusIconRow(
                    iconRes = R.drawable.ic_px_shield,
                    text = "为何收集/读取信息与隐私承诺（公开开源）",
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "• 本地渲染优先：通知正文、应用列表、专辑封面与系统状态仅在本机内存中实时渲染，无自建云端服务器、无广告统计 SDK、不收集用户画像。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "• 定位与罗盘按需使用：仅在主动开启「胶囊导航」时读取 GPS 与指南针计算 360° 朝向和路线；息屏即注销传感器，到达或结束立即删除本地路线缓存。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "• 按需直连外部接口：仅在开启 LRC 歌词（查询公开词库）或发起胶囊导航（请求您选定的地图路线）时联网，绝不附带个人身份标识。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "• 无障碍零读屏：已显式声明 canRetrieveWindowContent=false，绝不读取屏幕文字或密码。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { dialogTab = 0 },
                        modifier = Modifier.weight(1f)
                    ) { Text("查看用户协议") }
                    OutlinedButton(
                        onClick = { dialogTab = 1 },
                        modifier = Modifier.weight(1f)
                    ) { Text("查看隐私政策") }
                }
            }
        }

        // 以后冷启动开屏显示模式选择（首次默认方案 A，用户可自由切换 A / B）
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusIconRow(
                    iconRes = R.drawable.ic_px_tune,
                    text = "以后冷启动时的开屏显示方式（可随时在设置中修改）",
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "首次打开已默认展示品牌开屏（方案 A），你可以决定以后每次打开应用是否保留开屏过渡：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = showLaunchSplash,
                        onClick = { onSelectLaunchSplash(true) },
                        label = { Text("方案 A：显示品牌开屏") }
                    )
                    FilterChip(
                        selected = !showLaunchSplash,
                        onClick = { onSelectLaunchSplash(false) },
                        label = { Text("方案 B：直达主页") }
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("开屏权限申请与用途说明", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = onRefreshStatus) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_px_refresh),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("刷新状态")
            }
        }

        PermissionGuideCard(
            iconRes = R.drawable.ic_px_notifications_filled,
            title = "1. 发送通知权限（必需）",
            purpose = "为何申请：用于向状态栏推送灵动胶囊与展开交互卡片，是本应用核心显示链路的基础权限。",
            granted = hasPostNotifications,
            actionText = "立即授权",
            onAction = onRequestPostNotifications
        )

        PermissionGuideCard(
            iconRes = R.drawable.ic_px_notifications,
            title = "2. 通知使用权（核心推荐）",
            purpose = "为何申请：用于本地读取微信/QQ/音乐等通知正文、专辑封面与播放进度，并将原通知按钮透传到胶囊与横幅。",
            granted = hasNotificationAccess,
            actionText = "前往开启",
            onAction = onOpenNotifAccess
        )

        PermissionGuideCard(
            iconRes = R.drawable.ic_px_tune,
            title = "3. 位置权限（胶囊导航按需）",
            purpose = "为何申请：仅在启动胶囊导航时获取起点并实时比对本地路线缓存；息屏自动释放 GPS 与指南针，结束导航即删缓存。",
            granted = hasLocationPermission,
            actionText = "立即授权",
            onAction = onRequestLocationPermission
        )

        PermissionGuideCard(
            iconRes = R.drawable.ic_px_apps,
            title = "4. 悬浮窗权限（全机型通用按需）",
            purpose = "为何申请：用于在非荣耀机型或通用模式下于应用外绘制可收起迷你胶囊与自定义悬浮横幅。",
            granted = hasOverlayPermission,
            actionText = "前往开启",
            onAction = onOpenOverlaySettings
        )

        PermissionGuideCard(
            iconRes = R.drawable.ic_px_apps,
            title = "5. 无障碍顶层横幅服务（可选）",
            purpose = "为何申请：仅用于在系统原生横幅上方绘制自定义顶层横幅（支持左右滑掉与按钮交互）；已禁用窗口内容读取。",
            granted = hasAccessibilityEnabled,
            actionText = "前往开启",
            onAction = onOpenAccessibility
        )

        PermissionGuideCard(
            iconRes = R.drawable.ic_px_shield,
            title = "6. Shizuku 增强授权（可选）",
            purpose = "为何申请：用于可选授予 WRITE_SECURE_SETTINGS（屏蔽重复系统横幅）、只读查询温控/Doze 状态与酷狗桌面锁词。",
            granted = shizukuGranted || hasWriteSecureSettings,
            grantedLabel = if (hasWriteSecureSettings) "权限已就绪" else "已授权",
            actionText = "申请 Shizuku",
            onAction = onRequestShizuku
        )

        PermissionGuideCard(
            iconRes = R.drawable.ic_px_battery,
            title = "7. 忽略电池优化（建议）",
            purpose = "为何申请：防止系统休眠误杀后台进程导致通知监听断开；本应用息屏自动释放传感器并降频以控制耗电。",
            granted = isIgnoringBatteryOpt,
            actionText = "去设置",
            onAction = onRequestBatteryOpt
        )

        // GitHub 开源仓库入口
        OutlinedButton(
            onClick = onOpenGithub,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_px_info),
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text("GitHub 开源仓库 · Oyama-Mahiro-onimai/MagicCapsule")
        }

        Spacer(Modifier.height(2.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = agreedNotice,
                onCheckedChange = { agreedNotice = it }
            )
            Text(
                text = "我已阅读并同意《用户协议》与《隐私政策》，充分知晓以上权限及信息收集用途",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        if (!hasPostNotifications) {
            Text(
                text = "提示：建议先授予「发送通知权限」，否则无法弹出灵动胶囊（也可先勾选协议进入应用预览设置）。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Button(
            onClick = onComplete,
            enabled = agreedNotice,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_px_check_circle),
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text("同意并进入灵动胶囊助手")
        }
    }
}

@Composable
private fun PermissionGuideCard(
    iconRes: Int,
    title: String,
    purpose: String,
    granted: Boolean,
    grantedLabel: String = "已授权",
    actionText: String,
    onAction: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        painter = painterResource(id = iconRes),
                        contentDescription = null,
                        tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (granted) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                ) {
                    Text(
                        text = if (granted) grantedLabel else "未授权",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (granted) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
            Text(
                text = purpose,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!granted) {
                OutlinedButton(
                    onClick = onAction,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(actionText)
                }
            }
        }
    }
}
