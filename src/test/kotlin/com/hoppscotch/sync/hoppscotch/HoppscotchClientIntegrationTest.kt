package com.hoppscotch.sync.hoppscotch

import com.intellij.openapi.diagnostic.Logger
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlin.test.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * HoppscotchClient 集成测试。
 *
 * 测试流程：
 * 1. 创建测试集合
 * 2. 创建测试请求
 * 3. 调用 listRequestTitlesForCollections 验证匹配
 * 4. 清理（删除测试集合）
 *
 * 前置条件：
 * - 设置环境变量（与插件配置一致）：
 *   HOPPSCOTCH_URL=http://your-server:3000
 *   HOPPSCOTCH_ACCESS_TOKEN=your_access_token
 *
 * 运行：
 *   export HOPPSCOTCH_URL=... HOPPSCOTCH_ACCESS_TOKEN=...
 *   ./gradlew test --tests "com.hoppscotch.sync.hoppscotch.HoppscotchClientIntegrationTest"
 */
class HoppscotchClientIntegrationTest {

    companion object {
        private const val TEST_COLLECTION_PREFIX = "__hoppscotch_plugin_test__"
        private const val TEST_REQUEST_TITLE = "GET /api/test/hello"

        @JvmStatic
        @BeforeAll
        fun setupLogger() {
            // Mock IntelliJ Logger 以便在 IDE 外实例化 HoppscotchClient
            mockkStatic(Logger::class)
            every { Logger.getInstance(any<Class<*>>()) } returns mockk(relaxed = true)
        }
    }

    private fun createClient(): HoppscotchClient {
        val serverUrl = System.getenv("HOPPSCOTCH_URL")
        val accessToken = System.getenv("HOPPSCOTCH_ACCESS_TOKEN")
        val refreshToken = System.getenv("HOPPSCOTCH_REFRESH_TOKEN")

        assumeTrue(!serverUrl.isNullOrBlank()) { "环境变量 HOPPSCOTCH_URL 未设置，跳过集成测试" }
        assumeTrue(!accessToken.isNullOrBlank()) { "环境变量 HOPPSCOTCH_ACCESS_TOKEN 未设置，跳过集成测试" }

        return HoppscotchClient(
            serverUrl = serverUrl,
            accessToken = accessToken,
            refreshToken = refreshToken.ifBlank { null }
        )
    }

    @Test
    fun `验证 listRequestTitlesForCollections 能正确匹配集合和请求`() {
        val client = createClient()
        var createdCollectionId: String? = null

        try {
            // 1. 创建测试集合（唯一名称避免冲突）
            val testTitle = "${TEST_COLLECTION_PREFIX}_${System.currentTimeMillis()}"
            val createResult = client.createCollection(testTitle)
            assertTrue(createResult.isSuccess, "创建测试集合失败: ${createResult.exceptionOrNull()?.message}")
            val collection = createResult.getOrNull()!!
            createdCollectionId = collection.id
            println("✅ 创建测试集合: id=${collection.id}, title=${collection.title}")

            // 2. 在集合中创建测试请求
            val requestJson = """{"v":"16","name":"hello","method":"GET","endpoint":"/api/test/hello"}"""
            val reqResult = client.createRequest(collection.id, TEST_REQUEST_TITLE, requestJson)
            assertTrue(reqResult.isSuccess, "创建测试请求失败: ${reqResult.exceptionOrNull()?.message}")
            println("✅ 创建测试请求: id=${reqResult.getOrNull()?.id}, title=$TEST_REQUEST_TITLE")

            // =========================================================
            // 3. 核心验证：用集合标题精确匹配，查询请求标题
            // =========================================================
            val titlesResult = client.listRequestTitlesForCollections(setOf(testTitle))
            assertTrue(titlesResult.isSuccess, "查询请求标题失败: ${titlesResult.exceptionOrNull()?.message}")

            val titles = titlesResult.getOrNull()!!
            println("✅ 查询到服务端请求标题: $titles")

            // 验证测试请求标题在结果中
            assertTrue(
                TEST_REQUEST_TITLE in titles,
                "请求标题 '$TEST_REQUEST_TITLE' 不在查询结果中！结果: $titles"
            )
            println("✅ 验证通过: '$TEST_REQUEST_TITLE' 存在于查询结果中")

            // 验证结果数量
            assertEquals(1, titles.size, "应只返回 1 个请求标题")
            println("✅ 结果数量正确: ${titles.size}")

            // 4. 验证不匹配的集合标题返回空
            val emptyResult = client.listRequestTitlesForCollections(setOf("__nonexistent_collection__"))
            assertTrue(emptyResult.isSuccess)
            val emptyTitles = emptyResult.getOrNull()!!
            assertTrue(emptyTitles.isEmpty(), "不匹配的集合应返回空结果，但得到: $emptyTitles")
            println("✅ 不匹配集合返回空结果: 正确")

        } finally {
            // 5. 清理：删除测试集合
            val id = createdCollectionId
            if (id != null) {
                val delResult = client.deleteCollection(id)
                if (delResult.isSuccess) {
                    println("✅ 清理完成：测试集合已删除")
                } else {
                    System.err.println("⚠️ 清理失败: ${delResult.exceptionOrNull()?.message}")
                }
            }
            client.close()
        }
    }

