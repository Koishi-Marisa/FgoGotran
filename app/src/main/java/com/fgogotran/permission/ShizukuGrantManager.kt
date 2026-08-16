package com.fgogotran.permission

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.fgogotran.accessibility.FgoAccessibilityService
import com.fgogotran.util.FgoLogger
import dev.rikka.shizuku.Shizuku
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Shizuku 一键授权管理器。
 *
 * 原理：用户通过 adb（或无线调试）给 Shizuku 一次性 shell 授权后，
 * 本 App 即可借用 shell 身份执行系统命令，在 App 内自动开启
 * 「无障碍服务」与「显示在其他应用上层（悬浮窗）」两个权限，
 * 免去每次卸载重装 / 系统重置后手动进设置开关。
 *
 * 用到的 shell 命令：
 *  - 悬浮窗：`appops set <pkg> SYSTEM_ALERT_WINDOW allow`
 *  - 无障碍：`settings put secure enabled_accessibility_services <component>`
 *            `settings put secure accessibility_enabled 1`
 */
object ShizukuGrantManager {

    private const val TAG = "Shizuku"

    /** 自定义请求码，用于 onRequestPermissionsResult 区分 Shizuku 授权回调 */
    const val REQUEST_CODE = 0x5E42

    private val _permissionGranted = MutableStateFlow(false)

    /** Shizuku 应用级授权状态（由 Activity.onRequestPermissionsResult 更新） */
    val permissionGranted: StateFlow<Boolean> = _permissionGranted.asStateFlow()

    /** Shizuku 服务是否在线（已安装 + 已通过 adb 授权 + binder 存活） */
    fun isAvailable(): Boolean =
        runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** 本 App 是否已获得 Shizuku 调用权限（首次使用会弹系统授权框） */
    fun hasPermission(): Boolean =
        runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
            .getOrDefault(false)

    /** 由 Activity.onRequestPermissionsResult 转发 */
    fun onRequestPermissionsResult(requestCode: Int, grantResults: IntArray) {
        if (requestCode != REQUEST_CODE) return
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        _permissionGranted.value = granted
        FgoLogger.info(TAG, "Shizuku permission result: granted=$granted")
    }

    /** 重新读取真实权限状态（用于从 Shizuku 管理器手动授权后回到本页时同步 UI） */
    fun syncPermissionState() {
        _permissionGranted.value = hasPermission()
    }

    /** 发起 Shizuku 授权请求（系统会弹出确认框） */
    fun requestPermission() {
        runCatching {
            Shizuku.requestPermission(REQUEST_CODE)
        }.onFailure {
            FgoLogger.warn(TAG, "Shizuku requestPermission failed: ${it.message}")
        }
    }

    data class GrantResult(
        val overlayGranted: Boolean,
        val accessibilityGranted: Boolean,
        val messages: List<String>
    )

    /** 一键授予悬浮窗 + 无障碍（在 IO 线程执行 shell 命令） */
    suspend fun grantAccessibilityAndOverlay(context: Context): GrantResult =
        withContext(Dispatchers.IO) {
            val messages = mutableListOf<String>()
            val overlay = grantOverlay(context.packageName, messages)
            val accessibility = grantAccessibility(context, messages)
            FgoLogger.info(
                TAG,
                "Shizuku grant done: overlay=$overlay accessibility=$accessibility " +
                    messages.joinToString(" | ")
            )
            GrantResult(overlay, accessibility, messages)
        }

    private fun grantOverlay(pkg: String, messages: MutableList<String>): Boolean {
        val (code, out) = exec("appops", "set", pkg, "SYSTEM_ALERT_WINDOW", "allow")
        if (code == 0) {
            messages += "悬浮窗权限已授予"
            return true
        }
        messages += "悬浮窗授予失败 (exit=$code): ${out.trim().take(120)}"
        return false
    }

    private fun grantAccessibility(context: Context, messages: MutableList<String>): Boolean {
        val component = ComponentName(
            context,
            FgoAccessibilityService::class.java
        ).flattenToString()

        // 先读取系统已启用的无障碍服务列表，把本服务追加进去，
        // 避免覆盖用户已开启的其它无障碍服务（如 TalkBack）。
        var existing = ""
        runCatching {
            val (_, out) = exec("settings", "get", "secure", "enabled_accessibility_services")
            existing = out.trim().removePrefix("null").trim()
        }
        val entries = existing.split(':').map(String::trim).filter(String::isNotBlank).toMutableList()
        if (entries.none { it == component }) entries.add(component)
        val joined = entries.joinToString(":")

        val (c1, o1) = exec("settings", "put", "secure", "enabled_accessibility_services", joined)
        val (c2, o2) = exec("settings", "put", "secure", "accessibility_enabled", "1")
        if (c1 == 0 && c2 == 0) {
            messages += "无障碍服务已启用"
            return true
        }
        messages += "无障碍启用失败 (exit=$c1/$c2): ${(o1 + o2).trim().take(120)}"
        return false
    }

    /** 通过 Shizuku 以 shell 身份执行命令，返回 (exitCode, output) */
    private fun exec(vararg cmd: String): Pair<Int, String> {
        return runCatching {
            val process = Shizuku.newProcess(cmd, null, null)
                ?: return runCatching { -2 to "Shizuku.newProcess returned null" }
                    .getOrDefault(-2 to "unknown")
            val out = process.inputStream?.bufferedReader()?.readText().orEmpty()
            val err = process.errorStream?.bufferedReader()?.readText().orEmpty()
            val code = process.waitFor()
            code to (out + err)
        }.getOrDefault(-3 to "exec failed: ${cmd.firstOrNull()}")
    }
}
