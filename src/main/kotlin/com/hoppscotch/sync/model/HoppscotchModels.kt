package com.hoppscotch.sync.model

/**
 * Hoppscotch 请求数据结构
 * https://github.com/hoppscotch/hoppscotch
 */
data class HoppscotchRequest(
    val v: String = "17",
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

/**
 * 集合树节点，包含子集合和请求列表。
 * 用于 [HoppscotchClient.getFullCollectionTree] / [HoppscotchClient.getChildCollectionTree] 的
 * 一次性树查询结果。
 */
data class CollectionTreeNode(
    val id: String,
    val title: String,
    val children: List<CollectionTreeNode> = emptyList(),
    val requests: List<RequestInfo> = emptyList()
) {
    /** 递归收集该节点及其所有子节点下的所有集合 ID */
    fun collectAllIds(): Set<String> {
        val ids = mutableSetOf(id)
        for (child in children) {
            ids.addAll(child.collectAllIds())
        }
        return ids
    }

    /** 递归收集该节点及其所有子节点下的所有请求标题 */
    fun collectAllRequestTitles(): Set<String> {
        val titles = requests.map { it.title }.toMutableSet()
        for (child in children) {
            titles.addAll(child.collectAllRequestTitles())
        }
        return titles
    }

    /** 递归收集该节点及其所有子节点下的所有 [RequestInfo] */
    fun collectAllRequestInfos(): List<RequestInfo> {
        val infos = requests.toMutableList()
        for (child in children) {
            infos.addAll(child.collectAllRequestInfos())
        }
        return infos
    }
}

data class RequestInfo(
    val id: String,
    val title: String,
    val request: String = ""
) {
    companion object {
        private val reqGson = com.google.gson.GsonBuilder().create()
    }

    /**
     * 从 [request] JSON 中提取 HTTP method。
     * 用于与方法+endpoint 匹配，不依赖 title。
     */
    val methodFromBody: String?
        get() = try {
            reqGson.fromJson(request, Map::class.java)?.get("method")?.toString()
        } catch (_: Exception) { null }

    /**
     * 从 [request] JSON 中提取 endpoint（= fullPath）。
     * 用于与方法+endpoint 匹配，不依赖 title。
     */
    val endpointFromBody: String?
        get() = try {
            reqGson.fromJson(request, Map::class.java)?.get("endpoint")?.toString()
        } catch (_: Exception) { null }

    /**
     * 从 [request] JSON 中提取 method:endpoint 组合键，用于增量匹配。
     * 格式： "GET:/api/users/{id}"
     */
    val methodEndpointKey: String?
        get() {
            val m = methodFromBody ?: return null
            val e = endpointFromBody ?: return null
            return "$m:$e"
        }
}

/**
 * 同步持久化数据：存储 serverId + localHash + srvHash。
 */
data class SyncPersistData(
    val serverId: String? = null,
    val localHash: Int = 0,
    val srvHash: Int = 0
) {
    companion object {
        /**
         * 从持久化字符串解析。
         * 兼容 "serverId,localHash,srvHash"、"localHash,srvHash"、"localHash" 三种格式。
         */
        fun parse(value: String): SyncPersistData {
            val hasSrvId = value.count { it == ',' } >= 2
            val srvId = if (hasSrvId) value.substringBefore(",").takeIf { it.isNotEmpty() } else null
            val rest = if (hasSrvId) value.substringAfter(",") else value
            val localHash = rest.substringBefore(",").toIntOrNull() ?: 0
            val srvHash = rest.substringAfter(",", "0").toIntOrNull() ?: 0
            return SyncPersistData(srvId, localHash, srvHash)
        }
    }
}

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
    val errors: List<String> = emptyList(),
    /** 成功同步的端点映射：endpointKey → serverId，用于后续持久化 */
    val syncedEndpoints: Map<String, String> = emptyMap()
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
 * 优先使用 @ApiOperation 的值，没有则回退为 fullPath。
 */
fun requestTitleOnServer(endpoint: SpringEndpoint): String =
    endpoint.description?.takeIf { it.isNotBlank() } ?: endpoint.fullPath
