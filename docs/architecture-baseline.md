# 架构基线（本项目落地版）

> 本文是《三层架构 S1》在 DeepChat Mobile 里的具体落法。派单的任务卡引用本文，
> 不在卡里复述分层定义。结构的推导与文献出处见 《三层架构 S1》。
> 版本：2026-11-22。改动本文需要总调度。

---

## 一、模块划分与依赖

七个 Gradle 模块，依赖单向指向内层。

| 模块 | 层 | 职责 | 可以依赖 |
|---|---|---|---|
| `:domain` | 领域 | 实体、值对象、业务规则、不变式、事件与投影（纯函数） | 仅本层 + 标准库 |
| `:application` | 应用 | 用例编排、事务边界、端口定义、上下文策略 | `:domain` |
| `:adapter-persistence` | 适配 | SQLite、事件日志、投影缓存、迁移 | `:application`、`:domain` |
| `:adapter-network` | 适配 | OpenAI 兼容 / Anthropic / Gemini、SSE、搜索、目录 | `:application`、`:domain` |
| `:adapter-platform` | 适配 | SAF、Keystore、前后台、导出导入 | `:application`、`:domain` |
| `:ui` | 适配 | Compose 界面；**只通过 `ChatGateway` + `ChatSnapshot` 取数** | `:application`、`:domain` |
| `:app` | 组装根 | `AppContainer`、`AppNavHost`、`MainActivity`、`MobileChatApp` | 全部 |

规则：

1. `:domain` 源码里不出现 Android、协程、时间（时间由调用方传入 `nowMillis`）、JSON。
2. `:application` 里不出现 SQL、表名、Android 类型。
3. 适配层只做存取与协议映射，不出现业务判断。
4. 组装根之外不出现 `new 具体实现()`；只有 `AppContainer` 知道谁实现谁。
5. 端口按用途命名（`ChatCompletionPort` 而非 `OpenAiPort`）。

## 二、组装根

- 位置：`app/src/main/java/com/deepseek/mobilechat/wiring/AppContainer.kt`，进程内唯一。
- 全部依赖 `by lazy`：冷启动只构造界面实际用到的那部分。
- 无 DI 容器、无注解处理器。这是刻意的：AGP 9 内建 Kotlin，KSP 与 Kotlin 版本线不匹配，
  且注解会把框架类型带进内层。
- `AppContainer` 是 `AppServices` 与 `SettingsCapability` 的实现；界面通过
  `LocalAppServices` / `LocalChatGateway` 拿到它，不 new、不查服务定位器。

## 三、端口清单（定义在 `application/.../Ports.kt`）

| 用途 | 端口 | 实现 |
|---|---|---|
| 模型调用 | `ChatCompletionPort` | `adapter-network` 三协议 + 路由 |
| 模型目录 | `ModelCatalogPort` | `adapter-network` |
| 联网搜索 | `SearchPort` | `adapter-network`（DeepSeek / Bing / Tavily / Brave / Serper） |
| 会话日志 | `ConversationLogStore` | `adapter-persistence`（SQLite 事件表） |
| 流式草稿 | `TurnDraftStore` | `adapter-persistence` |
| 用量聚合 | `UsageAggregateStore` | `adapter-persistence` |
| 会话/消息索引 | `ConversationStore`、`MessageStore` | `adapter-persistence` |
| 预设/服务源 | `PresetStore`、`ProviderProfileStore` | `adapter-persistence` |
| 附件/文件夹/标签/注入/压缩 | `AttachmentStore`、`FolderTagStore`、`InjectionStore`、`CompactionStore` | `adapter-persistence` |
| 设置/密钥 | `SettingsStore`、`SecretStore` | `adapter-platform` |
| 文件导入导出 | `FileImportPort`、`ExportPort` | `adapter-platform` |
| 平台能力 | `ConnectivityPort`、`BackgroundExecutionPort` | `adapter-platform` |
| 时间/标识/估算 | `ClockPort`、`IdPort`、`TokenEstimatorPort` | `:app` / 纯实现 |
| 架构选择 | `ModelBudgetLookup`、`ContextStrategy` | `:application` 内置 |

端口可以有多个适配器：真实实现、内存实现（测试）、测试桩。

## 四、会话的真相是事件日志

会话历史不以 `messages` 表为真相，详见 `docs/architecture-v2-log.md`：

- 真相 = `conversation_events`（append-only，`{type, seq, schema_version, data_json}`）。
- 三个投影：`SurfaceFolder.fold`（模型可见）、`TranscriptProjector`（人可见，只读追加来源）、
  `ChatSnapshot`（界面可见，一个版本号一个值）。
- 压缩是事件（`compaction/start|summary|end`），不是删除。
- 界面不得从 `messages` 表读历史，也不得自己推导业务状态。

## 五、冻结文件（改动需总调度）

`domain/.../Contracts.kt`、`domain/.../log/**`、`application/.../Ports.kt`、
`application/.../Services.kt`、`application/.../log/Gateway.kt`、`ui/.../AppServices.kt`、
全部 `*.gradle.kts` 与 `gradle/libs.versions.toml`、`docs/**`、`_lead/**`。

## 六、审查清单（违规征兆）

- 领域层出现 Android / 协程 / JSON / 时间调用。
- 应用层出现 SQL 字符串或表名。
- 适配层出现业务判断（阈值、排序规则、状态机）。
- 组装根之外出现具体实现类型。
- 端口以技术命名。
- 存在第二处「知道全部具体实现」的代码。
- 界面直接 import `*Store` 读历史。

## 七、本项目的取舍

1. **没有 Hilt / Room / KSP**：手写 `AppContainer` + `SQLiteOpenHelper`，DDL 手写并被
   JVM 测试用 `sqlite-jdbc` 真实执行。
2. **没有 instrumentation 测试**：这台机器没有模拟器，验收靠 JVM 测试 + 真机手工冒烟。
3. **UI 是适配层**：Compose 不放进领域层，界面只消费快照、只发命令。
4. **框架不设组装根**：本项目是应用，不对外发布库。
