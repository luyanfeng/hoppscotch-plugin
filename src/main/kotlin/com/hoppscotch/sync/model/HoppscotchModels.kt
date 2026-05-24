package com.hoppscotch.sync.model

/**
 * Hoppscotch 请求数据结构
 * https://github.com/hoppscotch/hoppscotch
 */
data class HoppscotchRequest(
    val v: String = "16",
    val name: String,
    val method: String,
    val endpoint: String,
    val params: List<HoppscotchParam> = emptyList(),
    val headers: List<HoppscotchHeader> = emptyList(),
    val body: HoppscotchBody = HoppscotchBody(),
    val auth: HoppscotchAuth = HoppscotchAuth(),
    val preRequestScript: String = "",
    val testScript: String = "",
    val requestVariables: List<HoppscotchVariable> = emptyList(),
    val responses: Map<String, Any> = emptyMap()
)

data class HoppscotchParam(
    val key: String,
    val value: String = "",
    val active: Boolean = true
)

data class HoppscotchHeader(
    val key: String,
    val value: String,
    val active: Boolean = true
)

data class HoppscotchBody(
    val contentType: String? = null,
    val body: String? = null
)

data class HoppscotchAuth(
    val authType: String = "inherit",
    val authActive: Boolean = true
)

data class HoppscotchVariable(
    val key: String,
    val value: String = ""
)

data class CollectionInfo(
    val id: String,
    val title: String
)

data class RequestInfo(
    val id: String,
    val title: String,
    val request: String = ""
)

/**
 * 端点同步状态
 */
enum class SyncStatus {
    /** 未同步到服务端 */
    UNSYNCED,
    /** 已同步，本地内容与上次同步时一致 */
    SYNCED,
    /** 已覆盖更新（本地代码在同步后发生过修改） */
    MODIFIED
}

/**
 * 同步结果
 */
data class SyncResult(
    val total: Int = 0,
    val success: Int = 0,
    val failed: Int = 0,
    val collectionsCreated: Int = 0,
    val requestsCreated: Int = 0,
    val requestsSkipped: Int = 0,
    val requestsUpdated: Int = 0,
    val requestsMerged: Int = 0,
    val errors: List<String> = emptyList()
) {
    val isSuccess: Boolean get() = failed == 0
}

// ====================================================================
//  LogLevel for debug logging control
// ====================================================================

/**
 * 插件日志级别，在设置中可配置。
 * 控制 `log.info()`、`System.err.println` 等调试输出是否显示。
 */
enum class LogLevel(val label: String, val labelZh: String) {
    DEBUG("DEBUG", "调试"),
    INFO("INFO", "信息"),
    WARN("WARN", "警告"),
    ERROR("ERROR", "错误"),
    OFF("OFF", "关闭");

    companion object {
        fun fromId(id: String): LogLevel = entries.find { it.name == id } ?: INFO
    }

    /** 是否允许输出指定级别的日志 */
    fun allows(level: LogLevel): Boolean = ordinal <= level.ordinal
}

// ====================================================================
//  Sync status helper functions
// ====================================================================

/**
 * 计算端点在持久化存储中的唯一键。
 * 用于匹配本地端点 ↔ 存储的 hash ↔ 服务端请求标题。
 */
fun computeEndpointKey(
    endpoint: SpringEndpoint,
    group: ControllerGroup
): String = "${group.controllerQualifiedName}:${endpoint.httpMethod.name}:${endpoint.path}"

/**
 * 计算端点的内容 hash，用于检测本地代码是否修改。
 * 仅 hash 影响实际同步结果的字段（方法、路径、参数）。
 */
fun computeEndpointHash(endpoint: SpringEndpoint): Int {
    var hash = endpoint.httpMethod.ordinal
    hash = 31 * hash + endpoint.path.hashCode()
    hash = 31 * hash + endpoint.fullPath.hashCode()
    val sortedParams = endpoint.parameters.sortedWith(
        compareBy({ it.source.ordinal }, { it.name })
    )
    for (param in sortedParams) {
        hash = 31 * hash + param.name.hashCode()
        hash = 31 * hash + param.type.hashCode()
        hash = 31 * hash + param.source.ordinal
        hash = 31 * hash + (if (param.required) 1 else 0)
    }
    return hash
}

/**
 * 端点在服务端的请求标题（与 SyncService.buildRequestTitle 一致）。
 */
fun requestTitleOnServer(endpoint: SpringEndpoint): String =
    endpoint.fullPath
