package com.hoppscotch.sync.service

import com.google.gson.JsonParser
import com.intellij.notification.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.hoppscotch.sync.hoppscotch.HoppscotchClient
import com.hoppscotch.sync.settings.AppSettings
import com.hoppscotch.sync.util.I18n
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.*

/**
 * IDEA 启动时自动检查 Hoppscotch Token 是否过期。
 *
 * - 有 refreshToken → 主动刷新一次（刷新成功则 token 续期）
 * - 只有 accessToken → 本地解码 JWT 检查 exp 字段，已过期则尝试 desktop 端点刷新
 * - 所有刷新方式都失败 → 区分「网络不通」和「Token 无效」，分别弹出对应通知
 */
class TokenRefreshStartupActivity : StartupActivity.DumbAware {

    override fun runActivity(project: Project) {
        val settings = AppSettings.getInstance()
        if (settings.serverUrl.isBlank()) return
        if (settings.accessToken.isBlank() && settings.refreshToken.isBlank()) return

        if (settings.refreshToken.isNotBlank()) {
            // 有 refreshToken → 用 HoppscotchClient 主动刷新
            val client = HoppscotchClient(
                serverUrl = settings.serverUrl,
                accessToken = settings.accessToken,
                refreshToken = settings.refreshToken,
                onTokenRefreshed = { newAccess, newRefresh ->
                    settings.accessToken = newAccess
                    if (newRefresh != null) settings.refreshToken = newRefresh
                }
            )
            try {
                val ok = client.tryRefreshSession()
                if (!ok && settings.accessToken.isNotBlank()) {
                    // refreshToken 方式失败，再尝试 desktop 端点
                    notifyRefreshFailure(settings, project)
                }
            } finally {
                client.close()
            }
        } else {
            // 只有 accessToken → 本地检查 JWT 是否过期
            if (isJwtExpired(settings.accessToken)) {
                notifyRefreshFailure(settings, project)
            }
        }
    }

    // ========================================================================
    //  Desktop 端点刷新（备选方案）
    // ========================================================================

    /**
     * 尝试 desktop 端点刷新。成功更新 [AppSettings] 并返回 true，否则返回 false。
     */
    private fun tryDesktopRefresh(settings: AppSettings): Boolean {
        if (settings.accessToken.isBlank()) return false
        return try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create("${settings.serverUrl.trimEnd('/')}/v1/auth/desktop?redirect_uri=http://localhost:12345"))
                .header("Authorization", "Bearer ${settings.accessToken}")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build()
            val c = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build()
            val resp = c.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() != 200) return false
            val json = JsonParser.parseString(resp.body()).asJsonObject
            val newAccess = json.get("access_token")?.asString ?: return false
            val newRefresh = json.get("refresh_token")?.asString
            settings.accessToken = newAccess
            if (newRefresh != null) settings.refreshToken = newRefresh
            true
        } catch (_: Exception) {
            false
        }
    }

    // ========================================================================
    //  通知 — 区分服务端不可达 vs Token 无效
    // ========================================================================

    /**
     * 刷新失败后的通知处理：先探测服务端连通性，再决定报什么错。
     */
    private fun notifyRefreshFailure(settings: AppSettings, project: Project) {
        if (tryDesktopRefresh(settings)) return

        if (isServerReachable(settings.serverUrl)) {
            // 服务端可达但刷新失败 → Token 问题
            notifyTokenExpired(project)
        } else {
            // 服务端不可达 → 网络问题
            notifyServerUnreachable(project, settings.serverUrl)
        }
    }

    private fun notifyTokenExpired(project: Project) {
        Notifications.Bus.notify(
            Notification(
                "Hoppscotch Sync",
                I18n.message("notification.tokenExpired.title"),
                I18n.message("notification.tokenExpired.content"),
                NotificationType.WARNING
            ),
            project
        )
    }

    private fun notifyServerUnreachable(project: Project, serverUrl: String) {
        Notifications.Bus.notify(
            Notification(
                "Hoppscotch Sync",
                I18n.message("notification.serverUnreachable.title"),
                I18n.message("notification.serverUnreachable.content", serverUrl),
                NotificationType.WARNING
            ),
            project
        )
    }

    // ========================================================================
    //  连通性检测
    // ========================================================================

    /**
     * 检测 Hoppscotch 服务端是否可达（不依赖 HTTP 响应状态码，只检查 TCP 连接）。
     */
    private fun isServerReachable(serverUrl: String): Boolean {
        return try {
            val uri = URI.create(serverUrl)
            val host = uri.host ?: return false
            val port = when {
                uri.port > 0 -> uri.port
                uri.scheme == "https" -> 443
                else -> 80
            }
            val socket = java.net.Socket()
            try {
                socket.connect(java.net.InetSocketAddress(host, port), 5000)
                true
            } finally {
                socket.close()
            }
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        /**
         * 本地解码 JWT 并检查 `exp`（过期时间戳）是否已过期。
         * 解码失败时认为已过期（安全保守）。
         */
        fun isJwtExpired(token: String): Boolean {
            return try {
                val parts = token.split(".")
                if (parts.size != 3) return true
                val payload = String(Base64.getUrlDecoder().decode(parts[1]))
                val json = JsonParser.parseString(payload).asJsonObject
                val exp = json.get("exp")?.asLong ?: return false
                System.currentTimeMillis() / 1000 >= exp
            } catch (_: Exception) {
                true
            }
        }
    }
}
