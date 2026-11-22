# DeepChat Mobile

Android 上本地优先的 AI 聊天客户端。数据全部存在本机，无账号、无云同步；模型通过多 provider 适配层接入（OpenAI 兼容 / Anthropic / Gemini）。

<img src="docs/icon.png" width="120" alt="DeepChat Mobile 图标" />

## 功能

- **流式对话**：逐字输出、可随时停止、`length` 截断后可继续生成
- **消息树**：重新生成产生兄弟分支、`‹ n/m ›` 切换、编辑旧消息自动分叉
- **推理等级**：Off / Low / High / Max，气泡内拖动、松手吸附，高档位轨道走紫色星云与星尘
- **预设**：系统提示词、温度、最大 token、思考档位、上下文策略
- **手动注入上下文**：常驻 / 仅本次请求
- **上下文压缩**：超预算时给出提案，确认后才生效，可撤销
- **上下文调试面板**：查看实际发出去的 block、token 占用与原始 prompt
- **附件**：图片视觉输入、文本文件；导出可选是否内嵌
- **联网搜索**：应用内执行（Tavily / Brave / Serper），结果作为临时上下文注入
- **会话管理**：全文搜索（FTS4）、文件夹、标签、置顶
- **生成队列**：同会话串行、全局并发上限、可暂停
- **导入导出**：JSON / Markdown，附件可选
- **费用与用量**：每条消息记录 token 与估算费用

## 架构

领域层 / 应用层 / 适配层 + 组装根，依赖方向始终向内：

```
:app（组装根）──▶ :ui · :adapter-* · :platform
                        └──────▶ :application ──▶ :domain
```

| 模块 | 职责 |
| --- | --- |
| `:domain` | 实体、值对象、消息树、规则。纯 Kotlin，零依赖 |
| `:application` | 端口、用例、上下文组装、压缩策略、生成队列 |
| `:adapter-persistence` | 手写 SQLite（含 FTS4）、DAO、迁移 |
| `:adapter-network` | 三种 provider 协议、SSE 解析、搜索适配器 |
| `:adapter-platform` | SAF、Keystore、导出导入、前台服务、连接状态 |
| `:ui` | 设计系统、动效 token、全部 Compose 界面 |
| `:app` | 组装根 `AppContainer`、导航、Application |

设计说明见 [docs/architecture.md](docs/architecture.md)，UI 规格见 [docs/ui-spec-v2.md](docs/ui-spec-v2.md)。

## 构建

需要 JDK 17+、Android SDK Platform 37.1、AGP 9.5.1、Gradle 9.8.2。

```bash
gradle :app:assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
gradle test                  # 全部 JVM 单测（376 个）
gradle :app:assembleRelease  # 需要自行配置签名
```

> 仓库未附带 Gradle wrapper：开发机的网络无法访问 `services.gradle.org` 下载发行包，请使用本地已安装的 Gradle 9.8.2。

## 首次使用

1. 「设置 → 模型服务」新增 provider：协议、baseUrl、API Key、默认模型
2. 「测试连接」确认可用
3. 可选：新增预设并设为默认
4. 回到会话开始聊天

联网搜索需要在「设置 → 联网搜索」里填一家 Key。

## 已知限制

- 工具调用只保存、不执行（v1 范围）
- 无自动重试与退避，限流只透传 `retryAfterSeconds`
- 费用来自离线价格表，是估算值
- 前台服务已实现并声明，但生成仍在应用进程内执行
- `ThinkingLevel.OFF` 对部分 provider 只是"不传参数"，模型可能仍按默认档思考

## 许可

个人项目，未附许可协议；如需引用请先联系作者。

## 构建产物说明（重要）

日常使用请装 **release** 包。同一份代码的实测冷启动（`am start -W`）：

| 构建 | 冷启动 | 热启动 |
|---|---|---|
| `assembleRelease` | 323–352 ms | 106–159 ms |
| `assembleDebug` | 2119–2293 ms | — |

debug 包之所以慢，是 Compose 在调试模式下逐帧校验与 JIT 的结果，不是业务代码的问题。
设置页「关于」里的版本号在 debug 包上会显示 `0.2.0 · debug`，可据此确认手上是哪一种。
