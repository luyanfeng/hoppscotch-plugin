package com.hoppscotch.sync.service

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.hoppscotch.sync.hoppscotch.HoppscotchClient
import com.hoppscotch.sync.hoppscotch.HoppscotchDataConverter
import com.hoppscotch.sync.model.*
import com.hoppscotch.sync.psi.SpringControllerParser

/**
 * 同步编排服务。
 *
 * 协调从 Spring Controller 解析到 Hoppscotch 同步的完整流程：
 * 1. 解析项目中的所有 [ControllerGroup]
 * 2. 对每个 [ControllerGroup] 创建 Hoppscotch 集合
 * 3. 对组内每个端点创建 Hoppscotch 请求
 * 4. 通过 [ProgressIndicator] 报告进度
 */
class SyncService(
    private val project: Project,
    private val client: HoppscotchClient,
    private val parser: SpringControllerParser,
    private val indicator: ProgressIndicator? = null
) {

    private val converter = HoppscotchDataConverter()

    /**
 * 同步指定的 Controller 组（跳过解析步骤，直接同步）。
     *
     * 增量同步模式：先查 Hoppscotch 上已有的集合和请求，跳过已存在的项目。
     *
     * @param groups 待同步的 Controller 组（每个组内的端点即待同步的端点）
     * @param targetParentCollectionId 如果指定，则在目标父集合下创建子集合（而非在根级创建）
     * @param createSubDirectories 当指定了 targetParentCollectionId 时，是否按项目名生成子集合
     * @return [SyncResult] 包含同步统计和错误列表
     */
    fun syncGroups(
        groups: List<ControllerGroup>,
        targetParentCollectionId: String? = null,
        strategy: SyncStrategy = SyncStrategy.SERVER_FIRST,
        createSubDirectories: Boolean = true
    ): SyncResult {
        // 未勾选"生成子目录"且指定了目标集合时，直接同步到目标集合
        if (targetParentCollectionId != null && !createSubDirectories) {
            return syncDirectToTarget(groups, targetParentCollectionId, strategy)
        }
        if (groups.isEmpty()) {
            indicator?.let {
                it.text = "未发现任何 Spring Controller"
                it.isIndeterminate = false
                it.fraction = 1.0
            }
            return SyncResult(total = 0)
        }

        val totalEndpoints = groups.sumOf { it.endpoints.size }
        var processedEndpoints = 0
        var collectionsCreated = 0
        var collectionsSkipped = 0
        var requestsCreated = 0
        var requestsSkipped = 0
        var requestsUpdated = 0
        var requestsMerged = 0
        val errors = mutableListOf<String>()
        val totalCollections = groups.size

        // 1. 查询已有集合（失败时不影响后续）
        val existingCollections: MutableMap<String, CollectionInfo>
        if (targetParentCollectionId != null) {
            existingCollections = client.listChildCollections(targetParentCollectionId)
                .getOrNull()?.associateBy { it.title }?.toMutableMap() ?: mutableMapOf()
        } else {
            existingCollections = client.listCollections()
                .getOrNull()?.associateBy { it.title }?.toMutableMap() ?: mutableMapOf()
        }

        indicator?.let {
            it.text = "准备同步 $totalCollections 个集合 / $totalEndpoints 个端点"
            it.fraction = 0.0
        }

        // 遍历每个 ControllerGroup
        for ((groupIndex, group) in groups.withIndex()) {
            val collectionTitle = buildCollectionTitle(group)
            val existingCollection = existingCollections[collectionTitle]

            indicator?.let {
                it.text = "[${groupIndex + 1}/$totalCollections] 处理集合: $collectionTitle"
                it.fraction = processedEndpoints.toDouble() / totalEndpoints.coerceAtLeast(1)
            }

            // 2a. 获取集合 ID（存在则复用，否则新建）
            val collectionInfo: CollectionInfo?
            if (existingCollection != null) {
                collectionInfo = existingCollection
                collectionsSkipped++
            } else {
                val collectionResult = if (targetParentCollectionId != null) {
                    client.createChildCollection(
                        title = collectionTitle,
                        parentCollectionId = targetParentCollectionId
                    )
                } else {
                    client.createCollection(collectionTitle)
                }
                collectionInfo = collectionResult.getOrNull()
                if (collectionInfo == null) {
                    val errorMsg = collectionResult.exceptionOrNull()?.message ?: "未知错误"
                    errors.add("集合 [$collectionTitle] 创建失败: $errorMsg")
                    processedEndpoints += group.endpoints.size
                    continue
                }
                // 新建的集合加入已有集合映射，避免同模块后续 group 重复创建
                existingCollections[collectionTitle] = collectionInfo
                collectionsCreated++
            }

            // 2b. 查询该集合中已有的请求（仅对已有集合才查询，新建集合已知为空）
            val existingRequestsMap: Map<String, RequestInfo> = if (existingCollection != null) {
                client.listRequests(collectionInfo.id)
                    .getOrNull()
                    ?.associateBy { it.title } ?: emptyMap()
            } else {
                emptyMap()
            }

            // 2c. 遍历组内端点创建请求
            for ((reqIndex, endpoint) in group.endpoints.withIndex()) {
                val requestTitle = buildRequestTitle(endpoint)
                val requestProgress = processedEndpoints.toDouble() / totalEndpoints.coerceAtLeast(1)

                indicator?.let {
                    it.text = "[${groupIndex + 1}/$totalCollections] 处理请求 [$reqIndex]: $requestTitle"
                    it.fraction = requestProgress.coerceIn(0.0, 0.99)
                }

                if (requestTitle in existingRequestsMap.keys) {
                    when (strategy) {
                        SyncStrategy.SERVER_FIRST -> {
                            requestsSkipped++
                            processedEndpoints++
                            continue
                        }
                        SyncStrategy.PLUGIN_FIRST -> {
                            val requestJson = try {
                                val hoppscotchRequest = converter.toHoppscotchRequest(endpoint)
                                converter.toRequestRequestBody(hoppscotchRequest)
                            } catch (e: Exception) {
                                errors.add("请求 [$requestTitle] 转换失败: ${e.message}")
                                processedEndpoints++
                                continue
                            }

                            val updateResult = client.updateRequest(
                                existingRequestsMap[requestTitle]!!.id,
                                requestTitle,
                                requestJson
                            )
                            if (updateResult.isSuccess) {
                                requestsUpdated++
                            } else {
                                errors.add("请求 [$requestTitle] 更新失败: ${updateResult.exceptionOrNull()?.message}")
                            }
                            processedEndpoints++
                            continue
                        }
                        SyncStrategy.MERGE_SERVER_FIRST, SyncStrategy.MERGE_PLUGIN_FIRST -> {
                            val newRequestJson = try {
                                val hoppscotchRequest = converter.toHoppscotchRequest(endpoint)
                                converter.toRequestRequestBody(hoppscotchRequest)
                            } catch (e: Exception) {
                                errors.add("请求 [$requestTitle] 转换失败: ${e.message}")
                                processedEndpoints++
                                continue
                            }

                            val serverFirst = strategy == SyncStrategy.MERGE_SERVER_FIRST
                            val mergedJson = mergeRequestJsons(
                                existingRequestsMap[requestTitle]!!.request,
                                newRequestJson,
                                serverFirst
                            )
                            val updateResult = client.updateRequest(
                                existingRequestsMap[requestTitle]!!.id,
                                requestTitle,
                                mergedJson
                            )
                            if (updateResult.isSuccess) {
                                requestsMerged++
                            } else {
                                errors.add("请求 [$requestTitle] 合并更新失败: ${updateResult.exceptionOrNull()?.message}")
                            }
                            processedEndpoints++
                            continue
                        }
                    }
                }

                // 转换为请求 JSON
                val requestJson = try {
                    val hoppscotchRequest = converter.toHoppscotchRequest(endpoint)
                    converter.toRequestRequestBody(hoppscotchRequest)
                } catch (e: Exception) {
                    errors.add("请求 [$requestTitle] 转换失败: ${e.message}")
                    processedEndpoints++
                    continue
                }

                // 创建请求
                val requestResult = client.createRequest(collectionInfo.id, requestTitle, requestJson)
                if (requestResult.isSuccess) {
                    requestsCreated++
                } else {
                    errors.add("请求 [$requestTitle] 创建失败: ${requestResult.exceptionOrNull()?.message}")
                }

                processedEndpoints++
            }
        }

        indicator?.let {
            val skipInfo = buildString {
                if (collectionsSkipped > 0) append("(跳过 ${collectionsSkipped} 个已存在) ")
                if (requestsSkipped > 0) append("(跳过 ${requestsSkipped} 个已存在) ")
                if (requestsUpdated > 0) append("(更新 ${requestsUpdated} 个) ")
                if (requestsMerged > 0) append("(合并 ${requestsMerged} 个) ")
            }
            it.text = "同步完成: 集合 $collectionsCreated 个 / 请求 $requestsCreated 个 ${skipInfo}"
            it.fraction = 1.0
        }

        return SyncResult(
            total = totalEndpoints,
            success = requestsCreated + requestsUpdated + requestsMerged,
            failed = totalEndpoints - requestsCreated - requestsUpdated - requestsMerged - requestsSkipped,
            collectionsCreated = collectionsCreated,
            requestsCreated = requestsCreated,
            requestsSkipped = requestsSkipped,
            requestsUpdated = requestsUpdated,
            requestsMerged = requestsMerged,
            errors = errors
        )
    }

    /**
     * 根据 [ControllerGroup] 构建 Hoppscotch 集合标题。
     *
     * 使用模块名/项目名作为集合标题，并做特殊字符替换。
     * 这样多个 Controller 在同一个模块下会共享一个集合。
     */
    private fun buildCollectionTitle(group: ControllerGroup): String {
        val raw = if (group.moduleName.isNotBlank()) group.moduleName
        else group.controllerClassName
        return sanitizeTitle(raw)
    }

    /**
     * 替换标题中 Hoppscotch/文件系统不友好的字符为下划线。
     */
    private fun sanitizeTitle(name: String): String {
        return name.replace(Regex("[<>:\"/\\\\|?*\\[\\]]"), "_")
    }

    /**
     * 根据 [SpringEndpoint] 构建请求标题。
     */
    private fun buildRequestTitle(endpoint: SpringEndpoint): String {
        return endpoint.fullPath
    }

    /**
     * 合并两个请求 JSON。
     *
     * @param serverJson 服务端的请求 JSON
     * @param newJson 插件推送的请求 JSON
     * @param serverFirst true=服务端优先（保留服务端字段），false=推送优先（用推送覆盖）
     * @return 合并后的 JSON 字符串
     */
    private fun mergeRequestJsons(serverJson: String, newJson: String, serverFirst: Boolean): String {
        return try {
            val serverObj = com.google.gson.JsonParser.parseString(serverJson).asJsonObject
            val newObj = com.google.gson.JsonParser.parseString(newJson).asJsonObject

            val result = com.google.gson.JsonObject()

            // 收集所有 key
            val allKeys = mutableSetOf<String>()
            allKeys.addAll(serverObj.keySet())
            allKeys.addAll(newObj.keySet())

            for (key in allKeys) {
                when {
                    serverFirst -> {
                        // 服务端优先：优先取服务端值，缺失时取推送值
                        result.add(key, serverObj.get(key) ?: newObj.get(key))
                    }
                    else -> {
                        // 推送优先：优先取推送值，缺失时取服务端值
                        result.add(key, newObj.get(key) ?: serverObj.get(key))
                    }
                }
            }

            result.toString()
        } catch (e: Exception) {
            // 合并失败时返回推送的完整内容
            newJson
        }
    }

    /**
     * 直接同步到目标集合（不生成子目录）。
     *
     * 将所有分组的所有端点直接作为请求创建到 [targetCollectionId] 中，
     * 跳过子集合的创建逻辑，并按 [strategy] 处理已存在的请求。
     */
    private fun syncDirectToTarget(
        groups: List<ControllerGroup>,
        targetCollectionId: String,
        strategy: SyncStrategy
    ): SyncResult {
        if (groups.isEmpty()) {
            indicator?.let {
                it.text = "未发现任何 Spring Controller"
                it.isIndeterminate = false
                it.fraction = 1.0
            }
            return SyncResult(total = 0)
        }

        val totalEndpoints = groups.sumOf { it.endpoints.size }
        var processedEndpoints = 0
        var requestsCreated = 0
        var requestsSkipped = 0
        var requestsUpdated = 0
        var requestsMerged = 0
        val errors = mutableListOf<String>()

        // 查询目标集合中已有的请求
        val existingRequestsMap: MutableMap<String, RequestInfo> = client.listRequests(targetCollectionId)
            .getOrNull()
            ?.associateBy { it.title }
            ?.toMutableMap() ?: mutableMapOf()

        indicator?.let {
            it.text = "准备同步 $totalEndpoints 个端点到目标集合"
            it.fraction = 0.0
        }

        // 将所有分组展开为 (title, endpoint) 列表
        val allEntries = groups.flatMap { group ->
            group.endpoints.map { endpoint ->
                buildRequestTitle(endpoint) to endpoint
            }
        }

        for ((reqIndex, entry) in allEntries.withIndex()) {
            val requestTitle = entry.first
            val endpoint = entry.second
            indicator?.let {
                it.text = "[${reqIndex + 1}/${totalEndpoints}] 处理请求: $requestTitle"
                it.fraction = processedEndpoints.toDouble() / totalEndpoints.coerceAtLeast(1)
            }

            if (requestTitle in existingRequestsMap) {
                when (strategy) {
                    SyncStrategy.SERVER_FIRST -> {
                        requestsSkipped++
                        processedEndpoints++
                        continue
                    }
                    SyncStrategy.PLUGIN_FIRST -> {
                        val requestJson = try {
                            val hoppscotchRequest = converter.toHoppscotchRequest(endpoint)
                            converter.toRequestRequestBody(hoppscotchRequest)
                        } catch (e: Exception) {
                            errors.add("请求 [$requestTitle] 转换失败: ${e.message}")
                            processedEndpoints++
                            continue
                        }
                        val updateResult = client.updateRequest(
                            existingRequestsMap[requestTitle]!!.id,
                            requestTitle,
                            requestJson
                        )
                        if (updateResult.isSuccess) {
                            requestsUpdated++
                        } else {
                            errors.add("请求 [$requestTitle] 更新失败: ${updateResult.exceptionOrNull()?.message}")
                        }
                        processedEndpoints++
                        continue
                    }
                    SyncStrategy.MERGE_SERVER_FIRST, SyncStrategy.MERGE_PLUGIN_FIRST -> {
                        val newRequestJson = try {
                            val hoppscotchRequest = converter.toHoppscotchRequest(endpoint)
                            converter.toRequestRequestBody(hoppscotchRequest)
                        } catch (e: Exception) {
                            errors.add("请求 [$requestTitle] 转换失败: ${e.message}")
                            processedEndpoints++
                            continue
                        }
                        val serverFirst = strategy == SyncStrategy.MERGE_SERVER_FIRST
                        val mergedJson = mergeRequestJsons(
                            existingRequestsMap[requestTitle]!!.request,
                            newRequestJson,
                            serverFirst
                        )
                        val updateResult = client.updateRequest(
                            existingRequestsMap[requestTitle]!!.id,
                            requestTitle,
                            mergedJson
                        )
                        if (updateResult.isSuccess) {
                            requestsMerged++
                        } else {
                            errors.add("请求 [$requestTitle] 合并更新失败: ${updateResult.exceptionOrNull()?.message}")
                        }
                        processedEndpoints++
                        continue
                    }
                }
            }

            // 转换为请求 JSON
            val requestJson = try {
                val hoppscotchRequest = converter.toHoppscotchRequest(endpoint)
                converter.toRequestRequestBody(hoppscotchRequest)
            } catch (e: Exception) {
                errors.add("请求 [$requestTitle] 转换失败: ${e.message}")
                processedEndpoints++
                continue
            }

            // 创建请求
            val requestResult = client.createRequest(targetCollectionId, requestTitle, requestJson)
            if (requestResult.isSuccess) {
                requestsCreated++
            } else {
                errors.add("请求 [$requestTitle] 创建失败: ${requestResult.exceptionOrNull()?.message}")
            }
            processedEndpoints++
        }

        indicator?.let {
            val skipInfo = buildString {
                if (requestsSkipped > 0) append("(跳过 ${requestsSkipped} 个已存在) ")
                if (requestsUpdated > 0) append("(更新 ${requestsUpdated} 个) ")
                if (requestsMerged > 0) append("(合并 ${requestsMerged} 个) ")
            }
            it.text = "同步完成: 请求 $requestsCreated 个 ${skipInfo}"
            it.fraction = 1.0
        }

        return SyncResult(
            total = totalEndpoints,
            success = requestsCreated + requestsUpdated + requestsMerged,
            failed = totalEndpoints - requestsCreated - requestsUpdated - requestsMerged - requestsSkipped,
            collectionsCreated = 0,
            requestsCreated = requestsCreated,
            requestsSkipped = requestsSkipped,
            requestsUpdated = requestsUpdated,
            requestsMerged = requestsMerged,
            errors = errors
        )
    }
}
