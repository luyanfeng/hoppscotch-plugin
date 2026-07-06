# Hoppscotch Sync Plugin — AGENTS.md

IntelliJ IDEA 插件，扫描 Spring Boot Controller 并通过 GraphQL API 同步 REST 端点到 Hoppscotch Self-Hosted。

## 项目概况

- **语言**: Kotlin 2.3.0 + JDK 21
- **构建**: Gradle 9.3 (wrapper), IntelliJ Platform Gradle Plugin 2.6.0
- **目标平台**: IntelliJ IDEA Ultimate 2026.1+ (Build 261–262.*)
- **插件 ID**: `com.hoppscotch.sync`，已发布到 JetBrains Marketplace
- **依赖**: `com.intellij.java`（bundledPlugin），使用 IntelliJ PSI API 解析 Java 源码
- **包路径**: `com.hoppscotch.sync.*`
- **单模块项目**（非 monorepo），所有源码在 `src/main/kotlin/com/hoppscotch/sync/`

## 开发命令

```bash
# 编译 Kotlin
gradle compileKotlin

# 构建插件 JAR
gradle buildPlugin
# 产物: build/libs/hoppscotch-sync-plugin-1.3.2.jar
#       build/distributions/hoppscotch-sync-plugin-1.3.2.zip

# 启动沙箱 IDEA（自动加载插件）
gradle runIde

# 验证插件兼容性
gradle verifyPlugin

# 运行单元测试（JUnit 5 + MockK）
gradle test

# 运行集成测试（独立 main 方法，不依赖 IntelliJ Platform）
gradle runIntegrationTest -DHOPPSCOTCH_URL=... -DHOPPSCOTCH_ACCESS_TOKEN=...
```

**注意事项**:
- `gradle runIde` 可能需要 `--no-configuration-cache` 以避免缓存问题
- SDKMAN 管理 JDK：`source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk env install`
- `.sdkmanrc` 指定 `java=21.0.11-amzn`

## 项目结构（关键文件）

```
src/main/kotlin/com/hoppscotch/sync/
├── model/
│   ├── SpringEndpoint.kt          # 数据模型：HttpMethod, EndpointParameter, SpringEndpoint, ControllerGroup
│   ├── HoppscotchModels.kt        # Hoppscotch 请求模型、SyncStatus、SyncResult、SyncPersistData、computeEndpointKey/hash
│   └── SyncStrategy.kt            # 同步策略枚举（SERVER_FIRST / PLUGIN_FIRST / MERGE_*）
├── psi/
│   └── SpringControllerParser.kt  # PSI 解析器 — 扫描 @RestController/@Controller，提取端点+参数+JSON 骨架
├── hoppscotch/
│   ├── HoppscotchClient.kt        # GraphQL 客户端（JDK HttpClient），支持双 Token 自动刷新
│   ├── HoppscotchDataConverter.kt # SpringEndpoint → HoppscotchRequest 转换，hash 计算
│   ├── HoppscotchVersionChecker.kt# 服务端版本/健康检测
│   └── RequestValidator.kt        # 请求 JSON 前置校验（Zod schema 约束）
├── service/
│   └── SyncService.kt             # 同步编排 — 集合创建/增量匹配/请求创建
├── settings/
│   ├── AppSettings.kt             # 持久化设置（PersistentStateComponent → hoppscotch-sync-settings.xml）
│   └── AppSettingsConfigurable.kt # 设置 UI 面板
├── toolwindow/
│   ├── HoppscotchSyncPanel.kt     # 工具窗口主面板（表格、搜索、同步状态颜色、列显隐）
│   ├── CollectionPickerDialog.kt  # 集合树选择对话框
│   └── HoppscotchSyncToolWindowFactory.kt
├── util/
│   ├── I18n.kt                    # 国际化（中/英切换）
│   └── LogUtil.kt                 # 调试日志门面
└── action/
    └── SyncAction.kt              # Tools 菜单动作
```

## 关键架构细节

### 数据流

1. **PSI 解析** → `SpringControllerParser.parseAllControllers(moduleNames?)` 返回 `List<ControllerGroup>`
   - 双模式：优先 `AnnotatedElementsSearch`（索引级搜索），降级为文件遍历
   - 支持 Swagger `@Api`（类级标签）、`@ApiOperation` / `@Operation`（方法级描述）
   - `@RequestBody` 复杂对象自动递归生成 JSON 骨架（用于展示）和 JSON 模板（用于同步）

