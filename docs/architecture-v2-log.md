# 骨架设计 v2：会话日志 + 双向投影

> 状态：设计稿，未实现。日期 2026-11-21。
> 本轮只讨论架构，不产出代码。实现时的契约文件仍由主调度维护。

---

## 1. 决策摘要

1. **会话的唯一真相是一条 append-only 事件日志**，不是"messages 表 + conversations.activeLeafId"。
2. **视图是折算出来的**：同一份日志有两个投影 —— 给人看的**转录**、给模型看的**上下文**。两者独立演进、互不牵制。
3. **压缩不是删除，是日志里的一条事件**；可回退，被覆盖的内容永不物理丢失。
4. **流式中间态不进日志**：生成中的文本写"草稿"表，回合结束时一次性落成事件。进程被杀不会留下半截事件。
5. **UI 只消费一个东西**：`Flow<ChatSnapshot>`（带版本号）+ 命令接口。UI 不再直接读 store，也不再自己推导业务状态。
6. **一次用户动作 = 一个版本号 = 一次原子提交**。不存在"两个 store 各更新了一半"的中间态。

理想下这几条能让"瞬时不一致"这类 bug 在结构上无法表达，而不是靠补丁防住。

---

## 2. 为什么要改：审查给出的三类根因

前一轮的缺陷不是十几个独立的 bug，是三个结构性问题的不同表现：

**(a) 真相有两份，且写不同步。**
`messages` 和 `conversations.activeLeafId` 是两次独立写入、两次 `ChangeBus` 通知。UI 分别订阅，必然存在"消息到了、leaf 还没到"的窗口。于是需要 `visibleMessages` 的兜底：平铺（把两条分支的回复叠在一起）或空白（"这个会话还是空的"）。删除、分叉、编辑、重新生成都在这个窗口内出过问题。

**(b) 进行中的事不在状态里。**
`isGenerating` 是从持久化的 `status == STREAMING` 推出来的。重新生成时，占位消息还没写，所以"正在生成"是 false —— 屏幕看起来空闲：没有转圈、没有停止键、发送键可用。输入区 `isSending` 又只有取消路径会复位，任何异常都会把它永久卡在"发送中"。**用户说的"不可靠"，很大一部分是"不知道现在到底在干什么"。**

**(c) 上下文是从转录里拼出来的。**
`ContextAssembler` 遍历的 `Message` 列表，和 UI 显示的是同一份。于是两件事被硬塞进它：联网搜索结果（用"临时注入一段文本"的通道）、以及将来要做的压缩（只能覆盖、不能表达"这一段被替换成摘要了"）。模型看到什么、人看到什么，本该是两份不同的东西。

---

## 3. 参考：DSH 的 session log（已核实的实现）

DSH 的会话目录形如：

```
~/.dsh/sessions/<workspace>/<session>/session.v4.jsonl.zstd
```

- **文件名带格式版本**（`v4`），内容是 **zstd 压缩的 JSONL**；
- 每行是一个事件，形如 `{"type":"session/end-seed","seq":3,"time":1793435387029,"data":{}}`；
- 安装目录里对应的一整套包印证了这个设计：
  `dsh-session-format` 加 **`dsh-session-format-v0-to-v1` / `v1-to-v2` / `v2-to-v3` / `v3-to-v4`**（格式迁移链）、
  `dsh-session-persistence-jsonl`（追加写）、
  **`dsh-session-projection` + `dsh-session-projection-cache`**（投影与投影缓存）、
  `dsh-compaction` / `dsh-compaction-basic` / `dsh-compaction-tool-result-pruner` / `dsh-compaction-image-offload`（压缩是日志之上的一种变换）、
  `dsh-session-checkpoint-policy`、`dsh-session-query-sqlite`（SQLite 只做查询索引）、
  `dsh-client-ui-settings-session-log`（日志本身对用户可见）。

它守的是三条规则：

1. **日志是唯一真相。状态是 `fold(日志)` 的结果，不是另存的一份。**
2. **同一份日志有不同的投影。**"人看到的对话"和"模型看到的上下文"是两个投影，各自有各自的规则；上下文可以是有损的、按策略裁剪的，而转录不许丢。
3. **压缩是一条事件，不是一次删除。**`CompactionApplied{覆盖范围, 摘要}` 之后，投影在折算时跳过被覆盖的区间、插入摘要块；回退就是再追加一条 `CompactionReverted`。被压缩的内容**从未离开日志**。

我们能直接借用的还有两件事：**格式版本 + 迁移链**（每代事件都有 upcast），以及**投影缓存**（否则每次打开会话都要全量折叠）。

