import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.6.0"
}

group = "com.hoppscotch.sync"
            version = "1.2.4"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create(IntelliJPlatformType.IntellijIdeaUltimate, "2026.1")
        bundledPlugin("com.intellij.java")
    }

    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.mockk:mockk:1.13.14")
    testImplementation("com.google.code.gson:gson:2.11.0")
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("runIntegrationTest") {
    description = "运行 HoppscotchClient 集成测试（绕过 IntelliJ 测试沙箱）"
    group = "verification"
    mainClass.set("com.hoppscotch.sync.hoppscotch.IntegrationTestRunner")
    classpath = sourceSets.test.get().runtimeClasspath
    // 传递 -D 系统属性给子进程
    systemProperties = mapOf(
        "HOPPSCOTCH_URL" to (System.getProperty("HOPPSCOTCH_URL") ?: ""),
        "HOPPSCOTCH_ACCESS_TOKEN" to (System.getProperty("HOPPSCOTCH_ACCESS_TOKEN") ?: ""),
        "HOPPSCOTCH_REFRESH_TOKEN" to (System.getProperty("HOPPSCOTCH_REFRESH_TOKEN") ?: ""),
        "TARGET_COLLECTION_ID" to (System.getProperty("TARGET_COLLECTION_ID") ?: "")
    ).filterValues { it.isNotEmpty() }
}

intellijPlatform {
    pluginConfiguration {
        id = "com.hoppscotch.sync"
            name = "Hoppscotch Sync"
            version = "1.2.4"
        description = """
            <h3>English</h3>
            <p>Sync Spring Boot REST API endpoints to Hoppscotch self-hosted instance.</p>
            <p><b>⚠️ Spring Boot (Java) only.</b></p>
            <p>Features:</p>
            <ul>
                <li>Parse @RestController and @Controller classes in your project</li>
                <li>Extract all HTTP endpoints (@RequestMapping, @GetMapping, @PostMapping, etc.)</li>
                <li>Parse path/query/body/header parameters with JSON skeleton preview</li>
                <li>Incremental sync to Hoppscotch via GraphQL API — skip existing, only create new</li>
                <li>Dual-hash sync status tracking: SYNCED (green), MODIFIED (blue), UNSYNCED (white)</li>
                <li>Multi-project/module support in a single IDEA window</li>
                <li>Selective sync with checkbox — search filters auto-select visible rows</li>
                <li>Collection tree picker to choose target folder before sync</li>
                <li>Auto token refresh with access_token + refresh_token dual auth</li>
                <li>Adjustable table columns (show/hide, reorder, resize with session persistence)</li>
                <li>Bilingual UI (English / 中文) with instant switch</li>
            </ul>
            <hr>
            <h3>中文</h3>
            <p>将 Spring Boot REST API 端点同步到 Hoppscotch 自托管实例。</p>
            <p><b>⚠️ 仅支持 Spring Boot (Java) 项目。</b></p>
            <p>功能：</p>
            <ul>
                <li>扫描 @RestController / @Controller 类，提取所有 HTTP 端点</li>
                <li>解析路径/查询/请求体/请求头参数，展示 JSON 骨架预览</li>
                <li>通过 GraphQL API 增量同步到 Hoppscotch — 跳过已存在，仅创建新增</li>
                <li>双 Hash 同步状态追踪：🟢 已同步 / 🔵 已修改 / ⬜ 未同步</li>
                <li>单窗口多项目/多模块支持</li>
                <li>复选框选择性同步，搜索过滤自动勾选可见行</li>
                <li>同步前弹出集合树选择器选择目标文件夹</li>
                <li>access_token + refresh_token 双 Token 认证，自动续期</li>
                <li>表格列显隐 / 排序 / 拖拽调整宽度，重启后保留</li>
                <li>中英文界面即时切换</li>
            </ul>
        """.trimIndent()
            changeNotes = """
            <ul>
                <li><b>1.2.3</b> 优化: 集合树查询改为一次 GraphQL 嵌套请求，消除 N+1 懒加载和 Thread.sleep(300)</li>
                <li><b>1.2.2</b> 优化: 同步状态检查新增 target 模式；集合选择器支持 target 路径显示</li>
                <li><b>1.2.1</b> Fix: 网络不通时区分「Token 过期」和「服务器不可达」; 移除设置中的帮助链接</li>
                <li><b>1.2.0</b> 新增: 物理列显隐; 标题列合并入路径列; 接口列显示 @Api tag; 列宽跨会话持久化; 列显隐恢复宽度; 支持以项目及类结构生成子集合</li>
                <li><b>1.1.0</b> 新增: 标题列; 支持 @ApiOperation 标题; 同步状态持久化 serverId</li>
                <li><b>1.0.0</b> Initial release</li>
            </ul>
        """.trimIndent()
        ideaVersion {
            sinceBuild = "261"
            untilBuild = "262.*"
        }
        vendor {
            name = "Hoppscotch Sync"
            email = "luyanfeng001001@gmail.com"
            url = "https://github.com/luyanfeng/hoppscotch-plugin"
        }
    }
    pluginVerification {
        ides {
            ide(IntelliJPlatformType.IntellijIdeaUltimate, "2026.1")
        }
    }

    signing {
        certificateChain = System.getenv("CERTIFICATE_CHAIN") ?: ""
        privateKey = System.getenv("PRIVATE_KEY") ?: ""
        password = System.getenv("PRIVATE_KEY_PASSWORD") ?: ""
    }
    publishing {
        token = System.getenv("JETBRAINS_TOKEN") ?: ""
        channels = listOf("default")
    }
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }

}
