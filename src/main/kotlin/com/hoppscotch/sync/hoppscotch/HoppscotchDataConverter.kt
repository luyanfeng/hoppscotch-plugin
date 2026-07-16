package com.hoppscotch.sync.hoppscotch

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.hoppscotch.sync.model.*

/**
 * Spring 端点数据到 Hoppscotch 请求数据格式的转换器。
 *
 * 负责将 [SpringEndpoint] 转换为 [HoppscotchRequest]，
 * 并序列化为 JSON 字符串以用于 GraphQL 变更操作。
 */
class HoppscotchDataConverter {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()

    companion object {
        /** 紧凑 JSON 序列化，用于服务端请求 hash 计算（确保 hash 一致） */
        private val compactGson: Gson = GsonBuilder().serializeNulls().create()

        /**
         * 基于 [HoppscotchRequest] 计算服务端请求的规范化 hash。
         * 用于检测同步后服务端请求是否被外部修改。
         */
        fun computeServerRequestHash(request: HoppscotchRequest): Int {
            return compactGson.toJson(request).hashCode()
        }

        /**
         * 基于服务端返回的请求 JSON 字符串计算规范化 hash。
         * 先解析为 [HoppscotchRequest] 再重新序列化，消除格式差异。
         */
        fun computeServerRequestHashFromJson(requestJson: String): Int {
            return try {
                val request = compactGson.fromJson(requestJson, HoppscotchRequest::class.java)
                compactGson.toJson(request).hashCode()
            } catch (_: Exception) {
                // 若 JSON 格式不匹配，回退到原始字符串 hash
                requestJson.hashCode()
            }
        }

        /**
         * 检测持久化值是否包含 serverId。
         * 新格式 "serverId,localHash,srvHash"  有 2 个逗号 → true
         * 旧格式 "localHash,srvHash"           有 1 个逗号 → false
         */
        private fun hasServerId(value: String): Boolean = value.count { it == ',' } >= 2

        /**
         * 从持久化字符串中解析服务端请求 id。
         * 格式： "serverId,localHash,srvHash" → serverId
         * 旧格式（无 serverId）→ null
         */
        fun parseServerId(value: String): String? {
            if (!hasServerId(value)) return null
            return value.substringBefore(",").takeIf { it.isNotEmpty() }
        }

        /**
         * 从持久化字符串中解析本地 hash。
         * 新格式 "serverId,localHash,srvHash" → localHash
         * 旧格式 "localHash,srvHash"           → localHash
         * 旧格式 "localHash"                   → localHash
         */
        fun parseLocalHash(value: String): Int {
            val v = if (hasServerId(value)) value.substringAfter(",") else value
            return v.substringBefore(",").toIntOrNull() ?: 0
        }

        /**
         * 从持久化字符串中解析服务端请求 hash。
         * 新格式 "serverId,localHash,srvHash" → srvHash
         * 旧格式 "localHash,srvHash"           → srvHash
         */
        fun parseSrvReqHash(value: String): Int {
            val v = if (hasServerId(value)) value.substringAfter(",") else value
            return v.substringAfter(",", "0").toIntOrNull() ?: 0
        }

        /**
         * 构建持久化值字符串（无 serverId，旧格式）。
         * 格式： "localHash,srvHash"
         */
        fun buildSyncValue(localHash: Int, srvHash: Int): String = "$localHash,$srvHash"

        /**
         * 构建持久化值字符串（含 serverId，新格式）。
         * 格式： "serverId,localHash,srvHash"
         */
        fun buildSyncValue(serverId: String, localHash: Int, srvHash: Int): String = "$serverId,$localHash,$srvHash"

        /**
         * 根据参数类型名返回 HTTP 请求中合理的占位值（字符串形式）。
         * 用于路径变量、query param 等场景。
         */
        @JvmStatic
        fun placeholderValueForType(type: String): String = when {
            type in listOf(
                "int", "Integer", "java.lang.Integer",
                "long", "Long", "java.lang.Long",
                "short", "Short", "java.lang.Short",
                "byte", "Byte", "java.lang.Byte"
            ) -> "0"
            type in listOf(
                "double", "Double", "java.lang.Double",
                "float", "Float", "java.lang.Float"
            ) -> "0.0"
            type in listOf("boolean", "Boolean", "java.lang.Boolean") -> "true"
            type in listOf("java.util.UUID") -> "00000000-0000-0000-0000-000000000000"
            type in listOf(
                "String", "java.lang.String",
                "char", "Character", "java.lang.Character",
                "java.time.LocalDate", "java.time.LocalDateTime",
                "java.time.LocalTime", "java.util.Date"
            ) -> ""
            type.startsWith("List<") || type.startsWith("Set<") || type.startsWith("Collection<") -> ""
            else -> ""
        }
    }