---

## 4. 骨架：三层 + 组合根 + 前端契约

```
            ┌───────────────────────────── :ui ─────────────────────────────┐
            │  只依赖 ChatSnapshot（带 version）与命令接口；不 import 任何存储 │
            └───────────────▲──────────────────────────────┬────────────────┘
                            │ Flow<ChatSnapshot>           │ 命令(intent)
            ┌───────────────┴──────────────────────────────▼────────────────┐
            │                       :application                            │
            │  用例（Send / Regenerate / Edit / SwitchBranch / Compact …）    │
            │  事务边界：一次动作 = 一次 append 批次（一个 version）            │
            │  端口：ConversationLogStore / SnapshotStore / Clock / Id        │
            └───────────────▲──────────────────────────────┬────────────────┘
                            │ 事件与投影类型（纯）           │ 端口实现
            ┌───────────────┴──────────────┐  ┌────────────▼────────────────┐
            │           :domain            │  │    :adapter-persistence     │
            │  Event / Log / 两个投影       │  │  SQLite：事件表 + 快照 + 草稿 │
            │  纯函数 fold，无 IO、无框架    │  │  只做存取，不含业务规则        │
            └──────────────────────────────┘  └─────────────────────────────┘
                          ▲
                    :adapter-network / :adapter-platform（协议与平台能力，同上）
                          ▲
                     :app（组合根）：唯一构造所有对象、唯一决定装配
```

规则（沿用并加严）：

- 依赖只能向内：`ui → application → domain`，`adapters → application`。
- `domain` 不出现 Android、不出现协程、不出现时间（时间由调用方传 `nowMillis`）。
- **`ui` 不许 import 任何 `*Store` 端口。** 这是当前最大的架构泄漏：`AppServices` 把 `messageStore` / `conversationStore` 直接交给了界面，绕过了应用层。审查里至少一半的"状态对不上"来自这里。
- 组合根是唯一知道"谁实现谁"的地方。

### 前后端契约（这一版最重要的一条）

```kotlin
// 一个版本号 = 一个原子视角。UI 看到的任何状态都必须能对应到某个 version。
data class ChatSnapshot(
    val version: Long,
    val conversationId: ConversationId,
    val title: String,
    val transcript: List<TranscriptItem>,   // 只含当前活动路径 + 分支位置
    val branches: Map<MessageId, BranchSlot>,
    val overlay: Overlay,                   // 进行中的事，见下
    val totals: UsageTotals,
)

data class Overlay(
    val streaming: StreamingTurn?,          // 正在吐字的回合（含已生成文本）
    val searching: Boolean,
    val pendingUserText: String?,           // 压缩确认后待发的原文
    val compactionProposal: CompactionProposal?,
    val busy: BusyKind?,                    // 命令在飞，UI 据此禁用输入区
)
```

`Overlay` 的存在直接消灭两个 bug 类别：
**"屏幕看起来空闲但其实在跑"**（重新生成的装配窗口）、**"输入区永久卡在发送中"**（状态与命令生命周期绑定，命令结束必清）。

命令只有一种形状：`suspend fun <Cmd> cmd(...): Outcome<...>`，UI 不写状态，只发命令。

---

## 5. 领域模型（签名草案）

```kotlin
// 事件：只增不改，带格式版本
sealed interface ConversationEvent {
    val eventId: EventId
    val schemaVersion: Int           // 便于将来 upcast
    val type: String                 // 用于持久化与诊断
}

data class ConversationCreated(val title: String, val modelRef: ModelRef?, val params: SamplingParams)
data class UserMessageAppended(val messageId: MessageId, val parentId: MessageId?, val parts: List<MessagePart>)
data class AssistantTurnStarted(val messageId: MessageId, val parentId: MessageId, val modelInfo: ModelInfo)
data class SearchPerformed(val messageId: MessageId, val query: String, val hits: List<SearchHit>)
data class ServerToolEvent(val messageId: MessageId, val kind: String, val payload: JsonElement) // 模型内联网/抓网页
data class AssistantTurnCompleted(
    val messageId: MessageId, val parts: List<MessagePart>,
    val usage: Usage?, val finishReason: FinishReason, val citations: List<Source>,
)
data class AssistantTurnFailed(val messageId: MessageId, val error: String, val partialText: String)
data class LeafChanged(val messageId: MessageId)          // 切分支 / 采纳某个兄弟
data class MessageEdited(val messageId: MessageId, val newText: String)   // 就地编辑
data class CompactionApplied(val id: CompactionId, val from: EventId, val to: EventId,
                             val strategyId: String, val summary: String)
data class CompactionReverted(val id: CompactionId)
data class ModelOverrideChanged(val modelRef: ModelRef?, val params: SamplingParams?, val thinking: ThinkingLevel?)
data class TitleChanged(val title: String)
data class MessageDeleted(val messageId: MessageId)       // 墓碑，不物理删
```

