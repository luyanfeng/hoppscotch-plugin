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

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    companion object {
        /** 紧凑 JSON 序列化，用于服务端请求 hash 计算（确保 hash 一致） */
        private val compactGson: Gson = GsonBuilder().create()

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
         * 从持久化字符串中解析本地 hash。
         * 格式： "localHash,srvHash"
         */
        fun parseLocalHash(value: String): Int = value.substringBefore(",").toIntOrNull() ?: 0

        /**
         * 从持久化字符串中解析服务端请求 hash。
         * 格式： "localHash,srvHash"
         */
        fun parseSrvReqHash(value: String): Int = value.substringAfter(",", "0").toIntOrNull() ?: 0

        /**
         * 构建持久化值字符串。
         */
        fun buildSyncValue(localHash: Int, srvHash: Int): String = "$localHash,$srvHash"
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
            params.add(
                HoppscotchParam(
                    key = paramName,
                    value = endpointParam?.defaultValue ?: "",
                    active = true
                )
            )
        }

        // ---- 查询参数 ----
        // 当 consumes 为 form-data/urlencoded 时，@RequestParam 参数是表单字段，不放入 URL 查询串
        for (ep in endpoint.parameters.filter { it.source == ParamSource.QUERY }) {
            // 避免与路径变量重名
            if (ep.name !in pathParamNames) {
                if (isFormLike) {
                    formBodyParams.add(ep)
                } else {
                    if (ep.objectFields.isNotEmpty()) {
                        // 复杂对象（无 @RequestBody）展开为多个字段参数
                        for (field in ep.objectFields) {
                            params.add(HoppscotchParam(key = field, value = "", active = true))
                        }
                    } else {
                        params.add(
                            HoppscotchParam(
                                key = ep.name,
                                value = ep.defaultValue ?: "",
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
        } else if (hasRequestBody) {
            // 存在 @RequestBody 但未指定 consumes → 默认 application/json
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
            formBodyParams.isNotEmpty() -> buildFormBody(formBodyParams)
            hasRequestBody -> buildRequestBodyTemplate(endpoint)
            else -> HoppscotchBody()
        }

        return HoppscotchRequest(
            name = "${endpoint.httpMethod.name} ${endpoint.fullPath}",
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
     */
    private fun buildFormBody(formParams: List<EndpointParameter>): HoppscotchBody {
        val body = formParams.joinToString("&") { ep ->
            val value = ep.defaultValue?.takeIf { it.isNotBlank() } ?: ""
            "${ep.name}=$value"
        }
        return HoppscotchBody(body = body)
    }
}
