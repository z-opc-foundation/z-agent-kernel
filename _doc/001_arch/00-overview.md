# 项目总览

## 1. 项目简介

z-agent-kernel 是 z-opc-foundation 的 **Agent 框架基座**：把「消息、工具、记忆、模型 provider、会话、事件、中断、权限」这些上层各写一套的契约，收口成 25 个 jar 模块的 SPI + 少量轻量默认实现。

它**不是应用**：全仓 `org.springframework` 命中 0 个文件，无 `src/main/resources`、无 `application*.yml`、无 Controller、无监听端口。消费方（z-agent / z-bot / z-llm / z-skill / z-mcp / z-ctc / z-opc）只声明自己用到的那几个坐标，kernel 不替它们做装配。

**技术栈：** Java 8 · 纯 POJO（零 Spring 注解）· OkHttp 4.12.0 + okhttp-sse（仅 `llm` 模块）· Jackson 2.18.6（仅 `llm` 模块）· JUnit 4

## 2. 模块架构

聚合根 `z-agent-kernel`（`packaging=pom`）下挂 25 个 jar 子模块，目录与 `<modules>` 逐字一致：

```
z-agent-kernel/
├── z-agent-kernel-types/        # MessageRole（5 值）· TokenUsage —— 全仓共享基础类型
├── z-agent-kernel-message/      # Msg · ToolCall · MessageType —— 内部统一消息
├── z-agent-kernel-tool/         # Tool · BaseTool · ToolRegistry · ToolSchemaBuilder · ToolArguments
│                                #   · ToolDescriptor · ToolsetDistributions · ToolResult
├── z-agent-kernel-llm/          # LlmProvider · Model · ChatCompletions{Request,Response} · LlmHttp
│                                #   · provider/ 7 个 provider（OpenAI / Anthropic / Gemini / DashScope
│                                #     直接实现，DeepSeek / Qwen / Ollama 继承 OpenAIProvider）
├── z-agent-kernel-agent/        # Agent · AgentRequest/Response · AgentContext · ContextEngine
│                                #   · IterationBudget · InterruptFlag · SteerQueue · DelegateSpec
│                                #   · 群聊一组（GroupChatRunner / GroupMessage / Participant / 策略枚举）
├── z-agent-kernel-memory/       # MemoryStore · MemoryItem · TextMemoryItem · InMemoryMemoryStore · MemoryProvider
├── z-agent-kernel-event/        # Event · EventEnvelope · EventBus · SyncEventBus · AsyncEventBus
├── z-agent-kernel-middleware/   # Middleware · MiddlewareChain（+ MiddlewareAbortedException）
├── z-agent-kernel-state/        # State · StateManager · StateSnapshot · SessionStore · InMemorySessionStore
├── z-agent-kernel-credential/   # CredentialStore SPI · CredentialPool（多 key 轮换 + 冷却）
├── z-agent-kernel-skill/        # Skill · SkillLoader · SkillDocument · MarkdownSkillLoader
├── z-agent-kernel-mcp/          # McpClient · McpTransport · StdioMcpTransport
├── z-agent-kernel-app/          # LlmAgent（把 LlmProvider 直接包成 Agent 的一层薄壳）
├── z-agent-kernel-pipeline/     # Pipeline · PipelineStep —— 只有接口
├── z-agent-kernel-workspace/    # Workspace（read/write/list/exists/exec）—— 只有接口
├── z-agent-kernel-formatter/    # Formatter<T>（Direction 枚举）—— 只有接口
├── z-agent-kernel-embedding/    # Embedding SPI（实现方 z-vector）
├── z-agent-kernel-rag/          # RagPipeline + Document（实现方 z-kb）
├── z-agent-kernel-tts/          # Tts + TtsOptions（实现方 z-msg）
├── z-agent-kernel-realtime/     # Realtime + RealtimeSession + RealtimeEvent（实现方 z-msg）
├── z-agent-kernel-permission/   # PermissionCheck SPI + PermissionDeniedException（实现方 z-ctc）
└── z-agent-kernel-{app,classifier,console,exception,tui}/
                                 # classifier / console / exception / tui 四个模块仅 package-info，0 类型
```

另有 `_frontend/z-agent-kernel-suit/`：React 19 + antd 6 + Vite 6 的独立管理台壳（`"private": true`，不发布 npm），dev 端口 3032。

## 3. 核心功能

### 3.1 工具注册与生命周期（`tool`）

`ToolRegistry` 负责 owner 所有权校验、按 toolset 整组注销、generation 计数器、可用性探测 TTL 与时间窗宽限、单工具结果长度上限（`maxResultChars()`）；底层用 `LinkedHashMap` 保序，使 schema 前缀可逐字节重放。`ToolSchemaBuilder` 输出 OpenAI function-calling 格式的 Map，`ToolArguments.of(Map)` 把 `"300"` 这类字符串规范化回整型/布尔。