日志与投影：

```kotlin
class ConversationLog(val conversationId: ConversationId, val events: List<StampedEvent>) {
    val version: Long get() = events.size.toLong()
}

// 纯函数：给定日志，算出人要看的转录
fun projectTranscript(log: ConversationLog): Transcript

// 纯函数：给定日志 + 上下文策略，算出模型要看的 prompt 结构
fun projectContext(log: ConversationLog, policy: ContextPolicy, budget: Int): ContextPlan
```

**两个投影的分歧是刻意的：**

| | 转录 | 上下文 |
|---|---|---|
| 思考内容 | 全部保留、可展开 | 默认丢弃（协议要求回传时例外，按 provider 规则） |
| 被压缩的区间 | 显示分隔条 + 摘要 | 替换成摘要块 |
| 联网结果 | 显示来源卡片 | 作为工具结果或上下文块进入 |
| 分支 | 显示兄弟切换器 | 只取活动路径 |
| 工具调用 | 完整展示（包括失败） | 只保留可重放的部分（本 app 不执行工具调用，见 §9） |

---

## 6. 应用层：命令与事务边界

一条铁律：**一个用例 = 一次原子 append = 一个新版本号。**

| 命令 | 一次提交里追加的事件 |
|---|---|
| `Send` | `UserMessageAppended` (+`SearchPerformed`) + `LeafChanged` |
| `Regenerate` | `AssistantTurnStarted`（**不动 leaf**） |
| 生成完成 | `AssistantTurnCompleted` + `LeafChanged{新消息}` （同一次提交） |
| `Edit`（就地） | `MessageEdited` |
| `Edit`（分叉） | `UserMessageAppended{sibling}` + `LeafChanged` + `AssistantTurnStarted` |
| `SwitchBranch` | `LeafChanged` |
| `Compact` | `CompactionApplied` |
| `Cancel` | `AssistantTurnFailed{reason=cancelled, partialText}` |

重新生成这条最重要：**旧的答案在 `AssistantTurnStarted` 时仍然在活动路径上**，只有新回合完成才切 leaf。于是"点重新生成 → 旧答案消失 → 没有转圈"这个现象在结构上不可能出现。

流式期间：文本写 `turn_drafts`（单行覆盖写，不进日志）。回合结束 → 删草稿 + 追加完成事件（同一事务）。进程被杀 → 启动时的核对器读草稿，追加 `AssistantTurnFailed{interrupted, partialText}`，再删草稿。**日志里永远不会出现"半个回合"。**

---

## 7. 适配层：存储布局

```sql
conversation_events(
  conversation_id TEXT NOT NULL,
  seq             INTEGER NOT NULL,     -- 会话内单调
  event_id        TEXT NOT NULL,
  type            TEXT NOT NULL,
  schema_version  INTEGER NOT NULL,
  payload_json    TEXT NOT NULL,
  created_at      INTEGER NOT NULL,
  PRIMARY KEY (conversation_id, seq)
);

conversation_snapshots(               -- 投影缓存，可随时丢弃重建
  conversation_id TEXT NOT NULL,
  version         INTEGER NOT NULL,
  transcript_json TEXT NOT NULL,
  PRIMARY KEY (conversation_id, version)
);

turn_drafts(                          -- 流式中间态，只有一行/回合
  message_id TEXT PRIMARY KEY,
  conversation_id TEXT NOT NULL,
  text TEXT NOT NULL,
  reasoning TEXT NOT NULL,
  updated_at INTEGER NOT NULL
);
```

- SQLite 在这里是**索引与查询层**，不是真相；真相是 `conversation_events` 的顺序本身（对应 DSH 用 JSONL 存日志、SQLite 只做查询）。
- 打开会话：读最新快照 + 折叠尾部事件；每 N 条事件或会话关闭时写快照。
- 全文检索（FTS）挂在转录投影上，不挂在日志上。

---

## 8. 这套骨架如何消掉已发现的缺陷

