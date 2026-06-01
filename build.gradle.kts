import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.6.0"
}

group = "com.hoppscotch.sync"
version = "1.1.0"

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
            version = "1.1.0"
        description = """
            <p>Sync Spring Boot REST API endpoints to Hoppscotch self-hosted instance.</p>
            <p>Features:</p>
            <ul>
                <li>Parse @RestController and @Controller classes in your project</li>
                <li>Extract all HTTP endpoints (@RequestMapping, @GetMapping, @PostMapping, etc.)</li>
                <li>Sync endpoints to Hoppscotch self-hosted collections</li>
                <li>Tool window for easy management</li>
            </ul>
        """.trimIndent()
        changeNotes = """
            <ul>
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
            url = "https://github.com/hoppscotch/hoppscotch"
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
    patchPluginXml {
        sinceBuild = "261"
        untilBuild = "262.*"
    }
}
