# 架构设计 v0.1

Android 本地优先的 AI 聊天客户端。本文是架构的**唯一事实来源**，后续变更走 ADR 追加。

---

## 1. 约束与定位

| 项 | 决定 |
| --- | --- |
| 平台 | Android 8.0+（minSdk 26），Kotlin + Jetpack Compose，edge-to-edge |
| 形态 | 单机应用，纯本地数据，无账号、无云同步 |
| 模型接入 | 多 provider 统一适配，OpenAI 兼容协议为基准，另支持 Anthropic、Gemini 原生协议 |
| 联网搜索 | 应用内执行，搜索结果作为上下文块注入，不依赖模型内置搜索 |
| 跑团 | 一期不做角色卡 / 世界书；保留「手动注入上下文」 |
| 动画 | 架构层只定义 motion token 与转场契约，实现阶段强制补齐，不允许裸跳转 |
| 分层 | 领域层 / 应用层 / 适配层 + 组装根（依赖倒置，组装根是唯一知道全图的代码） |

---

## 2. 分层总览

```
                         :app（组装根）
                ┌──────────┬──────────┬──────────┐
                ▼          ▼          ▼          ▼
              :ui     :adapter-*  :platform   导航/入口
                └──────────┴──────────┴──────────┘
                            ▼
                     :application
                     用例 / 端口 / 编排
                            ▼
                       :domain
                     纯 Kotlin，零依赖
```

依赖方向铁律：

- `domain` 不依赖任何东西，连 coroutines 都不需要。
- `application` 只依赖 `domain`，通过**自己定义**的端口访问外部。
- `adapters` / `platform` / `ui` 依赖 `application` + `domain`，实现端口。
- `app` 是唯一同时依赖全部模块的地方，负责构造与注入。
- 反向依赖一律编译期不通过（用 Gradle 模块边界兜底，而不是靠自觉）。

| 层 | 允许的依赖 | 禁止出现 |
| --- | --- | --- |
| domain | Kotlin stdlib | Android、Room、OkHttp、Hilt、kotlinx.serialization（序列化放在适配层映射） |
| application | domain、kotlinx-coroutines | Android SDK、任何具体网络/存储库、Hilt 注解 |
| adapters | application、domain | 互相依赖（provider 适配器不得依赖 Room 适配器） |
| ui | application、domain | 具体适配器、直接 new 用例以外的依赖构造 |
| app | 全部 | 业务逻辑 |

---

## 3. 领域层（domain）

### 3.1 聚合与实体

| 实体 | 关键字段 | 说明 |
| --- | --- | --- |
| `Conversation` | id, title, folderId, tagIds, activeLeafId, overrides, createdAt, updatedAt | 聚合根；`activeLeafId` 是当前可见路径的终点 |
| `Message` | id, conversationId, parentId, seq, role, parts, status, modelInfo, usage, createdAt, editedAt | 消息树节点；`seq` 保证同一父节点下的兄弟顺序稳定 |
| `ContextInjection` | id, conversationId, anchorMessageId, scope(ephemeral/persistent), position, content, createdAt | 手动注入上下文 |
| `Compaction` | id, conversationId, covers(fromMessageId..toMessageId), summary, kind, createdAt, undoneAt | 可撤销的上下文压缩记录 |
| `Preset` | id, name, systemPrompt, modelRef, params, thinkingLevel, toolToggles, contextStrategyId, isDefault | 提示词 + 参数预设 |
| `ProviderProfile` | id, protocol, baseUrl, secretRef, defaultModelId, extraHeaders, createdAt | provider 配置；密钥本体只存在 SecretStore |
| `Folder` / `Tag` | id, name / id, name, color | 会话组织 |
| `Attachment` | id, mimeType, size, localPath, sha256, extractedText? | 附件；图片走视觉输入，纯文本走文本注入 |

### 3.2 值对象

`ConversationId` / `MessageId` / `AttachmentId` / `ProviderId` / `ModelId`（强类型，避免串参）、`ModelRef(providerId, modelId)`、`ThinkingLevel(OFF, LOW, MEDIUM, HIGH, AUTO)`、`SamplingParams(temperature, topP, maxTokens, stop)`、`MessageRole(SYSTEM, USER, ASSISTANT, TOOL)`、`TokenCount`、`TokenBudget(limit, used)`、`FinishReason`、`MessageStatus(STREAMING, COMPLETE, CANCELLED, FAILED)`。

### 3.3 消息内容（parts）

