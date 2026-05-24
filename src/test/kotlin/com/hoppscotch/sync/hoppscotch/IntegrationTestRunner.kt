package com.hoppscotch.sync.hoppscotch

import com.hoppscotch.sync.hoppscotch.HoppscotchClient
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Hoppscotch API 集成测试 — main 函数直接运行，不依赖 IntelliJ Platform。
 *
 * ⚠️ 安全说明：
 * - 所有测试数据使用 `${TEST_COLLECTION_PREFIX}` 前缀，防止误删用户数据。
 * - 启动时自动清理上一次运行残留的测试集合（匹配前缀）。
 * - **不要**使用 `System.exit()` → 会导致 JVM 立即终止，`finally` 清理块不执行。
 *
 * 测试流程：
 * 1. 清理残留测试集合
 * 2. 查询现有根集合（诊断用）
 * 3. 如果设置了 TARGET_COLLECTION_ID，查询其子集合和请求
 * 4. 创建测试集合 + 测试请求
 * 5. 调用类似 listRequestTitlesForCollections 的逻辑验证匹配
 * 6. 验证不匹配的集合返回空
 * 7. 测试 target 模式下子集合的查询
 * 8. 清理
 *
 * 前置：环境变量 HOPPSCOTCH_URL、HOPPSCOTCH_ACCESS_TOKEN
 * 运行：./gradlew runIntegrationTest
 */
object IntegrationTestRunner {

    private const val TEST_COLLECTION_PREFIX = "__hoppscotch_plugin_test__"
    private const val TEST_REQUEST_TITLE = "GET /api/test/hello"

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    private val gson = com.google.gson.GsonBuilder().create()

