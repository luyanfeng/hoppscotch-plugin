# Hoppscotch Sync Plugin — AGENTS.md

IntelliJ IDEA 插件，扫描 Spring Boot Controller 并通过 GraphQL API 同步 REST 端点到 Hoppscotch Self-Hosted。

## 项目概况

- **语言**: Kotlin 2.3.0 + JDK 21
- **构建**: Gradle 9.3 (wrapper), IntelliJ Platform Gradle Plugin 2.6.0
- **目标平台**: IntelliJ IDEA Ultimate 2025.1+ (Build 251–262.\*)，依赖 `com.intellij.java`（bundledPlugin）
- **插件 ID**: `com.hoppscotch.sync`；版本号以 `build.gradle.kts` 为准（当前 1.3.6）
- **单模块项目**，源码 `src/main/kotlin/com/hoppscotch/sync/`

## 开发命令

```bash
gradle compileKotlin        # 编译
gradle buildPlugin          # 构建插件 JAR / ZIP（build/distributions/）
gradle runIde               # 启动沙箱 IDEA（需 --no-configuration-cache 防缓存问题）
gradle verifyPlugin         # 验证兼容性
gradle test                 # JUnit 单元测试（见「测试」：可能触发服务端连接）
```

**服务端验证任务**（`verification` 组 JavaExec）：参数用 `-D` 系统属性传入，代码内（`?: System.getenv`）回退读环境变量：

```bash
gradle runScenarioTest -DHOPPSCOTCH_URL=... -DHOPPSCOTCH_ACCESS_TOKEN=...    # 25 项场景集成测试
gradle runIntegrationTest -DHOPPSCOTCH_URL=... -DHOPPSCOTCH_ACCESS_TOKEN=...  # HoppscotchClient API 集成测试（可选 -DTARGET_COLLECTION_ID）
gradle runSyncGroupReuseTest -DHOPPSCOTCH_URL=... -DHOPPSCOTCH_ACCESS_TOKEN=...  # 集合复用逻辑验证
gradle verifyRequestFormat  # 纯本地、无需服务端：验证请求 JSON 是否符合 Hoppscotch Zod schema
```

**JDK**: SDKMAN 管理 — `source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk env install`
`.sdkmanrc` 指定 `java=21.0.11-amzn`。

## 架构要点

### 包结构

| 包 | 职责 |
|---|---|
| `model/` | `SpringEndpoint`/`EndpointParameter`/`HttpMethod`/`ParamSource`, `HoppscotchModels`（请求模型/状态/hash）, `SyncStrategy` |
| `psi/` | `SpringControllerParser` — 注解索引搜索优先，降级文件遍历 |
| `hoppscotch/` | `HoppscotchClient`（JDK HttpClient + GraphQL）, `HoppscotchDataConverter`（转换 + 服务端 hash）, `HoppscotchVersionChecker`, `RequestValidator`（Zod 前置校验） |
| `service/` | `SyncService`（同步编排）, `TokenRefreshStartupActivity`（启动时 token 预检/刷新） |
| `settings/` | `AppSettings`（PersistentStateComponent → `hoppscotch-sync-settings.xml`）, `AppSettingsConfigurable` |
| `toolwindow/` | 工具窗口 UI: 表格/搜索/集合选择器 |
| `util/` | `I18n`（中英切换）, `LogUtil` |
| `action/` | `SyncAction` — Tools 菜单入口 |

### 关键约束

- **仅支持 Spring Boot (Java)**。Go/Node.js/Python 不支持。
- **GraphQL 不暴露 `orderIndex`**（Prisma DB 字段）。集合匹配用 `filter{title}.first()`，为避免重复创建，必须确保同名集合不在同一层级出现；插件本身不会重复创建同名集合。
- **请求匹配用 `methodEndpointKey`**（`"GET:/api/users/{id}"`），不是 title。修改标题不会断匹配；路径/方法改变 → 旧记录变为 UNSYNCED（白色），这是设计行为。
- **请求 JSON schema 版本 `"v":"17"`**（HoppscotchRequest 序列化）。改动请求模型结构时，服务端 Zod 校验会拒绝并显示 Untitled；1.2.4 修复过 Gson 排除 null 字段导致校验失败的坑——序列化时不要丢字段。
- **Converter 类型感知占位值**：路径/查询参数按 `endpointParam.type` 生成占位值（`placeholderValueForType()`，有 defaultValue 时优先）。MultipartFile 类型参数转入 form body 并强制 `multipart/form-data`（body 是 JSON 数组）；普通 form 是 `application/x-www-form-urlencoded`（body 是字符串）。改这两个分支时，参考 `VerifyRequestFormat` 的既有用例。
- **JWT 预检**：`isJwtExpired()` 提前 5 分钟预刷新，有效期内跳过网络调用。
- **持久化向后兼容**：`SyncPersistData.parse()` 支持 `"serverId,localHash,srvHash"` / `"localHash,srvHash"` / `"localHash"` 三种格式，改动格式必须保持旧格式可解析。

