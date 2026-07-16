package com.hoppscotch.sync.verify

import com.hoppscotch.sync.hoppscotch.HoppscotchDataConverter
import com.hoppscotch.sync.model.*

/**
 * 验证生成的请求 JSON 格式是否符合 Hoppscotch Zod schema。
 * 不依赖服务端，纯逻辑验证。
 */
object VerifyRequestFormat {

    data class TestResult(val name: String, val passed: Boolean, val detail: String)

    @JvmStatic
    fun main(args: Array<String>) {
        println("=" .repeat(70))
        println("请求格式验证")
        println("验证 multipart body 是否为 JSON 数组（非字符串）")
        println("验证 @RequestBody 在 multipart 模式下是否被包含")
        println("=" .repeat(70))

        val converter = HoppscotchDataConverter()
        val results = mutableListOf<TestResult>()

        // ====== 端1: @RequestBody ======
        results.add(testCase("端1: @RequestBody JSON") {
            val ep = SpringEndpoint(
                controllerClassName = "C", controllerClassQualifiedName = "C",
                methodName = "m", httpMethod = HttpMethod.POST,
                path = "/submit", fullPath = "/submit",
                parameters = listOf(
                    EndpointParameter(name = "reqVO", type = "OrderVO", source = ParamSource.BODY,
                        required = true, bodyJsonTemplate = """{"name":"","age":0}""")
                )
            )
            val req = converter.toHoppscotchRequest(ep)
            val body = req.body
            check(body.contentType == "application/json") { "contentType应为application/json" }
            check(body.body is String) { "JSON body应为String" }
            check((body.body as String).contains("name")) { "body应包含name字段" }
        })

        // ====== 端2: 无注解 POJO (urlencoded) ======
        results.add(testCase("端2: 无注解POJO urlencoded") {
            val ep = SpringEndpoint(
                controllerClassName = "C", controllerClassQualifiedName = "C",
                methodName = "m", httpMethod = HttpMethod.POST,
                path = "/submit-payment2", fullPath = "/submit-payment2",
                parameters = listOf(
                    EndpointParameter(name = "reqVO", type = "PaymentSubmitReqVO", source = ParamSource.QUERY,
                        required = true, objectFields = listOf("orderId", "payVoucherFileId", "userId"))
                )
            )
            val req = converter.toHoppscotchRequest(ep)
            val body = req.body
            check(body.contentType == "application/x-www-form-urlencoded") { "contentType应为urlencoded" }
            check(body.body is String) { "urlencoded body应为String" }
            val bodyStr = body.body as String
            check("orderId=" in bodyStr) { "body应包含orderId字段" }
        })

        // ====== 端3: @PathVariable + 无注解 POJO ======
        results.add(testCase("端3: @PathVariable + 无注解POJO") {
            val ep = SpringEndpoint(
                controllerClassName = "C", controllerClassQualifiedName = "C",
                methodName = "m", httpMethod = HttpMethod.POST,
                path = "/submit-payment3/{orderId}", fullPath = "/submit-payment3/{orderId}",
                parameters = listOf(
                    EndpointParameter(name = "orderId", type = "String", source = ParamSource.PATH, required = true),
                    EndpointParameter(name = "reqVO", type = "PaymentSubmitReqVO", source = ParamSource.QUERY,
                        required = true, objectFields = listOf("orderId", "payVoucherFileId", "userId"))
                )
            )
            val req = converter.toHoppscotchRequest(ep)
            // 路径变量有占位值
            check(req.params.any { it.key == "orderId" && it.value == "" }) { "orderId应为空字符串(String类型)" }
            val body = req.body
            check(body.contentType == "application/x-www-form-urlencoded") { "contentType应为urlencoded" }
        })

        // ====== 端4: GET + @PathVariable + 无注解 POJO ======
        results.add(testCase("端4: GET + @PathVariable + 无注解POJO") {
            val ep = SpringEndpoint(
                controllerClassName = "C", controllerClassQualifiedName = "C",
                methodName = "m", httpMethod = HttpMethod.GET,
                path = "/submit-payment4/{orderId}", fullPath = "/submit-payment4/{orderId}",
                parameters = listOf(
                    EndpointParameter(name = "orderId", type = "String", source = ParamSource.PATH, required = true),
                    EndpointParameter(name = "reqVO", type = "PaymentSubmitReqVO", source = ParamSource.QUERY,
                        required = true, objectFields = listOf("orderId", "payVoucherFileId", "userId"))
                )
            )
            val req = converter.toHoppscotchRequest(ep)
            // GET 方法，body 应为空
            check(req.body.contentType == null) { "GET请求body contentType应为null" }
            check(req.body.body == null) { "GET请求body应为null" }
        })

        // ====== 端5: MultipartFile + 无注解 POJO ======
        results.add(testCase("端5: MultipartFile + 无注解POJO") {
            val ep = SpringEndpoint(
                controllerClassName = "C", controllerClassQualifiedName = "C",
                methodName = "m", httpMethod = HttpMethod.POST,
                path = "/upload5", fullPath = "/upload5",
                parameters = listOf(
                    EndpointParameter(name = "file", type = "MultipartFile", source = ParamSource.QUERY,
                        required = true, isMultipartFile = true),
                    EndpointParameter(name = "reqVO", type = "PaymentSubmitReqVO", source = ParamSource.QUERY,
                        required = true, objectFields = listOf("orderId", "payVoucherFileId", "userId"))
                )
            )
            val req = converter.toHoppscotchRequest(ep)
            val body = req.body
            check(body.contentType == "multipart/form-data") { "contentType应为multipart/form-data" }
            // ★★★ 关键验证：body 必须为 List（JSON 数组），不能是 String ★★★
            check(body.body is List<*>) { "multipart body必须是List/数组，实际是${body.body?.let { it::class.simpleName }}" }
            val entries = body.body as List<*>
            check(entries.any { it is Map<*, *> && it["key"] == "file" && it["type"] == "file" }) { "应包含file字段(type=file)" }
            check(entries.any { it is Map<*, *> && it["key"] == "orderId" && it["type"] == "text" }) { "应包含orderId字段(type=text)" }
            println("    multipart数组条目数: ${entries.size}")
            entries.forEach { println("      ${it}") }
        })

        // ====== 端6: MultipartFile + @RequestBody ======
        results.add(testCase("端6: MultipartFile + @RequestBody") {
            val ep = SpringEndpoint(
                controllerClassName = "C", controllerClassQualifiedName = "C",
                methodName = "m", httpMethod = HttpMethod.POST,
                path = "/upload6", fullPath = "/upload6",
                parameters = listOf(
                    EndpointParameter(name = "file", type = "MultipartFile", source = ParamSource.QUERY,
                        required = true, isMultipartFile = true),
                    EndpointParameter(name = "reqVO", type = "PaymentSubmitReqVO", source = ParamSource.BODY,
                        required = true, bodyJsonTemplate = """{"orderId":"","payVoucherFileId":"","userId":""}""")
                )
            )
            val req = converter.toHoppscotchRequest(ep)
            val body = req.body
            check(body.contentType == "multipart/form-data") { "contentType应为multipart/form-data" }
            // ★★★ 关键验证1：body 必须为 List ★★★
            check(body.body is List<*>) { "multipart body必须是List/数组，实际是${body.body?.let { it::class.simpleName }}" }
            val entries = body.body as List<*>
            // ★★★ 关键验证2：@RequestBody 的 JSON 模板已加入 multipart body ★★★
            check(entries.any { it is Map<*, *> && it["key"] == "file" }) { "应包含file字段" }
            check(entries.any { it is Map<*, *> && it["key"] == "reqVO" }) { "应包含reqVO字段(@RequestBody应被加入)" }
            // ★★★ 关键验证3：JSON 模板内容正确 ★★★
            val reqVOEntry = entries.find { it is Map<*, *> && it["key"] == "reqVO" } as Map<*, *>
            check(reqVOEntry["value"] == """{"orderId":"","payVoucherFileId":"","userId":""}""") {
                "reqVO的value应为JSON模板"
            }
            println("    multipart数组条目数: ${entries.size}")
            entries.forEach { println("      ${it}") }
        })

        // ====== 路径变量占位值验证 ======
        results.add(testCase("路径变量: 类型感知占位值") {
            val ep = SpringEndpoint(
                controllerClassName = "C", controllerClassQualifiedName = "C",
                methodName = "m", httpMethod = HttpMethod.GET,
                path = "/users/{id}/posts/{name}", fullPath = "/users/{id}/posts/{name}",
                parameters = listOf(
                    EndpointParameter(name = "id", type = "Integer", source = ParamSource.PATH, required = true),
                    EndpointParameter(name = "name", type = "String", source = ParamSource.PATH, required = true)
                )
            )
            val req = converter.toHoppscotchRequest(ep)
            val idParam = req.params.find { it.key == "id" }
            check(idParam != null) { "应有id路径参数" }
            check(idParam!!.value == "0") { "Integer类型路径变量占位值应为\"0\"，实际为\"${idParam.value}\"" }
            val nameParam = req.params.find { it.key == "name" }
            check(nameParam != null) { "应有name路径参数" }
            check(nameParam!!.value == "") { "String类型路径变量占位值应为\"\"，实际为\"${nameParam.value}\"" }
        })

        // ====== 序列化后 multipart body 是 JSON 数组而非字符串 ======
        results.add(testCase("Gson序列化: multipart body是数组非字符串") {
            val ep = SpringEndpoint(
                controllerClassName = "C", controllerClassQualifiedName = "C",
                methodName = "m", httpMethod = HttpMethod.POST,
                path = "/upload5", fullPath = "/upload5",
                parameters = listOf(
                    EndpointParameter(name = "file", type = "MultipartFile", source = ParamSource.QUERY,
                        required = true, isMultipartFile = true),
                    EndpointParameter(name = "reqVO", type = "PaymentSubmitReqVO", source = ParamSource.QUERY,
                        required = true, objectFields = listOf("orderId", "payVoucherFileId", "userId"))
                )
            )
            val req = converter.toHoppscotchRequest(ep)
            val json = converter.toRequestRequestBody(req)
            // ★★★ 验证序列化后的 JSON 中 body.body 是数组不是字符串 ★★★
            // Gson pretty-print 输出格式:
            //   "body": {
            //     "contentType": "multipart/form-data",
            //     "body": [
            // 错误格式（字符串）:
            //   "body": {
            //     "contentType": "multipart/form-data",
            //     "body": "[...]"
            // 检查 body 行的第一个非空白字符是 [ 而不是 "
            val bodyLineMatch = Regex("\"body\":\\s*\"\\[").find(json)
            check(bodyLineMatch == null) {
                "序列化后 multipart body 是字符串(带引号)，应为数组!"
            }
            // 确认是数组格式
            val bodyArrayMatch = Regex("\"body\":\\s*\\[").find(json)
            check(bodyArrayMatch != null) {
                "序列化后未找到 body 数组格式"
            }
            println("    序列化后 body.body 是数组 ✅ (非字符串)")
        })

        // ====== 输出结果 ======
        println("\n" + "=" .repeat(70))
        println("验证结果")
        println("=" .repeat(70))
        var passed = 0
        var failed = 0
        for (r in results) {
            val status = if (r.passed) "✅" else "❌"
            println("$status ${r.name}")
            println("   ${r.detail}")
            if (r.passed) passed++ else failed++
        }
        println("\n" .repeat(2) + "=" .repeat(70))
        println("总计: ${results.size} | 通过: $passed | 失败: $failed")
        if (failed > 0) {
            println("❌ 存在失败项，请检查!")
            System.exit(1)
        } else {
            println("✅ 全部通过")
        }
    }

    private fun testCase(name: String, block: () -> Unit): TestResult {
        return try {
            block()
            TestResult(name, true, "通过")
        } catch (e: Throwable) {
            TestResult(name, false, "失败: ${e.message}")
        }
    }
}