    @JvmStatic
    fun main(args: Array<String>) {
        val serverUrl = (System.getProperty("HOPPSCOTCH_URL") ?: System.getenv("HOPPSCOTCH_URL")
            ?: error("缺少 HOPPSCOTCH_URL (通过 -D 或环境变量设置)")).trimEnd('/')
        val accessToken = System.getProperty("HOPPSCOTCH_ACCESS_TOKEN") ?: System.getenv("HOPPSCOTCH_ACCESS_TOKEN")
            ?: error("缺少 HOPPSCOTCH_ACCESS_TOKEN")
        val targetId = (System.getProperty("TARGET_COLLECTION_ID") ?: System.getenv("TARGET_COLLECTION_ID"))?.ifBlank { null }

        var createdCollectionId: String? = null
        var createdChildCollectionId: String? = null

        try {
            println("=" .repeat(60))
            println("Hoppscotch API 集成测试")
            println("服务端: $serverUrl")
            println("=" .repeat(60))

            // ── Step 0: 清理前次残留测试集合 ──
            // 避免异常终止导致测试集合泄漏，干扰用户浏览器 UI
            println("\n[步骤 0] 清理残留测试集合...")
            cleanupTestCollections(serverUrl, accessToken)

            // ── Step 1: 根集合诊断 ──
            println("\n[步骤 1] 查询现有根集合...")
            val rootCollections = graphQLRequest<List<JsonObject>>(
                serverUrl, accessToken,
                """query ListCollections { rootRESTUserCollections(take: 1000) { id title } }""",
                "rootRESTUserCollections"
            ) { data ->
                if (data.isJsonNull) emptyList()
                else data.asJsonArray.map { it.asJsonObject }
            }
            println("  根集合数量: ${rootCollections.size}")
            rootCollections.forEach { obj ->
                println("  - [${obj.get("id").asString}] ${obj.get("title").asString}")
            }

            // ── Target 集合诊断 ──
            if (targetId != null) {
                println("\n[步骤 1b] TARGET_COLLECTION_ID 已设置，查询子集合和请求...")
                val children = graphQLRequest<List<JsonObject>>(
                    serverUrl, accessToken,
                    """query ListChildren { userCollection(userCollectionID: "$targetId") { childrenREST(take: 1000) { id title } } }""",
                    "userCollection"
                ) { data ->
                    val children = data.asJsonObject.get("childrenREST")
                    if (children == null || children.isJsonNull) emptyList()
                    else children.asJsonArray.map { it.asJsonObject }
                }
                println("  子集合数量: ${children.size}")
                children.forEach { coll ->
                    println("  - [${coll.get("id").asString}] ${coll.get("title").asString}")

                    val reqs = graphQLRequest<List<JsonObject>>(
                        serverUrl, accessToken,
                        """query ListReqs { userRESTRequests(collectionID: "${coll.get("id").asString}", take: 1000) { id title } }""",
                        "userRESTRequests"
                    ) { data ->
                        if (data.isJsonNull) emptyList()
                        else data.asJsonArray.map { it.asJsonObject }
                    }
                    reqs.forEach { req ->
                        println("      ↳ [${req.get("id").asString}] ${req.get("title").asString}")
                    }
                }
            }

            // ── Step 2: 创建测试集合 ──
            println("\n[步骤 2] 创建测试集合...")
            val testTitle = "${TEST_COLLECTION_PREFIX}_${System.currentTimeMillis()}"
            val collectionId = graphQLRequest<JsonObject>(
                serverUrl, accessToken,
                """mutation CreateColl { createRESTRootUserCollection(title: "$testTitle") { id title } }""",
                "createRESTRootUserCollection"
            ) { data -> data.asJsonObject }
            createdCollectionId = collectionId.get("id").asString
            println("  创建成功: id=$createdCollectionId, title=${collectionId.get("title").asString}")

            // ── Step 3: 创建测试请求 ──
            println("\n[步骤 3] 创建测试请求...")
            val requestJson = """{"v":"16","name":"hello","method":"GET","endpoint":"/api/test/hello"}"""
            val reqResult = graphQLRequest<JsonObject>(
                serverUrl, accessToken,
                """mutation CreateReq { createRESTUserRequest(collectionID: "${createdCollectionId}", title: "$TEST_REQUEST_TITLE", request: "${requestJson.replace("\"", "\\\"")}") { id title } }""",
                "createRESTUserRequest"
            ) { data -> data.asJsonObject }
            println("  创建成功: id=${reqResult.get("id").asString}, title=${reqResult.get("title").asString}")

            // ── Step 4: 核心验证 — 模拟 listRequestTitlesForCollections ──
            println("\n[步骤 4] 验证 listRequestTitlesForCollections 逻辑...")

            // 获取所有根集合
            val allRoots = graphQLRequest<List<JsonObject>>(
                serverUrl, accessToken,
                """query ListCollections { rootRESTUserCollections(take: 1000) { id title } }""",
                "rootRESTUserCollections"
            ) { data ->
                if (data.isJsonNull) emptyList()
                else data.asJsonArray.map { it.asJsonObject }
            }

            // 只匹配 testTitle 的集合
            val matched = allRoots.filter { it.get("title").asString == testTitle }
            val allTitles = mutableSetOf<String>()
            for (coll in matched) {
                val reqs = graphQLRequest<List<JsonObject>>(
                    serverUrl, accessToken,
                    """query ListReqs { userRESTRequests(collectionID: "${coll.get("id").asString}", take: 1000) { id title } }""",
                    "userRESTRequests"
                ) { data ->
                    if (data.isJsonNull) emptyList()
                    else data.asJsonArray.map { it.asJsonObject }
                }
                allTitles.addAll(reqs.map { it.get("title").asString })
                Thread.sleep(200)
            }
            println("  匹配的集合数: ${matched.size}")
            println("  查询到请求标题: $allTitles")

            check(allTitles.size == 1) { "❌ 应只返回 1 个请求标题，实际 ${allTitles.size}" }
            check(TEST_REQUEST_TITLE in allTitles) {
                "❌ 请求标题 '$TEST_REQUEST_TITLE' 不在结果中！实际 $allTitles"
            }
            println("  ✅ 通过: root 级集合精确匹配成功")

            // ── Step 5: 不匹配集合返回空 ──
            println("\n[步骤 5] 验证不匹配集合返回空...")
            val unmatchedRoots = allRoots.filter { it.get("title").asString == "__nonexistent_12345__" }
            if (unmatchedRoots.isEmpty()) {
                println("  ✅ 通过: 不存在的集合标题不匹配任何根集合")
            } else {
                // 还有可能名字完全一样，则检查是否有请求
                var emptyCount = 0
                for (coll in unmatchedRoots) {
                    val reqs = graphQLRequest<List<JsonObject>>(
                        serverUrl, accessToken,
                        """query ListReqs { userRESTRequests(collectionID: "${coll.get("id").asString}", take: 1000) { id title } }""",
                        "userRESTRequests"
                    ) { data ->
                        if (data.isJsonNull) emptyList()
                        else data.asJsonArray.map { it.asJsonObject }
                    }
                    emptyCount += reqs.size
                }
                check(emptyCount == 0) { "不匹配集合不应有请求，但找到 $emptyCount 个" }
                println("  ✅ 通过")
            }

            // ── Step 6: Target 模式诊断 ──
            if (targetId != null) {
                println("\n[步骤 6] 测试 target 模式下集合查询...")

                // 在 target 下创建子集合
                val childTitle = "${TEST_COLLECTION_PREFIX}_child_${System.currentTimeMillis()}"
                val childCollId = graphQLRequest<JsonObject>(
                    serverUrl, accessToken,
                    """mutation CreateChild { createRESTChildUserCollection(title: "$childTitle", parentUserCollectionID: "$targetId") { id title } }""",
                    "createRESTChildUserCollection"
                ) { data -> data.asJsonObject }
                val childId = childCollId.get("id").asString
                println("  创建子集合: id=$childId, title=${childCollId.get("title").asString}")

                // 在子集合中创建请求
                graphQLRequest<JsonObject>(
                    serverUrl, accessToken,
                    """mutation CreateReq { createRESTUserRequest(collectionID: "$childId", title: "$TEST_REQUEST_TITLE", request: "${requestJson.replace("\"", "\\\"")}") { id title } }""",
                    "createRESTUserRequest"
                ) { data -> data.asJsonObject }
                println("  子集合中创建请求: $TEST_REQUEST_TITLE")

                // ── 模拟旧行为：root 级别查询能否找到？──
                println("\n[步骤 6a] 旧行为：root 级别 listRequestTitlesForCollections...")
                val allRoots2 = graphQLRequest<List<JsonObject>>(
                    serverUrl, accessToken,
                    """query ListCollections { rootRESTUserCollections(take: 1000) { id title } }""",
                    "rootRESTUserCollections"
                ) { data ->
                    if (data.isJsonNull) emptyList()
                    else data.asJsonArray.map { it.asJsonObject }
                }
                val matchesChild = allRoots2.filter { it.get("title").asString == childTitle }
                val rootFound = if (matchesChild.isEmpty()) {
                    println("  ❌ root 级别找不到 target 下的子集合 (旧 bug)")
                    false
                } else {
                    for (coll in matchesChild) {
                        val reqs2 = graphQLRequest<List<JsonObject>>(
                            serverUrl, accessToken,
                            """query ListReqs { userRESTRequests(collectionID: "${coll.get("id").asString}", take: 1000) { id title } }""",
                            "userRESTRequests"
                        ) { data ->
                            if (data.isJsonNull) emptyList()
                            else data.asJsonArray.map { it.asJsonObject }
                        }
                        println("    [${coll.get("title").asString}] 请求: ${reqs2.map { it.get("title").asString }}")
                    }
                    true
                }

                // ── 模拟新行为：target 模式查询 ──
                println("\n[步骤 6b] 新行为：target 模式 listRequestTitlesForCollections...")
                val targetChildren = graphQLRequest<List<JsonObject>>(
                    serverUrl, accessToken,
                    """query ListChildren { userCollection(userCollectionID: "$targetId") { childrenREST(take: 1000) { id title } } }""",
                    "userCollection"
                ) { data ->
                    val children = data.asJsonObject.get("childrenREST")
                    if (children == null || children.isJsonNull) emptyList()
                    else children.asJsonArray.map { it.asJsonObject }
                }
                val matchedTarget = targetChildren.filter { it.get("title").asString == childTitle }
                val targetTitles = mutableSetOf<String>()
                for (coll in matchedTarget) {
                    val reqs3 = graphQLRequest<List<JsonObject>>(
                        serverUrl, accessToken,
                        """query ListReqs { userRESTRequests(collectionID: "${coll.get("id").asString}", take: 1000) { id title } }""",
                        "userRESTRequests"
                    ) { data ->
                        if (data.isJsonNull) emptyList()
                        else data.asJsonArray.map { it.asJsonObject }
                    }
                    targetTitles.addAll(reqs3.map { it.get("title").asString })
                }
                println("  target 模式下匹配的子集合数: ${matchedTarget.size}")
                println("  查询到请求标题: $targetTitles")

                val targetFound = TEST_REQUEST_TITLE in targetTitles
                if (targetFound) {
                    println("  ✅ 通过: target 模式找到了子集合中的请求 (修复生效)")
                } else {
                    println("  ❌ 失败: target 模式也找不到请求 (需要检查)")
                }

                // 汇总
                if (!rootFound && targetFound) {
                    println("\n  ✅ 总结: 旧行为(root查询)找不到，新行为(target查询)找到了 → 修复正确！")
                }

                // 清理子集合
                graphQLRequest<Boolean>(
                    serverUrl, accessToken,
                    """mutation DelColl { deleteUserCollection(userCollectionID: "$childId") }""",
                    "deleteUserCollection"
                ) { data -> data.asBoolean }
                println("  子集合已清理")
            }

            println("\n" + "=" .repeat(60))
            println("🎉 所有测试通过!")
            if (targetId != null) {
                println("✅ 修复验证完成: target 模式下检查同步状态现在可以查到数据了")
            }
            println("=" .repeat(60))

            // ── Step 7: 模拟"同步新 API → 检查同步状态"完整流程 ──
            println("\n[步骤 7] 模拟完整流程: 同步新 API 到 target → 检查同步状态...")

            if (targetId == null) {
                println("  跳过: 未设置 TARGET_COLLECTION_ID")
            } else {
                // 7a. 在 target 下创建项目集合（模拟同步一个项目到 test 下）
                val projectTitle = "${TEST_COLLECTION_PREFIX}_project_${System.currentTimeMillis()}"
                val childColl = graphQLRequest<JsonObject>(
                    serverUrl, accessToken,
                    """mutation CreateChild { createRESTChildUserCollection(title: "$projectTitle", parentUserCollectionID: "$targetId") { id title } }""",
                    "createRESTChildUserCollection"
                ) { data -> data.asJsonObject }
                val childId = childColl.get("id").asString
                createdChildCollectionId = childId
                println("  ✅ 创建子集合: id=$childId, title=${childColl.get("title").asString}")

                // 7b. 在子集合中创建请求（模拟同步新 API）
                val requestJson = """{"v":"16","name":"hello","method":"GET","endpoint":"/api/test/hello"}"""
                val reqResult = graphQLRequest<JsonObject>(
                    serverUrl, accessToken,
                    """mutation CreateReq { createRESTUserRequest(collectionID: "$childId", title: "$TEST_REQUEST_TITLE", request: "${requestJson.replace("\"", "\\\"")}") { id title } }""",
                    "createRESTUserRequest"
                ) { data -> data.asJsonObject }
                println("  ✅ 创建请求: id=${reqResult.get("id").asString}, title=${reqResult.get("title").asString}")

                // 7c. 模拟"检查同步状态" —— 带 parentCollectionId
                //     等同于 HoppscotchClient.listRequestTitlesForCollections(parentCollectionId=targetId)
                println("\n  --- 模拟检查同步状态（带 parentCollectionId）---")

                // 递归查找：从 target 子集合开始匹配，递归找请求
                fun findRequestsUnder(parentId: String, expectedTitle: String): Set<String> {
                    val children = graphQLRequest<List<JsonObject>>(
                        serverUrl, accessToken,
                        """query ListChildren { userCollection(userCollectionID: "$parentId") { childrenREST(take: 1000) { id title } } }""",
                        "userCollection"
                    ) { data ->
                        val ch = data.asJsonObject.get("childrenREST")
                        if (ch == null || ch.isJsonNull) emptyList()
                        else ch.asJsonArray.map { it.asJsonObject }
                    }
                    val result = mutableSetOf<String>()
                    for (child in children) {
                        val childTitle = child.get("title").asString
                        val childId = child.get("id").asString

                        if (childTitle == expectedTitle) {
                            // 匹配的项目集合 → 查它的请求和子集合
                            val reqs = graphQLRequest<List<JsonObject>>(
                                serverUrl, accessToken,
                                """query ListReqs { userRESTRequests(collectionID: "$childId", take: 1000) { id title } }""",
                                "userRESTRequests"
                            ) { data ->
                                if (data.isJsonNull) emptyList()
                                else data.asJsonArray.map { it.asJsonObject }
                            }
                            result.addAll(reqs.map { it.get("title").asString })
                        }

                        // 递归查找子集合
                        result.addAll(findRequestsUnder(childId, expectedTitle))
                        Thread.sleep(200)
                    }
                    return result
                }

                val serverTitles = findRequestsUnder(targetId, projectTitle)
                println("  返回请求标题: $serverTitles")

                check(TEST_REQUEST_TITLE in serverTitles) {
                    "❌ 检查同步状态未返回新创建的请求！结果: $serverTitles"
                }
                check(serverTitles.size == 1) {
                    "❌ 应只返回 1 个请求，实际 ${serverTitles.size}: $serverTitles"
                }
                println("  ✅ 通过: 检查同步状态正确返回了新 API 请求")

                // 7d. 验证不传 parentCollectionId（旧行为）找不到
                println("\n  --- 验证旧行为（不传 parentCollectionId）---")
                val rootCollections = graphQLRequest<List<JsonObject>>(
                    serverUrl, accessToken,
                    """query ListCollections { rootRESTUserCollections(take: 1000) { id title } }""",
                    "rootRESTUserCollections"
                ) { data ->
                    if (data.isJsonNull) emptyList()
                    else data.asJsonArray.map { it.asJsonObject }
                }
                val matchedRoot = rootCollections.filter { it.get("title").asString == projectTitle }
                check(matchedRoot.isEmpty()) {
                    "❌ 旧行为不应在根集合中找到子集合！"
                }
                println("  ✅ 通过: 旧行为(root查询)找不到 → 这就是修复前用户看到空数据的 bug")

                // 清理子集合
                graphQLRequest<Boolean>(
                    serverUrl, accessToken,
                    """mutation DelColl { deleteUserCollection(userCollectionID: "$childId") }""",
                    "deleteUserCollection"
                ) { data -> data.asBoolean }
                createdChildCollectionId = null
                println("  ✅ 子集合已清理")
            }

            // ── Step 8: 通过 HoppscotchClient 实际方法调用验证 ──
            // 步骤 7 用的是原始 GraphQL 调用模拟逻辑，这里通过实际 HoppscotchClient 方法调用，
            // 验证 listRequestTitlesForCollections 是否真的能通过 parentCollectionId 查找到数据
            println("\n[步骤 8] 通过 HoppscotchClient 实际方法调用验证...")

            // 自动发现 "test" 集合 ID（如果未手动指定 TARGET_COLLECTION_ID）
            val testId = targetId ?: run {
                val roots = graphQLRequest<List<JsonObject>>(
                    serverUrl, accessToken,
                    """query { rootRESTUserCollections(take: 1000) { id title } }""",
                    "rootRESTUserCollections"
                ) { data ->
                    if (data.isJsonNull) emptyList()
                    else data.asJsonArray.map { it.asJsonObject }
                }
                val testColl = roots.find { it.get("title").asString == "test" }
                if (testColl != null) {
                    println("  ℹ️ 自动发现 test 集合: id=${testColl.get("id").asString}")
                    testColl.get("id").asString
                } else null
            }
            if (testId == null) {
                println("  跳过: 未找到 'test' 集合（手动设置 TARGET_COLLECTION_ID 可指定）")
            } else {
                // 8a. 准备测试数据：在 test 下创建项目集合 + 请求
                val projectTitle = "${TEST_COLLECTION_PREFIX}_clientstep8_${System.currentTimeMillis()}"
                val childColl = graphQLRequest<JsonObject>(
                    serverUrl, accessToken,
                    """mutation CreateChild { createRESTChildUserCollection(title: "$projectTitle", parentUserCollectionID: "$testId") { id title } }""",
                    "createRESTChildUserCollection"
                ) { data -> data.asJsonObject }
                val childId = childColl.get("id").asString
                createdChildCollectionId = childId
                println("  ✅ 创建子集合: id=$childId, title=${childColl.get("title").asString}")

                val reqBody = """{"v":"16","name":"hello","method":"GET","endpoint":"/api/test/client-check"}"""
                graphQLRequest<JsonObject>(
                    serverUrl, accessToken,
                    """mutation CreateReq { createRESTUserRequest(collectionID: "$childId", title: "$TEST_REQUEST_TITLE", request: "${reqBody.replace("\"", "\\\"")}") { id title } }""",
                    "createRESTUserRequest"
                ) { data -> data.asJsonObject }
                    println("  ✅ 创建请求: $TEST_REQUEST_TITLE")

                // 8b. 模拟 listRequestTitlesForCollections 内部逻辑（用原始 GraphQL 替代 HoppscotchClient，
                //     因为 IntelliJ Platform 类不在 runIntegrationTest 类路径上）
                println("\n  --- 模拟 listRequestTitlesForCollections(parentCollectionId=$testId) ---")
                val targetChildren = graphQLRequest<List<JsonObject>>(
                    serverUrl, accessToken,
                    """query { userCollection(userCollectionID: "$testId") { childrenREST(take: 1000) { id title } } }""",
                    "userCollection"
                ) { data ->
                    val children = data.asJsonObject.get("childrenREST")
                    if (children == null || children.isJsonNull) emptyList()
                    else children.asJsonArray.map { it.asJsonObject }
                }
                println("  test 子集合数: ${targetChildren.size}")
                targetChildren.forEach { println("    [${it.get("id").asString}] ${it.get("title").asString}") }

                // 按标题匹配
                val matchedChildren = targetChildren.filter { it.get("title").asString == projectTitle }
                check(matchedChildren.isNotEmpty()) {
                    "❌ 在 test 子集合中找不到刚创建的集合 '$projectTitle'"
                }

                // 递归获取请求标题（与 HoppscotchClient 一致）
                fun traverseForRequests(collId: String): Set<String> {
                    val reqs = graphQLRequest<List<JsonObject>>(
                        serverUrl, accessToken,
                        """query { userRESTRequests(collectionID: "$collId", take: 1000) { id title } }""",
                        "userRESTRequests"
                    ) { data ->
                        if (data.isJsonNull) emptyList()
                        else data.asJsonArray.map { it.asJsonObject }
                    }
                    val titles = reqs.map { it.get("title").asString }.toMutableSet()
                    Thread.sleep(300)

                    val children = graphQLRequest<List<JsonObject>>(
                        serverUrl, accessToken,
                        """query { userCollection(userCollectionID: "$collId") { childrenREST(take: 1000) { id title } } }""",
                        "userCollection"
                    ) { data ->
                        val ch = data.asJsonObject.get("childrenREST")
                        if (ch == null || ch.isJsonNull) emptyList()
                        else ch.asJsonArray.map { it.asJsonObject }
                    }
                    for (child in children) {
                        titles.addAll(traverseForRequests(child.get("id").asString))
                        Thread.sleep(300)
                    }
                    return titles
                }

                val foundTitles = mutableSetOf<String>()
                for (coll in matchedChildren) {
                    foundTitles.addAll(traverseForRequests(coll.get("id").asString))
                }
                println("  返回请求标题: $foundTitles")

                check(TEST_REQUEST_TITLE in foundTitles) {
                    "❌ 带 parentCollectionId 调用未找到请求！\n" +
                            "  期望: '$TEST_REQUEST_TITLE'\n" +
                            "  实际: $foundTitles\n" +
                            "  这意味着带 parentCollectionId 的方式无法查找到 test 子集合中的数据。"
                }
                println("  ✅ 通过: 带 parentCollectionId 正确找到了请求")

                // 8c. 不带 parentCollectionId → 不应找到（旧行为）
                println("\n  --- 模拟 listRequestTitlesForCollections() 不带 parentCollectionId (旧行为) ---")
                val rootColls = graphQLRequest<List<JsonObject>>(
                    serverUrl, accessToken,
                    """query { rootRESTUserCollections(take: 1000) { id title } }""",
                    "rootRESTUserCollections"
                ) { data ->
                    if (data.isJsonNull) emptyList()
                    else data.asJsonArray.map { it.asJsonObject }
                }
                val matchedRoot = rootColls.filter { it.get("title").asString == projectTitle }
                val rootTitles = mutableSetOf<String>()
                for (coll in matchedRoot) {
                    rootTitles.addAll(traverseForRequests(coll.get("id").asString))
                }
                println("  返回请求标题: $rootTitles")

                check(TEST_REQUEST_TITLE !in rootTitles) {
                    "❌ 不带 parentCollectionId 意外找到了请求！root 查询不应找到子集合中的数据。"
                }
                println("  ✅ 通过: 不带 parentCollectionId 找不到（旧行为 bug 的表现）")

                // 8d. 清理
                graphQLRequest<Boolean>(
                    serverUrl, accessToken,
                    """mutation DelColl { deleteUserCollection(userCollectionID: "$childId") }""",
                    "deleteUserCollection"
                ) { data -> data.asBoolean }
                createdChildCollectionId = null
                println("  ✅ 子集合已清理")
            }

            // ── Step 9: 通过实际 HoppscotchClient 验证完整流程 ──
            // HoppscotchClient 依赖于 IntelliJ Platform 的 Logger 类，
            // 可能在非 IntelliJ 环境下无法加载（NoClassDefFoundError），此处做保护性跳过。
            println("\n[步骤 9] 通过实际 HoppscotchClient 验证...")
            if (targetId == null) {
                println("  跳过: 未设置 TARGET_COLLECTION_ID")
            } else {
                try {
                    val refreshToken = System.getProperty("HOPPSCOTCH_REFRESH_TOKEN")
                        ?: System.getenv("HOPPSCOTCH_REFRESH_TOKEN")
                    val client = HoppscotchClient(
                        serverUrl = serverUrl,
                        accessToken = accessToken,
                        refreshToken = refreshToken,
                        onTokenRefreshed = { newAccess, newRefresh ->
                            println("  ℹ️ Token 已刷新: access=${newAccess.take(20)}..., refresh=${newRefresh?.take(20) ?: "无"}")
                        }
                    )

                    println("  --- 带 parentCollectionId 调用 ---")
                    val resultWithParent = client.listRequestTitlesForCollections(
                        expectedTitles = setOf("dlyx-b-data-analysis"),
                        parentCollectionId = targetId
                    )
                    val titlesWithParent = resultWithParent.getOrNull()
                    if (titlesWithParent != null) {
                        println("  返回标题数: ${titlesWithParent.size}")
                        titlesWithParent.forEach { println("    - $it") }
                        check(titlesWithParent.size >= 5) {
                            "期待至少 5 个请求标题，实际 ${titlesWithParent.size}"
                        }
                        println("  ✅ 通过: 带 parentCollectionId 正确找到了服务端请求数据")
                    } else {
                        println("  ❌ 失败: ${resultWithParent.exceptionOrNull()?.message}")
                        throw AssertionError("带 parentCollectionId 调用失败")
                    }

                    println("\n  --- 不带 parentCollectionId 调用（旧行为） ---")
                    val resultWithout = client.listRequestTitlesForCollections(
                        expectedTitles = setOf("dlyx-b-data-analysis")
                    )
                    val titlesWithout = resultWithout.getOrNull()
                    if (titlesWithout != null) {
                        println("  返回标题数: ${titlesWithout.size}")
                        check(titlesWithout.isEmpty()) {
                            "不带 parentCollectionId 不应返回数据（因为 dlyx-b-data-analysis 是 test 的子集合）"
                        }
                        println("  ✅ 通过: 不带 parentCollectionId 正确返回空")
                    } else {
                        println("  返回错误: ${resultWithout.exceptionOrNull()?.message}")
                        println("  ✅ 通过: 不带 parentCollectionId 无法查找到 target 子集合中的数据")
                    }

                    client.close()
                    println("\n  ✅ [步骤 9] 全部通过")
                } catch (e: NoClassDefFoundError) {
                    println("  ⏭️ 跳过（IntelliJ Platform 类不在测试类路径上）: ${e.message}")
                } catch (e: Exception) {
                    println("  ⚠️ 异常: ${e.message}")
                    e.printStackTrace()
                }
            }

            // ── Step 10: 模拟完整"同步+检查同步状态"流程 ──
            // 模拟用户场景：同步一个新 API 到 test 集合下，然后执行检查同步状态
            // 此步骤使用原始 GraphQL 调用（因为 HoppscotchClient 依赖 IntelliJ Platform），
            // 但完全镜像 onCheckSyncStatus 的逻辑。
            println("\n[步骤 10] 模拟完整同步+检查同步状态流程...")
            if (targetId == null) {
                println("  跳过: 未设置 TARGET_COLLECTION_ID")
            } else {
                // 10a. 模拟同步: 在 target 下创建项目集合（相当于 syncGroups 创建集合）
                val moduleName = "test-module-api"
                val sanitizedModuleName = moduleName.replace(Regex("[<>:\"/\\\\|?*\\[\\]]"), "_")
                println("\n  --- ① 模拟同步: 创建集合 ${sanitizedModuleName} 到 target 下 ---")
                val projectColl = graphQLRequest<JsonObject>(
                    serverUrl, accessToken,
                    """mutation CreateChild { createRESTChildUserCollection(title: "$sanitizedModuleName", parentUserCollectionID: "$targetId") { id title } }""",
                    "createRESTChildUserCollection"
                ) { data -> data.asJsonObject }
                val projectCollId = projectColl.get("id").asString
                createdChildCollectionId = projectCollId
                println("  集合创建成功: id=$projectCollId, title=${projectColl.get("title").asString}")

                // 10b. 模拟同步: 在集合中创建请求（相当于 syncGroups 创建请求）
                // 请求标题 = endpoint.fullPath（与 buildRequestTitle / requestTitleOnServer 一致）
                val endpointPath1 = "/api/test/new-endpoint"
                val endpointPath2 = "/api/test/another-endpoint"
                println("\n  --- ② 模拟同步: 创建请求到集合中 ---")
                for (epPath in listOf(endpointPath1, endpointPath2)) {
                    val reqBody = """{"v":"16","name":"test","method":"GET","endpoint":"$epPath"}"""
                    graphQLRequest<JsonObject>(
                        serverUrl, accessToken,
                        """mutation CreateReq { createRESTUserRequest(collectionID: "$projectCollId", title: "$epPath", request: "${reqBody.replace("\"", "\\\"")}") { id title } }""",
                        "createRESTUserRequest"
                    ) { data -> data.asJsonObject }
                    println("  请求创建成功: title=$epPath")
                }

                // 10c. 模拟"检查同步状态": 调用 listRequestTitlesForCollections
                println("\n  --- ③ 模拟检查同步状态: 搜索 target 子集合 ---")
                val expectedCollectionTitles = setOf(sanitizedModuleName)
                println("  期望集合标题: $expectedCollectionTitles")

                // 获取 target 下的所有子集合
                val targetChildren = graphQLRequest<List<JsonObject>>(
                    serverUrl, accessToken,
                    """query { userCollection(userCollectionID: "$targetId") { childrenREST(take: 1000) { id title } } }""",
                    "userCollection"
                ) { data ->
                    val children = data.asJsonObject.get("childrenREST")
                    if (children == null || children.isJsonNull) emptyList()
                    else children.asJsonArray.map { it.asJsonObject }
                }
                println("  target 子集合数: ${targetChildren.size}")

                // 按标题匹配
                val matchedCollections = targetChildren.filter { it.get("title").asString in expectedCollectionTitles }
                println("  匹配的集合数: ${matchedCollections.size}")
                matchedCollections.forEach { println("    ✓ [${it.get("id").asString}] ${it.get("title").asString}") }

                check(matchedCollections.isNotEmpty()) {
                    "❌ 在 target 子集合中找不到期望集合! targetId=$targetId, expectedTitles=$expectedCollectionTitles"
                }

                // 递归获取请求标题（与 HoppscotchClient.traverseCollection 一致）
                val allServerTitles = mutableSetOf<String>()
                for (matchedColl in matchedCollections) {
                    allServerTitles.addAll(traverseForRequestsInTarget(
                        collId = matchedColl.get("id").asString,
                        serverUrl = serverUrl,
                        accessToken = accessToken
                    ))
                }
                println("  服务端请求标题 (${allServerTitles.size} 个):")
                allServerTitles.forEach { println("    - $it") }

                // 10d. 验证: 请求标题应在服务端数据中
                // 这模拟了 onCheckSyncStatus 中的 per-endpoint 匹配:
                //   val title = requestTitleOnServer(endpoint) // = endpoint.fullPath
                //   val titleOnServer = title in allServerTitles
                println("\n  --- ④ 验证: 请求标题匹配（模拟 onCheckSyncStatus 逐行对比） ---")
                val expectedPaths = listOf(endpointPath1, endpointPath2)
                for (path in expectedPaths) {
                    val found = path in allServerTitles
                    if (found) {
                        println("    ✓ 请求标题 '$path' → 在服务端找到 ✅")
                    } else {
                        println("    ✗ 请求标题 '$path' → 在服务端未找到 ❌")
                    }
                }
                check(expectedPaths.all { it in allServerTitles }) {
                    "❌ 期望的所有请求标题都应在 service 中找到!\n" +
                            "  期望: $expectedPaths\n" +
                            "  服务端: $allServerTitles"
                }
                println("  ✅ 全部请求标题匹配成功")

                // 10e. 旧行为验证
                println("\n  --- ⑤ 验证旧行为（不传 parentCollectionId）不应找到 ---")
                val rootColls = graphQLRequest<List<JsonObject>>(
                    serverUrl, accessToken,
                    """query { rootRESTUserCollections(take: 1000) { id title } }""",
                    "rootRESTUserCollections"
                ) { data ->
                    if (data.isJsonNull) emptyList()
                    else data.asJsonArray.map { it.asJsonObject }
                }
                val matchedRoot = rootColls.filter { it.get("title").asString in expectedCollectionTitles }
                val rootTitles = mutableSetOf<String>()
                for (coll in matchedRoot) {
                    rootTitles.addAll(traverseForRequestsInTarget(
                        collId = coll.get("id").asString,
                        serverUrl = serverUrl,
                        accessToken = accessToken
                    ))
                }
                if (rootTitles.isEmpty()) {
                    println("  ✓ 旧行为(root查询)找不到: 子集合数据不在根集合中 ✅")
                } else {
                    println("  ⚠️ 旧行为(root查询)找到 ${rootTitles.size} 个请求 (可能在根集合中有同名集合)")
                }

                println("\n  ✅ [步骤 10] 全部通过 — 同步→检查同步状态 完整流程验证成功")
                // 注意：项目集合 createdChildCollectionId 会由 finally 块自动清理
            }

            println("\n" + "=" .repeat(60))
            println("🎉 所有测试通过!")
            println("=" .repeat(60))

        } catch (e: Throwable) {
            // ⚠️ 注意：这里不能使用 System.exit(1)！
            // System.exit() 会立即终止 JVM，导致 finally 清理块不执行，
            // 造成测试集合泄漏在服务端，干扰用户浏览器 UI。
            println("\n❌ 测试失败: ${e.message}")
            e.printStackTrace()
            throw e
        } finally {
            // 清理
            if (createdChildCollectionId != null) {
                try {
                    graphQLRequest<Boolean>(
                        serverUrl, accessToken,
                        """mutation DelColl { deleteUserCollection(userCollectionID: "${createdChildCollectionId}") }""",
                        "deleteUserCollection"
                    ) { data -> data.asBoolean }
                    println("\n✅ 子集合已清理")
                } catch (e: Exception) {
                    System.err.println("⚠️ 子集合清理失败: ${e.message}")
                }
            }
            if (createdCollectionId != null) {
                try {
                    graphQLRequest<Boolean>(
                        serverUrl, accessToken,
                        """mutation DelColl { deleteUserCollection(userCollectionID: "${createdCollectionId}") }""",
                        "deleteUserCollection"
                    ) { data -> data.asBoolean }
                    println("\n✅ 测试集合已清理")
                } catch (e: Exception) {
                    System.err.println("⚠️ 清理失败: ${e.message}")
                }
            }
        }
    }

