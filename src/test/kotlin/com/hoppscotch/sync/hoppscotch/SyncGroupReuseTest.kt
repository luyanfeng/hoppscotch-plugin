package com.hoppscotch.sync.hoppscotch

import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * 验证 syncGroups 中集合复用逻辑的集成测试。
 *
 * 模拟用户场景：
 * 1. 在 target 下创建项目集合 → 控制器子集合 → 请求（第一次同步）
 * 2. 再次同步同一个控制器 → 验证复用了已有集合，而不是新建
 * 3. 清理所有测试数据
 *
 * 运行：
 *   export HOPPSCOTCH_URL=... HOPPSCOTCH_ACCESS_TOKEN=...
 *   ./gradlew runSyncGroupReuseTest
 */
object SyncGroupReuseTest {

    private const val TEST_PREFIX = "__hoppscotch_plugin_sync_test__"
    private const val PROJECT_NAME = "${TEST_PREFIX}_project"
    private const val CONTROLLER_NAME = "${TEST_PREFIX}_controller"
    private const val REQUEST_TITLE = "GET /api/test/reuse"

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    private val gson = com.google.gson.GsonBuilder().create()

    @JvmStatic
    fun main(args: Array<String>) {
        val serverUrl = (System.getProperty("HOPPSCOTCH_URL") ?: System.getenv("HOPPSCOTCH_URL")
            ?: error("缺少 HOPPSCOTCH_URL")).trimEnd('/')
        val accessToken = System.getProperty("HOPPSCOTCH_ACCESS_TOKEN") ?: System.getenv("HOPPSCOTCH_ACCESS_TOKEN")
            ?: error("缺少 HOPPSCOTCH_ACCESS_TOKEN")

        var targetCollId: String? = null   // 用户选中的 target（如 gn）
        var projectCollId: String? = null   // 项目集合（如 dlyx-b-epower-base）
        var controllerCollId: String? = null // 控制器子集合（如 电子签章-用章记录）
        var createdRequestId: String? = null

        try {
            println("=" .repeat(60))
            println("SyncService 集合复用逻辑验证测试")
            println("服务端: $serverUrl")
            println("=" .repeat(60))

            // ── Step 0: 清理前次残留 ──
            println("\n[步骤 0] 清理残留测试数据...")
            cleanupTestData(serverUrl, accessToken)

            // ── Step 1: 获取一个 target 集合（用根集合 "gn"，如果没有就创建一个） ──
            println("\n[步骤 1] 准备 target 集合...")
            val roots = queryList(serverUrl, accessToken,
                """query { rootRESTUserCollections(take: 100) { id title } }""",
                "rootRESTUserCollections"
            )
            targetCollId = roots.firstOrNull { it.get("title").asString == "gn" }?.get("id")?.asString
            if (targetCollId == null) {
                val created = mutation(serverUrl, accessToken,
                    """mutation { createRESTRootUserCollection(title: "${TEST_PREFIX}_target") { id title } }""",
                    "createRESTRootUserCollection"
                )
                targetCollId = created.get("id").asString
                println("  创建 target 集合: id=$targetCollId, title=${created.get("title").asString}")
            } else {
                println("  使用已有 target 集合: id=$targetCollId, title=gn")
            }

            // ── Step 2: 第一次同步 — 创建项目集合 → 控制器子集合 → 请求 ──
            println("\n[步骤 2] 第一次同步：创建项目集合 + 控制器子集合 + 请求...")

            // 2a. 查 target 下是否有项目集合
            val projectCols = queryList(serverUrl, accessToken,
                """query { userCollection(userCollectionID: "$targetCollId") { childrenREST(take: 100) { id title } } }""",
                "userCollection"
            ) { data ->
                val children = data.asJsonObject.get("childrenREST")
                if (children == null || children.isJsonNull) emptyList()
                else children.asJsonArray.map { it.asJsonObject }
            }
            var existingProject = projectCols.firstOrNull { it.get("title").asString == PROJECT_NAME }
            if (existingProject != null) {
                projectCollId = existingProject.get("id").asString
                println("  项目集合已存在: id=$projectCollId, title=$PROJECT_NAME（复用）")
            } else {
                val created = mutation(serverUrl, accessToken,
                    """mutation { createRESTChildUserCollection(title: "$PROJECT_NAME", parentUserCollectionID: "$targetCollId") { id title } }""",
                    "createRESTChildUserCollection"
                )
                projectCollId = created.get("id").asString
                println("  创建项目集合: id=$projectCollId, title=$PROJECT_NAME")
            }

            // 2b. 查项目集合下是否有控制器子集合
            val controllerCols = queryList(serverUrl, accessToken,
                """query { userCollection(userCollectionID: "$projectCollId") { childrenREST(take: 100) { id title } } }""",
                "userCollection"
            ) { data ->
                val children = data.asJsonObject.get("childrenREST")
                if (children == null || children.isJsonNull) emptyList()
                else children.asJsonArray.map { it.asJsonObject }
            }
            var existingController = controllerCols.firstOrNull { it.get("title").asString == CONTROLLER_NAME }
            if (existingController != null) {
                controllerCollId = existingController.get("id").asString
                println("  控制器子集合已存在: id=$controllerCollId, title=$CONTROLLER_NAME（复用）")
            } else {
                val created = mutation(serverUrl, accessToken,
                    """mutation { createRESTChildUserCollection(title: "$CONTROLLER_NAME", parentUserCollectionID: "$projectCollId") { id title } }""",
                    "createRESTChildUserCollection"
                )
                controllerCollId = created.get("id").asString
                println("  创建控制器子集合: id=$controllerCollId, title=$CONTROLLER_NAME")
            }

            // 2c. 创建请求
            val requestJson = """{"v":"17","name":"test","method":"GET","endpoint":"/api/test/reuse","params":[],"headers":[],"auth":{"authType":"inherit","authActive":true},"body":{"contentType":null,"body":null},"responses":{},"testScript":"","preRequestScript":"","requestVariables":[]}"""
            val reqResult = mutation(serverUrl, accessToken,
                """mutation { createRESTUserRequest(collectionID: "$controllerCollId", title: "$REQUEST_TITLE", request: "${requestJson.replace("\"", "\\\"")}") { id title } }""",
                "createRESTUserRequest"
            )
            createdRequestId = reqResult.get("id").asString
            println("  创建请求: id=$createdRequestId, title=$REQUEST_TITLE")

            println("\n  ✅ 第一次同步完成")

            // ── Step 3: 第二次同步 — 模拟再次同步同一个控制器 ──
            // 重复 Step 2 的查询逻辑，验证是否复用已有集合而不是新建
            println("\n[步骤 3] 第二次同步：验证集合复用...")

            // 3a. 查项目集合
            val projectCols2 = queryList(serverUrl, accessToken,
                """query { userCollection(userCollectionID: "$targetCollId") { childrenREST(take: 100) { id title } } }""",
                "userCollection"
            ) { data ->
                val children = data.asJsonObject.get("childrenREST")
                if (children == null || children.isJsonNull) emptyList()
                else children.asJsonArray.map { it.asJsonObject }
            }
            val matchedProject = projectCols2.filter { it.get("title").asString == PROJECT_NAME }
            println("  目标下找到项目集合「$PROJECT_NAME」: ${matchedProject.size} 个")
            check(matchedProject.size == 1) {
                "❌ 失败：目标下有 ${matchedProject.size} 个同名项目集合，期望 1 个（说明重复创建了）"
            }
            println("  ✅ 项目集合唯一: id=${matchedProject[0].get("id").asString}")

            // 3b. 查控制器子集合
            val projectId = matchedProject[0].get("id").asString
            val controllerCols2 = queryList(serverUrl, accessToken,
                """query { userCollection(userCollectionID: "$projectId") { childrenREST(take: 100) { id title } } }""",
                "userCollection"
            ) { data ->
                val children = data.asJsonObject.get("childrenREST")
                if (children == null || children.isJsonNull) emptyList()
                else children.asJsonArray.map { it.asJsonObject }
            }
            val matchedController = controllerCols2.filter { it.get("title").asString == CONTROLLER_NAME }
            println("  项目集合下找到控制器子集合「$CONTROLLER_NAME」: ${matchedController.size} 个")
            check(matchedController.size == 1) {
                "❌ 失败：项目集合下有 ${matchedController.size} 个同名控制器子集合，期望 1 个（说明重复创建了）"
            }
            println("  ✅ 控制器子集合唯一: id=${matchedController[0].get("id").asString}")

            // 3c. 验证请求数
            val requests = queryList(serverUrl, accessToken,
                """query { userRESTRequests(collectionID: "${matchedController[0].get("id").asString}", take: 100) { id title } }""",
                "userRESTRequests"
            )
            println("  控制器子集合下请求数: ${requests.size}")
            check(requests.size >= 1) {
                "❌ 失败：控制器子集合下没有请求"
            }
            println("  ✅ 请求存在")

            println("\n" + "=" .repeat(60))
            println("🎉 测试通过！插件第二次同步时正确复用了已有集合")
            println("=" .repeat(60))

        } catch (e: Throwable) {
            println("\n❌ 测试失败: ${e.message}")
            e.printStackTrace()
            throw e
        } finally {
            // 清理
            println("\n[清理] 删除测试数据...")
            try {
                if (createdRequestId != null) {
                    mutation(serverUrl, accessToken,
                        """mutation { deleteUserRequest(requestID: "$createdRequestId") }""",
                        "deleteUserRequest"
                    )
                }
            } catch (_: Exception) {}
            try {
                if (controllerCollId != null) {
                    mutation(serverUrl, accessToken,
                        """mutation { deleteUserCollection(userCollectionID: "$controllerCollId") }""",
                        "deleteUserCollection"
                    )
                }
            } catch (_: Exception) {}
            try {
                if (projectCollId != null) {
                    mutation(serverUrl, accessToken,
                        """mutation { deleteUserCollection(userCollectionID: "$projectCollId") }""",
                        "deleteUserCollection"
                    )
                }
            } catch (_: Exception) {}
            cleanupTestData(serverUrl, accessToken)
            println("  清理完成")
        }
    }

