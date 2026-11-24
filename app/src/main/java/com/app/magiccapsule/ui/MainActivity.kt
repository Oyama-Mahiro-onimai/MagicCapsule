package com.app.magiccapsule.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.app.magiccapsule.model.CapsuleConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            val darkTheme = isSystemInDarkTheme()
            val colorScheme = if (darkTheme) {
                dynamicDarkColorScheme(this)
            } else {
                dynamicLightColorScheme(this)
            }
            MaterialTheme(colorScheme = colorScheme) {
                CapsuleScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CapsuleScreen(vm: CapsuleViewModel = viewModel()) {
    val state by vm.uiState.collectAsState()
    val notifications by vm.capturedNotifications.collectAsState()
    val listenerConnected by vm.listenerConnected.collectAsState()
    val config = state.config
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 返回页面时自动重新检测通知使用权并刷新活跃通知
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                vm.recheckNotificationAccess()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notifPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.startCapsule() else vm.setStatus("未授予通知权限，无法展示灵动胶囊")
    }

    fun runWithNotifPermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            action()
        }
    }

    fun openNotificationAccessSettings() {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }.onFailure {
            vm.setStatus("请前往：设置 → 隐私/权限 → 通知使用权 → 开启「灵动胶囊助手」")
        }
    }

    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOpt() {
        val pm = context.getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(context.packageName)) {
            vm.setStatus("已在电池优化白名单中")
            return
        }
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:${context.packageName}"))
            )
        }.onFailure {
            vm.setStatus("请手动在系统「应用启动管理」中允许后台活动")
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.systemBars,
        topBar = { TopAppBar(title = { Text("灵动胶囊助手 v10") }) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = if (state.isFrameworkSupported) {
                                "✅ 荣耀 framework-magic 接口可用"
                            } else {
                                "⚠️ 未检测到 framework-magic"
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = when {
                                state.hasNotificationAccess && listenerConnected ->
                                    "✅ 通知使用权已授权（监听服务已连接，实时自动刷新）"
                                state.hasNotificationAccess ->
                                    "⏳ 通知使用权已授权（等待系统绑定监听服务）"
                                else ->
                                    "❌ 尚未授予「通知使用权」，无法读取其他应用通知"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (!state.hasNotificationAccess) {
                            OutlinedButton(
                                onClick = { openNotificationAccessSettings() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("前往开启通知使用权")
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "自动将新通知同步到灵动胶囊",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = config.autoMirrorNotifications,
                        onCheckedChange = { checked ->
                            vm.updateConfig(saveImmediately = true) {
                                it.copy(autoMirrorNotifications = checked)
                            }
                            vm.setStatus(if (checked) "已开启新通知自动上岛" else "已关闭自动上岛")
                        }
                    )
                }

                // 实时读取到的系统通知列表（自动刷新）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "实时系统通知（${notifications.size} 条，点击推送到胶囊）",
                        style = MaterialTheme.typography.labelLarge
                    )
                    OutlinedButton(onClick = { vm.refreshAll() }) {
                        Text("刷新")
                    }
                }

                if (notifications.isEmpty()) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = if (state.hasNotificationAccess) {
                                "当前暂无可读取的第三方通知，新通知到达时会自动刷新显示。"
                            } else {
                                "开启上方「通知使用权」后，这里会自动展示并实时刷新系统通知。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                } else {
                    val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    notifications.take(8).forEach { item ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { runWithNotifPermission { vm.mirrorToCapsule(item) } }
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "${item.appName} · ${item.title}",
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = timeFmt.format(Date(item.postTime)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = item.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                Text("手动自定义胶囊配置", style = MaterialTheme.typography.labelLarge)

                OutlinedTextField(
                    value = config.businessName,
                    onValueChange = { v -> vm.updateConfig { it.copy(businessName = v) } },
                    label = { Text("业务短名称（建议 2 字）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = config.mainText,
                    onValueChange = { v -> vm.updateConfig { it.copy(mainText = v) } },
                    label = { Text("主文案") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = config.subText,
                    onValueChange = { v -> vm.updateConfig { it.copy(subText = v) } },
                    label = { Text("副文案 / 展开详情") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("胶囊模板", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CapsuleConfig.ALL_TEMPLATES.forEach { (tplKey, tplLabel) ->
                        FilterChip(
                            selected = config.template == tplKey,
                            onClick = {
                                vm.updateConfig {
                                    it.copy(template = tplKey, enableTimer = tplKey.contains("timer"))
                                }
                            },
                            label = { Text(tplLabel) }
                        )
                    }
                }

                Text("品牌主题色（MAGIC_COLOR 1–12）", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    (1..12).forEach { c ->
                        FilterChip(
                            selected = config.brandColor == c,
                            onClick = { vm.updateConfig { it.copy(brandColor = c) } },
                            label = { Text("#$c") }
                        )
                    }
                }

                Button(
                    onClick = { runWithNotifPermission { vm.startCapsule() } },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("保存并启动灵动胶囊")
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { openNotificationAccessSettings() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("通知使用权")
                    }
                    OutlinedButton(
                        onClick = { vm.stopCapsule() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("停止服务")
                    }
                }

                OutlinedButton(
                    onClick = { requestIgnoreBatteryOpt() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("申请忽略电池优化（防杀后台）")
                }

                Text(
                    text = state.statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                )
            }
        }
    }
}
