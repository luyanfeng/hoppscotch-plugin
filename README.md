# Hoppscotch Sync Plugin

[![JetBrains Marketplace](https://img.shields.io/jetbrains/plugin/v/hoppscotch-sync?color=blue&label=Marketplace)](https://plugins.jetbrains.com/plugin/hoppscotch-sync)

将 Spring Boot Controller 的 REST API 端点同步到 [Hoppscotch Self-Hosted](https://github.com/hoppscotch/hoppscotch) 实例的 IntelliJ IDEA 插件。

> ✅ 已发布到 JetBrains Marketplace，在 IDEA 的 Plugins → Marketplace 中搜索 `Hoppscotch Sync` 即可安装。

> ⚠️ 仅支持 Spring Boot (Java) 项目。
> 此文及项目由ai生成，本项目只自用，出于分享目的开源。并不考虑持久维护（能力和精力有限），更多的是希望现阶段可以给那些能力和精力更有限的朋友提供个能用的选择。

## 功能

- **自动扫描** — 扫描 `@RestController` / `@RequestMapping` 等注解，提取所有 REST 端点
- **同步到 Hoppscotch** — 通过 GraphQL API 将端点和参数推送到 Hoppscotch 集合
- **增量同步** — 跳过已存在的请求，只创建新增的；支持选择目标集合
- **同步状态检测** — 双向对比本地代码和服务端请求，标记 **SYNCED（绿）** / **MODIFIED（蓝）** / **UNSYNCED（白）**
- **Token 自动刷新** — access_token + refresh_token 双 Token 认证，遇 401 自动续期
- **友好 UI** — 专用 Tool Window，支持搜索过滤、排序、列宽自定义、列显隐、选中行同步
- **多模块支持** — 同一窗口中的多模块均可识别，Projects 下拉定向扫描
- **数据缓存** — 项目选择和扫描结果自动持久化，重启 IDE 无需重新扫描
- **中英文界面** — 设置中切换语言，即时生效

## 系统要求

- IntelliJ IDEA 2026.1+ (Build 261+)
- JDK 21
- Hoppscotch Self-Hosted 实例

## 快速开始

### 构建

```bash
gradle buildPlugin
```

产物：`build/distributions/hoppscotch-sync-plugin-1.0.0.zip`

### 安装

**Marketplace（推荐）**：
1. IntelliJ IDEA → Settings → Plugins → Marketplace
2. 搜索 `Hoppscotch Sync`
3. 点击 Install → 重启 IDE

**本地安装**：
1. IntelliJ IDEA → Settings → Plugins → ⚙ → Install Plugin from Disk
2. 选择 `build/distributions/hoppscotch-sync-plugin-1.0.0.zip`
3. 重启 IDE

### 配置

1. 打开 Hoppscotch Self-Hosted 实例，从浏览器 Cookie 获取 `access_token` 和 `refresh_token`
2. 在 IDEA 中：Settings → Tools → Hoppscotch Sync
3. 填写 Server URL、Access Token、Refresh Token

### 使用

**Tool Window**：View → Tool Windows → Hoppscotch Sync
- Projects 下拉选择项目 → **Refresh** 扫描
- **Sync Selected** 同步选中的端点到 Hoppscotch
- **检查同步状态** 对比服务端数据更新状态

**菜单动作**：Tools → Sync Spring Boot Endpoints to Hoppscotch

## 技术栈

| 技术 | 用途 |
|------|------|
| Kotlin 2.3 | 插件开发语言 |
| IntelliJ Platform Gradle Plugin 2.6 | 构建 |
| IntelliJ PSI API | Java 源码解析 |
| Java HttpClient (JDK 21) | HTTP 请求 |
| Gson | JSON 处理 |

## 项目结构

```
src/main/kotlin/com/hoppscotch/sync/
├── model/          # 数据模型 + SyncStatus + hash 辅助函数
├── psi/            # Spring Controller PSI 解析器
├── hoppscotch/     # GraphQL 客户端 + 数据格式转换器
├── service/        # 同步编排服务
├── settings/       # 持久化设置
├── toolwindow/     # 工具窗口 UI
├── util/           # 国际化 + 日志工具
└── action/         # Tools 菜单动作
```

## 开发

```bash
gradle runIde                      # 启动沙箱 IDEA 测试
gradle buildPlugin                 # 构建插件
gradle verifyPlugin                # 验证兼容性
```

## 许可

Apache 2.0

## 友情链接

[Linux DO](https://linux.do/) — 温暖、包容的 Linux 与开发者社区