### 3.2 多 provider LLM 调用（`llm`）

`LlmProvider` 契约含 `name` / `listModels` / `supportsModel` / `chat` / `streamChat` / `providerParams`。本仓给 7 个实现：`OpenAIProvider`、`AnthropicProvider`、`GeminiProvider`、`DashScopeProvider` 四个直接实现 `LlmProvider`；`DeepSeekProvider`、`QwenProvider`、`OllamaProvider` 三个继承 `OpenAIProvider` 复用 OpenAI 兼容协议。传输层 `LlmHttp` 用 OkHttp + okhttp-sse 实现同步与 SSE 流式。

### 3.3 预算、中断、插话与委派（`agent`）

`IterationBudget` 给出迭代与 token 双上限并带 grace 次收尾调用；`InterruptFlag` 的软中断只在迭代/工具边界 `checkpoint()` 生效；`SteerQueue` 承接运行中插话；`DelegateSpec` 支持把任务委派给子代理，子代理预算按 fraction 裁剪、深度 +1（`AgentContext.newChild`）。`ContextEngine` 只定义压缩契约（`onSessionStart` / `update` / `shouldCompress` / `compress`），压缩策略由上层实现。

### 3.4 群聊编排（`agent` 的群聊一组）

`GroupChatRunner` + `GroupChatContext` + `GroupChatResult` + `GroupMessage` + `Participant` + `GroupTranscript` 提供多参与者会话骨架，配合三组策略枚举：`BroadcastPolicy`（广播范围）、`MentionPolicy`（点名触发）、`ResponsePolicy`（谁该应答）。

### 3.5 记忆、会话与事件

- `memory`：`MemoryStore`（存取）与 `MemoryProvider`（时机：turn 前 `prefetch`、turn 后 `sync`、主动 `save`）分离，本仓给内存版 `InMemoryMemoryStore`。
- `state`：`SessionStore` / `State` / `StateManager` / `StateSnapshot`，内存版 `InMemorySessionStore` 的 `search` 为朴素 `contains`；带 WAL+FTS5 的生产实现不在本仓。
- `event`：`EventBus` 有同步版（调用线程内派发）与异步版（默认 4 线程池，type-prefix 订阅，`close()` 后拒绝 publish）两版。

### 3.6 SKILL 装载与 MCP stdio 客户端

- `MarkdownSkillLoader` 零三方依赖地解析 frontmatter，递归扫 `<root>/<category>/<skill>/SKILL.md` 两层，产出 `SkillDocument`。
- `StdioMcpTransport` 用 `ProcessBuilder` 拉起 MCP server 子进程，newline-delimited JSON-RPC，握手 `protocolVersion` 写死 `2024-11-05`，stderr 由守护线程丢弃。

### 3.7 凭据轮换

`CredentialPool` 只做「多把 key 按健康度挑选 + 失败计数 + 冷却」；`CredentialStore` 是 SPI（`get` / `list` / `isEncrypted`），实现方在 z-mist。**本仓不读任何环境变量**（`System.getenv` 实测 0 命中），API key 一律由调用方经构造器参数传入。

## 4. 对外集成 / 上下游

**本仓无对外 HTTP 边界。** 无 Controller、无监听端口、无 `application*.yml`，「接口」全部是 Java SPI。

**唯一会触达外部的两处：**

- `llm` 模块的 provider：向各自 base URL 发 HTTPS / SSE 请求，需要调用方自备 key。
- `mcp` 模块的 `StdioMcpTransport`：用 `ProcessBuilder` 拉起本机 MCP server 子进程。

**上游依赖（取自各子模块 `pom.xml`）：**

| 依赖 | 出现在 | 作用 |
|---|---|---|
| `com.squareup.okhttp3:okhttp` + `okhttp-sse` 4.12.0 | `llm` | HTTP 与 SSE 传输 |
| `jackson-databind` / `jackson-core` 2.18.6 | `llm` | JSON 编解码 |
| `z-util-parser-json` | `llm` | JSON 工具（模块内依赖） |
| `kotlin-stdlib{,-common,-jdk7,-jdk8}` 1.9.21 | 根 POM `dependencyManagement` | 拒绝父链经 spring-boot-dependencies 静默降级到 1.6.21 |
| `junit:junit` | 根 POM（test scope） | 全部 11 个测试类用 JUnit 4 |

**下游消费方：** 组织内各业务仓按需声明坐标；kernel 自身**不含** agent 主循环，`pipeline → tool → memory/rag → llm` 的串联发生在消费仓。

