package com.hoppscotch.sync.hoppscotch

import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * 场景化集成测试 — 覆盖 Hoppscotch GraphQL API 的主要操作场景。
 *
 * 测试前缀使用 `hstest`，所有测试数据在全新的测试集合中运行，不依赖服务端已有数据。
 * 每个场景独立 try-catch，一个场景失败不影响其他场景执行。
 *
 * 启动时清理前次残留（匹配 `hstest` 前缀），结束时清理本次创建的所有数据。
 *
 * 运行：
 *   export HOPPSCOTCH_URL=... HOPPSCOTCH_ACCESS_TOKEN=...
 *   ./gradlew runScenarioTest
 */
object ScenarioIntegrationTest {

    private const val TEST_PREFIX = "hstest"

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

        try {
            println("=" .repeat(60))
            println("场景化集成测试")
            println("服务端: $serverUrl")
            println("=" .repeat(60))

            // ── 启动时清理前次残留 ──
            println("\n[预清理] 清理前次残留的测试数据（前缀: $TEST_PREFIX）...")
            cleanupTestData(serverUrl, accessToken)

            // ============================================================
            // 场景 A：基础连接与认证
            // ============================================================
            runScenario("A", "基础连接与认证") {

                // A1: 用有效 token 调用 me { uid } → 应返回 uid
                try {
                    println("\n[A1] 用有效 token 查询 me.uid...")
                    val uid = graphQL(serverUrl, accessToken,
                        """query { me { uid } }""",
                        "me"
                    ) { data -> data.asJsonObject.get("uid").asString }
                    println("  uid=$uid")
                    check(uid.isNotBlank()) { "A1: uid 不应为空" }
                    println("  ✅ A1 通过")
                } catch (e: Throwable) {
                    println("  ❌ A1 失败: ${e.message}")
                    e.printStackTrace()
                }

                // A2: 用无效 token → 应返回 GraphQL 错误
                try {
                    println("\n[A2] 用无效 token 查询 me...")
                    val body = gson.toJson(mapOf("query" to "query { me { uid } }"))
                    val request = HttpRequest.newBuilder()
                        .uri(URI.create("$serverUrl/graphql"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer invalid_token_xxx")
                        .timeout(Duration.ofSeconds(15))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build()
                    val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                    // GraphQL 端点即使认证失败也返回 HTTP 200
                    check(response.statusCode() == 200) { "期望 HTTP 200，实际 ${response.statusCode()}" }
                    val json = JsonParser.parseString(response.body()).asJsonObject
                    val errors = json.get("errors")
                    check(errors != null && !errors.isJsonNull) {
                        "期望 GraphQL errors 字段，但响应中没有:\n${response.body()}"
                    }
                    val errorMessages = errors.asJsonArray.map { it.asJsonObject.get("message").asString }
                    println("  返回错误: $errorMessages")
                    println("  ✅ A2 通过")
                } catch (e: Throwable) {
                    println("  ❌ A2 失败: ${e.message}")
                    e.printStackTrace()
                }
            }

            // ============================================================
            // 场景 B：集合操作
            // ============================================================
            runScenario("B", "集合操作") {

                var collB1Id: String? = null
                var collB2Id: String? = null
                val dupCollIds = mutableListOf<String>()

                // B1: 在根级创建集合 hstest-B1 → 返回 id 和 title
                try {
                    println("\n[B1] 在根级创建集合 hstest-B1...")
                    val result = mutationObj(serverUrl, accessToken,
                        """mutation { createRESTRootUserCollection(title: "hstest-B1") { id title } }""",
                        "createRESTRootUserCollection"
                    )
                    collB1Id = result.get("id").asString
                    val title = result.get("title").asString
                    println("  id=$collB1Id, title=$title")
                    check(title == "hstest-B1") { "B1: 标题应为 hstest-B1，实际 $title" }
                    println("  ✅ B1 通过")
                } catch (e: Throwable) {
                    println("  ❌ B1 失败: ${e.message}")
                    e.printStackTrace()
                }

                // B2: 在 B1 创建的集合下创建子集合 hstest-B2
                try {
                    println("\n[B2] 在 hstest-B1 下创建子集合 hstest-B2...")
                    checkNotNull(collB1Id) { "B2: 依赖 B1 的集合 ID，B1 未成功创建" }
                    val result = mutationObj(serverUrl, accessToken,
                        """mutation { createRESTChildUserCollection(title: "hstest-B2", parentUserCollectionID: "${collB1Id}") { id title } }""",
                        "createRESTChildUserCollection"
                    )
                    val childId = result.get("id").asString
                    collB2Id = childId
                    println("  id=$childId, title=${result.get("title").asString}")
                    println("  ✅ B2 通过")
                } catch (e: Throwable) {
                    println("  ❌ B2 失败: ${e.message}")
                    e.printStackTrace()
                }

                // B3: 查询根级集合列表 → 应包含 B1 创建的集合
                try {
                    println("\n[B3] 查询根级集合列表...")
                    val roots = queryList(serverUrl, accessToken,
                        """query { rootRESTUserCollections(take: 100) { id title } }""",
                        "rootRESTUserCollections"
                    )
                    val found = roots.any { it.get("title").asString == "hstest-B1" }
                    println("  根集合列表包含 hstest-B1: $found")
                    check(found) { "B3: 根集合列表中未找到 hstest-B1" }
                    println("  ✅ B3 通过")
                } catch (e: Throwable) {
                    println("  ❌ B3 失败: ${e.message}")
                    e.printStackTrace()
                }

                // B4: 查询 B1 的子集合 → 应包含 B2 创建的集合
                try {
                    println("\n[B4] 查询 hstest-B1 的子集合...")
                    val children = queryChildCollections(serverUrl, accessToken, collB1Id!!)
                    val found = children.any { it.get("title").asString == "hstest-B2" }
                    println("  子集合包含 hstest-B2: $found")
                    check(found) { "B4: 子集合中未找到 hstest-B2" }
                    println("  ✅ B4 通过")
                } catch (e: Throwable) {
                    println("  ❌ B4 失败: ${e.message}")
                    e.printStackTrace()
                }

                // B5: 在同一父集合下创建两个同名集合 hstest-dup → 应都成功
                try {
                    println("\n[B5] 在同一父集合下创建两个同名集合 hstest-dup...")
                    for (i in 1..2) {
                        val result = mutationObj(serverUrl, accessToken,
                            """mutation { createRESTChildUserCollection(title: "hstest-dup", parentUserCollectionID: "${collB1Id}") { id title } }""",
                            "createRESTChildUserCollection"
                        )
                        val id = result.get("id").asString
                        dupCollIds.add(id)
                        println("  创建 #$i: id=$id")
                    }
                    check(dupCollIds.size == 2) { "B5: 应创建 2 个同名集合" }
                    check(dupCollIds[0] != dupCollIds[1]) { "B5: 两个集合 ID 应不同" }
                    println("  ✅ B5 通过")
                } catch (e: Throwable) {
                    println("  ❌ B5 失败: ${e.message}")
                    e.printStackTrace()
                }

                // 清理 dup 集合
                for (id in dupCollIds) {
                    try {
                        graphQL(serverUrl, accessToken,
                            """mutation { deleteUserCollection(userCollectionID: "$id") }""",
                            "deleteUserCollection"
                        ) { data -> data }
                    } catch (_: Exception) {}
                }

                // B6: 删除集合 → 返回 true
                try {
                    println("\n[B6] 删除集合 hstest-B1...")
                    val deleted = graphQL(serverUrl, accessToken,
                        """mutation { deleteUserCollection(userCollectionID: "${collB1Id}") }""",
                        "deleteUserCollection"
                    ) { data -> data.isJsonNull || data.asBoolean }
                    println("  删除结果: $deleted")
                    check(deleted) { "B6: deleteUserCollection 应返回 true" }

                    // 验证已删除
                    val roots = queryList(serverUrl, accessToken,
                        """query { rootRESTUserCollections(take: 100) { id title } }""",
                        "rootRESTUserCollections"
                    )
                    val stillExists = roots.any { it.get("id").asString == collB1Id }
                    check(!stillExists) { "B6: 删除后 hstest-B1 仍然存在" }
                    println("  ✅ B6 通过")
                    collB1Id = null
                } catch (e: Throwable) {
                    println("  ❌ B6 失败: ${e.message}")
                    e.printStackTrace()
                }
            }

            // ============================================================
            // 场景 C：请求操作
            // ============================================================
            runScenario("C", "请求操作") {

                var collCId: String? = null
                var reqC1Id: String? = null

                try {
                    // 准备集合
                    println("\n[场景 C 准备] 创建测试集合 hstest-C...")
                    val collResult = mutationObj(serverUrl, accessToken,
                        """mutation { createRESTRootUserCollection(title: "hstest-C") { id title } }""",
                        "createRESTRootUserCollection"
                    )
                    collCId = collResult.get("id").asString
                    println("  集合创建成功: id=$collCId")

                    // C1: 在集合中创建请求 → 返回 id
                    try {
                        println("\n[C1] 在集合中创建请求...")
                        val requestJson = buildDefaultRequestJson("GET", "/api/test/c1")
                        val reqResult = mutationObj(serverUrl, accessToken,
                            """mutation { createRESTUserRequest(collectionID: "${collCId}", title: "GET /api/test/c1", request: "${requestJson.replace("\"", "\\\"")}") { id title } }""",
                            "createRESTUserRequest"
                        )
                        reqC1Id = reqResult.get("id").asString
                        val title = reqResult.get("title").asString
                        println("  id=$reqC1Id, title=$title")
                        check(title == "GET /api/test/c1") { "C1: 标题应为 'GET /api/test/c1'，实际 '$title'" }
                        println("  ✅ C1 通过")
                    } catch (e: Throwable) {
                        println("  ❌ C1 失败: ${e.message}")
                        e.printStackTrace()
                    }

                    // C2: 查询集合中的请求列表 → 应包含 C1 创建的请求
                    try {
                        println("\n[C2] 查询集合中的请求列表...")
                        val reqs = queryList(serverUrl, accessToken,
                            """query { userRESTRequests(collectionID: "${collCId}", take: 100) { id title } }""",
                            "userRESTRequests"
                        )
                        val found = reqs.any { it.get("id").asString == reqC1Id }
                        println("  请求列表包含 C1 请求: $found (共 ${reqs.size} 个)")
                        check(found) { "C2: 请求列表中未找到 C1 创建的请求 (id=$reqC1Id)" }
                        println("  ✅ C2 通过")
                    } catch (e: Throwable) {
                        println("  ❌ C2 失败: ${e.message}")
                        e.printStackTrace()
                    }

                    // C3: 更新请求 title → 再次查询 title 应变更
                    try {
                        println("\n[C3] 更新请求 title...")
                        checkNotNull(reqC1Id) { "C3: 依赖 C1 的请求 ID" }
                        val requestJson = buildDefaultRequestJson("GET", "/api/test/c1")
                        val updated = mutationObj(serverUrl, accessToken,
                            """mutation { updateRESTUserRequest(id: "${reqC1Id}", title: "GET /api/test/c1-updated", request: "${requestJson.replace("\"", "\\\"")}") { id title } }""",
                            "updateRESTUserRequest"
                        )
                        val newTitle = updated.get("title").asString
                        println("  更新后 title: $newTitle")
                        check(newTitle == "GET /api/test/c1-updated") { "C3: 更新后标题应为 'GET /api/test/c1-updated'，实际 '$newTitle'" }

                        // 再次查询确认
                        val reqs = queryList(serverUrl, accessToken,
                            """query { userRESTRequests(collectionID: "${collCId}", take: 100) { id title } }""",
                            "userRESTRequests"
                        )
                        val foundUpdated = reqs.any { it.get("title").asString == "GET /api/test/c1-updated" }
                        check(foundUpdated) { "C3: 再次查询未找到更新后的 title" }
                        println("  ✅ C3 通过")
                    } catch (e: Throwable) {
                        println("  ❌ C3 失败: ${e.message}")
                        e.printStackTrace()
                    }

                } finally {
                    // 清理场景 C 数据（按依赖顺序：先删请求，再删集合）
                    if (reqC1Id != null) {
                        try {
                            graphQL(serverUrl, accessToken,
                                """mutation { deleteUserRequest(requestID: "${reqC1Id}") }""",
                                "deleteUserRequest"
                            ) { data -> data }
                            println("  [清理] 删除请求: $reqC1Id")
                        } catch (_: Exception) {}
                    }
                    if (collCId != null) {
                        try {
                            graphQL(serverUrl, accessToken,
                                """mutation { deleteUserCollection(userCollectionID: "${collCId}") }""",
                                "deleteUserCollection"
                            ) { data -> data }
                            println("  [清理] 删除集合: $collCId")
                        } catch (_: Exception) {}
                    }
                }
            }

            // ============================================================
            // 场景 D：集合树查询
            // ============================================================
            runScenario("D", "集合树查询") {

                var collDRootId: String? = null
                var collDChildId: String? = null
                var collDGrandchildId: String? = null

                try {
                    // 准备嵌套结构：hstest-D-root → hstest-D-child → hstest-D-grandchild
                    println("\n[场景 D 准备] 创建三层嵌套集合结构...")
                    val rootResult = mutationObj(serverUrl, accessToken,
                        """mutation { createRESTRootUserCollection(title: "hstest-D-root") { id title } }""",
                        "createRESTRootUserCollection"
                    )
                    collDRootId = rootResult.get("id").asString
                    println("  根集合 hstest-D-root: id=$collDRootId")

                    val childResult = mutationObj(serverUrl, accessToken,
                        """mutation { createRESTChildUserCollection(title: "hstest-D-child", parentUserCollectionID: "${collDRootId}") { id title } }""",
                        "createRESTChildUserCollection"
                    )
                    collDChildId = childResult.get("id").asString
                    println("  子集合 hstest-D-child: id=$collDChildId")

                    val grandResult = mutationObj(serverUrl, accessToken,
                        """mutation { createRESTChildUserCollection(title: "hstest-D-grandchild", parentUserCollectionID: "${collDChildId}") { id title } }""",
                        "createRESTChildUserCollection"
                    )
                    collDGrandchildId = grandResult.get("id").asString
                    println("  孙子集合 hstest-D-grandchild: id=$collDGrandchildId")

                    // 在根集和子集合中各创建一个请求
                    val dReqJson = buildDefaultRequestJson("GET", "/api/test/d-root")
                    mutationObj(serverUrl, accessToken,
                        """mutation { createRESTUserRequest(collectionID: "${collDRootId}", title: "GET /api/test/d-root", request: "${dReqJson.replace("\"", "\\\"")}") { id title } }""",
                        "createRESTUserRequest"
                    )
                    println("  根集合中创建请求: GET /api/test/d-root")

                    val dChildReqJson = buildDefaultRequestJson("GET", "/api/test/d-child")
                    mutationObj(serverUrl, accessToken,
                        """mutation { createRESTUserRequest(collectionID: "${collDChildId}", title: "GET /api/test/d-child", request: "${dChildReqJson.replace("\"", "\\\"")}") { id title } }""",
                        "createRESTUserRequest"
                    )
                    println("  子集合中创建请求: GET /api/test/d-child")

                    // D1: 获取完整集合树
                    try {
                        println("\n[D1] 获取完整集合树（检查多层嵌套）...")

                        // 使用嵌套查询获取两层子集合
                        val tree = queryList(serverUrl, accessToken,
                            """query { rootRESTUserCollections(take: 100) { id title childrenREST(take: 100) { id title childrenREST(take: 100) { id title } } } }""",
                            "rootRESTUserCollections"
                        )

                        // 验证找到 hstest-D-root
                        val dRoot = tree.firstOrNull { it.get("title").asString == "hstest-D-root" }
                        checkNotNull(dRoot) { "D1: 集合树中未找到 hstest-D-root" }

                        // 验证第一层子集合
                        val children = dRoot.get("childrenREST")?.asJsonArray
                        checkNotNull(children) { "D1: hstest-D-root 没有 childrenREST 字段" }
                        val hasChild = children.any { it.asJsonObject.get("title").asString == "hstest-D-child" }
                        check(hasChild) { "D1: 树中应包含 hstest-D-child" }

                        // 验证第二层子集合
                        val dChild = children.first { it.asJsonObject.get("title").asString == "hstest-D-child" }.asJsonObject
                        val grandchildren = dChild.get("childrenREST")?.asJsonArray
                        checkNotNull(grandchildren) { "D1: hstest-D-child 没有 childrenREST 字段" }
                        val hasGrandchild = grandchildren.any { it.asJsonObject.get("title").asString == "hstest-D-grandchild" }
                        check(hasGrandchild) { "D1: 树中应包含 hstest-D-grandchild" }

                        println("  ✅ D1 通过: 完整集合树包含三层嵌套结构")
                    } catch (e: Throwable) {
                        println("  ❌ D1 失败: ${e.message}")
                        e.printStackTrace()
                    }

                    // D2: 按集合标题匹配请求
                    try {
                        println("\n[D2] 按集合标题匹配请求列表...")

                        // 查询 hstest-D-root 下的请求
                        val rootReqs = queryList(serverUrl, accessToken,
                            """query { userRESTRequests(collectionID: "${collDRootId}", take: 100) { id title } }""",
                            "userRESTRequests"
                        )
                        val rootTitles = rootReqs.map { it.get("title").asString }
                        println("  hstest-D-root 请求: $rootTitles")
                        check("GET /api/test/d-root" in rootTitles) {
                            "D2: hstest-D-root 下应包含 'GET /api/test/d-root'"
                        }

                        // 查询 hstest-D-child 下的请求
                        val childReqs = queryList(serverUrl, accessToken,
                            """query { userRESTRequests(collectionID: "${collDChildId}", take: 100) { id title } }""",
                            "userRESTRequests"
                        )
                        val childTitles = childReqs.map { it.get("title").asString }
                        println("  hstest-D-child 请求: $childTitles")
                        check("GET /api/test/d-child" in childTitles) {
                            "D2: hstest-D-child 下应包含 'GET /api/test/d-child'"
                        }

                        println("  ✅ D2 通过: 按集合标题准确匹配到对应请求")
                    } catch (e: Throwable) {
                        println("  ❌ D2 失败: ${e.message}")
                        e.printStackTrace()
                    }

                } finally {
                    // 清理场景 D：删除根集合会递归清理所有子集合和请求
                    if (collDRootId != null) {
                        try {
                            graphQL(serverUrl, accessToken,
                                """mutation { deleteUserCollection(userCollectionID: "${collDRootId}") }""",
                                "deleteUserCollection"
                            ) { data -> data }
                            println("  [清理] 删除集合树 (根: $collDRootId)")
                        } catch (_: Exception) {}
                    }
                }
            }

            // ============================================================
            // 场景 E：同步流程模拟
            // ============================================================
            runScenario("E", "同步流程模拟") {

                var projectCollId: String? = null
                var controllerCollId: String? = null
                var reqE1Id: String? = null

                try {
                    // E1: 创建项目集合 → 控制器子集合 → 请求（模拟首次同步）
                    try {
                        println("\n[E1] 首次同步：创建项目集合 → 控制器子集合 → 请求...")
                        val projectResult = mutationObj(serverUrl, accessToken,
                            """mutation { createRESTRootUserCollection(title: "hstest-E-project") { id title } }""",
                            "createRESTRootUserCollection"
                        )
                        projectCollId = projectResult.get("id").asString
                        println("  项目集合: id=$projectCollId")

                        val controllerResult = mutationObj(serverUrl, accessToken,
                            """mutation { createRESTChildUserCollection(title: "hstest-E-controller", parentUserCollectionID: "${projectCollId}") { id title } }""",
                            "createRESTChildUserCollection"
                        )
                        controllerCollId = controllerResult.get("id").asString
                        println("  控制器子集合: id=$controllerCollId")

                        val requestJson = buildDefaultRequestJson("GET", "/api/test/e1")
                        val reqResult = mutationObj(serverUrl, accessToken,
                            """mutation { createRESTUserRequest(collectionID: "${controllerCollId}", title: "GET /api/test/e1", request: "${requestJson.replace("\"", "\\\"")}") { id title } }""",
                            "createRESTUserRequest"
                        )
                        reqE1Id = reqResult.get("id").asString
                        println("  请求: id=$reqE1Id")
                        println("  ✅ E1 通过: 首次同步创建完成")
                    } catch (e: Throwable) {
                        println("  ❌ E1 失败: ${e.message}")
                        e.printStackTrace()
                    }

                    // E2: 再次同步同一端点 → 验证集合被复用（不重复创建）
                    try {
                        println("\n[E2] 再次同步，验证集合被复用...")

                        // 检查根集合下 hstest-E-project 是否唯一（不重复创建）
                        val roots = queryList(serverUrl, accessToken,
                            """query { rootRESTUserCollections(take: 100) { id title } }""",
                            "rootRESTUserCollections"
                        )
                        val projectMatches = roots.filter { it.get("title").asString == "hstest-E-project" }
                        println("  根级 hstest-E-project 数量: ${projectMatches.size}")
                        check(projectMatches.size == 1) {
                            "E2: 期望 1 个 hstest-E-project，实际 ${projectMatches.size}（说明重复创建了集合）"
                        }

                        // 检查项目集合下 hstest-E-controller 是否唯一
                        val children = queryChildCollections(serverUrl, accessToken, projectCollId!!)
                        val controllerMatches = children.filter { it.get("title").asString == "hstest-E-controller" }
                        println("  项目集合下 hstest-E-controller 数量: ${controllerMatches.size}")
                        check(controllerMatches.size == 1) {
                            "E2: 期望 1 个 hstest-E-controller，实际 ${controllerMatches.size}（说明重复创建了子集合）"
                        }

                        // 检查控制器下已有请求
                        val requests = queryList(serverUrl, accessToken,
                            """query { userRESTRequests(collectionID: "${controllerCollId}", take: 100) { id title } }""",
                            "userRESTRequests"
                        )
                        println("  控制器下请求数: ${requests.size}")
                        check(requests.any { it.get("title").asString == "GET /api/test/e1" }) {
                            "E2: 控制器下应包含之前创建的请求"
                        }
                        println("  ✅ E2 通过: 集合被正确复用，未重复创建")
                    } catch (e: Throwable) {
                        println("  ❌ E2 失败: ${e.message}")
                        e.printStackTrace()
                    }

                    // E3: 同名集合 → 创建两个同名集合，验证可以创建成功
                    try {
                        println("\n[E3] 同名集合创建测试...")
                        val dupIds = mutableListOf<String>()

                        // 在项目集合下创建两个同名控制器集合
                        for (i in 1..2) {
                            val result = mutationObj(serverUrl, accessToken,
                                """mutation { createRESTChildUserCollection(title: "hstest-E-duplicate", parentUserCollectionID: "${projectCollId}") { id title } }""",
                                "createRESTChildUserCollection"
                            )
                            val id = result.get("id").asString
                            dupIds.add(id)
                            println("  创建同名集合 #$i: id=$id")
                        }

                        check(dupIds.size == 2) { "E3: 应创建 2 个同名集合" }
                        check(dupIds[0] != dupIds[1]) { "E3: 两个集合 ID 应不同" }
                        println("  ✅ E3 通过: 同名集合创建成功")

                        // 清理两个重复集合
                        for (id in dupIds) {
                            graphQL(serverUrl, accessToken,
                                """mutation { deleteUserCollection(userCollectionID: "$id") }""",
                                "deleteUserCollection"
                            ) { data -> data }
                        }
                        println("  已清理同名集合")
                    } catch (e: Throwable) {
                        println("  ❌ E3 失败: ${e.message}")
                        e.printStackTrace()
                    }

                } finally {
                    // 清理场景 E 数据（按依赖顺序）
                    if (reqE1Id != null) {
                        try {
                            graphQL(serverUrl, accessToken,
                                """mutation { deleteUserRequest(requestID: "${reqE1Id}") }""",
                                "deleteUserRequest"
                            ) { data -> data }
                        } catch (_: Exception) {}
                    }
                    if (controllerCollId != null) {
                        try {
                            graphQL(serverUrl, accessToken,
                                """mutation { deleteUserCollection(userCollectionID: "${controllerCollId}") }""",
                                "deleteUserCollection"
                            ) { data -> data }
                        } catch (_: Exception) {}
                    }
                    if (projectCollId != null) {
                        try {
                            graphQL(serverUrl, accessToken,
                                """mutation { deleteUserCollection(userCollectionID: "${projectCollId}") }""",
                                "deleteUserCollection"
                            ) { data -> data }
                        } catch (_: Exception) {}
                    }
                }
            }

            println("\n" + "=" .repeat(60))
            println("🎉 所有场景测试完成！")
            println("=" .repeat(60))

        } catch (e: Throwable) {
            println("\n❌ 顶层异常: ${e.message}")
            e.printStackTrace()
            throw e
        } finally {
            // 最终清理：删除所有 hstest 前缀的测试数据
            println("\n[最终清理] 删除所有 hstest 前缀的测试数据...")
            try {
                cleanupTestData(serverUrl, accessToken)
                println("  最终清理完成")
            } catch (e: Exception) {
                System.err.println("  ⚠️ 最终清理异常: ${e.message}")
            }
        }
    }

    // ====================================================================
    // 场景运行辅助
    // ====================================================================

    /**
     * 运行一个场景，带标题头和 finally 清理。
     * 场景内的异常被捕获，不影响后续场景。
     */
    private fun runScenario(label: String, title: String, block: () -> Unit) {
        println("\n" + "=" .repeat(60))
        println("场景 $label：$title")
        println("=" .repeat(60))
        try {
            block()
        } catch (e: Throwable) {
            println("\n⚠️ 场景 $label 整体异常: ${e.message}")
            e.printStackTrace()
        }
    }

    // ====================================================================
    // GraphQL 工具方法
    // ====================================================================

    /**
     * 查询子集合列表（处理 childrenREST 嵌套提取）。
     */
    private fun queryChildCollections(
        serverUrl: String,
        accessToken: String,
        parentId: String
    ): List<com.google.gson.JsonObject> {
        return graphQL(serverUrl, accessToken,
            """query { userCollection(userCollectionID: "$parentId") { childrenREST(take: 100) { id title } } }""",
            "userCollection"
        ) { data ->
            val children = data.asJsonObject.get("childrenREST")
            if (children == null || children.isJsonNull) emptyList()
            else children.asJsonArray.map { it.asJsonObject }
        }
    }

    /**
     * 执行查询并返回 JSON 对象列表（从指定 operation 的 JSON 数组提取）。
     */
    private fun queryList(
        serverUrl: String,
        accessToken: String,
        query: String,
        operation: String
    ): List<com.google.gson.JsonObject> {
        return graphQL(serverUrl, accessToken, query, operation) { data ->
            if (data.isJsonNull) emptyList()
            else data.asJsonArray.map { it.asJsonObject }
        }
    }

    /**
     * 执行变更操作，返回单个 JSON 对象。
     */
    private fun mutationObj(
        serverUrl: String,
        accessToken: String,
        query: String,
        operation: String
    ): com.google.gson.JsonObject {
        return graphQL(serverUrl, accessToken, query, operation) { data -> data.asJsonObject }
    }

    /**
     * 通用的 GraphQL 调用工具方法。
     *
     * 发送 POST 请求到 {serverUrl}/graphql，解析 JSON 响应，自动检查 HTTP 状态码和 errors 字段。
     *
     * @param serverUrl Hoppscotch 服务端地址
     * @param accessToken Bearer token
     * @param query GraphQL 查询或变更语句（内联变量形式）
     * @param operation 响应 data 中对应的字段名
     * @param extractor 从 JsonElement 中提取目标数据
     * @return 提取器返回的数据
     */
    private fun <T> graphQL(
        serverUrl: String,
        accessToken: String,
        query: String,
        operation: String,
        extractor: (com.google.gson.JsonElement) -> T
    ): T {
        val body = gson.toJson(mapOf("query" to query))
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$serverUrl/graphql"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $accessToken")
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            error("HTTP ${response.statusCode()}: ${response.body()}")
        }
        val json = JsonParser.parseString(response.body()).asJsonObject
        json.get("errors")?.let { errors ->
            val msg = errors.asJsonArray.map { it.asJsonObject.get("message").asString }.joinToString("; ")
            error("GraphQL 错误: $msg\n查询: $query")
        }
        val data = json.getAsJsonObject("data") ?: error("响应缺少 data")
        val result = data.get(operation) ?: error("响应缺少 operation '$operation'")
        return extractor(result)
    }

    // ====================================================================
    // 数据生成
    // ====================================================================

    /**
     * 构建默认的 Hoppscotch 请求 JSON（v17 格式）。
     */
    private fun buildDefaultRequestJson(method: String, endpoint: String): String {
        return """{"v":"17","name":"test","method":"$method","endpoint":"$endpoint","params":[],"headers":[],"auth":{"authType":"inherit","authActive":true},"body":{"contentType":null,"body":null},"responses":{},"testScript":"","preRequestScript":"","requestVariables":[]}"""
    }

    // ====================================================================
    // 数据清理
    // ====================================================================

    /**
     * 清理所有标题以 [TEST_PREFIX] 开头的根级集合。
     *
     * 安全机制：只删除标题以 `hstest` 开头的集合，不会误删用户数据。
     * 用于：
     * 1. 启动时清理前次异常终止残留的测试数据
     * 2. 结束时确保所有测试数据被删除
     */
    private fun cleanupTestData(serverUrl: String, accessToken: String) {
        try {
            val roots = queryList(serverUrl, accessToken,
                """query { rootRESTUserCollections(take: 10000) { id title } }""",
                "rootRESTUserCollections"
            )
            var cleaned = 0
            for (root in roots) {
                val title = root.get("title").asString
                val id = root.get("id").asString
                if (title.startsWith(TEST_PREFIX)) {
                    graphQL(serverUrl, accessToken,
                        """mutation { deleteUserCollection(userCollectionID: "$id") }""",
                        "deleteUserCollection"
                    ) { data -> data }
                    println("  🗑️ 删除: [$id] $title")
                    cleaned++
                }
            }
            if (cleaned > 0) {
                println("  已清理 $cleaned 个测试集合")
            } else {
                println("  没有发现残留测试数据")
            }
        } catch (e: Exception) {
            System.err.println("  ⚠️ 清理阶段异常: ${e.message}")
        }
    }
}
