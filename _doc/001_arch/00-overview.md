# 项目总览

## 1. 项目简介

z-agent-kernel 是 z-opc-foundation 的 **Agent 框架基座**：把「消息、工具、记忆、模型 provider、会话、事件、中断、权限」这些上层各写一套的契约，收口成 26 个 jar 模块的 SPI + 少量轻量默认实现。

它**不是应用**：全仓 `org.springframework` 命中 0 个文件，无 `src/main/resources`、无 `application*.yml`、无 Controller、无监听端口。消费方（z-agent / z-bot / z-llm / z-skill / z-mcp / z-ctc / z-opc）只声明自己用到的那几个坐标，kernel 不替它们做装配。

**技术栈：** Java 8 · 纯 POJO（零 Spring 注解）· OkHttp 4.12.0 + okhttp-sse（仅 `llm` 模块）· JSON 走 `z-util-parser-json`（**全仓零 Jackson 坐标**、源码零 `com.fasterxml` import）· JUnit 4 与 JUnit 5 并存

## 2. 模块架构

聚合根 `z-agent-kernel`（`packaging=pom`）下挂 26 个 jar 子模块，目录与 `<modules>` 逐字一致：

```
z-agent-kernel/
├── z-agent-kernel-types/        # MessageRole（5 值）· TokenUsage · AudioFormat —— 全仓共享基础类型
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
├── z-agent-kernel-tts/          # Tts + TtsOptions + TtsStream(speak/finish/stop/close) —— 流式合成契约
├── z-agent-kernel-realtime/     # Realtime + RealtimeSession(带 endTurn) + RealtimeEvent(带 partial/audioFormat)
├── z-agent-kernel-asr/          # Asr + AsrSession + AsrEvent(PARTIAL/FINAL) —— 流式识别契约
├── z-agent-kernel-permission/   # PermissionCheck SPI + PermissionDeniedException（实现方 z-ctc）
└── z-agent-kernel-{classifier,console,exception,tui}/
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

### 3.8 语音链路契约（`asr` / `realtime` / `tts`，0.2.4）

- **`asr`（新模块）**：`Asr` 契约 = `open(onEvent, onError)` → `AsrSession`（`sendAudio` / `endTurn` / `close`），结果用 `AsrEvent` 表达，`type` 只有 `PARTIAL`（中间结果）与 `FINAL`（本句定稿）两种，带 `text`；`default inputFormat()` 声明实现方期望的输入音频格式。
- **`realtime`**：`RealtimeEvent` 加 `partial`（中间结果标志）与 `audioFormat`；`RealtimeSession` 加 **`endTurn()`**（"用户说完了"）；`Realtime` 加 `default audioFormat()`。
- **`tts`**：`Tts` 加 `default synthesizeStream(...)`（一段文本出流）与 `default openStream(...)`（增量喂文本 → 电话级延迟的关键）→ 嵌套 `TtsStream`（`speak` / `finish` / `stop` / `close`）；`TtsOptions` 的 `format` 从 `String` 换成 `types` 的 `AudioFormat`。
- **`types`**：`AudioFormat`（encoding / sampleRate / channels / bitsPerSample + `pcm16()` 工厂）是三个语音契约共享的音频格式类型。

**契约要有牙齿的两处**：`RealtimeSession.endTurn()` 刻意做成**抽象方法**（不是 default）⇒ 任何实现方都必须显式表达"一句话结束"；`Tts.openStream` 的默认实现是**整段缓冲到 `finish()` 再合成**（语义对、首字延迟高），要真增量必须覆写。

**仓内零实现**：三个契约的唯一实现方在仓外 —— `z-voice-demo`（百炼 `paraformer-realtime-v2` 真流式 ASR + MiniMax `t2a_v2` WS 增量 TTS + kernel `OpenAIProvider` 走 MiniMax）。

### 3.9 厂商兼容的组合层（0.2.4 新增）

语音链路要接的厂商不是一个：ASR 有百炼流式 / MiniMax 整段 / 小米 MiMo 整段，TTS 有 MiniMax `t2a_v2` WS / 别的厂商，LLM 有本地 Ollama / MiniMax / 智谱 / 百炼。**"今天哪把 key 能用、哪个模型还在"是运行期状态**，不该让上层业务代码写 `if/else` ⇒ 这层兼容收进 kernel：

- **`llm/FailoverLlmProvider`**：provider 链。`entry(name, provider, model)` 按家覆盖 model id；**未产出内容才切**（已产出就报错，避免两家的半句话拼在一起）；**空 chunk 不算"已产出"**（OpenAI 兼容协议第一个 chunk 常只有 role 没有 content，Ollama 连不上时也常这样 —— 把空 chunk 当内容就永远切不了）；连续失败 2 次熔断 30 秒、成功一次清零。
- **`asr/FailoverAsr`**：按 `enabled()` 挑一家（要不要 key / 额度），`open()` 抛异常顺延。⚠️ **只在建连阶段选一家** —— ASR 是有状态的（音频要连续喂给同一家），中途换家等于放弃已积累的上下文，所以不做"谁先出结果用谁"的竞速。
- **`tts/FailoverTts`**：一次性合成失败直接换；**流式**返回一个**可换家的会话句柄** —— 还没吐音频就失败时内部悄悄换到下一家并**把已喂进去的文本重放**过去（用户一个字的没听到，重放无副作用），已经吐过音频则直接报错（换过去两家的声音会拼在一起）。
- **`llm/RuleBasedLlmProvider`**：**零模型**的规则应答器（关键词命中 → 预设话术，都不命中 → fallback）。存在的理由是语音回路的**骨架**（ASR 听 → 策略 → TTS 说）不该被"LLM 额度用完了"卡死；规则/FAQ 本来就是电话与客服的第一档应答。流式按**中文句读**切块逐块吐，让"边生成边播报"在没有模型时也能被完整验证。
- **`types/AudioBytes`**：把「裸 PCM → 各家要的容器」收在一处 —— 16bit WAV 容器（头字段逐个对齐）、`data:{MIME};base64,` data URL（MiMo 要的形状）、纯 base64、UTF-8 文本。厂商协议适配（各家报文细节）仍留在消费侧的实现类里，本仓只提供厂商无关的骨架。

**分工的边界**：kernel 只给**厂商无关的组合骨架 + 音频字节工具**；把百炼/MiniMax/MiMo 的报文细节写进 kernel 会污染"纯契约、零 Spring、只有 llm 带三方依赖"的定位。

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
| `junit:junit` + `junit-jupiter` + `junit-vintage-engine` | 根 POM（test scope，全局下发，子模块不得自行声明 junit 坐标） | 15 个测试类，JUnit 4 旧风格与 JUnit 5 并存 |

**下游消费方：** 组织内各业务仓按需声明坐标；kernel 自身**不含** agent 主循环，`pipeline → tool → memory/rag → llm` 的串联发生在消费仓。

**发布边界：** 全仓没有任何模块设 `maven.deploy.skip`，27 个坐标（聚合根 + 26 模块）全部可发布；根 POM 的 `central` profile 用 `central-publishing-maven-plugin` 0.8.0（`autoPublish=true`）。发布用的 `flatten-maven-plugin` 用 `flattenMode=oss`，把 `<parent>` 剥掉、版本落成字面量，使发布 pom 自包含。

## 5. 技术特点

1. **零 Spring 依赖**：`org.springframework` 在全仓 `.java` 里命中 0 个文件 —— 这是本仓能被任意宿主（含非 Spring 应用）引用的前提，也是它「不带自动装配」的原因。
2. **契约与实现分离得极彻底**：26 个模块里 **9 个是纯接口**（`asr` / `embedding` / `formatter` / `permission` / `pipeline` / `rag` / `realtime` / `tts` / `workspace`），另有 **4 个只有 `package-info.java`**（`classifier` / `console` / `exception` / `tui`）；这 9 个纯接口模块的实现方全部在仓外（`rag`→z-kb、`embedding`→z-vector、`permission`→z-ctc、`credential`→z-mist、`asr`/`tts`/`realtime`→z-voice-demo）。
3. **provider 分两类继承**：`DeepSeek` / `Qwen` / `Ollama` 继承 `OpenAIProvider` 复用 OpenAI 兼容协议，不重复实现 HTTP 细节；`Anthropic` / `Gemini` / `DashScope` 协议不同，各自实现 `LlmProvider`。
4. **只有 `llm` 一个模块带三方依赖**：OkHttp / okhttp-sse 全压在 `llm` 里，其余 25 个模块是纯 JDK，其余消费方不会被拖进 HTTP 栈。
5. **凭据零落盘、零环境变量**：key 只走构造器参数，`System.getenv` 实测 0 命中；`CredentialStore` 的契约键名写在 javadoc（形如 `openai.api_key`），仓内不出现任何 key 值。
6. **多线程安全靠自己保证，不靠框架**：`ToolRegistry` 的 `LinkedHashMap` 保序、`AsyncEventBus` 的 4 线程池、`AssetProviderRegistry` 式的 `CopyOnWriteArrayList` 这类选择都是显式写在实现里的。
7. **测试集中在有逻辑的模块**：15 个测试类（排除 README 自守卫为 14 个）覆盖 `tool`（3）、`agent`（2，含 `GroupChatRunnerTest`）、`mcp`（2）、`asr` / `realtime` / `tts`（各 1 个契约测试类）、`credential`、`skill`、`state`、`app`；`llm` 的 7 个 provider 一支测试都没有。`@Test` 共 89 支，落在 10 个模块；其余 16 个模块零测试。

## 6. 目录结构

本仓无 Spring 装配、无前端资源目录，源码按「一个 SPI 一个模块」组织，Java 包根为 `com.zifang.z.agent.kernel.<模块名>`：

```
z-agent-kernel/
├── pom.xml                              # 聚合根：26 个 <module> + reactor 自钉 DM + central profile
├── z-agent-kernel-types/src/main/java/…/kernel/types/        # MessageRole · TokenUsage · AudioFormat
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
├── z-agent-kernel-{pipeline,workspace,formatter,embedding,rag,tts,realtime,asr,permission}/
│                                                       # 纯接口模块，只有 package-info + 1 个接口
├── z-agent-kernel-{classifier,console,exception,tui}/    # 仅 package-info.java，0 类型
└── _frontend/z-agent-kernel-suit/                    # React 管理台壳（不发布 npm）
```

规模实测（2026-10-23 快照）：main 113 个 `.java` 共 8114 行（= 26 `package-info` + 30 `interface` + 55 `class` + 2 `enum`），test 20 个 `.java` 共 4497 行；`@Test` 共 139 支（落在 12 个模块，其余 14 个模块零测试）。

---

## 7. 新增模块 / 新增能力时的同步清单

本仓 README 由 `z-agent-kernel-tool` 的 `ReadmeClaimsTest`（14 个用例）**逐字校验** ⇒ 新增/删除模块、改测试文件数、改依赖都会让守卫变红。同步清单（每条都是实测踩出来的）：

1. **根 POM**：`<modules>` 加一行 + `<dependencyManagement>` 加一条自钉坐标（`${project.version}`）+ DM 注释里的「自钉 N 个」数字 + **`<revision>`**（新坐标必须换到一个**未发布**的新版本，否则与 Central 上已有版本冲突；本轮 `0.2.3` → `0.2.4`）。
2. **README**：模块数、`<modules>` 条数、结构树要点名每个模块、规模（main 文件数 / 总行数 / 类型分布四项之和）、可发布坐标数、跨模块 `import` 边表（逐格对）、对外接口面表（**每个方法名都要在对应 SPI 源码里真实存在**）、测试分布（`@Test` 支数 / 落在几个模块 / 零测试模块名单）、已知缺陷字面量。
3. **守卫测试自身**：`ReadmeClaimsTest` 里**写死的期望值**也要改（模块数 + 1、有测试的模块数）—— 只改 README 不改守卫，下一轮新增能力会重复红在同一个坑上。
   ⚠ 断言消息里 `expected: <26> but was: <27>` 的方向与直觉相反：`expected` 是**写死的常量**、`was` 才是**从 README 读出来的数** ⇒ 先读守卫源码再改文档，否则会越改越错。
4. **模块内**：每个模块都要有 `package-info.java`；`pom.xml` 里**不写字面版本**（兄弟模块版本由根 DM 自钉）；测试放 `src/test/java`（JUnit 4 或 5 均可 —— 根 POM 全局下发 `junit-jupiter` + `junit-vintage-engine`，**子模块不得自行声明 junit 坐标**）。
5. **Java 8 铁律**：`maven.compiler.source/target=8`（父链 `z-boot-parent:1.1.0` 下发）⇒ 不得用 Java 9+ 语法（`List.of` / `Map.of` / `var` / 文本块）。
6. **零 Spring 铁律**：全仓 `org.springframework` 必须 0 命中；不得有 `src/main/resources`、不得有 `application*.yml`、不得有 `@Controller`。
7. **本文件不在守卫范围内** ⇒ 改模块数 / 规模 / 依赖时**要顺手改它**，否则它会长期落后于 README（本轮之前它就停在「25 个模块 / 6540 行 / 75 支测试 / llm 用 Jackson」）。

