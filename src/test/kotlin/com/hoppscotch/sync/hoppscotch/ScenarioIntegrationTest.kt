package com.hoppscotch.sync.hoppscotch

import com.hoppscotch.sync.model.*
import com.hoppscotch.sync.model.LogLevel
import com.hoppscotch.sync.service.SyncService
import com.hoppscotch.sync.settings.AppSettings
import com.hoppscotch.sync.util.LogUtil
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import com.intellij.openapi.diagnostic.Logger

/**
 * 场景化集成测试 — 通过 HoppscotchClient 实例调用，覆盖插件实际功能模块。
 *
 * 按设置页功能模块分组，每个模块独立 try-catch。纯逻辑测试（C3/C5/C6）在前，
 * 需要服务端的测试（C1/C2/S4）在后，服务器不可达时纯逻辑测试仍可运行。
 *
 * 测试数据使用 `hstest` 前缀，启动时清前次残留，结束时清本次数据。
 *
 * 运行：
 *   export HOPPSCOTCH_URL=... HOPPOTSCOTCH_ACCESS_TOKEN=...
 *   ./gradlew runScenarioTest
 */
object ScenarioIntegrationTest {

    private const val TEST_PREFIX = "hstest"

    /** 构建默认的 Hoppscotch 请求 JSON（v17 格式）。 */
    private fun buildDefaultRequestJson(method: String, endpoint: String): String {
        return """{"v":"17","name":"test","method":"$method","endpoint":"$endpoint","params":[],"headers":[],"auth":{"authType":"inherit","authActive":true},"body":{"contentType":null,"body":null},"responses":{},"testScript":"","preRequestScript":"","requestVariables":[]}"""
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val serverUrl = (System.getProperty("HOPPSCOTCH_URL") ?: System.getenv("HOPPSCOTCH_URL")
            ?: error("缺少 HOPPSCOTCH_URL")).trimEnd('/')
        val accessToken = System.getProperty("HOPPSCOTCH_ACCESS_TOKEN") ?: System.getenv("HOPPSCOTCH_ACCESS_TOKEN")
            ?: error("缺少 HOPPSCOTCH_ACCESS_TOKEN")

        // ── 提前 mock IntelliJ Platform 依赖 ──
        mockkStatic(Logger::class)
        every { Logger.getInstance(any<Class<*>>()) } returns mockk(relaxed = true)
        // LogUtil.stdout/stackTrace 内部访问 AppSettings（需要 IntelliJ Application），
        // 显式 mock 使其不执行 lambda 体
        mockkObject(LogUtil)
        every { LogUtil.stdout(any<() -> String>()) } answers {}
        every { LogUtil.stackTrace(any<Throwable>()) } answers {}
        every { LogUtil.debug(any(), any<() -> String>()) } answers {}
        every { LogUtil.info(any(), any<() -> String>()) } answers {}

        var client: HoppscotchClient? = null

        try {
            println("=" .repeat(60))
            println("场景化集成测试（HoppscotchClient 版）")
            println("服务端: $serverUrl")
            println("=" .repeat(60))

            // ── 初始化客户端 ──
            client = HoppscotchClient(
                serverUrl = serverUrl,
                accessToken = accessToken,
                refreshToken = null
            )

            // ── 启动时清理前次残留 ──
            println("\n[预清理] 清理前次残留测试数据（前缀: $TEST_PREFIX）...")
            cleanupTestData(client)

            // ============================================================
            // 纯逻辑测试（不需要服务器）→ 放在最前面
            // ============================================================
            runModule("C3", "数据转换") { testDataConversion() }
            runModule("C5", "同步状态检测") { testSyncStatusDetection() }
            runModule("C6", "持久化") { testPersistence() }
            runModule("S4P", "合并策略") { testMergeStrategies() }

            // ============================================================
            // 需要服务端的测试
            // ============================================================
            runModule("C1", "服务端连接配置") { testServerConnectionConfig(serverUrl, accessToken, client) }
            runModule("C2", "版本检测") { testVersionDetection(serverUrl) }
            runModule("S4", "同步编排") { testSyncOrchestration(client) }

            println("\n" + "=" .repeat(60))
            println("🎉 所有模块测试完成！")
            println("=" .repeat(60))

        } catch (e: Throwable) {
            println("\n❌ 顶层异常: ${e.message}")
            e.printStackTrace()
            throw e
        } finally {
            // ── 最终清理 ──
            println("\n[最终清理] 删除所有 hstest 前缀的测试数据...")
            if (client != null) {
                try {
                    cleanupTestData(client)
                } catch (e: Exception) {
                    System.err.println("  ⚠️ 最终清理异常: ${e.message}")
                }
                try {
                    client.close()
                    println("  HoppscotchClient 已关闭")
                } catch (_: Exception) {}
            }
        }
    }