    private fun cleanupTestData(serverUrl: String, accessToken: String) {
        try {
            val roots = queryList(serverUrl, accessToken,
                """query { rootRESTUserCollections(take: 10000) { id title } }""",
                "rootRESTUserCollections"
            )
            for (root in roots) {
                val title = root.get("title").asString
                val id = root.get("id").asString
                if (title.startsWith(TEST_PREFIX)) {
                    mutation(serverUrl, accessToken,
                        """mutation { deleteUserCollection(userCollectionID: "$id") }""",
                        "deleteUserCollection"
                    )
                    println("  🗑️ 删除残留: [$id] $title")
                }
            }
        } catch (_: Exception) {}
    }

    private fun queryList(serverUrl: String, accessToken: String, query: String, operation: String): List<com.google.gson.JsonObject> {
        return graphQL(serverUrl, accessToken, query, operation) { data ->
            if (data.isJsonNull) emptyList()
            else data.asJsonArray.map { it.asJsonObject }
        }
    }

    private inline fun <reified T> queryList(serverUrl: String, accessToken: String, query: String, operation: String, crossinline extract: (com.google.gson.JsonElement) -> T): T {
        return graphQL(serverUrl, accessToken, query, operation) { data -> extract(data) }
    }

    private fun mutation(serverUrl: String, accessToken: String, query: String, operation: String): com.google.gson.JsonObject {
        return graphQL(serverUrl, accessToken, query, operation) { data -> data.asJsonObject }
    }

    private fun <T> graphQL(serverUrl: String, accessToken: String, query: String, operation: String, extractor: (com.google.gson.JsonElement) -> T): T {
        val body = gson.toJson(mapOf("query" to query))
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$serverUrl/graphql"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $accessToken")
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) error("HTTP ${response.statusCode()}: ${response.body()}")
        val json = JsonParser.parseString(response.body()).asJsonObject
        json.get("errors")?.let { errors ->
            val msg = errors.asJsonArray.map { it.asJsonObject.get("message").asString }.joinToString("; ")
            error("GraphQL 错误: $msg\n查询: $query")
        }
        val data = json.getAsJsonObject("data") ?: error("响应缺少 data")
        val result = data.get(operation) ?: error("响应缺少 $operation")
        return extractor(result)
    }
}
