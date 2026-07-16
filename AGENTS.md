# Hoppscotch Sync Plugin — AGENTS.md

IntelliJ IDEA 插件，扫描 Spring Boot Controller 并通过 GraphQL API 同步 REST 端点到 Hoppscotch Self-Hosted。

## 项目概况

- **语言**: Kotlin 2.3.0 + JDK 21
- **构建**: Gradle 9.3 (wrapper), IntelliJ Platform Gradle Plugin 2.6.0
- **目标平台**: IntelliJ IDEA Ultimate 2026.1+ (Build 261–262.\*)
- **插件 ID**: `com.hoppscotch.sync`
- **依赖**: `com.intellij.java`（bundledPlugin），使用 IntelliJ PSI API 解析 Java 源码
- **单模块项目**，源码 `src/main/kotlin/com/hoppscotch/sync/`

## 开发命令

```bash
gradle compileKotlin            # 编译
gradle buildPlugin               # 构建插件 JAR / ZIP（build/distributions/）
gradle runIde                    # 启动沙箱 IDEA（需 --no-configuration-cache 防缓存问题）
gradle verifyPlugin              # 验证兼容性
gradle test                      # 单元测试（JUnit 5 + MockK，不依赖 IntelliJ Platform）
gradle runScenarioTest -DHOPPSCOTCH_URL=... -DHOPPSCOTCH_ACCESS_TOKEN=...  # 25 项场景集成测试
```

**JDK**: SDKMAN 管理 — `source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk env install`
`.sdkmanrc` 指定 `java=21.0.11-amzn`。

## 架构要点

### 包结构

| 包 | 职责 |
|---|---|
| `model/` | `SpringEndpoint`, `ControllerGroup`, `HoppscotchModels`（请求模型/状态/hash）, `SyncStrategy` |
| `psi/` | `SpringControllerParser` — 注解索引搜索优先，降级文件遍历 |
| `hoppscotch/` | `HoppscotchClient`（JDK HttpClient + GraphQL）, `DataConverter`, `VersionChecker`, `RequestValidator` |
| `service/` | `SyncService` — 同步编排 |
| `settings/` | `AppSettings`（PersistentStateComponent → `hoppscotch-sync-settings.xml`）, 设置面板 |
| `toolwindow/` | 工具窗口 UI: 表格/搜索/集合选择器 |
| `util/` | `I18n`（中英切换）, `LogUtil` |
| `action/` | `SyncAction` — Tools 菜单入口 |

### 关键约束

- **仅支持 Spring Boot (Java)**。Go/Node.js/Python 不支持。
- **GraphQL 不暴露 `orderIndex`**（Prisma DB 字段）。集合匹配用 `filter{title}.first()`，如需避免重复创建，必须确保同名集合不在同一层级出现。插件本身不会重复创建同名集合。
- **请求匹配用 `methodEndpointKey`**（`"GET:/api/users/{id}"`），不是 title。修改标题不会断匹配。代码变更导致路径/方法改变 → 旧数据变为 UNSYNCED（白色），这是设计行为。
- **JWT 预检**：`isJwtExpired()` 提前 5 分钟预刷新，有效期内跳过网络调用。
- **持久化向后兼容**：`SyncPersistData.parse()` 支持 `"serverId,localHash,srvHash"` / `"localHash,srvHash"` / `"localHash"` 三种格式。

## 测试

### 单元测试（`gradle test`）

JUnit 5 + MockK，不依赖 IntelliJ Platform。可测试 Gson/hash/RequestValidator 等纯逻辑。

### 场景集成测试（`gradle runScenarioTest`）

入口：`ScenarioIntegrationTest.kt`（独立 main 方法，不是 IntelliJ 沙箱）。
测试数据前缀 `hstest`，启动时自动清理前次残留，结束时清理本次数据。
6 模块 25 个测试点，纯逻辑测试（C3/C5/C6/S4P）在前，需服务端测试（C1/C2/S4）在后。

**运行要求**：
- 环境变量 `HOPPSCOTCH_URL` + `HOPPSCOTCH_ACCESS_TOKEN`（必需）
- `runScenarioTest` 在 `doFirst` 中追加 `intellijPlatformRuntimeClasspath` + `intellijPlatformTestClasspath`（因为用 `mockk` mock `Logger`/`LogUtil`）
- ⚠️ **不要用 `System.exit()`** — 导致 finally 清理块不执行，测试集合泄漏
- `LogUtil` 和 `com.intellij.openapi.diagnostic.Logger` 必须在测试 `main()` 开头显式 mock：
  ```kotlin
  mockkStatic(Logger::class)
  every { Logger.getInstance(any<Class<*>>()) } returns mockk(relaxed = true)
  mockkObject(LogUtil)
  every { LogUtil.stdout(any()) } answers {}
  every { LogUtil.debug(any(), any()) } answers {}
  // ...
  ```

### 合并策略测试（纯逻辑）

`SyncService.mergeRequestJsons()` 在 `SyncService` companion object 中（`@JvmStatic`），可直接调用，无需服务端。

## 发布

```bash
# 前提：cert 文件在 /home/.../hoppscotch-cert/
export CERTIFICATE_CHAIN=$(cat /path/to/chain.crt)
export PRIVATE_KEY=$(cat /path/to/private.pem)   # 支持未加密私钥
export JETBRAINS_TOKEN='perm-...'
gradle publishPlugin   # 自动 sign → upload
```

`build.gradle.kts` 中 `signing`/`publishing` 配置读取环境变量，无密码的 `private.pem` 可用，`PRIVATE_KEY_PASSWORD` 可省略。
Marketplace token 需从 JetBrains Account 获取。

## 注意事项

- 修改 `plugin.xml` 时注意 `since-build="261" until-build="262.*"` 约束，新增 extension/action 需注册。
- `plugin.xml` 中的 `<version>` 和 `<description>` 会在 `patchPluginXml` 任务中被 `build.gradle.kts` 配置覆盖，直接改 `build.gradle.kts` 即可。
- 集合标题中的特殊字符（`<>:"/\|?*[]`）会被 `sanitizeTitle()` 替换为下划线。
- 使用 IntelliJ PSI API 时注意双模式：优先 `AnnotatedElementsSearch`（索引级搜索），降级为文件遍历。