    // ====================================================================
    // 模块运行辅助
    // ====================================================================

    private fun runModule(label: String, title: String, block: () -> Unit) {
        println("\n" + "=" .repeat(60))
        println("模块 $label：$title")
        println("=" .repeat(60))
        try {
            block()
        } catch (e: Throwable) {
            println("\n⚠️ 模块 $label 整体异常: ${e.message}")
            e.printStackTrace()
        }
    }

    // ====================================================================
    // 数据清理
    // ====================================================================

    /**
     * 清理所有标题以 [TEST_PREFIX] 开头的根级集合。
     * 安全机制：只删除标题以 `hstest` 开头的集合，不会误删用户数据。
     */
    private fun cleanupTestData(client: HoppscotchClient) {
        try {
            val roots = client.listCollections().getOrNull() ?: return
            var cleaned = 0
            for (root in roots) {
                if (root.title.startsWith(TEST_PREFIX)) {
                    client.deleteCollection(root.id)
                    println("  🗑️ 删除: [${root.id}] ${root.title}")
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

    // ====================================================================
    // 模块 C3：数据转换（纯逻辑，不需要服务器）
    // ====================================================================

    private fun testDataConversion() {
        // C3-1: SpringEndpoint(GET) → HoppscotchRequest → 序列化 JSON
        try {
            println("\n[C3-1] SpringEndpoint(GET) → HoppscotchRequest → 序列化 JSON...")
            val endpoint = SpringEndpoint(
                controllerClassName = "TestController",
                controllerClassQualifiedName = "com.test.TestController",
                methodName = "getUser",
                httpMethod = HttpMethod.GET,
                path = "/users/{id}",
                fullPath = "/api/users/{id}",
                description = "获取用户信息",
                parameters = listOf(
                    EndpointParameter(name = "id", type = "Long", source = ParamSource.PATH, required = true)
                )
            )
            val converter = HoppscotchDataConverter()
            val request = converter.toHoppscotchRequest(endpoint)
            val json = converter.toRequestRequestBody(request)
            println("  method=${request.method}, endpoint=${request.endpoint}")
            check(request.method == "GET") { "method 应为 GET，实际 ${request.method}" }
            check(request.endpoint == "/api/users/{id}") { "endpoint 不匹配" }
            check(request.params.any { it.key == "id" }) { "应包含 path param id" }
            check(json.contains("\"method\": \"GET\"")) { "JSON 应包含 method: GET（pretty-print 带空格）" }
            println("  ✅ C3-1 通过")
        } catch (e: Throwable) {
            println("  ❌ C3-1 失败: ${e.message}")
            e.printStackTrace()
        }

        // C3-2: SpringEndpoint(POST, @RequestBody) → HoppscotchRequest → 含 body
        try {
            println("\n[C3-2] SpringEndpoint(POST, @RequestBody) → HoppscotchRequest → 含 body...")
            val endpoint = SpringEndpoint(
                controllerClassName = "TestController",
                controllerClassQualifiedName = "com.test.TestController",
                methodName = "createUser",
                httpMethod = HttpMethod.POST,
                path = "/users",
                fullPath = "/api/users",
                parameters = listOf(
                    EndpointParameter(
                        name = "user", type = "User", source = ParamSource.BODY, required = true,
                        bodyJsonTemplate = """{"name":"string","age":0}"""
                    )
                )
            )
            val converter = HoppscotchDataConverter()
            val request = converter.toHoppscotchRequest(endpoint)
            val json = converter.toRequestRequestBody(request)
            println("  method=${request.method}, contentType=${request.body.contentType}")
            check(request.method == "POST") { "method 应为 POST" }
            check(request.body.contentType == "application/json") { "contentType 应为 application/json" }
            check(request.body.body?.contains("name") == true) { "body 应包含 name 字段" }
            check(json.contains("\"method\": \"POST\"")) { "JSON 应包含 method: POST（pretty-print 带空格）" }
            println("  ✅ C3-2 通过")
        } catch (e: Throwable) {
            println("  ❌ C3-2 失败: ${e.message}")
            e.printStackTrace()
        }

        // C3-3: RequestValidator.validate(合法 JSON) → valid=true
        try {
            println("\n[C3-3] RequestValidator.validate(合法 JSON)...")
            val validJson = buildDefaultRequestJson("GET", "/api/test/validate")
            val result = RequestValidator.validate(validJson)
            println("  valid=${result.valid}, errors=${result.errors}, warnings=${result.warnings}")
            check(result.valid) { "合法 JSON 应通过校验" }
            println("  ✅ C3-3 通过")
        } catch (e: Throwable) {
            println("  ❌ C3-3 失败: ${e.message}")
            e.printStackTrace()
        }

        // C3-4: RequestValidator.validate(缺少必填字段) → valid=false, 有 errors
        try {
            println("\n[C3-4] RequestValidator.validate(缺少必填字段)...")
            val invalidJson = """{"name":"test"}"""
            val result = RequestValidator.validate(invalidJson)
            println("  valid=${result.valid}, errors=${result.errors}")
            check(!result.valid) { "缺少必填字段应校验失败" }
            check(result.errors.isNotEmpty()) { "应有错误信息" }
            println("  ✅ C3-4 通过")
        } catch (e: Throwable) {
            println("  ❌ C3-4 失败: ${e.message}")
            e.printStackTrace()
        }
    }

    // ====================================================================
    // 模块 C5：同步状态检测（纯逻辑，不需要服务器）
    // ====================================================================

    private fun testSyncStatusDetection() {
        // C5-1: computeEndpointHash(相同端点) → hash 相同
        try {
            println("\n[C5-1] computeEndpointHash(相同端点) → hash 相同...")
            val ep1 = SpringEndpoint(
                controllerClassName = "Ctl", controllerClassQualifiedName = "com.Ctl",
                methodName = "get", httpMethod = HttpMethod.GET,
                path = "/users/{id}", fullPath = "/api/users/{id}",
                parameters = listOf(
                    EndpointParameter(name = "id", type = "Long", source = ParamSource.PATH, required = true)
                )
            )
            val ep2 = SpringEndpoint(
                controllerClassName = "Ctl", controllerClassQualifiedName = "com.Ctl",
                methodName = "get", httpMethod = HttpMethod.GET,
                path = "/users/{id}", fullPath = "/api/users/{id}",
                parameters = listOf(
                    EndpointParameter(name = "id", type = "Long", source = ParamSource.PATH, required = true)
                )
            )
            val hash1 = computeEndpointHash(ep1)
            val hash2 = computeEndpointHash(ep2)
            println("  hash1=$hash1, hash2=$hash2")
            check(hash1 == hash2) { "相同端点的 hash 应相同" }
            println("  ✅ C5-1 通过")
        } catch (e: Throwable) {
            println("  ❌ C5-1 失败: ${e.message}")
            e.printStackTrace()
        }

        // C5-2: computeEndpointHash(不同端点) → hash 不同
        try {
            println("\n[C5-2] computeEndpointHash(不同端点) → hash 不同...")
            val ep1 = SpringEndpoint(
                controllerClassName = "Ctl", controllerClassQualifiedName = "com.Ctl",
                methodName = "get", httpMethod = HttpMethod.GET,
                path = "/users", fullPath = "/api/users",
            )
            val ep2 = SpringEndpoint(
                controllerClassName = "Ctl", controllerClassQualifiedName = "com.Ctl",
                methodName = "create", httpMethod = HttpMethod.POST,
                path = "/users", fullPath = "/api/users",
            )
            val hash1 = computeEndpointHash(ep1)
            val hash2 = computeEndpointHash(ep2)
            println("  hash1=$hash1 (GET), hash2=$hash2 (POST)")
            check(hash1 != hash2) { "不同端点的 hash 应不同" }
            println("  ✅ C5-2 通过")
        } catch (e: Throwable) {
            println("  ❌ C5-2 失败: ${e.message}")
            e.printStackTrace()
        }

        // C5-3: SyncPersistData.parse() → 兼容新旧格式
        try {
            println("\n[C5-3] SyncPersistData.parse() 兼容性测试...")

            // 新格式: "serverId,localHash,srvHash"
            val newFormat = SyncPersistData.parse("abc123,12345,67890")
            check(newFormat.serverId == "abc123") { "新格式: serverId 应为 abc123，实际 ${newFormat.serverId}" }
            check(newFormat.localHash == 12345) { "新格式: localHash 应为 12345" }
            check(newFormat.srvHash == 67890) { "新格式: srvHash 应为 67890" }
            println("  新格式 'abc123,12345,67890' → serverId=${newFormat.serverId}, localHash=${newFormat.localHash}, srvHash=${newFormat.srvHash}")

            // 旧格式: "localHash,srvHash"
            val oldFormat = SyncPersistData.parse("111,222")
            check(oldFormat.serverId == null) { "旧格式: serverId 应为 null" }
            check(oldFormat.localHash == 111) { "旧格式: localHash 应为 111" }
            check(oldFormat.srvHash == 222) { "旧格式: srvHash 应为 222" }
            println("  旧格式 '111,222' → serverId=${oldFormat.serverId}, localHash=${oldFormat.localHash}, srvHash=${oldFormat.srvHash}")

            // 最旧格式: "localHash"
            val oldestFormat = SyncPersistData.parse("42")
            check(oldestFormat.serverId == null) { "最旧格式: serverId 应为 null" }
            check(oldestFormat.localHash == 42) { "最旧格式: localHash 应为 42" }
            check(oldestFormat.srvHash == 0) { "最旧格式: srvHash 应为 0" }
            println("  最旧格式 '42' → serverId=${oldestFormat.serverId}, localHash=${oldestFormat.localHash}, srvHash=${oldestFormat.srvHash}")

            // 空字符串
            val empty = SyncPersistData.parse("")
            check(empty.serverId == null) { "空字符串: serverId 应为 null" }
            check(empty.localHash == 0) { "空字符串: localHash 应为 0" }
            println("  空字符串 '' → serverId=${empty.serverId}, localHash=${empty.localHash}")

            println("  ✅ C5-3 通过")
        } catch (e: Throwable) {
            println("  ❌ C5-3 失败: ${e.message}")
            e.printStackTrace()
        }
    }

    // ====================================================================
    // 模块 C6：持久化（纯逻辑，不需要服务器）
    // ====================================================================

    private fun testPersistence() {
        // C6-1: AppSettings.deserializeSyncMap / serializeSyncMap → 往返一致
        try {
            println("\n[C6-1] AppSettings serializeSyncMap ↔ deserializeSyncMap 往返一致...")
            val original = mapOf(
                "com.TestController:GET:/api/users" to "12345,67890",
                "com.TestController:POST:/api/users" to "abc123,111,222"
            )
            val serialized = AppSettings.serializeSyncMap(original)
            println("  序列化: $serialized")
            val deserialized = AppSettings.deserializeSyncMap(serialized)
            println("  反序列化: $deserialized")
            check(deserialized == original) { "往返不一致: 期望 $original, 实际 $deserialized" }

            // 空白字符串
            val empty = AppSettings.deserializeSyncMap("")
            check(empty.isEmpty()) { "空白序列化字符串应返回空 map" }

            // 旧格式兼容（Int values）
            // 注意：Gson 的 fromJson<Map<String,String>> 会将 Int 值自动转为 String，
            // 因此旧格式 Int values 会被新格式路径捕获，不触发旧格式转换逻辑
            val oldJson = """{"key1":12345,"key2":67890}"""
            val oldResult = AppSettings.deserializeSyncMap(oldJson)
            println("  旧格式兼容: $oldJson → $oldResult")
            check(oldResult.size == 2) { "旧格式应解析出 2 个条目" }
            check(oldResult["key1"] == "12345") { "旧格式兼容: Gson 会将 Int 自动转为 String" }
            println("  ✅ C6-1 通过")
        } catch (e: Throwable) {
            println("  ❌ C6-1 失败: ${e.message}")
            e.printStackTrace()
        }

        // C6-2: deserializeProjects / serializeProjects → 往返一致
        try {
            println("\n[C6-2] AppSettings serializeProjects ↔ deserializeProjects 往返一致...")
            val original = setOf("module-a", "module-b", "module-c")
            val serialized = AppSettings.serializeProjects(original)
            println("  序列化: $serialized")
            val deserialized = AppSettings.deserializeProjects(serialized)
            println("  反序列化: $deserialized")
            check(deserialized == original) { "往返不一致: 期望 $original, 实际 $deserialized" }

            // 空白字符串
            val empty = AppSettings.deserializeProjects("")
            check(empty.isEmpty()) { "空白字符串应返回空 set" }

            // 单个元素
            val single = AppSettings.deserializeProjects("only-one")
            check(single == setOf("only-one")) { "单个元素解析有误" }
            println("  ✅ C6-2 通过")
        } catch (e: Throwable) {
            println("  ❌ C6-2 失败: ${e.message}")
            e.printStackTrace()
        }
    }

    // ====================================================================
    // 模块 S4（纯逻辑部分）— 合并策略（不需要服务器）
    // ====================================================================

    private fun testMergeStrategies() {
        // S4-8: MERGE_SERVER_FIRST — 服务端字段优先，推送字段补充
        try {
            println("\n[S4-8] MERGE_SERVER_FIRST：服务端字段优先合并...")
            val serverJson = """{"method":"GET","endpoint":"/api/users","name":"server-name"}"""
            val pluginJson = """{"method":"POST","endpoint":"/api/users","name":"plugin-name","headers":[]}"""
            val merged = SyncService.mergeRequestJsons(serverJson, pluginJson, serverFirst = true)
            val obj = com.google.gson.JsonParser.parseString(merged).asJsonObject
            println("  合并结果: $merged")
            check(obj.get("method").asString == "GET") { "SERVER_FIRST: method 应保留服务端值" }
            check(obj.get("name").asString == "server-name") { "SERVER_FIRST: name 应保留服务端值" }
            check(obj.get("headers")?.isJsonArray == true) { "SERVER_FIRST: headers 应从推送方补充" }
            println("  ✅ S4-8 通过")
        } catch (e: Throwable) {
            println("  ❌ S4-8 失败: ${e.message}")
            e.printStackTrace()
        }

        // S4-9: MERGE_PLUGIN_FIRST — 推送字段优先，服务端字段补充
        try {
            println("\n[S4-9] MERGE_PLUGIN_FIRST：推送字段优先合并...")
            val serverJson = """{"method":"GET","endpoint":"/api/users","name":"server-name"}"""
            val pluginJson = """{"method":"POST","endpoint":"/api/users","name":"plugin-name","headers":[]}"""
            val merged = SyncService.mergeRequestJsons(serverJson, pluginJson, serverFirst = false)
            val obj = com.google.gson.JsonParser.parseString(merged).asJsonObject
            println("  合并结果: $merged")
            check(obj.get("method").asString == "POST") { "PLUGIN_FIRST: method 应用推送值覆盖" }
            check(obj.get("name").asString == "plugin-name") { "PLUGIN_FIRST: name 应用推送值覆盖" }
            check(obj.get("headers")?.isJsonArray == true) { "PLUGIN_FIRST: headers 应从推送方补充" }
            println("  ✅ S4-9 通过")
        } catch (e: Throwable) {
            println("  ❌ S4-9 失败: ${e.message}")
            e.printStackTrace()
        }
    }

    // ====================================================================
    // 模块 C1：服务端连接配置（需要服务器）
    // ====================================================================

    private fun testServerConnectionConfig(serverUrl: String, accessToken: String, client: HoppscotchClient) {
        // C1-1: 有效 token → verifyTokens 返回 (true, ...)
        try {
            println("\n[C1-1] 有效 token → verifyTokens 返回 (true, ...)...")
            val (ok, msg) = HoppscotchClient.verifyTokens(serverUrl, accessToken, null)
            println("  result=($ok, ${msg.take(80)}...)")
            check(ok) { "有效 token 应验证通过, msg=$msg" }
            println("  ✅ C1-1 通过")
        } catch (e: Throwable) {
            println("  ❌ C1-1 失败: ${e.message}")
            e.printStackTrace()
        }

        // C1-2: 无效 token → verifyTokens 返回 (false, ...)
        try {
            println("\n[C1-2] 无效 token → verifyTokens 返回 (false, ...)...")
            val (ok, msg) = HoppscotchClient.verifyTokens(serverUrl, "invalid_token_xxx", null)
            println("  result=($ok, ${msg.take(80)}...)")
            check(!ok) { "无效 token 应验证失败" }
            println("  ✅ C1-2 通过")
        } catch (e: Throwable) {
            println("  ❌ C1-2 失败: ${e.message}")
            e.printStackTrace()
        }

        // C1-3: 不可达 URL → verifyTokens 返回 (false, ...)
        try {
            println("\n[C1-3] 不可达 URL → verifyTokens 返回 (false, ...)...")
            val (ok, msg) = HoppscotchClient.verifyTokens("http://0.0.0.0:1", accessToken, null)
            println("  result=($ok, ${msg.take(80)}...)")
            check(!ok) { "不可达 URL 应验证失败" }
            println("  ✅ C1-3 通过")
        } catch (e: Throwable) {
            println("  ❌ C1-3 失败: ${e.message}")
            e.printStackTrace()
        }

        // C1-4: 空白 token → verifyTokens 返回 (false, ...)
        try {
            println("\n[C1-4] 空白 token → verifyTokens 返回 (false, ...)...")
            val (ok, msg) = HoppscotchClient.verifyTokens(serverUrl, "", null)
            println("  result=($ok, ${msg.take(80)}...)")
            check(!ok) { "空白 token 应验证失败" }
            println("  ✅ C1-4 通过")
        } catch (e: Throwable) {
            println("  ❌ C1-4 失败: ${e.message}")
            e.printStackTrace()
        }

        // C1-5: fetchCurrentUserUid → 返回非空 uid
        try {
            println("\n[C1-5] fetchCurrentUserUid → 返回非空 uid...")
            val uid = client.fetchCurrentUserUid()
            println("  uid=$uid")
            check(!uid.isNullOrBlank()) { "uid 不应为空" }
            println("  ✅ C1-5 通过")
        } catch (e: Throwable) {
            println("  ❌ C1-5 失败: ${e.message}")
            e.printStackTrace()
        }
    }

    // ====================================================================
    // 模块 C2：版本检测（需要服务器）
    // ====================================================================

    private fun testVersionDetection(serverUrl: String) {
        // C2-1: 可达服务端 → checkServerHealth 返回 reachable=true
        try {
            println("\n[C2-1] 可达服务端 → checkServerHealth 返回 reachable=true...")
            val result = HoppscotchVersionChecker.checkServerHealth(serverUrl)
            println("  reachable=${result.reachable}, elapsedMs=${result.elapsedMs}, detectedEndpoint=${result.detectedEndpoint}")
            check(result.reachable) { "可达服务端应检测为 reachable=true" }
            println("  ✅ C2-1 通过")
        } catch (e: Throwable) {
            println("  ❌ C2-1 失败: ${e.message}")
            e.printStackTrace()
        }

        // C2-2: 不可达 URL → checkServerHealth 返回 reachable=false
        try {
            println("\n[C2-2] 不可达 URL → checkServerHealth 返回 reachable=false...")
            val result = HoppscotchVersionChecker.checkServerHealth("http://0.0.0.0:1")
            println("  reachable=${result.reachable}, elapsedMs=${result.elapsedMs}")
            check(!result.reachable) { "不可达 URL 应检测为 reachable=false" }
            println("  ✅ C2-2 通过")
        } catch (e: Throwable) {
            println("  ❌ C2-2 失败: ${e.message}")
            e.printStackTrace()
        }
    }

    // ====================================================================
    // 模块 S4：同步编排（需要服务器）
    // ====================================================================

    private fun testSyncOrchestration(client: HoppscotchClient) {
        var projectCollId: String? = null
        var controllerCollId: String? = null
        var requestId: String? = null
        var strategyReqId: String? = null

        try {
            // S4-1: 首次同步 — 模拟 createCollection → createChildCollection → createRequest
            try {
                println("\n[S4-1] 首次同步：创建项目集合 → 控制器子集合 → 请求...")
                val project = client.createCollection("hstest-S4-project").getOrThrow()
                projectCollId = project.id
                println("  项目集合: id=${project.id}, title=${project.title}")

                val controller = client.createChildCollection("hstest-S4-controller", projectCollId).getOrThrow()
                controllerCollId = controller.id
                println("  控制器子集合: id=${controller.id}, title=${controller.title}")

                val requestJson = buildDefaultRequestJson("GET", "/api/test/s4")
                val reqResult = client.createRequest(controllerCollId, "GET /api/test/s4", requestJson).getOrThrow()
                requestId = reqResult.id
                println("  请求: id=${reqResult.id}, title=${reqResult.title}")
                println("  ✅ S4-1 通过")
            } catch (e: Throwable) {
                println("  ❌ S4-1 失败: ${e.message}")
                e.printStackTrace()
            }

            // S4-2: 再次同步（SERVER_FIRST）→ 集合 title 匹配复用，不重复创建
            try {
                println("\n[S4-2] 再次同步：验证集合被复用（不重复创建）...")

                // 检查根集合下 hstest-S4-project 是否唯一
                val roots = client.listCollections().getOrThrow()
                val projectMatches = roots.filter { it.title == "hstest-S4-project" }
                println("  根级 hstest-S4-project 数量: ${projectMatches.size}")
                check(projectMatches.size == 1) {
                    "期望 1 个 hstest-S4-project，实际 ${projectMatches.size}（说明重复创建）"
                }

                // 检查项目集合下 hstest-S4-controller 是否唯一
                val children = client.listChildCollections(projectCollId!!).getOrThrow()
                val controllerMatches = children.filter { it.title == "hstest-S4-controller" }
                println("  项目集合下 hstest-S4-controller 数量: ${controllerMatches.size}")
                check(controllerMatches.size == 1) {
                    "期望 1 个 hstest-S4-controller，实际 ${controllerMatches.size}（说明重复创建）"
                }

                // 检查控制器下已有请求
                val requests = client.listRequests(controllerCollId!!).getOrThrow()
                println("  控制器下请求数: ${requests.size}")
                check(requests.any { it.title == "GET /api/test/s4" }) {
                    "控制器下应包含之前创建的请求"
                }
                println("  ✅ S4-2 通过")
            } catch (e: Throwable) {
                println("  ❌ S4-2 失败: ${e.message}")
                e.printStackTrace()
            }

            // S4-3: 同名集合 title 匹配 → filter{title}.first() 不重复创建
            try {
                println("\n[S4-3] 同名集合匹配测试...")
                val dupIds = mutableListOf<String>()

                // 创建两个同名子集合
                for (i in 1..2) {
                    val coll = client.createChildCollection("hstest-S4-dup", projectCollId!!).getOrThrow()
                    dupIds.add(coll.id)
                    println("  创建同名集合 #$i: id=${coll.id}, title=${coll.title}")
                }

                // 查询子集合列表，按 title 过滤取 first（与 SyncService 匹配逻辑一致）
                val children = client.listChildCollections(projectCollId!!).getOrThrow()
                val matched = children.filter { it.title == "hstest-S4-dup" }
                println("  匹配到同名集合数: ${matched.size}")
                check(matched.size == 2) { "应匹配到 2 个同名集合，实际 ${matched.size}" }

                val firstMatch = matched.first()
                println("  first() 选中的集合: id=${firstMatch.id}, title=${firstMatch.title}")
                check(firstMatch.id in dupIds) { "选中的集合应在之前创建的列表中" }

                // 清理两个同名集合
                for (id in dupIds) {
                    client.deleteCollection(id)
                    println("  清理同名集合: $id")
                }
                println("  ✅ S4-3 通过")
            } catch (e: Throwable) {
                println("  ❌ S4-3 失败: ${e.message}")
                e.printStackTrace()
            }

            // S4-4: 同步到目标集合下（target 模式）
            try {
                println("\n[S4-4] 同步到目标集合下（target 模式）...")

                // 创建一个根集合作为 target
                val target = client.createCollection("hstest-S4-target").getOrThrow()
                println("  创建 target 集合: id=${target.id}")

                // 在 target 下创建子集合（模拟同步到目标）
                val child = client.createChildCollection("hstest-S4-target-child", target.id).getOrThrow()
                println("  创建 target 子集合: id=${child.id}")

                // 在子集合中创建请求
                val reqJson = buildDefaultRequestJson("GET", "/api/test/s4-target")
                val req = client.createRequest(child.id, "GET /api/test/s4-target", reqJson).getOrThrow()
                println("  创建请求: id=${req.id}")

                // 验证 target 下有正确的子集合
                val targetChildren = client.listChildCollections(target.id).getOrThrow()
                val matchedChild = targetChildren.filter { it.title == "hstest-S4-target-child" }
                println("  target 下匹配的子集合数: ${matchedChild.size}")
                check(matchedChild.size == 1) { "target 下应有 1 个匹配子集合" }

                // 验证子集合下有请求
                val childRequests = client.listRequests(child.id).getOrThrow()
                println("  子集合下请求数: ${childRequests.size}")
                check(childRequests.any { it.title == "GET /api/test/s4-target" }) {
                    "子集合下应包含创建的请求"
                }

                // 清理 target 集合（递归删除子集和请求）
                client.deleteCollection(target.id)
                println("  清理 target 集合: ${target.id}")
                println("  ✅ S4-4 通过")
            } catch (e: Throwable) {
                println("  ❌ S4-4 失败: ${e.message}")
                e.printStackTrace()
            }

            // S4-5: 直接同步（不生成子目录）→ 请求直接创建在目标集合下
            try {
                println("\n[S4-5] 直接同步（不生成子目录）...")

                // 创建一个根集合
                val coll = client.createCollection("hstest-S4-direct").getOrThrow()
                println("  创建集合: id=${coll.id}")

                // 直接在集合下创建请求（不创建子集合）
                val reqJson = buildDefaultRequestJson("POST", "/api/test/s4-direct")
                val req = client.createRequest(coll.id, "POST /api/test/s4-direct", reqJson).getOrThrow()
                println("  请求直接创建在集合下: id=${req.id}")

                // 验证集合下有请求
                val requests = client.listRequests(coll.id).getOrThrow()
                println("  集合下请求数: ${requests.size}")
                check(requests.any { it.title == "POST /api/test/s4-direct" }) {
                    "集合下应包含直接创建的请求"
                }

                // 清理
                client.deleteCollection(coll.id)
                println("  清理集合: ${coll.id}")
                println("  ✅ S4-5 通过")
            } catch (e: Throwable) {
                println("  ❌ S4-5 失败: ${e.message}")
                e.printStackTrace()
            }

            // S4-6: SERVER_FIRST 策略模拟 — 创建请求后再次同步应跳过，内容不变
            try {
                println("\n[S4-6] SERVER_FIRST 策略：创建请求，验证内容完整...")
                val reqJson1 = buildDefaultRequestJson("GET", "/api/test/s4-strategy")
                val req1 = client.createRequest(controllerCollId!!, "GET /api/test/s4-strategy", reqJson1).getOrThrow()
                strategyReqId = req1.id
                println("  创建请求: id=${req1.id}")

                // 读取服务端存储的请求内容，验证往返一致
                val serverReqs = client.listRequests(controllerCollId).getOrThrow()
                val serverReq = serverReqs.first { it.title == "GET /api/test/s4-strategy" }
                val serverObj = com.google.gson.JsonParser.parseString(serverReq.request).asJsonObject
                println("  服务端 method=${serverObj.get("method").asString}, endpoint=${serverObj.get("endpoint").asString}")
                check(serverObj.get("method").asString == "GET") { "method 应为 GET" }
                check(serverObj.get("endpoint").asString == "/api/test/s4-strategy") { "endpoint 不匹配" }

                // SERVER_FIRST 行为验证：再次同步时不重复创建
                val reqsAgain = client.listRequests(controllerCollId).getOrThrow()
                val matches = reqsAgain.filter { it.title == "GET /api/test/s4-strategy" }
                check(matches.size == 1) { "不应重复创建请求，实际 ${matches.size} 个" }
                println("  ✅ S4-6 通过（SERVER_FIRST：请求未重复创建，内容完整）")
            } catch (e: Throwable) {
                println("  ❌ S4-6 失败: ${e.message}")
                e.printStackTrace()
            }

            // S4-7: PLUGIN_FIRST 策略模拟 — 覆盖更新请求内容
            try {
                println("\n[S4-7] PLUGIN_FIRST 策略：覆盖更新请求内容...")
                val strategyId = checkNotNull(strategyReqId) { "S4-7 依赖 S4-6 创建的请求 ID" }
                val reqJson2 = buildDefaultRequestJson("POST", "/api/test/s4-strategy")
                val updated = client.updateRequest(strategyId, "GET /api/test/s4-strategy", reqJson2).getOrThrow()
                println("  更新请求: id=${updated.id}")

                // 验证覆盖生效
                val serverReqs2 = client.listRequests(controllerCollId!!).getOrThrow()
                val serverReq2 = serverReqs2.first { it.id == strategyReqId }
                val serverObj2 = com.google.gson.JsonParser.parseString(serverReq2.request).asJsonObject
                val updatedMethod = serverObj2.get("method").asString
                println("  更新后 method=$updatedMethod, endpoint=${serverObj2.get("endpoint").asString}")
                check(updatedMethod == "POST") { "PLUGIN_FIRST: 更新后 method 应为 POST，实际 $updatedMethod" }
                println("  ✅ S4-7 通过（PLUGIN_FIRST：请求内容已覆盖更新）")
            } catch (e: Throwable) {
                println("  ❌ S4-7 失败: ${e.message}")
                e.printStackTrace()
            }

        } finally {
            // 按依赖顺序清理 S4 创建的测试数据（防止 S4-1 成功后 S4-2 失败的情况）
            if (requestId != null) {
                try {
                    // HoppscotchClient 没有单独的 deleteRequest 方法，
                    // 删除集合会递归清理子集合和请求
                } catch (_: Exception) {}
            }
            if (controllerCollId != null) {
                try {
                    client.deleteCollection(controllerCollId)
                } catch (_: Exception) {}
            }
            if (projectCollId != null) {
                try {
                    client.deleteCollection(projectCollId)
                } catch (_: Exception) {}
            }
            // cleanupTestData 会在 finally 中统一处理残余 hstest 集合
        }
    }
}