    @Test
    fun `target 模式验证子集合中的请求也能被查询到`() {
        val client = createClient()
        var targetCollectionId: String? = null
        var childCollectionId: String? = null

        try {
            // 1. 创建一个根集合作为 target（模拟已有的 test 集合）
            val targetTitle = "${TEST_COLLECTION_PREFIX}_target_${System.currentTimeMillis()}"
            val createTargetResult = client.createCollection(targetTitle)
            assertTrue(createTargetResult.isSuccess, "创建 target 集合失败: ${createTargetResult.exceptionOrNull()?.message}")
            targetCollectionId = createTargetResult.getOrNull()!!.id
            println("✅ 创建 target 集合: id=$targetCollectionId, title=${createTargetResult.getOrNull()!!.title}")

            // 2. 在 target 下创建子集合（模拟同步到 test 下的项目集合，如 dlyx-b-data-analysis）
            val projectTitle = "${TEST_COLLECTION_PREFIX}_project_${System.currentTimeMillis()}"
            val createChildResult = client.createChildCollection(
                title = projectTitle,
                parentCollectionId = targetCollectionId
            )
            assertTrue(createChildResult.isSuccess, "创建子集合失败: ${createChildResult.exceptionOrNull()?.message}")
            childCollectionId = createChildResult.getOrNull()!!.id
            println("✅ 创建子集合: id=$childCollectionId, title=${createChildResult.getOrNull()!!.title}")

            // 3. 在子集合中创建测试请求
            val requestJson = """{"v":"16","name":"hello","method":"GET","endpoint":"/api/test/hello"}"""
            val reqResult = client.createRequest(childCollectionId, TEST_REQUEST_TITLE, requestJson)
            assertTrue(reqResult.isSuccess, "创建测试请求失败: ${reqResult.exceptionOrNull()?.message}")
            println("✅ 创建测试请求: id=${reqResult.getOrNull()?.id}, title=$TEST_REQUEST_TITLE")

            // ================================================================
            // 4. 核心验证 A：带 parentCollectionId 调用（新行为）→ 应找到
            // ================================================================
            println("\n--- 验证 A: 带 parentCollectionId = targetId (新行为) ---")
            val titlesWithTarget = client.listRequestTitlesForCollections(
                expectedTitles = setOf(projectTitle),
                parentCollectionId = targetCollectionId
            )
            assertTrue(titlesWithTarget.isSuccess, "target 模式查询失败: ${titlesWithTarget.exceptionOrNull()?.message}")
            val foundTitles = titlesWithTarget.getOrNull()!!
            println("查询结果: $foundTitles")

            assertTrue(
                TEST_REQUEST_TITLE in foundTitles,
                "target 模式未能找到请求 '$TEST_REQUEST_TITLE'！结果: $foundTitles\n" +
                        "这说明 listRequestTitlesForCollections 带 parentCollectionId 时无法找到子集合中的请求。"
            )
            println("✅ 验证 A 通过: target 模式找到了子集合中的请求")

            // ================================================================
            // 5. 核心验证 B：不带 parentCollectionId（旧行为）→ 不找到
            // ================================================================
            println("\n--- 验证 B: 不带 parentCollectionId → 在根集合中查找 (旧行为) ---")
            val titlesWithoutTarget = client.listRequestTitlesForCollections(
                expectedTitles = setOf(projectTitle)
            )
            assertTrue(titlesWithoutTarget.isSuccess, "root 模式查询失败: ${titlesWithoutTarget.exceptionOrNull()?.message}")
            val rootTitles = titlesWithoutTarget.getOrNull()!!
            println("查询结果: $rootTitles")

            assertFalse(
                TEST_REQUEST_TITLE in rootTitles,
                "旧行为(root查询)不应找到子集合中的请求，但找到了！" +
                        "这说明子集合被意外地创建成了根集合。"
            )
            println("✅ 验证 B 通过: 旧行为(root查询)找不到子集合中的请求")

            // ================================================================
            // 6. 总结
            // ================================================================
            println("\n=== 总结: target 模式修复验证 ===")
            println("  旧行为(root查询): ❌ 找不到 → 修复前用户看到的就是空数据")
            println("  新行为(target查询): ✅ 找到了 → 修复后检查同步状态正确返回")
            println("=== 验证通过 ===\n")

        } finally {
            // 7. 清理
            if (childCollectionId != null) {
                val delResult = client.deleteCollection(childCollectionId)
                if (delResult.isSuccess) {
                    println("✅ 子集合已清理")
                } else {
                    System.err.println("⚠️ 子集合清理失败: ${delResult.exceptionOrNull()?.message}")
                }
            }
            if (targetCollectionId != null) {
                val delResult = client.deleteCollection(targetCollectionId)
                if (delResult.isSuccess) {
                    println("✅ target 集合已清理")
                } else {
                    System.err.println("⚠️ target 集合清理失败: ${delResult.exceptionOrNull()?.message}")
                }
            }
            client.close()
        }
    }

    @Test
    fun `空标题集合应直接返回空结果`() {
        val client = createClient()
        try {
            val result = client.listRequestTitlesForCollections(emptySet())
            assertTrue(result.isSuccess)
            val titles = result.getOrNull()!!
            assertTrue(titles.isEmpty(), "空标题集合应返回空结果")
            println("✅ 空标题集合返回空结果: 正确")
        } finally {
            client.close()
        }
    }
}
