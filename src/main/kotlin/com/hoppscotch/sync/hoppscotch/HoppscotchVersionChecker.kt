package com.hoppscotch.sync.hoppscotch

import com.hoppscotch.sync.util.LogUtil
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Hoppscotch 服务端版本检测与兼容性管理。
 *
 * 由于后端没有暴露版本号的 API，通过以下方式推断：
 * 1. 探测 /health 或 /ping 端点确认服务可达
 * 2. 通过 GraphQL 查询结果判定 API 能力
 * 3. 映射到对应的 Zod schema 版本
 */
object HoppscotchVersionChecker {

    // ====================================================================
    //  Schema 版本兼容性表
    // ====================================================================

    /** 插件支持的 Zod schema 版本（按新到旧排列） */
    val SUPPORTED_SCHEMAS: List<SchemaVersionInfo> = listOf(
        SchemaVersionInfo(
            version = "17",
            minServerVersion = "2025.0.0",
            description = "新增 description 字段",
            features = setOf("description", "fullCollectionTree")
        ),
        SchemaVersionInfo(
            version = "16",
            minServerVersion = "2025.0.0",
            description = "新增 _ref_id 字段",
            features = setOf("_ref_id", "fullCollectionTree")
        ),
    )

    /** 当前插件默认使用的 schema 版本（最新） */
    val DEFAULT_SCHEMA_VERSION: String = SUPPORTED_SCHEMAS.first().version

    // ====================================================================
    //  Server 健康探测
    // ====================================================================

    /**
     * 探测 Hoppscotch 服务端状态。
     *
     * 依次尝试：
     * 1. [GET /ping] — 最简单的存活检查（返回 "Success"）
     * 2. [GET /health] — 数据库健康检查
     *
     * @return [ServerHealthResult] 包含可达性、响应时间等
     */
    fun checkServerHealth(serverUrl: String): ServerHealthResult {
        val baseUrl = serverUrl.trimEnd('/')
        val startTime = System.currentTimeMillis()

        // 先尝试 /ping
        val pingResult = tryPing(baseUrl)
        if (pingResult) {
            val elapsed = System.currentTimeMillis() - startTime
            LogUtil.stdout { "[HS-Version] Server reachable via /ping (${elapsed}ms)" }
            return ServerHealthResult(
                reachable = true,
                elapsedMs = elapsed,
                detectedEndpoint = "/ping"
            )
        }

        // 再尝试 /health
        val healthResult = tryHealth(baseUrl)
        if (healthResult) {
            val elapsed = System.currentTimeMillis() - startTime
            LogUtil.stdout { "[HS-Version] Server reachable via /health (${elapsed}ms)" }
            return ServerHealthResult(
                reachable = true,
                elapsedMs = elapsed,
                detectedEndpoint = "/health"
            )
        }

        val elapsed = System.currentTimeMillis() - startTime
        LogUtil.stdout { "[HS-Version] Server unreachable (${elapsed}ms)" }
        return ServerHealthResult(reachable = false, elapsedMs = elapsed)
    }

    private fun tryPing(baseUrl: String): Boolean = try {
        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()
        val req = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/ping"))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        resp.statusCode() == 200
    } catch (_: Exception) {
        false
    }

    private fun tryHealth(baseUrl: String): Boolean = try {
        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()
        val req = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/health"))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        resp.statusCode() == 200
    } catch (_: Exception) {
        false
    }

    // ====================================================================
    //  Schema 版本选择
    // ====================================================================

    /**
     * 根据服务端可达性和特征选择最佳 schema 版本。
     *
     * 当前策略：最新版服务端 → 最新 schema 版本。
     * 后续可扩展为根据探测到的服务端特征自动降级。
     */
    fun resolveSchemaVersion(health: ServerHealthResult): String {
        if (!health.reachable) {
            // 服务端不可达时使用默认版本
            return DEFAULT_SCHEMA_VERSION
        }
        // 目前所有可检测的服务端版本都支持最新 schema
        return DEFAULT_SCHEMA_VERSION
    }

    /**
     * 检查指定 schema 版本是否受当前插件支持。
     */
    fun isSchemaSupported(schemaVersion: String): Boolean {
        return SUPPORTED_SCHEMAS.any { it.version == schemaVersion }
    }

    /**
     * 获取插件支持的 schema 版本列表（用户可读）。
     */
    fun getSupportedSchemaVersions(): String {
        return SUPPORTED_SCHEMAS.joinToString(", ") {
            "v${it.version} (${it.description})"
        }
    }

    /**
     * 获取某个 schema 版本支持的特性集合。
     */
    fun getFeaturesForSchema(schemaVersion: String): Set<String> {
        return SUPPORTED_SCHEMAS
            .firstOrNull { it.version == schemaVersion }
            ?.features ?: emptySet()
    }

    // ====================================================================
    //  数据类
    // ====================================================================

    /**
     * Schema 版本元信息。
     * @param version Zod schema 版本号（如 "17"）
     * @param minServerVersion 最低兼容的服务端版本（语义化版本）
     * @param description 变更描述
     * @param features 该版本支持的特性标签集合
     */
    data class SchemaVersionInfo(
        val version: String,
        val minServerVersion: String,
        val description: String,
        val features: Set<String> = emptySet()
    )

    /**
     * 服务端健康探测结果。
     * @param reachable 服务端是否可达
     * @param elapsedMs 探测耗时（毫秒）
     * @param detectedEndpoint 成功响应的端点（"/ping" / "/health"）
     */
    data class ServerHealthResult(
        val reachable: Boolean,
        val elapsedMs: Long = 0,
        val detectedEndpoint: String? = null
    )
}