消息正文不存字符串，存有序 part 列表，序列化为 JSON 存一列，另派生 `plainText` 列供全文检索：

```kotlin
sealed interface MessagePart {
    data class Text(val text: String) : MessagePart
    data class Image(val attachmentId: AttachmentId) : MessagePart
    data class FileRef(val attachmentId: AttachmentId) : MessagePart
    data class Reasoning(val text: String, val signature: String?) : MessagePart
    data class ContextBlock(
        val title: String,
        val body: String,
        val source: BlockSource,      // USER_INJECTED / SEARCH / FILE_EXTRACT / COMPACTION / MEMORY
        val citations: List<Citation> = emptyList()
    ) : MessagePart
    data class SearchResults(val query: String, val hits: List<SearchHit>) : MessagePart
    data class ToolCall(val id: String, val name: String, val argumentsJson: String) : MessagePart
    data class ToolResult(val callId: String, val ok: Boolean, val content: String) : MessagePart
}
```

`Reasoning.signature` 必须保留：Anthropic 的 thinking 块在后续请求中要连签名一起回传，丢了会报错或降级。

### 3.4 规则（可单测，不依赖任何框架）

1. `activeLeafId` 必须属于本会话，且是一棵树的叶子。
2. `parentId` 指向的消息必须在同一会话内。
3. 兄弟顺序由 `seq` 决定，重排也改 `seq`，不依赖时间戳。
4. **原地编辑仅允许发生在 active path 上、且该消息没有子节点**；否则编辑必须产生新分支（sibling）。
5. 重新生成只针对 assistant 消息，产生同父级的新兄弟，旧回复保留。
6. Compaction 只能覆盖 active path 上的连续区间，且不得与未完成的 Streaming 消息相交。
7. 附件引用不得悬空：删除消息时级联清理，或删除附件前检查引用。
8. `STREAMING` 状态的消息不可编辑、不可作为压缩输入。
9. 预设解析优先级：会话覆盖 > 会话绑定预设 > 全局默认预设 > provider 默认值。

### 3.5 领域服务

`MessageTree`：纯函数结构，提供 `pathTo(leaf)`、`siblings(id)`、`subtree(id)`、`canEditInPlace(id)`、`validate()`。分支、重新生成、编辑、删除、导出的正确性全部压在这里，测试覆盖率要求最高。

`ContextBudgetPolicy`：给定预算与各块估算，判断是否超限、需要哪种处理。

---

## 4. 应用层（application）

### 4.1 端口（应用层拥有接口，适配层实现）

```kotlin
interface ChatCompletionPort {
    fun stream(req: GenerationRequest): Flow<GenerationEvent>
}
interface ModelCatalogPort {
    suspend fun listModels(profile: ProviderProfile): List<ModelDescriptor>
    suspend fun testConnection(profile: ProviderProfile): ConnectionResult
}
interface SearchPort {
    suspend fun search(query: String, opts: SearchOptions): SearchResponse
}
interface ConversationStore { /* observeList, get, upsert, delete, title */ }
interface MessageStore { /* observeTree, append, update, deleteSubtree, activeLeaf */ }
interface AttachmentStore { /* save, load, delete, sha */ }
interface PresetStore { }
interface ProviderProfileStore { }
interface ContextInjectionStore { }
interface CompactionStore { }
interface FolderTagStore { }
interface TokenEstimatorPort { fun estimate(parts: List<MessagePart>): TokenCount }
interface FileImportPort { suspend fun import(uri: Uri): ImportedFile }   // SAF 选择器
interface ExportPort { suspend fun write(bundle: ExportBundle, target: Uri) }
interface SecretStore { suspend fun put(ref: String, value: String); suspend fun get(ref: String): String? }
interface ClockPort { fun now(): Instant }
interface IdPort { fun newId(): String }
interface BackgroundExecutionPort {
    suspend fun runInForeground(title: String, onCancel: () -> Unit, block: suspend () -> Unit)
}
interface ConnectivityPort { fun observe(): Flow<Boolean> }
```

`GenerationEvent`：`Started(messageId)` / `TextDelta` / `ReasoningDelta` / `ToolCallDelta` / `Usage` / `Finished(reason)` / `Failed(AppError)`。

`GenerationRequest` 只含内部类型（`Message` 列表 + 参数 + 能力开关），**不含任何 provider 专有字段**。provider 差异在适配层吸收。

### 4.2 用例

