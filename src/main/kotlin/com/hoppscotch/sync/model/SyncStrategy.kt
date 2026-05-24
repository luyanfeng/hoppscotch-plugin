package com.hoppscotch.sync.model

/**
 * 同步策略枚举。
 *
 * 定义当服务端已存在请求时的处理方式。
 * label/description 为英文，labelZh/descriptionZh 为中文。
 */
enum class SyncStrategy(
    val id: String,
    val label: String,
    val description: String,
    val labelZh: String,
    val descriptionZh: String
) {
    SERVER_FIRST(
        id = "SERVER_FIRST",
        label = "Server First",
        description = "Existing requests on the server are preserved; new requests are created.",
        labelZh = "服务端优先",
        descriptionZh = "服务端已有的请求将跳过，不同步也不更新"
    ),

    PLUGIN_FIRST(
        id = "PLUGIN_FIRST",
        label = "Plugin First",
        description = "Existing requests on the server are overwritten by plugin content.",
        labelZh = "插件推送优先",
        descriptionZh = "服务端已有的请求将被插件推送的内容完全覆盖"
    ),

    MERGE_SERVER_FIRST(
        id = "MERGE_SERVER_FIRST",
        label = "Merge — Server First",
        description = "Server values are kept; only missing fields are added.",
        labelZh = "合并-服务端为主",
        descriptionZh = "合并时保留服务端已有字段值，只添加缺失字段"
    ),

    MERGE_PLUGIN_FIRST(
        id = "MERGE_PLUGIN_FIRST",
        label = "Merge — Plugin First",
        description = "Plugin values override corresponding server fields.",
        labelZh = "合并-推送为主",
        descriptionZh = "合并时用推送内容覆盖服务端对应字段值"
    );

    companion object {
        fun fromId(id: String): SyncStrategy =
            entries.find { it.id == id } ?: SERVER_FIRST
    }
}