| 已发现的缺陷 | 现在的成因 | 骨架里的结局 |
|---|---|---|
| 删除/分叉瞬间空白或两条分支叠一起 | 消息与 leaf 分开写，投影容错靠平铺 | 一次提交一个版本，投影只接受一致输入；无法表达"半更新" |
| 重新生成时旧答案消失、无转圈 | 先改 leaf 再建占位；in-flight 不在状态里 | `AssistantTurnStarted` 不改 leaf；`Overlay.streaming/busy` 是快照的一部分 |
| 输入区永久"发送中" | `isSending` 只在取消路径复位 | 状态跟随命令生命周期，命令结束必清；`Overlay` 由应用层产出 |
| 压缩确认后文字消失 / 重复发送 | 待发文本被清空后重发、成功不清草稿 | 待发文本是 `Overlay.pendingUserText`，由应用层唯一拥有 |
| 分支/滚动状态串到另一个会话 | UI 复用同一个 ViewModel 与列表状态，自己推导 | 一个会话一个 `ChatSnapshot(conversationId, version)`，UI 不做推导 |
| 压缩将来要"改历史" | 没有表达"这段被摘要替换"的词汇 | `CompactionApplied` 事件 + 上下文投影替换 |
| 思考内容也进了上下文 / 或丢光 | 只有一份表示 | 两个投影；思考保留给人看，进不进上下文由策略决定 |
| 联网是"先搜一次拼文本" | 上下文里没有"搜索发生过"这个事实 | `SearchPerformed` / `ServerToolEvent` 是一等事件，转录与上下文各取所需 |
| 导出/导入不可靠 | 导出是另写的 JSON 结构 | 导出 = 日志本身（JSONL），导入 = 追加，天然可重放 |
| 测试靠假设（滚轮 bug 测不出） | 行为散在 UI 与 IO 里 | 折叠是纯函数：任何一条审查发现都能写成"给定日志 → 断言投影"的测试 |

---

## 9. 迁移路径（从当前 v3 到日志）

1. **先立契约**：`ChatSnapshot` + 命令接口 + `ConversationEvent` 定稿（主调度写，冻结）。
2. **加表不删表**：新增三张表；实现 `ConversationLogStore` 与投影器，并用**现有数据回放验证**——把每个会话的 messages 折成事件，再投影回 `Message` 列表，与旧表逐字段比对（这条是迁移的验收标准）。
3. **双读一个版本**：应用层默认读日志投影，同时保留旧读路径做对照；不一致就埋点上报（本地）。
4. **切换 UI**：`AppServices` 收窄为快照 + 命令，移除 store 暴露。
5. **下线旧表**：确认无回归后删除 messages/conversations 的读路径（表保留一个版本作为冷备份）。

风险点：迁移本身可能出错 → 必须有第 2 步的"投影 == 旧表"比对；日志体积增长 → 快照 + 保留策略；老版本回滚 → 旧表在，可回滚。

---

## 10. 不做什么（这一版刻意排除）

- 不做多端同步 / CRDT。
- 不做"每条 delta 都进日志"（会爆炸）；delta 只在草稿里。
- 不做通用事件溯源框架，只做会话这一处。
- 不为了优雅而重写 UI：这一版只把 UI 的**取数方式**改成快照 + 命令，外观与交互留给下一轮。

---

## 11. 未决问题

1. **事件粒度**：`AssistantTurnCompleted` 里放完整 `parts`（简单）还是放增量（省空间）？倾向放完整。
2. **快照策略**：每 N 条事件 / 会话关闭 / 定时？需要实测折叠成本。
3. **删会话**：删掉整条日志，还是保留一个 `ConversationDeleted` 墓碑？倾向墓碑 + 延迟清理，避免误删。
4. **压缩策略 SPI**：沿用现有 `ContextStrategy` 概念，升级为"输入日志 + 预算 → 输出摘要事件"的纯函数。
5. **旧表下线时点**：一个版本还是两个版本。

---

## 12. 对齐 DSH：读源码之后要补的六件事（2026-11-21 修订）

读了 `app.asar` 里的 `dsh-session` / `dsh-session-format` / `dsh-session-projection`
/ `dsh-compaction` / `dsh-session-checkpoint-policy`。以下不是推测，逐条附证据。

### 12.1 事件是分层的，surface 只有五种

`dsh-session/lib/types/known-event-types.js` 列了 **64 种**事件（`user/message`、
`assistant/message`、`turn/start`、`turn/end`、`step/start`、`tool/call`、`tool/result`、
`compaction/start|summary|end|prune`、`image/offload`、`request/header`、`model/selection`…）。

而 `dsh-session/lib/types/surface.js` 只把 **五种**放进"模型可见层"：