| 用例 | 职责 |
| --- | --- |
| `SendMessage` | 落用户消息 → 组装上下文 → 入队生成 |
| `Regenerate` | 对指定 assistant 消息新建兄弟并重新生成 |
| `EditMessage` | 叶子原地改；非叶子转 `ForkFrom` |
| `ForkFrom` / `SwitchBranch` | 切换 active leaf、在兄弟间导航 |
| `DeleteMessageSubtree` | 删子树并修正 active leaf |
| `InjectContext` | 添加临时/常驻注入块 |
| `RemoveInjection` | 撤回注入 |
| `AssembleContext` | 纯计算：给定会话与叶子，产出 `ContextAssembly`（可预览、可调试） |
| `PlanCompaction` | 超预算时产出 `CompactionProposal`（不落库） |
| `ApplyCompaction` / `UndoCompaction` | 确认后落库 / 撤销 |
| `SearchWeb` | 执行搜索并产出可注入的搜索结果块 |
| `CancelGeneration` | 取消并保留部分内容 |
| `CreateConversation` / `RenameConversation` / `MoveToFolder` / `TagConversation` | 会话管理 |
| `SearchConversations` | FTS + 标签/文件夹过滤 |
| `SavePreset` / `ApplyPreset` / `UpsertProviderProfile` / `TestConnection` | 配置管理 |
| `ExportConversation` / `ImportConversation` | 导出导入（JSON / Markdown / 压缩包） |
| `Enqueue / Pause / Resume / ReorderGeneration` | 生成队列 |

用例统一返回 `Outcome<T>`（`Ok` / `Err(AppError)`），**预期失败不用异常**；只有程序错误才抛。

```kotlin
sealed interface Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>
    data class Err(val error: AppError) : Outcome<Nothing>
}
```

### 4.3 上下文组装管线

固定顺序（顺序本身就是接口，改它算破坏性变更）：

1. 系统提示词（会话覆盖 > 预设 > 全局）
2. 常驻注入块（按 position 排序）
3. 压缩摘要块（替换其覆盖的区间）
4. active path 历史消息（跳过被压缩覆盖的，`pinned` 消息豁免）
5. 悬挂块：搜索结果为本次触发轮次的上下文块、文件抽取文本
6. 临时注入块
7. 当前用户消息 + 附件

每一步都记 `tokenEstimate`，`ContextAssembly` 同时返回每块的来源与占用，供上下文调试面板展示**实际发送内容**。

### 4.4 压缩与策略扩展点（用户确认制）

- 默认策略 `ConfirmCompaction`：超预算时**不静默截断**，返回 `NeedsConfirmation(proposal)`，UI 展示「将把 N 条消息压缩为摘要，预计节省 X tokens」，确认后才写入 `Compaction`。
- 策略 SPI：

```kotlin
interface ContextStrategy {
    val id: String
    fun plan(assembly: ContextAssembly, budget: TokenBudget): ContextPlan
}
```

- 一期实现：`NoopStrategy`（超限直接报错）、`ConfirmCompactionStrategy`（默认）、`DropOldestStrategy`（可选，带撤销）。
- 预留：`RollingMemoryStrategy`、`RetrievalMemoryStrategy` —— 只需新增实现并在设置里可选，不改组装管线与数据模型（`Compaction.kind` 已留枚举位）。
- 所有压缩都可撤销：`Compaction` 保留原区间引用，撤销即恢复。

### 4.5 生成编排

`GenerationCoordinator`：会话级串行（同一会话同时只跑一个生成），全局并发上限可配（默认 2）。`GenerationQueue` 维护排队项、暂停、重排、取消。

流式落库策略：assistant 草稿每 ~300ms 落一次盘（可配），进程被杀最多丢一个节拍；完成/取消/失败时写最终状态与 usage。

---

## 5. 适配层

| 适配器 | 实现要点 |
| --- | --- |
| `OpenAiCompatibleAdapter` | 自定义 baseUrl；SSE 增量解析；`reasoning_effort` 映射 `ThinkingLevel`；vision content parts；tool calling 归一化 |
| `AnthropicAdapter` | Messages API；thinking budget 映射；必须回传 `Reasoning.signature`；SSE 事件类型映射 |
| `GeminiAdapter` | generateContent / streamGenerateContent；`thinkingConfig` 映射 |
| `ProviderAdapterFactory` | 按 `ProviderProfile.protocol` 选适配器，用户可自建 profile |
| `TavilyAdapter` / `BraveAdapter` / `SerperAdapter` | 统一返回 `SearchResponse`，含 title/url/snippet/score |
| `Sqlite*Store` | `SQLiteOpenHelper` + 手写 DAO；消息树按 `conversationId + parentId` 索引；FTS4 建在 `plainText`；DDL 在 JVM 测试里用 sqlite-jdbc 真实执行 |
| `SafFilePort` / `InternalAttachmentStore` | 选择器导入 → 复制进内部存储 → 记录 sha256；图片生成缩略图 |
| `AndroidSecretStore` | Keystore 加密（EncryptedSharedPreferences 或自管 AES-GCM）；密钥永不出现在 Room / 日志 |
| `ForegroundGenerationService` | `dataSync` 类型前台服务承接后台生成，通知带进度与取消 |
| `AndroidConnectivityPort` | 在线状态与重试退避 |

