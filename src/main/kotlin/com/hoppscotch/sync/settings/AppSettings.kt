package com.hoppscotch.sync.settings

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*

@State(
    name = "com.hoppscotch.sync.settings.AppSettings",
    storages = [Storage("hoppscotch-sync-settings.xml")]
)
class AppSettings : PersistentStateComponent<AppSettings.State> {

    data class State(
        var serverUrl: String = "http://localhost:3170",
        var accessToken: String = "",
        var refreshToken: String = "",
        var language: String = "en",       // "en" | "zh"
        var hiddenColumnIds: String = "",   // comma-separated column indices, e.g. "4,6"
        var columnWidths: String = "",       // "colIdx:width,...", e.g. "3:280,4:160"
        var targetCollectionId: String = "", // 用户选择的目标父集合 ID
        var targetCollectionPath: String = "", // 人类可读路径，如 "Root / Child"
        var createSubDirectory: Boolean = true, // 同步时是否按项目名生成子目录
        var syncStatusData: String = "", // JSON: endpointKey → hash 映射，持久化同步状态
        var syncStrategy: String = "SERVER_FIRST",
        var logLevel: String = "INFO", // 调试日志级别
        var selectedProjectsData: String = "", // JSON 数组：用户选中的项目名列表
        var cachedScanJson: String = "", // JSON: 上次刷新扫描的 groups 序列化数据
        var serverSchemaVersion: String = "", // 检测到的服务端 Zod schema 版本（如 "17"）
        var serverVersionCheckedAt: Long = 0, // 上次版本检测的时间戳（毫秒）
        var requestValidationEnabled: Boolean = true // 是否启用请求数据前置校验
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    var serverUrl: String
        get() = myState.serverUrl
        set(value) { myState.serverUrl = value }

    var accessToken: String
        get() = myState.accessToken
        set(value) { myState.accessToken = value }

    var refreshToken: String
        get() = myState.refreshToken
        set(value) { myState.refreshToken = value }

    var language: String
        get() = myState.language
        set(value) { myState.language = value }

    var hiddenColumnIds: String
        get() = myState.hiddenColumnIds
        set(value) { myState.hiddenColumnIds = value }

    var columnWidths: String
        get() = myState.columnWidths
        set(value) { myState.columnWidths = value }

    var targetCollectionId: String
        get() = myState.targetCollectionId
        set(value) { myState.targetCollectionId = value }

    var targetCollectionPath: String
        get() = myState.targetCollectionPath
        set(value) { myState.targetCollectionPath = value }

    var createSubDirectory: Boolean
        get() = myState.createSubDirectory
        set(value) { myState.createSubDirectory = value }

    var syncStatusData: String
        get() = myState.syncStatusData
        set(value) { myState.syncStatusData = value }

    var syncStrategy: String
        get() = myState.syncStrategy
        set(value) { myState.syncStrategy = value }

    var logLevel: String
        get() = myState.logLevel
        set(value) { myState.logLevel = value }

    var selectedProjectsData: String
        get() = myState.selectedProjectsData
        set(value) { myState.selectedProjectsData = value }

    var cachedScanJson: String
        get() = myState.cachedScanJson
        set(value) { myState.cachedScanJson = value }

    var serverSchemaVersion: String
        get() = myState.serverSchemaVersion
        set(value) { myState.serverSchemaVersion = value }

    var serverVersionCheckedAt: Long
        get() = myState.serverVersionCheckedAt
        set(value) { myState.serverVersionCheckedAt = value }

    var requestValidationEnabled: Boolean
        get() = myState.requestValidationEnabled
        set(value) { myState.requestValidationEnabled = value }

    companion object {
        fun getInstance(): AppSettings {
            return ApplicationManager.getApplication().getService(AppSettings::class.java)
        }

        private val syncGson = GsonBuilder().create()

        /**
         * 解析持久化的同步状态映射：endpointKey → "localHash,srvHash"。
         * 兼容旧格式（Int values），自动转换为新格式。
         */
        fun deserializeSyncMap(data: String): Map<String, String> {
            if (data.isBlank()) return emptyMap()
            // 尝试新格式（String 值，如 "12345,67890"）
            val newType = object : TypeToken<Map<String, String>>() {}.type
            val newResult = try {
                syncGson.fromJson<Map<String, String>>(data, newType)
            } catch (_: Exception) { null }
            if (newResult != null) return newResult
            // 兼容旧格式（Int 值，如 12345），转换为 "value,0"
            return try {
                val oldType = object : TypeToken<Map<String, Int>>() {}.type
                val old = syncGson.fromJson<Map<String, Int>>(data, oldType) ?: return emptyMap()
                old.mapValues { "${it.value},0" }
            } catch (_: Exception) { emptyMap() }
        }

        /**
         * 序列化同步状态映射为 JSON 字符串。
         */
        fun serializeSyncMap(map: Map<String, String>): String = syncGson.toJson(map)

        /** 逗号分隔的 project 名 → Set */
        fun deserializeProjects(data: String): Set<String> {
            if (data.isBlank()) return emptySet()
            return data.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
        }

        /** Set<String> → 逗号分隔 */
        fun serializeProjects(projects: Set<String>): String =
            projects.joinToString(",")
    }

    /** 获取同步状态映射（快捷方式） */
    fun getSyncStatusMap(): Map<String, String> = deserializeSyncMap(syncStatusData)

    /** 设置同步状态映射（快捷方式） */
    fun setSyncStatusMap(map: Map<String, String>) { syncStatusData = serializeSyncMap(map) }

    /** 获取持久化的选中项目集合 */
    fun getSelectedProjects(): Set<String> = deserializeProjects(selectedProjectsData)

    /** 保存选中项目集合 */
    fun setSelectedProjects(projects: Set<String>) {
        selectedProjectsData = serializeProjects(projects)
    }

    /** 获取缓存的扫描数据（反序列化为 List<ControllerGroup>） */
    fun getCachedScanGroups(): List<com.hoppscotch.sync.model.ControllerGroup>? {
        if (cachedScanJson.isBlank()) return null
        return try {
            val type = object : TypeToken<List<com.hoppscotch.sync.model.ControllerGroup>>() {}.type
            syncGson.fromJson<List<com.hoppscotch.sync.model.ControllerGroup>>(cachedScanJson, type)
        } catch (_: Exception) { null }
    }

    /** 缓存扫描数据 */
    fun setCachedScanGroups(groups: List<com.hoppscotch.sync.model.ControllerGroup>) {
        cachedScanJson = syncGson.toJson(groups)
    }

    /** 获取当前同步策略 */
    fun getSyncStrategy(): com.hoppscotch.sync.model.SyncStrategy =
        com.hoppscotch.sync.model.SyncStrategy.fromId(syncStrategy)

    /** 设置同步策略 */
    fun setSyncStrategy(strategy: com.hoppscotch.sync.model.SyncStrategy) {
        syncStrategy = strategy.id
    }

    /** 将逗号分隔的字符串解析为列索引集合 */
    fun getHiddenColumnSet(): Set<Int> {
        return hiddenColumnIds.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 0..7 }
            .toSet()
    }

    /** 保存隐藏列索引集合 */
    fun setHiddenColumns(columns: Set<Int>) {
        hiddenColumnIds = columns.filter { it in 0..7 }.sorted().joinToString(",")
        // 列 0(#) 和 1(复选框) 不允许隐藏，自动过滤
    }

    /** 解析持久化的列宽度映射 */
    fun getColumnWidthMap(): Map<Int, Int> {
        if (columnWidths.isBlank()) return emptyMap()
        return columnWidths.split(",").mapNotNull { entry ->
            val parts = entry.trim().split(":")
            if (parts.size == 2) {
                val col = parts[0].toIntOrNull()
                val w = parts[1].toIntOrNull()
                if (col != null && w != null && col in 2..7) col to w else null
            } else null
        }.toMap()
    }

    /** 持久化列宽度映射 */
    fun setColumnWidths(widths: Map<Int, Int>) {
        columnWidths = widths.entries
            .filter { it.key in 2..7 }
            .sortedBy { it.key }
            .joinToString(",") { "${it.key}:${it.value}" }
    }
}