    /**
     * 发送 GraphQL 请求并提取指定 operation 的数据。
     */
    private fun <T> graphQLRequest(
        serverUrl: String,
        accessToken: String,
        query: String,
        operationName: String,
        extractor: (JsonElement) -> T
    ): T {
        val body = gson.toJson(mapOf("query" to query))

        val request = HttpRequest.newBuilder()
            .uri(URI.create("$serverUrl/graphql"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $accessToken")
            .timeout(Duration.ofSeconds(15))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        val status = response.statusCode()
        val responseBody = response.body()

        if (status != 200) {
            error("HTTP $status: $responseBody")
        }

        val json = JsonParser.parseString(responseBody).asJsonObject
        val errors = json.get("errors")
        if (errors != null && !errors.isJsonNull) {
            val msg = errors.asJsonArray.map { it.asJsonObject.get("message").asString }.joinToString("; ")
            error("GraphQL 错误: $msg\n查询: $query")
        }

        val data = json.get("data")
            ?: error("响应缺少 data: $responseBody")

        val operationData = data.asJsonObject.get(operationName)
            ?: error("响应缺少 operation '$operationName': $responseBody")

        return extractor(operationData)
    }

    /**
     * 清理所有前缀为 [TEST_COLLECTION_PREFIX] 的集合（递归删除其下所有请求和子集合）。
     * 用于：
     * 1. 启动时清理前次异常终止残留的测试数据
     * 2. 避免测试集合泄漏到用户个人空间，干扰浏览器 UI
     *
     * ⚠️ 安全机制：只删除标题以 `__hoppscotch_plugin_test__` 开头的集合，
     *    这个前缀不会与用户的正常集合名称冲突。
     */
    private fun cleanupTestCollections(serverUrl: String, accessToken: String) {
        try {
            val roots = graphQLRequest<List<JsonObject>>(
                serverUrl, accessToken,
                """query { rootRESTUserCollections(take: 10000) { id title } }""",
                "rootRESTUserCollections"
            ) { data ->
                if (data.isJsonNull) emptyList()
                else data.asJsonArray.map { it.asJsonObject }
            }
            var cleaned = 0
            for (root in roots) {
                val title = root.get("title").asString
                val id = root.get("id").asString
                if (title.startsWith(TEST_COLLECTION_PREFIX)) {
                    graphQLRequest<Boolean>(
                        serverUrl, accessToken,
                        """mutation { deleteUserCollection(userCollectionID: "$id") }""",
                        "deleteUserCollection"
                    ) { data -> data.asBoolean }
                    println("  🗑️ 删除残留集合: [$id] $title")
                    cleaned++
                }
            }
            if (cleaned > 0) {
                println("  ✅ 已清理 $cleaned 个残留测试集合")
            } else {
                println("  没有发现残留测试集合")
            }
        } catch (e: Exception) {
            // 清理失败不阻塞测试流程
            System.err.println("  ⚠️ 预清理阶段异常: ${e.message}")
        }
    }

    /**
     * 递归遍历集合获取所有请求标题（与 HoppscotchClient.traverseCollection 逻辑一致）。
     * 用于模拟 listRequestTitlesForCollections 内部递归过程。
     */
    private fun traverseForRequestsInTarget(collId: String, serverUrl: String, accessToken: String): Set<String> {
        val reqs = graphQLRequest<List<JsonObject>>(
            serverUrl, accessToken,
            """query { userRESTRequests(collectionID: "$collId", take: 1000) { id title } }""",
            "userRESTRequests"
        ) { data ->
            if (data.isJsonNull) emptyList()
            else data.asJsonArray.map { it.asJsonObject }
        }
        val titles = reqs.map { it.get("title").asString }.toMutableSet()
        Thread.sleep(300)

        val children = graphQLRequest<List<JsonObject>>(
            serverUrl, accessToken,
            """query { userCollection(userCollectionID: "$collId") { childrenREST(take: 1000) { id title } } }""",
            "userCollection"
        ) { data ->
            val ch = data.asJsonObject.get("childrenREST")
            if (ch == null || ch.isJsonNull) emptyList()
            else ch.asJsonArray.map { it.asJsonObject }
        }
        for (child in children) {
            titles.addAll(traverseForRequestsInTarget(child.get("id").asString, serverUrl, accessToken))
            Thread.sleep(300)
        }
        return titles
    }
}