适配层统一职责：**协议翻译 + 错误归一**。HTTP 401/429/5xx、协议解析失败、超时，全部映射成应用层 `AppError`，禁止把 OkHttp 异常漏到上层。

---

## 6. 组装根（:app）

不引入 Hilt / KSP：注解处理器与 Kotlin 版本强耦合、拖慢编译，还会迫使核心层带框架注解。组装根是手写的 `AppContainer`：

```kotlin
interface AppContainer {
    val sendMessage: SendMessage
    val conversationStore: ConversationStore
    // ...
}
class DefaultAppContainer(private val context: Context) : AppContainer { /* 手动 new 所有实现 */ }
```

- 所有依赖显式构造传入，无反射、无注解。
- `.domain` / `.application` 里没有任何 Android import 与框架注解。
- `ui` 通过 `ViewModelProvider.Factory` 从容器取用例，`@Composable` 只消费 `UiState`。
- 启动顺序：`Application`（建容器）→ 打开数据库并迁移 → 恢复未完成生成 → `setContent`。

组装根同时是**唯一允许决定实现选择**的地方：换 SQLite 为别的存储、换 OkHttp 为 Ktor，只动这一个模块 + 适配器模块。

---

## 7. 工程模块划分（一期）

| 模块 | 内容 | 依赖 |
| --- | --- | --- |
| `:domain` | 实体、值对象、MessageTree、策略 | 无 |
| `:application` | 端口、用例、上下文组装、队列 | `:domain` |
| `:adapter-persistence` | SQLite（SQLiteOpenHelper）、DAO、映射、FTS4、迁移 | `:application` |
| `:adapter-network` | provider 适配器、搜索适配器、HTTP 客户端 | `:application` |
| `:adapter-platform` | 文件、Keystore、前台服务、连接状态 | `:application` |
| `:ui` | 设计系统 + motion token + 各屏 Compose | `:application` |
| `:app` | 组装根、导航宿主、Application | 全部 |

`ui` 稳定后可按 feature 拆成 `:ui-chat` / `:ui-conversations` / `:ui-settings`，一期不拆，减少构建负担。拆分的判据是「单人维护的编译时间」，不是教条。

---

## 8. 关键流程

### 8.1 发送与流式

```
UI → SendMessage → 落 UserMessage → AssembleContext
   → 预算检查 ──超限──→ NeedsConfirmation(proposal) → UI 确认 → ApplyCompaction → 重新组装
   → GenerationQueue.enqueue → GenerationCoordinator
   → ChatCompletionPort.stream → 增量事件 → 节流写库 → UI 观察 Flow 重绘
   → Finished：落 usage/modelInfo；Failed：落错误态；Cancel：落 CANCELLED + 部分内容
```

### 8.2 重新生成与分支

```
Regenerate(msgId)
  → 校验 msgId 是 assistant
  → 新建 sibling（seq = max+1），activeLeaf 指向它
  → 用同一父级上下文重新组装请求
旧回复仍在树里，UI 显示「上一版」导航（n 个兄弟）
```

### 8.3 编辑

叶子消息：原地更新 + `editedAt`。
非叶子消息：走 `ForkFrom`，新建 sibling，原分支保留。

### 8.4 导入导出

- JSON：`schemaVersion` + 完整树 + 预设 + 附件清单；附件放 `files/`，整体打包 zip。
- Markdown：可读转录，含角色、时间、分支标记（分支只导出 active path）。
- 导入：默认全部新建 id 避免覆盖；同 id 冲突策略可选（跳过 / 复制为新会话）。

---

## 9. 跨切面

**错误模型**

```
AppError
├── Validation
├── BranchConflict
├── BudgetExceeded(proposal)
├── ProviderAuth / ProviderRateLimit(retryAfter) / ProviderProtocol / ProviderUnavailable
├── Network / Timeout
├── Storage / FileTooLarge / UnsupportedMime
├── SecretMissing
└── Unknown(cause)
```