```js
const SURFACE_EVENT_TYPES = new Set([
  'system/message', 'developer/message', 'user/message', 'assistant/message', 'tool/result',
]);
```

**结论：日志的词汇量 ≠ 模型的词汇量。** 六十四种事件里只有五种会变成 prompt；
其余（思考、工具调用本身、检索、审批、目标、todo）都是记录，按需投影。

这正是我们需要的"思考内容不进上下文"：不是"过滤掉"，而是**只有五类事件天生有资格进上下文**。

### 12.2 surface 是"影子"层，而转录必须绕开它（此前漏掉的关键点）

surface 事件带一个 `surfaceOp` 标记：压缩产生的**替身消息**带标记，原始追加事件不带。
surface 用替身**遮住**被替换的区间。原文注释写得很直白：

> The model-visible surface deliberately shadows replaced ranges, so it is the wrong source
> for a human transcript — a landed replacement would erase conversation the user already saw.
> **Append-origin events are that transcript's durable source material.**

**这是"压缩把用户看过的东西抹掉"这一类 bug 的正解**：给模型看的投影允许有损、允许影子；
给人看的转录只能由**追加来源（append-origin）**的事件构成。我原来的设计只写了"两个投影"，
没规定"转录不得取自 surface"——这条必须补进契约。

### 12.3 未知事件默认拒绝解释，除非它自己声明可忽略

`known-event-types.js` 注释：

> The persistence read path refuses to interpret a log containing a type outside this set
> unless the event carries the envelope's `ignorable` marker … silently skipping a required
> event would reconstruct a wrong session.

即：**兼容性由事件上的 `ignorable` 位决定，不由读取方"猜"**。比"不认识就跳过"安全一个量级：
跳过一条必需的叶子切换事件，重建出来的就是另一个会话。

### 12.4 格式版本是一条相邻迁移链，构造时就校验完整性

`dsh-session-format/lib/index.js`：
- 文件头带 `version`；
- 迁移按"相邻精确一步"注册（`v0→v1 … v3→v4`），构造时遍历 0..current 检查**每一代都有且只有一个 codec、每一段迁移都存在**，缺一段直接抛错，不等读的时候才发现；
- 读到**比本版本更新**的日志：`stored Session uses newer format vN; this build writes vM` —— **报错，不猜**；
- 每代迁移同时负责 `migrateHeader` 与 artifact 的 `restorer`，restorer 返回的版本不是目标版本也算错。

### 12.5 checkpoint：不可逆动作之前先把日志刷盘，失败则不执行

`dsh-session-checkpoint-policy/lib/index.js`：三个语义检查点——发 LLM 请求前、
执行顶层工具前、每个 agent 步骤前，都 `await sessions.flush(session)`；
并且 **checkpoint 失败是 fail-closed**："the downstream adapter or tool body is not invoked"。

对我们直接可用：**发请求之前必须把"用户这条消息"事件刷到磁盘**，刷不进去就不发。
（这比"先发请求、失败了再补"可靠：网络请求不可逆。）

### 12.6 压缩是一次事务，不是一条事件

`dsh-compaction`：
- 事件有四类：`compaction/start` / `compaction/summary` / `compaction/end` / `compaction/prune`；
- 摘要节点是一条**带标记的替身 user message**：
  `{kind:'compact-checkpoint', compactionId, sourceCommandId?}`，靠标记识别与关联，
  **不是旁表**，所以模型和界面都能按普通消息处理它；
- 有 per-session 的压缩锁，防并发；
- 专门导出 `toolPairingBalancedBefore/After`：**不许在 tool_call 与 tool_result 之间下刀**。
  这正是我们之前吃过的 `HTTP 400 Invalid assistant message: content, tool_call…` 的成因；
- 另有 `dsh-compaction-tool-result-pruner`（裁旧工具结果）与 `dsh-compaction-image-offload`
  （把旧图移出上下文），后者被 `MESSAGE_PROJECTION_EVENT_TYPES` 单独列出，说明它的
  "模型可见效果"需要显式纯解释器——不是删数据，是改投影。

### 12.7 我们的事件清单（从 64 类里挑 16 类）

