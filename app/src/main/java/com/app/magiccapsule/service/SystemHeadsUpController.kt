package com.app.magiccapsule.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.app.magiccapsule.crash.AppLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * 通过 WRITE_SECURE_SETTINGS 动态控制 Android 全局系统横幅开关 (heads_up_notifications_enabled)：
 * - 自定义 TYPE_ACCESSIBILITY_OVERLAY 顶层横幅启用时抑制系统原生重复横幅
 * - 检测到微信/QQ等语音视频来电时临时恢复系统横幅（防止原生来电浮窗/接听被系统拦截），来电结束后自动恢复原状态
 */
object SystemHeadsUpController {

    private const val KEY_HEADS_UP = "heads_up_notifications_enabled"
    private val activeCallKeys = ConcurrentHashMap.newKeySet<String>()

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    fun isSystemHeadsUpEnabled(context: Context): Boolean =
        runCatching { Settings.Global.getInt(context.contentResolver, KEY_HEADS_UP, 1) == 1 }
            .getOrDefault(true)

    fun setSystemHeadsUpSuppressed(context: Context, suppress: Boolean): Boolean {
        if (!hasPermission(context)) return false
        return runCatching {
            Settings.Global.putInt(context.contentResolver, KEY_HEADS_UP, if (suppress) 0 else 1)
            true
        }.getOrDefault(false)
    }

    fun suspendSuppressionForCall(context: Context, callKey: String) {
        activeCallKeys.add(callKey)
        if (hasPermission(context) && !isSystemHeadsUpEnabled(context)) {
            setSystemHeadsUpSuppressed(context, suppress = false)
            AppLogger.i("Notif", "CALL_PASSTHROUGH：来电期间已临时恢复系统原生横幅以保障接听")
        }
    }

    fun resumeSuppressionAfterCall(context: Context, callKey: String, shouldSuppressAgain: Boolean) {
        val removed = activeCallKeys.remove(callKey)
        if (removed && activeCallKeys.isEmpty() && shouldSuppressAgain && hasPermission(context)) {
            setSystemHeadsUpSuppressed(context, suppress = true)
            AppLogger.i("Notif", "CALL_ENDED：来电已结束，已恢复系统横幅抑制状态")
        }
    }

    fun restoreDefault(context: Context): Boolean {
        activeCallKeys.clear()
        return setSystemHeadsUpSuppressed(context, suppress = false)
    }
}