取消不是错误（`CancellationException`），不弹错、不落 Failed。

**并发**：所有写操作走同一仓储串行；`MessageTree` 不可变，写入用「读取-校验-提交」事务；生成期间禁止删除正在生成的消息。

**安全**：接口密钥进 Keystore；日志脱敏（不打印 Authorization / 消息正文）；禁止明文 HTTP；导出文件默认不含密钥。

**迁移**：SQLite `user_version` 驱动的手写迁移，每版配「升级前数据 + 升级后断言」测试；导出格式带 `schemaVersion`，导入做版本校验。

**测试与验收（对干员硬性要求）**

| 层 | 要求 |
| --- | --- |
| domain | 纯单测；`MessageTree` 需覆盖编辑/分支/删除/排序边界，建议 property-based |
| application | 全端口用内存假实现，确定时钟与 id；覆盖超预算、取消、失败重试 |
| adapters | 契约测试 + 录制样本（SSE 字节流、典型 provider JSON）；Room 迁移测试；不得在单测里打真实网络 |
| ui | 关键流程 Compose UI 测试；设计 token 与转场有快照 |

铁律：交付必须附**真实执行的命令与输出**；禁止 mock 被测对象本身；禁止用「测试通过」替代输出证据；跨模块用内存实现跑通端到端后我才验收。

---

## 10. 范围

**一期必做**：流式聊天、分支、重新生成、编辑、预设管理、provider 管理、附件（文本 + 图片）、手动注入、压缩确认、应用内搜索、会话搜索/标签/文件夹、导出/导入、生成队列。

**延后**：TTS、slash 命令、角色卡/世界书、多端同步、本地模型、PDF/Office 抽取、多窗口、主题商城。

**明确不做**：账号体系、云端存储、计费。

---

## 11. 已确认决议（2026-11-20）

1. 包名 `com.deepseek.mobilechat`，显示名 `Mobile Chat`（临时，改动只涉及 3 个文件）。
2. 自动生成会话标题：要。首次回复完成后异步触发，失败不影响主流程。
3. 上下文调试面板：要。展示实际出站 payload 与逐块 token 占用。
4. Token / 费用统计：要。每条消息记录 usage 与估算费用；余额查询按 provider 能力提供，没有余额接口的隐藏入口。
5. 导出：附件可选（开关控制是否内嵌图片/文件）；Markdown 只导出 active path。
6. 「继续生成」：仅在 `FinishReason.LENGTH` 截断时出现，不作为独立功能。

## 12. 实现状态（2026-11-20 交付）

| 模块 | 实现 | 单测 |
| --- | --- | --- |
| `:domain` | 实体/值对象/`MessageTree`/预算 | 14 |
| `:application` | 上下文组装、3 种策略、10 个服务、生成队列、价格回退 | 116 |
| `:adapter-persistence` | 11 张表、FTS4、8 个 Store、迁移骨架 | 77 |
| `:adapter-network` | 3 协议 provider、3 家搜索、SSE 解析、错误归一 | 100 |
| `:adapter-platform` | SAF、Keystore、导出导入、连接状态、前台服务 | 18 |
| `:ui` | 设计系统、动效 token、聊天 + 5 个二级界面 | 15（纯逻辑） |

合计 346 个 JVM 单测，0 失败；`assembleDebug` 产出 15 MB debug APK，编译零警告。

**完成度审计补记**：交付后复查发现两个特性只有实现、没有调用方——`webSearch` 没有任何 UI 入口，`ensureTitle` 从未被调用。已补齐：输入区新增「联网」开关（搜索失败不阻断发送，结果走既有的临时注入通道，只影响一次请求），ChatViewModel 在首条回复完成后触发一次标题生成。审计同时移除了策略选择器里无法实现的「丢弃最早消息」选项。

集成期修掉的一个真实缺陷：三个 provider 适配器都把 `.catch {}` 放在 `.flowOn(IO)` 之后，导致失败事件与已缓冲的 `Started` 走不同通道，事件序列存在竞态（整套测试曾出现一次 `expected: 2 but was: 1`）。把 `catch` 移到 `flowOn` 内侧后连续 15 轮全绿。

**未做运行时验收**：本机没有模拟器，界面与 Android 专有能力（Keystore、SAF、前台服务、通知）只验证到编译与 manifest 合并层面，观感与真机行为需上设备复核。
