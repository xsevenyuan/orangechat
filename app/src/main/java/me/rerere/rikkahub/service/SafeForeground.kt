/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 *
 * SafeForeground —— 前台服务安全启动工具（照 Su 已验证方案移植）
 *
 * Android 13+ (API 33) 未授予 POST_NOTIFICATIONS 权限时，调用 Service.startForeground()
 * 会抛 CannotPostForegroundServiceNotificationException（Bad notification for startForeground），
 * 且该异常是从 system_server 的 Binder 线程异步抛出的，普通 try-catch 兜不住，会导致整个 App 崩溃。
 *
 * 因此所有前台服务必须在 startForeground 之前先检查通知权限：无权限则优雅退出（stopSelf + 不启动前台），
 * 而不是硬 startForeground 触发崩溃。
 */

package me.rerere.rikkahub.service

import android.app.Notification
import android.app.Service
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat

object SafeForeground {

    private const val TAG = "SafeForeground"

    /**
     * 是否可以在前台服务里显示通知（即是否具备 startForeground 所需的通知权限）。
     * Android 13 以下默认可以；13+ 需检查 POST_NOTIFICATIONS 是否被授予。
     */
    fun canShowNotification(service: Service): Boolean {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                true
            } else {
                NotificationManagerCompat.from(service).areNotificationsEnabled()
            }
        } catch (e: Exception) {
            Log.w(TAG, "areNotificationsEnabled 检查失败，保守认为无权限", e)
            false
        }
    }

    /**
     * 安全地调用 startForeground。无通知权限时不启动前台、直接返回 false，
     * 由调用方在返回 false 时 stopSelf() 优雅退出。
     *
     * @param specialUse 是否以 FOREGROUND_SERVICE_TYPE_SPECIAL_USE 类型启动（Android 14+）
     */
    fun start(service: Service, id: Int, notification: Notification, specialUse: Boolean = false): Boolean {
        if (!canShowNotification(service)) {
            Log.w(TAG, "no POST_NOTIFICATIONS permission, skip startForeground(id=$id) to avoid crash")
            return false
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    service,
                    id,
                    notification,
                    if (specialUse) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    else ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
                )
            } else {
                service.startForeground(id, notification)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed (id=$id)", e)
            false
        }
    }

    /**
     * 便捷包装：构建 notification 并安全启动前台，失败时 stopSelf。
     * 返回是否成功启动了前台服务。
     */
    fun startWithNotification(
        service: Service,
        id: Int,
        channelId: String,
        title: String,
        content: String,
        smallIcon: Int,
        specialUse: Boolean = false
    ): Boolean {
        val notification = NotificationCompat.Builder(service, channelId)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(smallIcon)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        val ok = start(service, id, notification, specialUse)
        if (!ok) {
            service.stopSelf()
        }
        return ok
    }
}

/**
 * 安全启动 Service：有通知权限 -> startForegroundService；无权限 -> startService。
 * 用于所有"外部/广播/定时"触发 Service 的调用点，避免 Android 14+ 下
 * startForegroundService 后 5 秒内未 startForeground 而抛 ForegroundServiceDidNotStartInTimeException。
 */
object SafeStart {
    private const val TAG = "SafeStart"

    fun service(context: android.content.Context, intent: android.content.Intent) {
        try {
            val hasNotifPerm = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
            } else {
                true
            }
            if (hasNotifPerm) {
                context.startForegroundService(intent)
            } else {
                Log.w(TAG, "no POST_NOTIFICATIONS, fallback to background startService to avoid crash")
                context.startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "SafeStart failed, fallback to startService", e)
            try { context.startService(intent) } catch (e2: Exception) { Log.e(TAG, "startService fallback failed", e2) }
        }
    }
}