**发布边界：** 全仓没有任何模块设 `maven.deploy.skip`，26 个坐标（聚合根 + 25 模块）全部可发布；根 POM 的 `central` profile 用 `central-publishing-maven-plugin` 0.8.0（`autoPublish=true`）。发布用的 `flatten-maven-plugin` 用 `flattenMode=oss`，把 `<parent>` 剥掉、版本落成字面量，使发布 pom 自包含。

## 5. 技术特点

1. **零 Spring 依赖**：`org.springframework` 在全仓 `.java` 里命中 0 个文件 —— 这是本仓能被任意宿主（含非 Spring 应用）引用的前提，也是它「不带自动装配」的原因。
2. **契约与实现分离得极彻底**：19 个模块里 13 个是纯接口（`pipeline` / `workspace` / `formatter` / `embedding` / `rag` / `tts` / `realtime` / `permission` 等），实现方全部在仓外（z-kb / z-vector / z-msg / z-ctc / z-mist）。
3. **provider 分两类继承**：`DeepSeek` / `Qwen` / `Ollama` 继承 `OpenAIProvider` 复用 OpenAI 兼容协议，不重复实现 HTTP 细节；`Anthropic` / `Gemini` / `DashScope` 协议不同，各自实现 `LlmProvider`。
4. **只有 `llm` 一个模块带三方依赖**：OkHttp / Jackson / okhttp-sse / okio 全压在 `llm` 里，其余 24 个模块是纯 JDK，其余消费方不会被拖进 HTTP 栈。
5. **凭据零落盘、零环境变量**：key 只走构造器参数，`System.getenv` 实测 0 命中；`CredentialStore` 的契约键名写在 javadoc（形如 `openai.api_key`），仓内不出现任何 key 值。
6. **多线程安全靠自己保证，不靠框架**：`ToolRegistry` 的 `LinkedHashMap` 保序、`AsyncEventBus` 的 4 线程池、`AssetProviderRegistry` 式的 `CopyOnWriteArrayList` 这类选择都是显式写在实现里的。
7. **测试集中在有逻辑的模块**：11 个测试类覆盖 `tool`（3）、`agent`（2，含 `GroupChatRunnerTest`）、`mcp`（2）、`credential`、`skill`、`state`、`app`；`llm` 的 7 个 provider 一支测试都没有。

## 6. 目录结构

本仓无 Spring 装配、无前端资源目录，源码按「一个 SPI 一个模块」组织，Java 包根为 `com.zifang.z.agent.kernel.<模块名>`：

```
z-agent-kernel/
├── pom.xml                              # 聚合根：25 个 <module> + reactor 自钉 DM + central profile
├── z-agent-kernel-types/src/main/java/…/kernel/types/        # MessageRole · TokenUsage
├── z-agent-kernel-message/src/main/java/…/kernel/message/    # Msg · ToolCall · MessageType
├── z-agent-kernel-tool/src/main/java/…/kernel/tool/          # Tool · ToolRegistry · ToolSchemaBuilder …
├── z-agent-kernel-llm/src/main/java/…/kernel/llm/            # LlmProvider · Model · LlmHttp · provider/
├── z-agent-kernel-agent/src/main/java/…/kernel/agent/        # Agent · AgentRequest/Response · 预算/中断/群聊
├── z-agent-kernel-memory/src/main/java/…/kernel/memory/      # MemoryStore · MemoryProvider · 内存版
├── z-agent-kernel-event/src/main/java/…/kernel/event/        # EventBus · Sync/Async 两版
├── z-agent-kernel-middleware/src/main/java/…/kernel/middleware/
├── z-agent-kernel-state/src/main/java/…/kernel/state/        # SessionStore · State · StateManager
├── z-agent-kernel-credential/src/main/java/…/kernel/credential/
├── z-agent-kernel-skill/src/main/java/…/kernel/skill/        # MarkdownSkillLoader · SkillDocument
├── z-agent-kernel-mcp/src/main/java/…/kernel/mcp/            # McpClient · StdioMcpTransport
├── z-agent-kernel-app/src/main/java/…/kernel/app/            # LlmAgent
├── z-agent-kernel-{pipeline,workspace,formatter,embedding,rag,tts,realtime,permission}/
│                                                       # 纯接口模块，只有 package-info + 1 个接口
├── z-agent-kernel-{classifier,console,exception,tui}/    # 仅 package-info.java，0 类型
└── _frontend/z-agent-kernel-suit/                    # React 管理台壳（不发布 npm）
```

规模实测：main 105 个 `.java` 共 6540 行，test 11 个 `.java`；`@Test` 共 75 支。
