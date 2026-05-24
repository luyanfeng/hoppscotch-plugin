package com.hoppscotch.sync.hoppscotch

import com.google.gson.*
import com.hoppscotch.sync.model.*
import com.hoppscotch.sync.util.LogUtil
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Hoppscotch GraphQL API 客户端，支持 access_token + refresh_token 自动刷新。
 *
 * 认证流程：
 * 1. 首次使用 refreshToken 调用 [GET /v1/auth/refresh] 获取 accessToken
 * 2. GraphQL 请求携带 Bearer accessToken
 * 3. 检测到 401/Unauthorized 时自动用 refreshToken 刷新 token 对
 * 4. 刷新成功后通过 [onTokenRefreshed] 回调持久化新 token
 */
class HoppscotchClient(
    private val serverUrl: String,
    accessToken: String,
    private var refreshToken: String?,
    private val onTokenRefreshed: ((accessToken: String, refreshToken: String?) -> Unit)? = null
) : AutoCloseable {

    private val normalizedUrl: String = serverUrl.trimEnd('/')

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    private val gson: Gson = GsonBuilder().create()

    /** 当前 accessToken，可能被刷新调用更新 */
    @Volatile
    var currentAccessToken: String = accessToken
        private set

    /** 当前 refreshToken，可能被刷新调用更新 */
    @Volatile
    var currentRefreshToken: String? = refreshToken
        private set

    private val refreshLock = Any()

    // ====================================================================
    //  Public API
    // ====================================================================

    /**
     * 尝试用当前 refreshToken 去刷新 accessToken。
     * 如果初始未提供 accessToken，也通过此方法获取。
     *
     * @return true 表示刷新成功，false 表示失败（token 无效等）
     */
    fun tryRefreshSession(): Boolean {
        if (refreshToken == null) return false
        return refreshAccessToken()
    }

    /**
     * 仅获取指定标题集合对应的请求标题。
     * 先获取指定层级（根级或 target 父集合下）的集合，按标题匹配后只遍历匹配的集合及其子集合。
     * 用于优化"检查同步状态"时只查询选中项目的数据。
     *
     * @param expectedTitles 期望匹配的集合标题
     * @param parentCollectionId 如果指定，则在该父集合的子集合中搜索（target 模式）；否则搜索根集合
     */
    fun listRequestTitlesForCollections(
        expectedTitles: Set<String>,
        parentCollectionId: String? = null
    ): Result<Set<String>> {
        if (expectedTitles.isEmpty()) return Result.success(emptySet())

        val collections = if (parentCollectionId != null) {
            LogUtil.stdout { "[HS-API] 查询 target 集合 [$parentCollectionId] 下的子集合"}
            listChildCollections(parentCollectionId).getOrNull()
                ?: return Result.failure(HoppscotchException("获取 target 子集合列表失败"))
        } else {
            listCollections().getOrNull()
                ?: return Result.failure(HoppscotchException("获取集合列表失败"))
        }

        LogUtil.stdout { "[HS-API] 服务端${if (parentCollectionId != null) "子" else "根"}集合数量: ${collections.size}"}
        collections.forEach { coll ->
            val matched = if (coll.title in expectedTitles) "✓ 匹配" else "✗ 不匹配"
            LogUtil.stdout { "[HS-API]   集合: [${coll.title}] (id: ${coll.id}) $matched"}
        }

        val allTitles = mutableSetOf<String>()

        fun traverseCollection(collectionId: String) {
            val titles = listRequests(collectionId).getOrNull()
                ?.map { it.title }
                ?.toSet() ?: emptySet()
            allTitles.addAll(titles)

            Thread.sleep(300)

            val children = listChildCollections(collectionId).getOrNull() ?: emptyList()
            for (child in children) {
                traverseCollection(child.id)
                Thread.sleep(300)
            }
        }

        for (coll in collections) {
            if (coll.title in expectedTitles) {
                traverseCollection(coll.id)
            }
            Thread.sleep(300)
        }
        return Result.success(allTitles)
    }

    /**
     * 查询与 [expectedTitles] 标题匹配的集合下的所有请求信息（含完整的 request JSON）。
     * 与 [listRequestTitlesForCollections] 逻辑相同，但返回完整的 [RequestInfo]。
     *
     * @param expectedTitles 目标集合标题集合（匹配成功的集合的所有请求都会被收集）
     * @param parentCollectionId 限定搜索的父集合 ID；null 表示从根集合开始
     * @return 匹配到的所有 [RequestInfo] 列表
     */
    fun listRequestInfosForCollections(
        expectedTitles: Set<String>,
        parentCollectionId: String? = null
    ): Result<List<RequestInfo>> {
        if (expectedTitles.isEmpty()) return Result.success(emptyList())

        val collections = if (parentCollectionId != null) {
            listChildCollections(parentCollectionId).getOrNull()
                ?: return Result.failure(HoppscotchException("获取 target 子集合列表失败"))
        } else {
            listCollections().getOrNull()
                ?: return Result.failure(HoppscotchException("获取集合列表失败"))
        }

        val allRequests = mutableListOf<RequestInfo>()

        fun traverseCollection(collectionId: String) {
            val requests = listRequests(collectionId).getOrNull() ?: emptyList()
            allRequests.addAll(requests)
            Thread.sleep(300)
            val children = listChildCollections(collectionId).getOrNull() ?: emptyList()
            for (child in children) {
                traverseCollection(child.id)
                Thread.sleep(300)
            }
        }

        for (coll in collections) {
            if (coll.title in expectedTitles) {
                traverseCollection(coll.id)
            }
            Thread.sleep(300)
        }
        return Result.success(allRequests)
    }

    fun createCollection(title: String): Result<CollectionInfo> {
        val mutation = """
            mutation createRESTRootUserCollection(${'$'}title: String!) {
                createRESTRootUserCollection(title: ${'$'}title) {
                    id
                    title
                }
            }
        """.trimIndent()

        val variables = buildMap<String, Any> {
            put("title", title)
        }

        val body = buildMap<String, Any> {
            put("query", mutation)
            put("variables", variables)
        }

        return executeWithRefresh(body, "createRESTRootUserCollection") { data ->
            val obj = data.asJsonObject
            CollectionInfo(
                id = obj.get("id").asString,
                title = obj.get("title").asString
            )
        }
    }

    /**
     * 查询当前用户已有的所有根级 REST 集合。
     * @param take 最多返回条数（默认 1000）
     */
    fun listCollections(take: Int = 1000): Result<List<CollectionInfo>> {
        val query = """
            query ListCollections(${'$'}take: Int) {
                rootRESTUserCollections(take: ${'$'}take) {
                    id
                    title
                }
            }
        """.trimIndent()

        val variables = buildMap<String, Any> {
            put("take", take)
        }

        val body = buildMap<String, Any> {
            put("query", query)
            put("variables", variables)
        }

        return executeWithRefresh(body, "rootRESTUserCollections") { data ->
            if (data.isJsonNull) emptyList()
            else data.asJsonArray.map { element ->
                val obj = element.asJsonObject
                CollectionInfo(
                    id = obj.get("id").asString,
                    title = obj.get("title").asString
                )
            }
        }
    }

    /**
     * 查询指定集合下已有的所有 REST 请求。
     * @param collectionId 集合 ID
     * @param take 最多返回条数（默认 1000）
     */
    fun listRequests(collectionId: String, take: Int = 1000): Result<List<RequestInfo>> {
        val query = """
            query ListRequests(${'$'}collectionID: ID!, ${'$'}take: Int) {
                userRESTRequests(collectionID: ${'$'}collectionID, take: ${'$'}take) {
                    id
                    title
                    request
                }
            }
        """.trimIndent()

        val variables = buildMap<String, Any> {
            put("collectionID", collectionId)
            put("take", take)
        }

        val body = buildMap<String, Any> {
            put("query", query)
            put("variables", variables)
        }

        return executeWithRefresh(body, "userRESTRequests") { data ->
            if (data.isJsonNull) emptyList()
            else data.asJsonArray.map { element ->
                val obj = element.asJsonObject
                RequestInfo(
                    id = obj.get("id").asString,
                    title = obj.get("title").asString,
                    request = obj.get("request")?.asString ?: ""
                )
            }
        }
    }

    fun createRequest(collectionId: String, title: String, requestJson: String): Result<RequestInfo> {
        val mutation = """
            mutation createRESTUserRequest(${'$'}collectionID: ID!, ${'$'}title: String!, ${'$'}request: String!) {
                createRESTUserRequest(collectionID: ${'$'}collectionID, title: ${'$'}title, request: ${'$'}request) {
                    id
                    title
                }
            }
        """.trimIndent()

        val variables = buildMap<String, Any> {
            put("collectionID", collectionId)
            put("title", title)
            put("request", requestJson)
        }

        val body = buildMap<String, Any> {
            put("query", mutation)
            put("variables", variables)
        }

        return executeWithRefresh(body, "createRESTUserRequest") { data ->
            val obj = data.asJsonObject
            RequestInfo(
                id = obj.get("id").asString,
                title = obj.get("title").asString
            )
        }
    }

    /**
     * 更新指定 ID 的请求内容。
     *
     * @param requestId 请求的唯一 ID
     * @param title 请求标题（可与原值相同）
     * @param requestJson 请求内容的 JSON 字符串
     * @return 更新后的请求信息
     */
    fun updateRequest(requestId: String, title: String, requestJson: String): Result<RequestInfo> {
        val mutation = """
            mutation updateRESTUserRequest(${'$'}id: ID!, ${'$'}title: String!, ${'$'}request: String!) {
                updateRESTUserRequest(id: ${'$'}id, title: ${'$'}title, request: ${'$'}request) {
                    id
                    title
                }
            }
        """.trimIndent()

        val variables = buildMap<String, Any> {
            put("id", requestId)
            put("title", title)
            put("request", requestJson)
        }

        val body = buildMap<String, Any> {
            put("query", mutation)
            put("variables", variables)
        }

        return executeWithRefresh(body, "updateRESTUserRequest") { data ->
            val obj = data.asJsonObject
            RequestInfo(
                id = obj.get("id").asString,
                title = obj.get("title").asString
            )
        }
    }

    /**
     * 查询指定集合的子集合。
     */
    fun listChildCollections(parentId: String, take: Int = 1000): Result<List<CollectionInfo>> {
        val query = """
            query ListChildCollections(${'$'}collectionID: ID!, ${'$'}take: Int) {
                userCollection(userCollectionID: ${'$'}collectionID) {
                    childrenREST(take: ${'$'}take) {
                        id
                        title
                    }
                }
            }
        """.trimIndent()

        val variables = buildMap<String, Any> {
            put("collectionID", parentId)
            put("take", take)
        }

        val body = buildMap<String, Any> {
            put("query", query)
            put("variables", variables)
        }

        return executeWithRefresh(body, "userCollection") { data ->
            val children = data.asJsonObject.get("childrenREST")
            if (children == null || children.isJsonNull) emptyList()
            else children.asJsonArray.map { element ->
                val obj = element.asJsonObject
                CollectionInfo(id = obj.get("id").asString, title = obj.get("title").asString)
            }
        }
    }

    /**
     * 在指定父集合下创建子集合。
     */
    fun createChildCollection(
        title: String,
        parentCollectionId: String
    ): Result<CollectionInfo> {
        val mutation = """
            mutation CreateChildCollection(${'$'}title: String!, ${'$'}parentUserCollectionID: ID!) {
                createRESTChildUserCollection(title: ${'$'}title, parentUserCollectionID: ${'$'}parentUserCollectionID) {
                    id
                    title
                }
            }
        """.trimIndent()

        val variables = buildMap<String, Any> {
            put("title", title)
            put("parentUserCollectionID", parentCollectionId)
        }

        val body = buildMap<String, Any> {
            put("query", mutation)
            put("variables", variables)
        }

        return executeWithRefresh(body, "createRESTChildUserCollection") { data ->
            val obj = data.asJsonObject
            CollectionInfo(id = obj.get("id").asString, title = obj.get("title").asString)
        }
    }

    /**
     * 删除指定 ID 的集合（递归删除其下所有请求和子集合）。
     * 返回 true 表示删除成功。
     */
    fun deleteCollection(collectionId: String): Result<Boolean> {
        val mutation = """
            mutation DeleteCollection(${'$'}userCollectionID: ID!) {
                deleteUserCollection(userCollectionID: ${'$'}userCollectionID)
            }
        """.trimIndent()

        val variables = buildMap<String, Any> {
            put("userCollectionID", collectionId)
        }

        val body = buildMap<String, Any> {
            put("query", mutation)
            put("variables", variables)
        }

        return executeWithRefresh(body, "deleteUserCollection") { data ->
            data.asJsonObject.get("deleteUserCollection").asBoolean
        }
    }

    // ====================================================================
    //  Token Refresh (instance methods)
    // ====================================================================

    /**
     * 调用 [GET /v1/auth/desktop] 获取新的 token 对（用当前 access_token 授权）。
     */
    private fun refreshViaDesktop(): Boolean {
        if (currentAccessToken.isBlank()) return false
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$normalizedUrl/v1/auth/desktop?redirect_uri=http://localhost:12345"))
                .header("Authorization", "Bearer $currentAccessToken")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build()
            val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .orTimeout(20, TimeUnit.SECONDS)
                .get(20, TimeUnit.SECONDS)
            if (response.statusCode() != 200) return false
            val json = JsonParser.parseString(response.body()).asJsonObject
            val newAccess = json.get("access_token")?.asString ?: return false
            val newRefresh = json.get("refresh_token")?.asString
            currentAccessToken = newAccess
            if (newRefresh != null) currentRefreshToken = newRefresh
            onTokenRefreshed?.invoke(currentAccessToken, currentRefreshToken)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 调用 [GET /v1/auth/desktop]，用 refresh_token 换取新的 token 对。
     * 该端点通过 Bearer Authorization 传递 refresh_token，返回新 access_token 和 refresh_token。
     */
    private fun refreshAccessToken(): Boolean {
        synchronized(refreshLock) {
            val rt = currentRefreshToken ?: return false

            return try {
                val request = HttpRequest.newBuilder()
                    .uri(URI.create("$normalizedUrl/v1/auth/desktop?redirect_uri=http://localhost:12345"))
                    .header("Authorization", "Bearer $rt")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build()

                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() != 200) return false

                val json = JsonParser.parseString(response.body()).asJsonObject
                val newAccess = json.get("access_token")?.asString ?: return false
                val newRefresh = json.get("refresh_token")?.asString

                currentAccessToken = newAccess
                if (newRefresh != null) currentRefreshToken = newRefresh

                onTokenRefreshed?.invoke(currentAccessToken, currentRefreshToken)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    // ====================================================================
    //  GraphQL Execution with Auto-Refresh
    // ====================================================================

    /**
     * 执行 GraphQL 请求，遇到 401/Unauthorized 时自动刷新 token 并重试一次。
     */
    private fun <T> executeWithRefresh(
        body: Map<String, Any>,
        operationName: String,
        extractor: (JsonElement) -> T
    ): Result<T> {
        for (attempt in 1..2) {
            val result = doGraphQLRequest(body, operationName, extractor)
            if (result.isSuccess) return result

            // 第一次失败且是认证错误时尝试刷新或 desktop 端点获取新 token
            if (attempt == 1) {
                val errMsg = result.exceptionOrNull()?.message ?: ""
                if (isAuthError(errMsg)) {
                    // 先尝试 refresh token
                    if (currentRefreshToken != null && refreshAccessToken()) continue
                    // refresh 失败时，尝试 desktop 端点（用当前 access_token 换取新 token）
                    if (refreshViaDesktop()) continue
                }
            }
            return result
        }
        return Result.failure(HoppscotchException("请求重试耗尽"))
    }

    private fun <T> doGraphQLRequest(
        body: Map<String, Any>,
        operationName: String,
        extractor: (JsonElement) -> T
    ): Result<T> {
        return try {
            val requestBody = gson.toJson(body)
            LogUtil.stdout { "[HS-API] >>> Request [$operationName]: ${requestBody.take(3000)}"}

            val httpRequest = HttpRequest.newBuilder()
                .uri(URI.create("$normalizedUrl/graphql"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $currentAccessToken")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build()

            val response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString())
            val responseBody = response.body()
            LogUtil.stdout { "[HS-API] <<< Response [$operationName]: HTTP ${response.statusCode()}, body=${responseBody.take(3000)}"}

            if (response.statusCode() != 200) {
                LogUtil.stdout { "[HS-API] <<< ERROR HTTP ${response.statusCode()}: ${responseBody.take(2000)}"}
                return Result.failure(HoppscotchException(
                    "HTTP ${response.statusCode()}: $responseBody"
                ))
            }

            val jsonResponse = JsonParser.parseString(responseBody).asJsonObject

            jsonResponse.get("errors")?.let { errorsElement ->
                val errors = errorsElement.asJsonArray
                if (errors.size() > 0) {
                    val messages = errors.map { it.asJsonObject.get("message").asString }
                    LogUtil.stdout { "[HS-API] <<< GraphQL errors: $messages"}
                    return Result.failure(HoppscotchException(
                        "GraphQL 错误: ${messages.joinToString("; ")}"
                    ))
                }
            }

            val dataObj = jsonResponse.getAsJsonObject("data")
                ?: return Result.failure(HoppscotchException("GraphQL 响应中没有 data 字段"))

            val operationResult = dataObj.get(operationName)
                ?: return Result.failure(HoppscotchException("GraphQL 响应中没有 $operationName 字段"))

            LogUtil.stdout { "[HS-API] <<< OK [$operationName], result type=${operationResult.javaClass.simpleName}"}
            Result.success(extractor(operationResult))

        } catch (e: Exception) {
            LogUtil.stdout { "[HS-API] <<< Exception: ${e.message}"}
            Result.failure(HoppscotchException("GraphQL 请求失败: ${e.message}", e))
        }
    }

    /** 判断错误是否跟认证有关 */
    private fun isAuthError(msg: String): Boolean {
        val lowered = msg.lowercase()
        return lowered.contains("unauthorized") ||
                lowered.contains("unauthenticated") ||
                lowered.contains("401") ||
                lowered.contains("access_denied") ||
                lowered.contains("forbidden") ||
                lowered.contains("not_found") && lowered.contains("cookie")
    }

    override fun close() {
        client.close()
    }

    companion object {
        /**
         * 验证 token 是否有效。
         * 使用 `sendAsync().orTimeout()` 确保即使底层网络挂起也能触发超时。
         */
        fun verifyTokens(
            serverUrl: String,
            accessToken: String,
            refreshToken: String?
        ): Pair<Boolean, String> {
            val baseUrl = serverUrl.trimEnd('/')
            var at = accessToken
            val rt = refreshToken
            LogUtil.stdout { "[HS-verify] verifyTokens called: baseUrl=$baseUrl atBlank=${at.isBlank()} rt=${rt?.let { "<${it.length} chars>" } ?: "null"}"}

            if (at.isBlank() && rt.isNullOrBlank())
                return false to "请提供 Access Token 或 Refresh Token"

            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build()
            LogUtil.stdout { "[HS-verify] HttpClient created"}

            try {
                // 1. 如果有 refreshToken，尝试刷新获取新 access_token（失败不终止）
                if (!rt.isNullOrBlank()) {
                    LogUtil.stdout { "[HS-verify] Attempting refresh via $baseUrl/v1/auth/refresh"}
                    val refreshRequest = HttpRequest.newBuilder()
                        .uri(URI.create("$baseUrl/v1/auth/refresh"))
                        .header("Cookie", "refresh_token=$rt")
                        .timeout(Duration.ofSeconds(15))
                        .GET()
                        .build()

                    try {
                        val refreshResponse = client.sendAsync(refreshRequest, HttpResponse.BodyHandlers.ofString())
                            .orTimeout(20, TimeUnit.SECONDS)
                            .get(20, TimeUnit.SECONDS)
                        LogUtil.stdout { "[HS-verify] Refresh response: HTTP ${refreshResponse.statusCode()}"}
                        if (refreshResponse.statusCode() == 200) {
                            val (newAccess, newRefresh) = parseSetCookieTokensStatic(refreshResponse)
                            if (newAccess != null) {
                                at = newAccess
                                LogUtil.stdout { "[HS-verify] Refresh succeeded, using new access_token"}
                            }
                        } else {
                            LogUtil.stdout { "[HS-verify] Refresh failed (HTTP ${refreshResponse.statusCode()}), falling back to provided access_token"}
                        }
                    } catch (e: Exception) {
                        LogUtil.stdout { "[HS-verify] Refresh exception: ${e.message}, falling back to provided access_token"}
                    }
                }

                // 2. 如果没有有效的 access_token，到此可以报错了
                if (at.isBlank()) {
                    return false to "没有可用的 Access Token（Refresh 也失败了）"
                }

                // 3. 用 accessToken 调用 GraphQL me 查询
                LogUtil.stdout { "[HS-verify] Calling GraphQL me query with access_token"}
                val meQuery = """{"query":"query { me { uid displayName } }"}"""
                val meRequest = HttpRequest.newBuilder()
                    .uri(URI.create("$baseUrl/graphql"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer $at")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(meQuery))
                    .build()

                val meResponse = client.sendAsync(meRequest, HttpResponse.BodyHandlers.ofString())
                    .orTimeout(20, TimeUnit.SECONDS)
                    .get(20, TimeUnit.SECONDS)
                LogUtil.stdout { "[HS-verify] GraphQL response: HTTP ${meResponse.statusCode()}"}
                val body = meResponse.body()

                if (meResponse.statusCode() != 200) {
                    return false to "GraphQL 请求失败 (HTTP ${meResponse.statusCode()}): ${body.take(200)}"
                }

                val json = JsonParser.parseString(body).asJsonObject
                json.get("errors")?.let { errors ->
                    val msgs2 = errors.asJsonArray.map { it.asJsonObject.get("message").asString }
                    return false to "GraphQL 错误: ${msgs2.joinToString("; ")}"
                }

                val me = json.getAsJsonObject("data")?.getAsJsonObject("me")
                val uid = me?.get("uid")?.asString ?: "(unknown)"
                val displayName = me?.get("displayName")?.asString ?: "(no name)"

                // 如果 refresh_token 是旧的（未刷新成功），尝试 desktop 端点获取新 refresh_token
                var newRt: String? = null
                if (rt != null && at == accessToken) {
                    LogUtil.stdout { "[HS-verify] Trying desktop endpoint for new refresh_token"}
                    try {
                        val desktopReq = HttpRequest.newBuilder()
                            .uri(URI.create("$baseUrl/v1/auth/desktop?redirect_uri=http://localhost:12345"))
                            .header("Authorization", "Bearer $at")
                            .timeout(Duration.ofSeconds(15))
                            .GET()
                            .build()
                        val desktopResp = client.sendAsync(desktopReq, HttpResponse.BodyHandlers.ofString())
                            .orTimeout(20, TimeUnit.SECONDS)
                            .get(20, TimeUnit.SECONDS)
                        if (desktopResp.statusCode() == 200) {
                            val desktopJson = JsonParser.parseString(desktopResp.body()).asJsonObject
                            newRt = desktopJson.get("refresh_token")?.asString
                            LogUtil.stdout { "[HS-verify] Got new refresh_token via desktop endpoint"}
                        }
                    } catch (e: Exception) {
                        LogUtil.stdout { "[HS-verify] Desktop endpoint failed: ${e.message}"}
                    }
                }

                val msg = buildString {
                    appendLine("✅ Token 验证成功")
                    appendLine("用户: $displayName")
                    appendLine("UID: $uid")
                    if (newRt != null) {
                        appendLine()
                        appendLine("新 Refresh Token（已自动获取，请保存到设置）：")
                        append(newRt)
                    }
                }
                return true to msg

            } catch (e: TimeoutException) {
                LogUtil.stdout { "[HS-verify] TimeoutException: ${e.message}"}
                return false to "验证超时，请检查 Server URL 和网络连通性"
            } catch (e: Exception) {
                LogUtil.stdout { "[HS-verify] Exception: ${e.javaClass.name}: ${e.message}" }
                LogUtil.stackTrace(e)
                return false to "验证失败: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                client.close()
                LogUtil.stdout { "[HS-verify] client closed"}
            }
        }

        private fun parseSetCookieTokensStatic(response: HttpResponse<String>): Pair<String?, String?> {
            var accessToken: String? = null
            var refreshToken: String? = null

            for (cookieHeader in response.headers().allValues("Set-Cookie")) {
                val equalsIdx = cookieHeader.indexOf('=')
                if (equalsIdx < 0) continue
                val name = cookieHeader.substring(0, equalsIdx).trim()
                val semiIdx = cookieHeader.indexOf(';', equalsIdx)
                val value = if (semiIdx < 0)
                    cookieHeader.substring(equalsIdx + 1).trim()
                else
                    cookieHeader.substring(equalsIdx + 1, semiIdx).trim()

                when (name) {
                    "access_token" -> accessToken = value
                    "refresh_token" -> refreshToken = value
                }
            }
            return accessToken to refreshToken
        }
    }
}

/**
 * Hoppscotch API 调用异常。
 */
class HoppscotchException(message: String, cause: Throwable? = null) : Exception(message, cause)
