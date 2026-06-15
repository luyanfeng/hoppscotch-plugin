import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.6.0"
}

group = "com.hoppscotch.sync"
            version = "1.2.1"

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
version = "1.2.1"
        description = """
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
        """.trimIndent()
            changeNotes = """
            <ul>
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