| 我们的事件 | 对应 DSH | 进 surface？ | 说明 |
|---|---|---|---|
| `system/message` | 同名 | ✅ | 系统提示 |
| `user/message` | 同名 | ✅ | 用户消息（含附件引用） |
| `assistant/message` | 同名 | ✅ | 助手消息（正文 + 思考 + 用量 + 引用） |
| `tool/result` | 同名 | ✅ | 搜索/抓网页结果（将来模型内联网时） |
| `developer/message` | 同名 | ✅ | 注入的系统级上下文（手动注入走这里） |
| `turn/start` | 同名 | ❌ | 回合开始（含模型选择、思考等级） |
| `turn/end` | 同名 | ❌ | 回合结束（含 finishReason、usage） |
| `turn/interrupted` | （我们自己的） | ❌ | 进程被杀后的中断补记 |
| `compaction/start` | 同名 | ❌ | 压缩事务开始 |
| `compaction/summary` | 同名 | 替身进 surface | 摘要替身消息（带 compactionId） |
| `compaction/end` | 同名 | ❌ | 事务结束 |
| `compaction/prune` | 同名 | 影响投影 | 裁旧工具结果 |
| `image/offload` | 同名 | 影响投影 | 旧图移出上下文（附图占位符） |
| `leaf/changed` | （surface 的阴影机制） | 影响 surface | 分支切换 |
| `model/selection` | 同名 | ❌ | 模型/参数/思考等级变更 |
| `session/title` | 同名 | ❌ | 标题 |

比 DSH 少掉的：subagent / team / goal / todo / approval / schedule / workspace 那些
——我们没有那些能力，不抄。

### 12.8 明确不抄

- **Cordis 依赖注入与插件热插拔**。我们的规矩是手写组合根，这是既定架构约束。
- **JSONL + worker 线程**。Android 上 SQLite 更合适；但**保留"日志可导出为 JSONL"**：
  导出 = 原文，导入 = 追加，天然可重放。
- **64 种事件的完整词汇**。只抄我们有的 16 种。

---

## 13. 契约定稿（v2-log，冻结候选）

这一节是给实现者的：字段名、取值范围、校验规则、折叠顺序。实现时不得自行改动；
需要改动先改这里。

### 13.1 事件信封

```kotlin
data class StampedEvent(
    val seq: Long,                    // 会话内从 1 开始，连续无空洞
    val eventId: String,              // 全局唯一（日志自身也需要幂等重放）
    val type: String,                 // "user/message" 这种斜杠命名，对齐 DSH
    val schemaVersion: Int,           // 该 type 的 data 形状版本
    val time: Long,                   // 写入时刻（UTC 毫秒）
    val ignorable: Boolean = false,   // 见 C3
    val surfaceOp: SurfaceOp? = null, // 仅 5 种 surface 类型可带，见 C5
    val sourceEventSeqs: List<Long> = emptyList(), // 仅 replace 时必填，见 C6
    val data: JsonObject,
)

sealed interface SurfaceOp {
    data object Append : SurfaceOp
    /** 本事件是一条替身，遮住 [startSeq]..[endSeq] 这一段当前 surface 节点。 */
    data class Replace(val startSeq: Long, val endSeq: Long) : SurfaceOp
}
```

### 13.2 事件表（16 种）

**A. surface 类型（天生有资格进 prompt，必须携带 `surfaceOp`）**

| type | surfaceOp | data 关键字段 |
|---|---|---|
| `system/message` | append / replace(仅自身) | `text` |
| `developer/message` | append | `text`、`kind`（手动注入 / 上下文块标签） |
| `user/message` | append / replace(仅压缩替身) | `messageId`、`parentId`、`parts[]`、`source{kind:'compact-checkpoint', compactionId}` |
| `assistant/message` | append | `messageId`、`parentId`、`parts[]`（正文+思考）、`usage`、`finishReason`、`modelInfo`、`citations[]` |
| `tool/result` | append / replace(仅改 content) | `toolUseId`、`content`、`isError` |

**B. 记录类型（永不进 prompt，不得携带 `surfaceOp` / `sourceEventSeqs`）**

| type | 用途 |
|---|---|
| `turn/start` | 回合开始：`messageId`、`modelInfo`、`thinkingLevel`、`params` |
| `turn/end` | 回合结束：`messageId`、`finishReason`、`usage`、`durationMs` |
| `turn/interrupted` | 进程被杀后的补记：`messageId`、`partialText`、`reason` |
| `compaction/start` | 事务开始：`compactionId`、`strategyId`、`targetFromSeq`、`targetToSeq`、`tokensBefore` |
| `compaction/end` | 事务结束：`compactionId`、`tokensAfter`、`summarySeq` |
| `compaction/prune` | 分组标记：`compactionId`、`rewrittenSeqs[]`（真正的重写是 `tool/result` 的 replace） |
| `image/offload` | 预留：把旧图移出上下文（见 C10，本版不实现投影） |
| `leaf/changed` | `messageId`（切分支 / 采纳兄弟） |
| `model/selection` | `modelRef`、`params`、`thinkingLevel` |
| `session/title` | `title`、`source`（人工 / 自动） |

