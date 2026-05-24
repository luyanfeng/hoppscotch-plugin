# 同步后集合不可见 — 复盘记录

## 问题描述

Hoppscotch IDEA 插件的"同步到 Hoppscotch"功能执行完成后，集合被成功创建（GraphQL mutation 返回 200，返回了 id），但在 Hoppscotch Web UI 中看不到集合下的任何请求。集合展开后内容为空。

## 排查过程

### 第一轮：怀疑同步逻辑

检查了以下环节：

1. **端点数据是否正确** — `HoppscotchDataConverter` 将 Spring 端点数据转为 Hoppscotch 请求格式，逻辑正确，数据完整
2. **请求是否真的被创建** — 日志显示请求已成功创建（mutation 返回了 id）
3. **集合策略是否有误** — 检查了复用 vs 新建集合的逻辑，没有问题
4. **网络/认证问题** — token 有效，API 无错误

以上都正常，问题不在同步逻辑本身。

### 第二轮：发现对比实验的关键线索

做了手动/自动的对比实验：

| 方式 | 同步后可见？ |
|------|------------|
| 手动创建集合 + 手动添加请求 | ✅ 可见 |
| 插件同步集合 + 手动添加请求 | ❌ 不可见 |
| 手动创建集合 + 插件创建请求到该集合 | ✅ 可见 |

突破口：**"插件创建的集合"不可见，"手动创建的集合"可见**。差异在集合层面，不在请求层面。

### 第三轮：GraphQL mutation 对比

对比手动创建 vs 插件创建发送的 mutation：

**手动创建（浏览器 DevTools Network 抓包）：**

```graphql
mutation createRESTRootUserCollection($title: String!) {
  createRESTRootUserCollection(title: $title) {
    id
    title
  }
}
```

变量：`{ "title": "xxx" }` — 没有 `data` 参数。

**插件创建（日志/代码）：**

```graphql
mutation createRESTRootUserCollection($title: String!, $data: String) {
  createRESTRootUserCollection(title: $title, data: $data) {
    id
    title
  }
}
```

变量：`{ "title": "xxx", "data": "{\"requests\":[]}" }` — 传了 `data` 参数。

### 第四轮：根因确认

Hoppscotch Server 端对 `createRESTRootUserCollection` 的 `data` 参数处理逻辑：

- `data` 参数不为 null → 服务端将 `data` 作为**独立 JSON 字段**存入数据库
- 在 Hoppscotch Web UI 的查询中，如果 collection 上有 `data` 字段，UI 会认为该集合是"有独立数据的集合"，**不渲染内部子资源**

手动创建时 `data` 未被赋值，服务端存为 `null`，UI 正常渲染子请求。
插件创建时传了 `data: {"requests":[]}`，即使数组为空，非 null 就触发了 UI 的特殊渲染逻辑，导致内容不可见。

## 修复方案

### 修改点

**文件**：`HoppscotchClient.kt`

**之前**（两个方法均如此）：

```kotlin
fun createCollection(title: String, dataJson: String = """{"requests":[]}"""): Result<CollectionInfo>
```

- 总是传非 null 的 `data` 参数

**之后**：

```kotlin
fun createCollection(title: String, dataJson: String? = null): Result<CollectionInfo>
```

- 默认 `null`，不做特殊处理
- 变量中 `if (dataJson != null) put("data", dataJson)`
- null 时完全不传 `data` 参数，和手动创建行为一致

对应 `createChildCollection` 也做了相同修改。

### 调用方

`SyncService.kt` 中调用 `createCollection` 和 `createChildCollection` 的地方完全不需要改动——**保持原来的 None 传参即可**（`client.createCollection(collectionTitle)` 等价于 `dataJson = null`）。

### 副作用清理

由于不再需要构造 `data` JSON，移除了以下不再使用的代码：

- `HoppscotchCollection` 数据类 — 原来用于构造集合的 data 结构
- `toHoppscotchCollection()` 扩展方法 — 将 Spring 端点转为集合 data
- `toCollectionRequestBody()` 扩展方法 — 将集合 data 序列化为 JSON

## 经验教训

1. **第三方 API 的"隐式语义"**：GraphQL 参数 nullable 不代表传 null 和不传等价。Hoppscotch 服务端对 `data` 参数的处理是"有值则独立存储" vs "无值则关联存储"，这是从传输层无法推断的业务逻辑。
2. **对比法是排查 API 语义问题的最有效手段**：手动操作和自动操作的唯一差异就是根因。当两端业务逻辑一致时，逐层缩小差异往往比盲目阅读源码更高效。
3. **默认值的选择**：当初写 `dataJson = """{"requests":[]}"""` 的意图是"显式初始化空数据"，但恰好触发了服务端的特殊路径。对第三方 API，**默认行为应尽量和手动操作保持一致**，除非有明确理由偏离。
4. **GraphQL mutation 参数的"惰性传参"原则**：对于 nullable 参数，如果不确定服务端对 null 和 undefined 的处理是否一致，**只在有实际数据需要传递时才传参**，不要捏造"空结构"。

## 涉及文件

| 文件 | 变更 |
|------|------|
| `HoppscotchClient.kt:82` | `dataJson` 默认值从 `"""{"requests":[]}"""` 改为 `null` |
| `HoppscotchClient.kt:94` | 新增 `if (dataJson != null)` 条件传参 |
| `HoppscotchClient.kt:253` | `createChildCollection` 同样修改 |
| `HoppscotchClient.kt:267` | 同上 |
| `HoppscotchModels.kt` | 移除 `HoppscotchCollection` 数据类 |
| `HoppscotchDataConverter.kt` | 移除 `toHoppscotchCollection()`、`toCollectionRequestBody()` |
