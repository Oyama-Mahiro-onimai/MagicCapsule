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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.app.magiccapsule.model.CapsuleConfig

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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CapsuleScreen(vm: CapsuleViewModel = viewModel()) {
    val state by vm.uiState.collectAsState()
    val config = state.config
    val context = LocalContext.current

    val notifPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            vm.startCapsule()
        } else {
            vm.setStatus("未授予通知权限，无法展示灵动胶囊")
        }
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
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(title = { Text("灵动胶囊助手 v7") })
        }
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
                    Text(
                        text = if (state.isFrameworkSupported) {
                            "✅ 已检测到荣耀 framework-magic 灵动胶囊接口"
                        } else {
                            "⚠️ 未检测到 framework-magic（仅普通通知模式）"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(14.dp)
                    )
                }

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
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
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
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
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
                        onClick = { vm.refreshCapsule() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("静默刷新")
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