（`system/message` 的 replace 只允许"用另一条 system/message 覆盖 node 0 自身"，防止压缩吃掉系统提示——照抄 DSH 的 `assertSystemHeadRewrite`。）

### 13.3 surface 折叠规则（按顺序，全部校验，违反即抛）

`foldSurface(events) -> Surface`，逐条事件执行 `plan`（只校验、算计划）然后 `apply`（提交）。
**不许边校验边改状态**——半途失败必须没有任何副作用。

- **C1 连续性**：`event.seq` 必须等于期望值，空洞即错。
- **C2 surface 类型必须带 `surfaceOp`**；非 surface 类型带了 `surfaceOp` 或 `sourceEventSeqs` 即错。
- **C3 未知类型**：不在 16 种里且 `ignorable == false` → 报错（"本版本无法理解这条必需事件"）；
  `ignorable == true` → 保留为不透明白记录，**不改变 history**。
- **C4 replace 只能引用更早的事件**：`startSeq < seq && endSeq < seq`。
- **C5 replace 的范围必须能在当前 surface 上解析**：`startSeq`、`endSeq` 必须是当前 surface
  节点（已被别人遮住的不在其中），且 `startIdx <= endIdx`。
- **C6 `sourceEventSeqs` 必须完整列出被遮住的每一个节点**（漏一个即错），且不得重复、
  不得引用自身或未来。
- **C7 `tool/result` 的 replace 只能改 `content`**：其余字段必须与原节点深度相等；
  且一次只能重写一个节点。（这就是"裁旧工具结果"唯一被允许的形态：**改内容，不改历史**。）
- **C8 system 保护**：node 0 若是 `system/message`，只能被"覆盖它自己的另一条
  `system/message`"替换；压缩范围不许吃它。
- **C9 `assistant/message` 不得携带 `sourceEventSeqs`**（它自带完整内容，不需要引用）。
- **C10 投影注册表**：某事件类型若要"额外产生 prompt 消息"（如未来的 `image/offload`
  产生一个占位消息），必须在 `MESSAGE_PROJECTIONS` 里注册一个纯函数；缺注册即报错，
  不允许静默降级。

**折叠产物**：

```kotlin
data class SurfaceNode(val seq: Long, val type: String, val message: PromptMessage)
data class Surface(val nodes: List<SurfaceNode>)          // 直接就是发给模型的顺序
```

### 13.4 三个投影（名字对齐 DSH）

| 投影 | 输入 | 输出 | 规则 |
|---|---|---|---|
| **surface**（模型可见） | 全部事件 | `Surface` | 只含 5 种 surface 节点 + 替身；被遮住的不出现 |
| **transcript**（人可见） | 全部事件 | `Transcript` | 消息列表**只取 `surfaceOp == Append` 的事件**（append-origin）；压缩显示为"分隔条 + 被压缩条数 + 摘要可展开"，原文永不消失 |
| **snapshot**（UI 可见） | transcript + turn/leaf/model 事件 | `ChatSnapshot` | 加版本号、分支槽位、Overlay、用量汇总 |

**这条是硬约束：transcript 不得从 surface 派生。** 原文（DSH `surface.js`）：
"the model-visible surface deliberately shadows replaced ranges, so it is the wrong source
for a human transcript — a landed replacement would erase conversation the user already saw."

### 13.5 存储

```sql
conversation_events(
  conversation_id TEXT NOT NULL,
  seq             INTEGER NOT NULL,
  event_id        TEXT NOT NULL,
  type            TEXT NOT NULL,
  schema_version  INTEGER NOT NULL,
  time            INTEGER NOT NULL,
  ignorable       INTEGER NOT NULL DEFAULT 0,
  surface_op_json TEXT,            -- 'append' | {op,startSeq,endSeq} | NULL
  source_seqs_json TEXT,           -- "[1,2,3]" | NULL
  data_json       TEXT NOT NULL,
  PRIMARY KEY (conversation_id, seq)
);
CREATE INDEX idx_events_type ON conversation_events(conversation_id, type);

conversation_snapshots(            -- 投影缓存，可丢可重建
  conversation_id TEXT NOT NULL,
  version         INTEGER NOT NULL,
  surface_json    TEXT NOT NULL,
  transcript_json TEXT NOT NULL,
  PRIMARY KEY (conversation_id, version)
);

turn_drafts(                       -- 流式中间态，单行覆盖，不入日志
  message_id      TEXT PRIMARY KEY,
  conversation_id TEXT NOT NULL,
  text            TEXT NOT NULL,
  reasoning       TEXT NOT NULL,
  updated_at      INTEGER NOT NULL
);
```