    /**
     * 将单个 [SpringEndpoint] 转换为 [HoppscotchRequest]。
     *
     * 转换包括：
     * - HTTP 方法和路径 → Hoppscotch 请求方法和端点
     * - 路径参数（如 `/users/{id}`）→ Hoppscotch 参数
     * - 查询参数 → Hoppscotch 参数
     * - 请求头参数 → Hoppscotch 请求头
     * - @RequestBody → JSON 请求体模板
     * - Content-Type 请求头（根据 consumes 或请求体存在自动设置）
     */
    fun toHoppscotchRequest(endpoint: SpringEndpoint): HoppscotchRequest {
        val params = mutableListOf<HoppscotchParam>()
        val headers = mutableListOf<HoppscotchHeader>()

        // 判断 Content-Type 是否为 form-data 或 urlencoded
        val consumesLower = endpoint.consumes.joinToString(" ").lowercase()
        val isFormData = "form-data" in consumesLower || "multipart" in consumesLower
        val isFormUrlEncoded = "urlencoded" in consumesLower || "x-www-form-urlencoded" in consumesLower
        val isFormLike = isFormData || isFormUrlEncoded

        // 收集 QUERY 参数中需要转为 form body 的字段（当 consumes 为 form-data/urlencoded 时）
        val formBodyParams = mutableListOf<EndpointParameter>()

        // ---- 路径参数 ----
        // 从 fullPath 中提取 {paramName} 模式的路径变量
        val pathParamNames = extractPathParams(endpoint.fullPath).toSet()
        for (paramName in pathParamNames) {
            val endpointParam = endpoint.parameters.find {
                it.name == paramName && it.source == ParamSource.PATH
            }
            val value = when {
                endpointParam?.defaultValue != null -> endpointParam.defaultValue!!
                endpointParam != null -> placeholderValueForType(endpointParam.type)
                else -> paramName // 无对应参数 → 用参数名自身
            }
            params.add(
                HoppscotchParam(
                    key = paramName,
                    value = value,
                    active = true
                )
            )
        }

        // ---- 查询参数 ----
        // 当 consumes 为 form-data/urlencoded 时，@RequestParam 参数是表单字段，不放入 URL 查询串
        // MultipartFile 无论是否有 consumes 都转入 form body
        val isBodyMethod = endpoint.httpMethod.name in listOf("POST", "PUT", "PATCH")
        for (ep in endpoint.parameters.filter { it.source == ParamSource.QUERY }) {
            // 避免与路径变量重名
            if (ep.name !in pathParamNames) {
                if (isFormLike || ep.isMultipartFile) {
                    formBodyParams.add(ep)
                } else {
                    if (ep.objectFields.isNotEmpty()) {
                        // 复杂对象（无 @RequestBody）展开为多个字段参数
                        for (field in ep.objectFields) {
                            params.add(HoppscotchParam(key = field, value = "", active = true))
                        }
                        // POST/PUT/PATCH 中无 @RequestBody 的 POJO 也支持 form 表单传参
                        if (isBodyMethod) {
                            ep.objectFields.forEach { field ->
                                formBodyParams.add(
                                    EndpointParameter(
                                        name = field, type = "String",
                                        source = ParamSource.QUERY, required = false
                                    )
                                )
                            }
                        }
                    } else {
                        params.add(
                            HoppscotchParam(
                                key = ep.name,
                                value = ep.defaultValue ?: placeholderValueForType(ep.type),
                                active = true
                            )
                        )
                    }
                }
            }
        }

        // ---- 请求头参数 ----
        for (ep in endpoint.parameters.filter { it.source == ParamSource.HEADER }) {
            headers.add(
                HoppscotchHeader(
                    key = ep.name,
                    value = ep.defaultValue ?: "",
                    active = true
                )
            )
        }

        // ---- Content-Type 请求头 ----
        val hasRequestBody = endpoint.parameters.any { it.source == ParamSource.BODY } || formBodyParams.isNotEmpty()
        if (endpoint.consumes.isNotEmpty()) {
            // 使用 @RequestMapping(consumes = ...) 指定的 Content-Type
            headers.add(
                HoppscotchHeader(
                    key = "Content-Type",
                    value = endpoint.consumes.first(),
                    active = true
                )
            )
        } else if (formBodyParams.isNotEmpty()) {
            // form 表单参数 → 默认 application/x-www-form-urlencoded（含 multipart file 时改为 multipart/form-data）
            val ct = if (formBodyParams.any { it.isMultipartFile }) "multipart/form-data" else "application/x-www-form-urlencoded"
            headers.add(
                HoppscotchHeader(
                    key = "Content-Type",
                    value = ct,
                    active = true
                )
            )
        } else if (endpoint.parameters.any { it.source == ParamSource.BODY }) {
            // @RequestBody 但未指定 consumes → 默认 application/json
            headers.add(
                HoppscotchHeader(
                    key = "Content-Type",
                    value = "application/json",
                    active = true
                )
            )
        }

        // ---- 请求体 ----
        val body = when {
            formBodyParams.isNotEmpty() -> {
                val isMultipart = formBodyParams.any { it.isMultipartFile }
                val ct = endpoint.consumes.firstOrNull()
                    ?: if (isMultipart) "multipart/form-data"
                    else "application/x-www-form-urlencoded"
                // 当 multipart 模式下同时有 @RequestBody 时，把 JSON 模板也作为 text 字段加入 body
                if (isMultipart) {
                    val bodyParams = endpoint.parameters.filter { it.source == ParamSource.BODY }
                    for (bp in bodyParams) {
                        val jsonValue = bp.bodyJsonTemplate ?: "{}"
                        formBodyParams.add(
                            EndpointParameter(
                                name = bp.name, type = "String",
                                source = ParamSource.QUERY, required = false,
                                defaultValue = jsonValue
                            )
                        )
                    }
                }
                buildFormBody(formBodyParams, ct)
            }
            hasRequestBody -> buildRequestBodyTemplate(endpoint)
            else -> HoppscotchBody()
        }

        val displayName = endpoint.description?.takeIf { it.isNotBlank() }
            ?: "${endpoint.httpMethod.name} ${endpoint.fullPath}"
        return HoppscotchRequest(
            name = displayName,
            method = endpoint.httpMethod.name,
            endpoint = endpoint.fullPath,
            params = params,
            headers = headers,
            body = body
        )
    }