## 测试

**`gradle test`**（JUnit 5 + MockK，纯逻辑为主）。⚠️ 例外：`HoppscotchClientIntegrationTest` 也是 JUnit 测试，设置了 `HOPPSCOTCH_URL`/`HOPPSCOTCH_ACCESS_TOKEN` **环境变量**时会真实连接服务端（未设置则 `assumeTrue` 跳过）。只测本地逻辑时先 unset 这两个环境变量。

**`gradle runScenarioTest`**（入口 `ScenarioIntegrationTest.kt`，独立 main，非 JUnit）：
- 测试数据前缀 `hstest`；启动时自动清理前次残留，结束时清理本次数据。
- 6 模块 25 个测试点；纯逻辑测试（C3/C5/C6/S4P）在前，需服务端的（C1/C2/S4）在后——服务器不可达时纯逻辑仍可跑。
- ⚠️ **不要用 `System.exit()`** — 会跳过 finally 清理块，测试集合泄漏在服务端。
- `runScenarioTest` / `verifyRequestFormat` 在 `doFirst` 中追加 `intellijPlatformRuntimeClasspath` + `intellijPlatformTestClasspath`（mockk 需要 IntelliJ 的 Logger 类），新增类似任务记得照抄。
- 必须显式 mock IntelliJ 依赖，模式照抄现有测试 `main()` 开头：
  ```kotlin
  mockkStatic(Logger::class)
  every { Logger.getInstance(any<Class<*>>()) } returns mockk(relaxed = true)
  mockkObject(LogUtil)
  every { LogUtil.stdout(any<() -> String>()) } answers {}
  every { LogUtil.debug(any(), any<() -> String>()) } answers {}
  ```

**纯逻辑可直接调**：`SyncService.mergeRequestJsons()` 在 companion object（`@JvmStatic`），无需服务端或 IntelliJ 环境。

## 发布

```bash
# 前提：cert 文件在 /home/.../hoppscotch-cert/
export CERTIFICATE_CHAIN=$(cat /path/to/chain.crt)
export PRIVATE_KEY=$(cat /path/to/private.pem)   # 支持未加密私钥
export JETBRAINS_TOKEN='perm-...'
gradle publishPlugin   # 自动 sign → upload
```

`build.gradle.kts` 的 `signing`/`publishing` 读取环境变量，无密码的 `private.pem` 可用，`PRIVATE_KEY_PASSWORD` 可省略。Marketplace token 需从 JetBrains Account 获取。

## 注意事项

- `plugin.xml` 中的 `<version>`/`<description>`/`<change-notes>` 会被 `patchPluginXml` 用 `build.gradle.kts` 的值覆盖（plugin.xml 里现仍残留旧值 1.3.3），改版本/描述/更新日志只改 `build.gradle.kts`。
- 新增 extension/action 需在 `plugin.xml` 注册；注意 `since-build="251" until-build="262.*"` 约束。
- UI 文案走 i18n：新字符串必须同时加进 `messages/HoppscotchSyncBundle.properties` 与 `HoppscotchSyncBundle_zh.properties`（`I18n.message(key)` 按当前语言加载）。
- 集合标题特殊字符（`<>:"/\|?*[]`）会被 `sanitizeTitle()` 替换为下划线。
- 使用 IntelliJ PSI API 时注意双模式：优先 `AnnotatedElementsSearch`（索引级搜索），降级为文件遍历。
- `README.md` / `README_for_ai.md` 部分内容已过时（如 Gradle 8.14.5、产物名 1.0.0），以本文件与 `build.gradle.kts` 为准。