- 追加 = `INSERT` 一条，事务内完成；**一个用例的多条事件必须在同一个事务里追加**，
  共用一个新版本号。
- 打开会话 = 读最近快照 + 折叠尾部；折叠是 O(尾部事件数)。
- `turn_drafts` 只在流式期间存在；结束/中断时删除。

### 13.6 UI 契约

```kotlin
data class ChatSnapshot(
    val version: Long,                     // = 该会话已追加的事件数
    val conversationId: ConversationId,
    val title: String,
    val transcript: List<TranscriptNode>,  // 只来自 append-origin + 压缩分隔条
    val branches: Map<MessageId, BranchSlot>,
    val overlay: Overlay,
    val totals: UsageTotals,
)

data class Overlay(
    val streaming: StreamingTurn?,         // 正在生成的回合（含已到文本）
    val busy: BusyKind?,                   // 有命令在飞：Send/Regenerate/Edit/Switch/Compact/Cancel
    val searching: Boolean,
    val pendingUserText: String?,          // 压缩确认后待发原文，唯一拥有者
    val compactionProposal: CompactionProposal?,
)

interface ChatGateway {
    fun snapshots(conversationId: ConversationId): Flow<ChatSnapshot>
    suspend fun send(text: String, attachments: List<AttachmentId>, search: Boolean): Outcome<Unit>
    suspend fun regenerate(messageId: MessageId): Outcome<Unit>
    suspend fun edit(messageId: MessageId, text: String): Outcome<Unit>
    suspend fun switchBranch(messageId: MessageId): Outcome<Unit>
    suspend fun compact(proposalId: String): Outcome<Unit>
    suspend fun cancel(): Outcome<Unit>
}
```

UI 只依赖这两样。**`:ui` 不得 import 任何 `*Store` 端口**（现状是最严重的泄漏）。

### 13.7 十二条约不变量（每条都要有一个测试）

- I1 一次用户动作 = 一次提交 = 一个 `version`，中间不可能被观察到。
- I2 `seq` 连续；任何空洞、越界引用、漏列的 `sourceEventSeqs` 都在折叠时抛错。
- I3 surface 之外的事件永不改变 prompt 内容。
- I4 未知且非 ignorable 的事件 → 拒绝打开该会话，而不是猜。
- I5 transcript 只由 append-origin 事件构成；压缩不删除、不替换 transcript 里的任何文本。
- I6 压缩必须在同一事务里成对写 `compaction/start` 与 `compaction/end`；
  中途崩溃则该事务视为未发生（`start` 有、`end` 无 → 回滚语义）。
- I7 压缩永不切断 `assistant(tool_use)` 与对应 `tool/result` 的配对。
- I8 流式中间态只存在于 `turn_drafts`；日志里不存在半个回合。
- I9 发 LLM 请求前，本次请求依赖的事件必须已落盘；落盘失败则不发请求（fail-closed）。
- I10 `Regenerate` 在完成前不移动 leaf；旧答案在整个装配窗口内仍在活动路径上。
- I11 `Overlay` 由应用层唯一拥有；命令结束（成功/失败/取消）必须清空对应字段。
- I12 读日志时若 `schemaVersion` 高于本版本，报错，不降级猜测。

### 13.8 迁移（v3 → v2-log）的验收标准

对每个会话：
1. 按 `(parentId, seq)` 拓扑序把 `messages` 折成事件；
2. 用同一套折叠把事件投影回 `List<Message>`；
3. **与旧表逐字段比对**（id、parent、角色、parts、usage、status、时间戳）+ 会话标题与
   `activeLeafId`（折成最后一条 `leaf/changed`）。
4. 全部会话比对一致才算迁移成功；任何一条不一致就中止并保留旧表。

旧表在切换后保留一个版本，只读，用于回滚。

---

## 14. 实现状态（2026-11-21）

已实现并通过 645 个 JVM 测试（domain 30 / application 166 / persistence 163 / network 138 /
platform 18 / ui 130），真机 v3→v4 迁移已在真实数据上验证（6 会话 51 消息 → 63 事件，
`user_version=4`）。尚未做的一批：`message/pinned`、`message/deleted`、assistant-prefix 续写
（这三项尚未有事件词汇，相关按钮已从界面摘除，避免"点了没反应"）。