    /**
     * 将 [HoppscotchRequest] 序列化为 JSON 字符串，
     * 可用作 GraphQL 变更操作的请求体的一部分。
     */
    fun toRequestRequestBody(request: HoppscotchRequest): String {
        return gson.toJson(request)
    }

    // ======================== 辅助方法 ========================

    /**
     * 从路径模板中提取路径变量名。
     *
     * 例如 `/users/{userId}/posts/{postId}` → `["userId", "postId"]`
     */
    private fun extractPathParams(path: String): List<String> {
        val pattern = Regex("\\{([^}]+)}")
        return pattern.findAll(path).map { it.groupValues[1] }.toList()
    }

    /**
     * 为包含 [@RequestBody] 的端点构建 JSON 请求体模板。
     *
     * 优先使用解析器递归生成的 [EndpointParameter.bodyJsonTemplate]（含类型推导的占位值），
     * 若不可用则根据类型名回退为简单占位模板。
     */
    private fun buildRequestBodyTemplate(endpoint: SpringEndpoint): HoppscotchBody {
        val bodyParams = endpoint.parameters.filter { it.source == ParamSource.BODY }

        if (bodyParams.isEmpty()) {
            return HoppscotchBody()
        }

        val bodyParam = bodyParams.first()

        // 优先使用 PSI 递归解析的 JSON 模板
        if (bodyParam.bodyJsonTemplate != null) {
            return HoppscotchBody(
                contentType = "application/json",
                body = bodyParam.bodyJsonTemplate
            )
        }

        // 回退：基于类型名简单推断
        val typeHint = bodyParam.type
        val bodyContent = when {
            typeHint in listOf("String", "java.lang.String") -> "\"\""
            typeHint in listOf("int", "Integer", "java.lang.Integer") -> "0"
            typeHint in listOf("long", "Long", "java.lang.Long") -> "0"
            typeHint in listOf("boolean", "Boolean", "java.lang.Boolean") -> "false"
            typeHint in listOf("double", "Double", "java.lang.Double") -> "0.0"
            typeHint in listOf("float", "Float", "java.lang.Float") -> "0.0"
            typeHint.startsWith("List<") || typeHint.startsWith("Set<") || typeHint.startsWith("Collection<") -> "[]"
            typeHint.startsWith("Map<") -> "{}"
            else -> "{\n  \"${bodyParam.name}\": {}\n}"
        }

        return HoppscotchBody(
            contentType = "application/json",
            body = bodyContent
        )
    }

    /**
     * 为 consumes = multipart/form-data 或 application/x-www-form-urlencoded 的端点
     * 构建 form 请求体。
     *
     * - urlencoded → body = "key=value&k2=v2"（字符串）
     * - multipart   → body = List<Map>，Gson 序列化为 JSON 数组
     *                 `[{key, value, type}]` 格式，符合 Hoppscotch Zod schema
     */
    private fun buildFormBody(formParams: List<EndpointParameter>, contentType: String): HoppscotchBody {
        val body: Any = if (contentType == "multipart/form-data") {
            // Hoppscotch 的 multipart body 是 FormDataKeyValue 数组
            formParams.map { ep ->
                val value = ep.defaultValue?.takeIf { it.isNotBlank() } ?: ""
                val type = if (ep.isMultipartFile) "file" else "text"
                mapOf(
                    "key" to ep.name,
                    "value" to value,
                    "type" to type
                )
            }
        } else {
            formParams.joinToString("&") { ep ->
                val value = ep.defaultValue?.takeIf { it.isNotBlank() } ?: ""
                "${ep.name}=$value"
            }
        }
        return HoppscotchBody(contentType = contentType, body = body)
    }
}