2. **数据转换** → `HoppscotchDataConverter.toHoppscotchRequest(endpoint)` → `toRequestRequestBody()` 序列化为 Hoppscotch GraphQL 请求 JSON

3. **同步** → `SyncService.syncGroups(groups, targetParentCollectionId?, strategy, createSubDirectories)`
   - 按 ControllerGroup 创建/复用 Hoppscotch 集合
   - 增量匹配：使用 `methodEndpointKey`（`"GET:/api/users/{id}"`）匹配已有请求，不依赖 title
   - 支持四种 `SyncStrategy`：服务端优先/插件推送优先/两种合并模式

4. **状态检测** → 双 hash 对比：`computeEndpointHash()`（本地代码） vs `HoppscotchDataConverter.computeServerRequestHash()`（服务端请求 JSON）

### 认证

- **双 Token**: `access_token` + `refresh_token`，通过 Bearer header 和 Set-Cookie 交互
- **自动刷新**: 遇 401 时先尝试 `refreshAccessToken()`（Bearer refresh_token 调 desktop 端点），失败则 `refreshViaDesktop()`（Bearer access_token）
- **本地 JWT 预检**: `isJwtExpired()` 解码 JWT payload 检查 exp，提前 5 分钟预刷新

### 持久化

- `AppSettings` 基于 IntelliJ `PersistentStateComponent`，存储为 `hoppscotch-sync-settings.xml`
- 核心数据：serverUrl/tokens、同步策略、列宽/显隐、syncStatusData（`endpointKey → "serverId,localHash,srvHash"`）、缓存扫描结果
- `SyncPersistData.parse()` 兼容旧格式（`"localHash,srvHash"` 和 `"localHash"`）

### Hoppscotch GraphQL API

端点: `{serverUrl}/graphql`，认证: `Authorization: Bearer <access_token>`

关键操作（详见 `HoppscotchClient.kt`）：
- `me` 查询 → Token 验证
- `rootRESTUserCollections` / `userCollection(id) { childrenREST }` → 集合树
- `createRESTRootUserCollection` / `createRESTChildUserCollection` → 创建集合
- `createRESTUserRequest` / `updateRESTUserRequest` → 请求 CRUD
- `deleteUserCollection` → 递归删除集合
- `GET /v1/auth/desktop?redirect_uri=...` → Token 刷新

### 文件资源

`src/main/resources/META-INF/plugin.xml` — 插件描述符
`src/main/resources/messages/HoppscotchSyncBundle*.properties` — 中英文国际化资源

## 测试

### 单元测试

- **框架**: JUnit 5 + MockK (`io.mockk:mockk:1.13.14`)
- **运行**: `gradle test`
- **位置**: `src/test/kotlin/com/hoppscotch/sync/`
- 单元测试不依赖 IntelliJ Platform，可运行 Gson/hash 等逻辑测试

### 集成测试

- **入口**: `com.hoppscotch.sync.hoppscotch.IntegrationTestRunner`（独立 main 方法）
- **运行**: `gradle runIntegrationTest -DHOPPSCOTCH_URL=... -DHOPPSCOTCH_ACCESS_TOKEN=...`
- **环境变量**: `HOPPSCOTCH_URL`, `HOPPSCOTCH_ACCESS_TOKEN`, `HOPPSCOTCH_REFRESH_TOKEN`（可选）, `TARGET_COLLECTION_ID`（可选）
- 直接运行 main 方法，不通过 IntelliJ 测试沙箱
- 测试数据使用 `__hoppscotch_plugin_test__` 前缀，启动时自动清理前次残留
- ⚠️ 不要使用 `System.exit()` — 会导致 finally 清理块不执行，造成测试集合泄漏

## 注意事项

- **仅支持 Spring Boot (Java) 项目**。Go/Node.js/Python 不支持。
- 修改 `plugin.xml` 时注意 `since-build="261" until-build="262.*"` 约束
- 新增功能需在 `plugin.xml` 注册 extension/action
- 修改持久化格式时须保持向后兼容（参考 `SyncPersistData.parse()` 的多格式兼容模式）
- 集合标题中的特殊字符会被 `sanitizeTitle()` 替换为下划线
- 同步匹配使用 `methodEndpointKey`（`method:endpoint`）而非 title，修改标题不会断匹配
- 代码变更后同步匹配 key 变化（路径/方法修改）会导致旧数据变为 UNSYNCED（白色），这是设计行为
