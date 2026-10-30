# z-agent-kernel

> Agent 框架基座 —— 26 个 jar 模块的 SPI + 轻量默认实现：纯 POJO、不带 Spring、不建库表、不起服务。

它解决的是"上层各写一套 agent 抽象"的问题。`z-llm-gateway` / `z-mcp` / `z-skill` / `z-bot` / `z-agent` /
`z-agent-proxy` / `z-opc` 需要的不是同一个 agent 运行时，而是同一套**消息、工具、记忆、provider、
会话**契约。本仓把这些契约连同可复用的默认实现（工具注册表、迭代预算、多 key 凭据池、7 个 LLM
provider、stdio MCP 传输）一次性收口，上层只声明自己用到的那几个坐标。

本仓**不是应用**：全仓无 `src/main/resources`、无 `application*.yml`、无端口，
`@RequestMapping` / `@RestController` / `@SpringBootApplication` 实测 **0 命中**
（复算：`rg --no-ignore -c '@RequestMapping|@RestController|@GetMapping|@PostMapping|@SpringBootApplication' --glob '*.java' .`）。

---

## 📋 基本信息

| 字段 | 值 |
|------|-----|
| **仓库** | `z-agent-kernel`（`packaging=pom` 聚合根 + 26 个 `jar` 子模块） |
| **Maven 坐标** | `io.github.yuku123:z-agent-kernel:${revision}` |
| **当前版本** | `0.2.4`（根 POM `<revision>`，CI-friendly + flatten-maven-plugin 1.7.3，`flattenMode=oss`） |
| **父项目** | `io.github.yuku123:z-boot-parent:1.2.0`（`<relativePath/>` 留空，parent 在 repo1 不在磁盘；2026-10-30 Java17/SB3 火车，实测 200） |
| **父链下发** | `z-boot-parent:1.2.0` 的自身 parent 是 `z-boot:1.0.19`；**1.2.0 已不再 import `z-boot-fleet` / `z-boot-dependencies`**（那两个仓已退役），版本表直接由 parent 自身属性下发：`<z-agent-kernel.version>=0.2.1`、`<java.version>=17`、`<maven.compiler.source/target>=17`、`<spring-boot.version>=3.3.13`、`<jackson.version>=2.18.6`、`<okhttp.version>=4.12.0` |
| **Maven Central** | **`0.2.3` 已发布**：2026-10-20 逐个实测 26 个坐标（聚合根 + 25 模块）的 `maven-metadata.xml`，**每一个**都列出 `0.1.0` / `0.1.1` / `0.2.1` / `0.2.2` / `0.2.3`；**`0.2.0` 从未发布**（全坐标实测 404）。**`0.2.4` 为本地领先版（新增 `asr` 坐标，共 27 个），尚未发布** |
| **默认端口** | 无（库仓，不启动进程） |
| **运行口径** | Java 17（`java.version=17`、`maven.compiler.source/target=17` 由父链下发）· Spring Boot **未使用**（`org.springframework` 命中 **0 个文件**） |
| **最近更新** | 2026-10-23（补齐语音链路契约：新增 `asr` 模块、`Realtime` 加 `endTurn()`/`partial`/`audioFormat`、`Tts` 加 `synthesizeStream`/`openStream`、`types` 加 `AudioFormat`） |

---

## 🎯 能力清单（每条都对应到类）

| 能力 | 所在模块 / 类 | 实测说明 |
|------|---------------|----------|
| 工具注册与生命周期 | `tool`：`ToolRegistry` | owner 所有权校验、`deregisterByToolset` 整组注销、`generation()` 计数器、可用性探测 TTL + 时间窗宽限、`maxResultChars()` 单工具结果上限、`LinkedHashMap` 保序（schema 前缀可逐字节重放） |
| 工具 schema 与入参规范化 | `tool`：`ToolSchemaBuilder` / `ToolArguments` / `ToolDescriptor` / `ToolsetDistributions` | 输出 OpenAI function-calling 格式 Map；`ToolArguments.of(Map)` 把 `"300"` 之类字符串规范化回整型/布尔 |
| 多 provider LLM 调用 | `llm`：`LlmProvider` + `OpenAI/Anthropic/DeepSeek/Qwen/DashScope/Gemini/Ollama Provider` | **7** 个 provider 类，`chat()` 同步 + `streamChat()` SSE 流式；`DeepSeekProvider` / `QwenProvider` / `OllamaProvider` 直接继承 `OpenAIProvider` 复用兼容协议；默认 base URL 分别是 `api.openai.com/v1`、`api.anthropic.com`、`api.deepseek.com/v1`、`dashscope.aliyuncs.com/compatible-mode/v1`、`dashscope.aliyuncs.com/api/v1`、`generativelanguage.googleapis.com`、`localhost:11434/v1`（Ollama） |
| agent 运行契约 | `agent`：`Agent`(`run`/`streamRun`/`reset`) / `AgentRequest` / `AgentResponse.Step` | `AgentResponse` 带 `steps` 轨迹、累计 `TokenUsage`、`finishedReason` |
| 群聊编排（0.2.3 新增） | `agent/group`：`GroupChatRunner` / `Participant` / `GroupMessage` / `GroupChatContext` / `GroupChatResult` / `GroupTranscript` + `ResponsePolicy`（`BroadcastPolicy` / `MentionPolicy`） | `GroupChatRunner(roster, agents, policy).run(trigger)` 按 `ResponsePolicy` 选出应答者，产出 `GroupChatResult` 与 `history()`；`Participant.Kind` 区分 `HUMAN` / agent |
| 预算 / 中断 / 插话 / 委派 | `agent`：`IterationBudget` / `InterruptFlag` / `SteerQueue` / `DelegateSpec` / `AgentContext.newChild` | 迭代与 token 双上限 + grace 次收尾调用；软中断只在迭代/工具边界 `checkpoint()` 生效；子代理预算按 fraction 裁剪、深度 +1 |
| 上下文压缩 | `agent`：`ContextEngine`（含内嵌 `Summarizer`） | 只有契约，压缩策略由上层实现/整体替换 |
| 会话与状态快照 | `state`：`SessionStore` / `InMemorySessionStore` / `State` / `StateManager` / `StateSnapshot` | 内存版 `search` 为朴素 `contains`；带 WAL+FTS5 的生产实现不在本仓 |
| 记忆存储与编排 | `memory`：`MemoryStore` / `InMemoryMemoryStore` / `TextMemoryItem` / `MemoryProvider` | Provider 管时机（turn 前 `prefetch`、turn 后 `sync`、主动 `save`），Store 只管存取 |
| 事件分发 | `event`：`EventBus` + `SyncEventBus` / `AsyncEventBus` | 同步版调用线程内派发；异步版默认 4 线程池，type-prefix 订阅，`close()` 后拒绝 publish |
| 调用链拦截 | `middleware`：`Middleware` / `MiddlewareChain` | `before` 按 order 正序、`after` 逆序，任一 `before` 返回 false 即断链 |
| SKILL.md 装载 | `skill`：`MarkdownSkillLoader` / `SkillDocument` / `SkillLoader` | 零三方依赖 frontmatter 解析，递归扫 `<root>/<category>/<skill>/SKILL.md` 两层 |
| MCP stdio 客户端 | `mcp`：`StdioMcpTransport` / `McpTransport` / `McpClient` | `ProcessBuilder` 拉起 server 子进程，newline-delimited JSON-RPC，`protocolVersion=2024-11-05` 握手，stderr 由守护线程丢弃 |
| 凭据轮换 | `credential`：`CredentialPool` / `CredentialStore` | 池只做"多把 key 按健康度挑选 + 失败计数 + 冷却"；SPI 实现方是 z-mist |
| 默认 LLM agent 实现（0.2.3 新增） | `app`：`LlmAgent` | `implements Agent`，构造时绑 `LlmProvider` + `model` + `systemPrompt` + `List<Tool>`；`of(name, provider, model)` 便捷构造；`DEFAULT_MAX_TOKENS=128000` |
| 语音链路契约（0.2.4 新增） | `asr`：`Asr` / `realtime`：`Realtime` / `tts`：`Tts` / `types`：`AudioFormat` | 流式识别 `AsrSession.sendAudio`/`endTurn`，结果分 `AsrEvent.PARTIAL`/`FINAL`；实时会话 `RealtimeSession.endTurn`/`interrupt`，事件带 `partial` 与 `audioFormat`；语音合成 `synthesizeStream`（一段文本出流）与 `openStream`（增量喂文本，电话级延迟的关键） |
| 多厂商故障切换组合（0.2.4 新增） | `llm`：`FailoverLlmProvider` / `asr`：`FailoverAsr` / `tts`：`FailoverTts` | 链式 provider：**未产出内容才切**（LLM 未吐字 / TTS 未吐音频），已产出则直接报错（避免两家的半句话拼在一起）；连续失败 2 次熔断 30 秒、成功一次即清零；`entry(name, provider, model)` 可按家覆盖 model id |
| 零模型规则应答（0.2.4 新增） | `llm`：`RuleBasedLlmProvider` | **不调任何大模型**：关键词命中即给预设话术，都不命中给 fallback；流式按**中文句读**切块逐块吐（半角+全角标点都切），让语音回路在"没有模型"时也能被完整验证 |
| 音频字节工具（0.2.4 新增） | `types`：`AudioBytes` / `AudioFormat` | 裸 PCM16 → 16bit WAV 容器（头字段逐个对齐）、音频 → `data:{MIME};base64,` data URL（小米 MiMo 要的形状）、纯 base64、UTF-8 文本 |

### 关于凭据（只写名字，不写值）

- 本仓**不读任何环境变量**：`System.getenv` 实测 **0 命中**。API key 一律由调用方从外部（消费仓的
  yml / 环境变量 / 密钥服务）取出后经**构造器参数**传入，例如 `new OpenAIProvider(apiKey, apiBase)`。
- `CredentialStore` 的契约键名写在 javadoc 里，形如 `openai.api_key` / `anthropic.api_key`；
  `get(key)` 返回明文，`list()` 只返回 key 不返回值，`isEncrypted()` 表示是否加密落盘。
- 鉴权头形态（读代码可知，不含任何值）：OpenAI 兼容协议走 `Authorization: Bearer <apiKey>`，
  Anthropic 走 `x-api-key`。**任何 key 值都不得写进本仓文件、README 或镜像层。**

---

## 🏗️ 项目结构

`<modules>` 共 **26** 条，与磁盘目录**逐字一致**（复算：
`diff <(rg -o '<module>[^<]+</module>' pom.xml | sed 's/.*<module>//;s/<.*//' | sort) <(ls -d z-agent-kernel-* | sort)` ⇒ 空）。

```
z-agent-kernel/
├── pom.xml                    # 聚合根：26 个 <module> + 27 条 reactor 自钉 DM + central profile
├── z-agent-kernel-types/      # MessageRole（5 值）· TokenUsage · AudioFormat —— 全仓共享基础类型
├── z-agent-kernel-exception/  # 仅 package-info，0 类型（各模块异常实际是自己的内嵌类）
├── z-agent-kernel-classifier/ # 仅 package-info，0 类型
├── z-agent-kernel-message/    # Msg · ToolCall · MessageType —— 内部统一消息
├── z-agent-kernel-tool/       # Tool · BaseTool · ToolRegistry · ToolSchemaBuilder · ToolArguments
│                              #   · ToolDescriptor · ToolsetDistributions · ToolResult
├── z-agent-kernel-llm/        # LlmProvider · Model(Capability) · ChatCompletions{Request,Response}
│   └── .../provider/, .../support/   # 7 provider + LlmHttp(okhttp+SSE) + LlmException
├── z-agent-kernel-memory/     # MemoryStore · MemoryItem · TextMemoryItem · InMemoryMemoryStore · MemoryProvider
├── z-agent-kernel-agent/      # Agent · AgentRequest/Response · AgentContext · ContextEngine
│                              #   · IterationBudget · InterruptFlag · SteerQueue · DelegateSpec
│                              #   · group/(GroupChatRunner · Participant · GroupMessage · ResponsePolicy …)（0.2.3 新增）
├── z-agent-kernel-event/      # Event · EventEnvelope · EventBus · SyncEventBus · AsyncEventBus
├── z-agent-kernel-middleware/ # Middleware · MiddlewareChain(+MiddlewareAbortedException)
├── z-agent-kernel-state/      # State · StateManager · StateSnapshot · SessionStore · InMemorySessionStore
├── z-agent-kernel-pipeline/   # Pipeline · PipelineStep —— 只有接口，0 实现（多步/多 agent 编排序列）
├── z-agent-kernel-workspace/  # Workspace(read/write/list/exists/exec + ExecResult) —— 只有接口
├── z-agent-kernel-formatter/  # Formatter<T>(Direction 枚举) —— 只有接口
├── z-agent-kernel-embedding/  # Embedding SPI（实现方 z-vector）
├── z-agent-kernel-rag/        # RagPipeline(retrieve/buildContext) + Document（实现方 z-kb）
├── z-agent-kernel-tts/        # Tts + TtsOptions + TtsStream（synthesize/synthesizeStream/openStream）
├── z-agent-kernel-realtime/   # Realtime + RealtimeSession（含 endTurn）+ RealtimeEvent（含 partial/audioFormat）
├── z-agent-kernel-asr/        # Asr + AsrSession + AsrEvent —— 流式语音识别(SSR)契约（0.2.4 新增）
├── z-agent-kernel-skill/      # Skill · SkillLoader · SkillDocument · MarkdownSkillLoader（有实现）
├── z-agent-kernel-mcp/        # McpClient · McpTransport · StdioMcpTransport（有实现：子进程 stdio）
├── z-agent-kernel-credential/ # CredentialStore SPI · CredentialPool（有实现：轮换/冷却）
├── z-agent-kernel-permission/ # PermissionCheck SPI + PermissionDeniedException（实现方 z-ctc）
├── z-agent-kernel-console/    # 仅 package-info，0 类型
├── z-agent-kernel-tui/        # 仅 package-info，0 类型
└── z-agent-kernel-app/        # LlmAgent（implements Agent，0.2.3 新增）+ package-info
```

规模实测：main `113` 个 `.java`（= 26 `package-info` + 30 `interface` + 55 `class` + 2 `enum`）共
**8114** 行；test 侧 **2026-10-23 快照**：`20` 个 `.java` 共 **4497** 行（不含本仓 README 自守卫）。**没有任何模块设 `maven.deploy.skip`**（实测 grep 0 命中），
所以 27 个坐标全部可发布 —— 这点与 z-ctc 的"演示应用永不上传"模型不同。

### 运行时契约：代码真正支持的 vs 只写了接口的

`agent` 模块仍然只给契约（`Agent` / `AgentRequest` / `AgentResponse`），**不含** ReAct / Plan 那类主循环；
但 0.2.3 新增的 `app` 模块给了 `LlmAgent` —— 本仓唯一内建主循环的实现（`drive()` 里做工具调用轮转）。
消费仓里 `pipeline → tool → memory/rag → llm` 的完整串联仍发生在消费仓。
main 代码里的跨模块 `import` 边只有这些（逐文件实测）：

```
agent      -> message(6)  tool(1)  types(2)
app        -> agent(4)  llm(3)  message(3)  tool(2)  types(2)
asr        -> types(2)
llm        -> message(12) tool(1)  types(12)
memory     -> message(3)
message    -> types(1)
realtime   -> types(1)
tts        -> types(1)
```

`pipeline` / `rag` / `state` / `workspace` / `formatter` / `mcp` / `credential` 之间**零跨模块引用**：
它们各自定义契约，不认识彼此。真正的组装证据在仓外——组织内 `implements Agent|Pipeline|LlmProvider|
MemoryStore|SessionStore|McpClient|CredentialStore|Skill|Tool` 的类分布为
`z-bot-core` 35 处、`z-agent` 31 处（`z-agent-engine` 26 / `z-agent-core` 4 / `z-agent-center` 1）、
`z-agent-kernel` 自身 10 处、`z-llm-gateway-core` 2 处、`z-opc` 2 处。

### 哪些"面"是有名字没东西的

1. **4 个模块只有 `package-info.java`**：`classifier` / `console` / `exception` / `tui`。
   其中 `exception` 的自述是"所有 kernel 子模块异常的根"——但本仓没有任何异常基类，异常是各模块的
   内嵌类（`LlmException`、`CredentialStore.CredentialNotFoundException`、
   `PermissionCheck.PermissionDeniedException`、`MiddlewareChain.MiddlewareAbortedException`、
   `InterruptFlag.AgentInterruptedException`）。
   （`app` 已不再是空壳：0.2.3 起有 `LlmAgent` 实现 `Agent`。）
2. **139 支 `@Test` 只落在 12 个模块**：`tool` 28 / `agent` 19 / `llm` 22 / `tts` 18 / `asr` 12 /
   `app` 11 / `mcp` 9 / `types` 8 / `skill` 4 / `realtime` 4 / `credential` 2 / `state` 2；其余 **14 个模块零测试**（`classifier` / `console` / `embedding` / `event` /
   `exception` / `formatter` / `memory` / `message` / `middleware` / `permission` / `pipeline` /
   `rag` / `tui` / `workspace`）—— `llm` 那 22 支测的是**组合层**（故障切换 / 零模型规则应答），
   7 个 provider 实现本身仍一支没测。
   复算（口径排除本仓 README 自守卫 `ReadmeClaimsTest.java`，否则 `tool` 会被自己数进去）：
   `for m in z-agent-kernel-*; do printf "%-14s %s\n" "${m#z-agent-kernel-}" "$(rg --no-ignore -c '@Test' -g '!ReadmeClaimsTest.java' "$m" 2>/dev/null | awk -F: '{s+=$2} END {print s+0}')"; done`
3. **10 个模块在 foundation 内除版本表外没有任何消费者 pom 引用**：
   `app` / `classifier` / `console` / `embedding` / `exception` / `permission` / `pipeline` /
   `rag` / `state` / `tui`。（语音三件 `asr` / `realtime` / `tts` 自 0.2.4 起由仓外 `z-voice-demo` 消费。）
   复算（foundation 根跑，必须同时排掉本仓与 `z-boot/z-boot-parent` 的版本表，否则 26 个全被算成"被引用"）：
   `rg --no-ignore -l --glob 'pom.xml' '<artifactId>z-agent-kernel-<mod></artifactId>' . | grep -v '^\./z-agent-kernel/' | grep -v '^\./z-boot/z-boot-parent/'`

### 谁在用哪一块（实测，排除本仓与 `z-boot-parent` 的版本表）

| 被引用的 kernel 模块 | 引用它的仓 |
|---|---|
| `tool` | z-agent · z-bot · z-llm-gateway · z-opc · z-skill |
| `types` / `message` | z-agent · z-agent-proxy · z-bot · z-llm-gateway · z-skill |
| `llm` | z-agent · z-bot · z-llm-gateway · z-voice-demo |
| `agent` | z-agent · z-agent-proxy · z-bot |
| `memory` / `event` / `middleware` | z-agent · z-bot |
| `credential` / `formatter` / `mcp` / `workspace` | 仅 z-bot |
| `skill` | 仅 z-skill |
| `asr` / `realtime` / `tts` | 仅 z-voice-demo |

消费侧版本仍落后：`z-bot` 钉 `0.2.1`、`z-agent` 与 `z-opc` 钉 `0.2.3`，而 `z-skill` / `z-llm-gateway` 还钉 `0.1.0`；
`z-voice-demo` 钉 `0.2.4`（本地领先版，Central 上尚未发布，demo 需 `mvn install` 后才能引到 `asr` 坐标）。

---

## 🔧 技术栈

| 层级 | 技术（全部来自 pom 实测） |
|------|---------------------------|
| 语言 / 运行时 | Java 17（2026-10-30 火车；口径由 `z-boot-parent:1.2.0` 下发，本仓不重复声明） |
| 构建 | Maven · CI-friendly `${revision}` + `flatten-maven-plugin:1.7.3`（`updatePomFile=true`, `oss`） |
| HTTP / 流式 | `com.squareup.okhttp3:okhttp` + `okhttp-sse` 4.12.0 —— **仅 `llm` 模块使用** |
| JSON | 走 `z-util-parser-json`（仅 `llm` 模块声明）—— **全仓零 Jackson 坐标**：没有任何 pom 声明该坐标，源码零 `com.fasterxml` import；只有根 pom 与 `llm/pom.xml` 的注释里提过版本号 |
| Kotlin 兜底 | 根 DM 把 `kotlin-stdlib{,-common,-jdk7,-jdk8}` 钉 `1.9.21`，拒绝父链 `spring-boot-dependencies:2.7.18` 带来的 1.6.21 静默降级 |
| 测试 | JUnit 4（`org.junit.Test`）为主、JUnit 5（`org.junit.jupiter.api.Test`）并存 —— 根 POM 全局 test 依赖带 `junit-jupiter` + `junit-vintage-engine`，全模块可用；`TemporaryFolder` 规则 **1** 处（`skill/MarkdownSkillLoaderTest`） |
| 未使用 | Spring / Spring Boot、MyBatis、JDBC、日志框架 —— `java.sql|jdbc|DataSource|sqlite` 命中 **0 文件** |

---

## 🚀 快速开始

### 编译与测试

```bash
mvn clean install -DskipTests   # 全 reactor：聚合根 + 26 模块
mvn test                        # 139 支测试（复算口径排除本仓 README 自守卫），纯 JVM
```

解析顺序要求能拿到 `io.github.yuku123:z-boot-parent:1.2.0`（实测 repo1 200）。模块 POM 的
`<dependency>` 一律不写字面版本：兄弟模块由根 DM 的 27 条 `${project.version}` 自钉，第三方由父链下发。

### 作为依赖消费

`0.2.3` 已在 Maven Central（`0.2.4` 是本地领先版、尚未发布），下游**不需要**本机 `mvn install`：

```xml
<dependency>
    <groupId>io.github.yuku123</groupId>
    <artifactId>z-agent-kernel-tool</artifactId>
    <!-- 版本可不写：继承 z-boot-parent 即由 parent 属性下发 0.2.1 -->
</dependency>
```

### 最小可用样例（不需要网络即可跑通的部分）

```java
ToolRegistry registry = new ToolRegistry();
registry.register(myTool, "file");                      // toolset 分组
Map<String, Object> schema = new ToolSchemaBuilder()
        .string("path", "文件路径")
        .integer("limit", "行数上限", false)
        .build();                                       // OpenAI function 格式
MemoryStore<TextMemoryItem> mem = new InMemoryMemoryStore("session-42");
InMemorySessionStore sessions = new InMemorySessionStore();
CredentialPool pool = new CredentialPool(keysFromStore); // 多把 key 轮换 + 失败冷却
LlmProvider provider = new OpenAIProvider(apiKeyFromStore, apiBase);  // key 只经参数进来
```

只有 `z-agent-kernel-llm` 的 provider 与 `z-agent-kernel-mcp` 的 stdio 传输会触达外部：
前者向 provider base URL 发 HTTPS 请求（需要调用方自备 key），后者 `ProcessBuilder` 拉起子进程。

---

## 🔌 对外接口面

本仓**没有 HTTP API**（0 个 Controller，实测见首节）。"接口"是 Java SPI，消费方按需实现：

| SPI | 模块 | 实测方法签名 | javadoc 指定的实现方 |
|-----|------|--------------|----------------------|
| `Agent` | agent | `getName` / `run(AgentRequest)` / `streamRun(req, onStep, onComplete, onError)` / `reset` | z-agent、z-bot |
| `ContextEngine` | agent | `onSessionStart` / `update(inputTokens, outputTokens)` / `shouldCompress` / `compress(history, Summarizer)` | 上层可插拔替换 |
| `LlmProvider` | llm | `name` / `listModels` / `supportsModel` / `chat` / `streamChat` / `providerParams` | 本仓已给 7 个 |
| `Tool` | tool | `getName` / `getDescription` / `getSchema` / `execute(Map)` → `ToolResult` | 业务侧 |
| `MemoryStore` / `MemoryProvider` | memory | `getScope` / `save` / `load(id)`→`Optional` / `search(query, limit)` / `delete` / `listRecentMessages`；Provider 侧 `name`/`prefetch`/`sync`/`save` | 本仓给内存版 |
| `SessionStore` | state | `createSession` / `appendMessage` / `messages` / `updateSession` / `session` / `listSessions` / `search(query, limit)` / `deleteSession` / `close` | z-bot（SQLite+WAL） |
| `State` / `StateManager` | state | `getScope`/`get`/`set`/`delete`/`snapshot()`；`newState(scope)`/`snapshot(State)`/`restore(StateSnapshot)` | 上层 |
| `Pipeline` / `PipelineStep` | pipeline | `execute(PipelineContext)` → `PipelineResult`(含 `StepTrace`)；`PipelineStep.execute` → `StepResult(Status, output, nextStepName)` | 上层编排 |
| `Workspace` | workspace | `getScope` / `read` / `write` / `append` / `exists` / `list` / `delete` / `exec(cmd, args, timeoutMs)` → `ExecResult` | sandbox 侧 |
| `RagPipeline` / `Embedding` | rag / embedding | `retrieve(query, topK)` → `List<Document>` / `buildContext(docs)`；`embed`/`embedBatch`/`dim`/`model` | z-kb / z-vector |
| `McpClient` / `McpTransport` | mcp | `connect` / `listTools` / `call` / `disconnect` / `isConnected`；传输层 `open` / `request(json)` / `close` / `isOpen` | z-mcp；本仓给 stdio 版 |
| `CredentialStore` | credential | `get(key)` / `list()` / `isEncrypted()` | z-mist |
| `PermissionCheck` | permission | `isAllowed(subject, action, target)` / `permissionsOf(subject)` + `PermissionDeniedException` | z-ctc |
| `Skill` / `SkillLoader` | skill | `name` / `description` / `tools()` → `Map<String, ? extends Tool>` / `version` / `trigger`；`load` SKILL.md | z-skill |
| `Formatter` | formatter | `getProtocol` / `getDirection` / `format(T)` / `parse(String)`，`Direction ∈ {REQUEST, RESPONSE, BIDIRECTIONAL}` | 上层；当前实际协议转换写在 llm provider 内部 |
| `Tts` / `Realtime` / `Asr` | tts / realtime / asr | `synthesize(text, voice, options)` → `InputStream` / `defaultVoice` / `synthesizeStream(text, voice, options, onChunk, onError, onComplete)` / `openStream(voice, options, onChunk, onError, onComplete)` → `TtsStream`(`speak`/`finish`/`stop`/`close`)；`open(onEvent, onError)` → `RealtimeSession`(`sendAudio`/`sendText`/`endTurn`/`interrupt`/`close`)；`open(onEvent, onError)` → `AsrSession`(`sendAudio`/`endTurn`/`close`) | 语音服务侧（⚠️ 原写 z-msg 是错的：z-msg 的 Realtime 是消息推送） |
| `FailoverLlmProvider` / `RuleBasedLlmProvider` | llm | `entry(name, provider, model)` / `names()` / `skippedNow()` / `chat(request)` / `streamChat(...)`；`rule(reply, keywords...)` / `keyword(keyword, reply)` / `match(messages)` / `reply(messages)` / `chat(request)` / `streamChat(...)` | 本仓给的**组合层**，厂商协议适配留给实现方 |
| `FailoverAsr` | asr | `entry(name, asr, enabled)` / `names()` / `activeName()` / `open(onEvent, onError)` / `close(session)` / `inputFormat()` | 本仓给的**组合层** |
| `FailoverTts` | tts | `entry(name, tts, enabled)` / `names()` / `defaultVoice()` / `synthesize(text, voice, options)` / `openStream(voice, options, onChunk, onError, onComplete)` / `synthesizeStream(text, voice, options, onChunk, onError, onComplete)` | 本仓给的**组合层** |
| `AudioBytes` | types | `pcm16ToWav(pcm, sampleRate, channels)` / `pcm16ToWav(pcm, format)` / `toDataUrl(audio, mimeType)` / `toBase64(audio)` / `utf8(text)` | 本仓给的字节工具 |
| `EventBus` | event | `publish(envelope)` / `publish(source, event)` / `subscribe(typePattern, handler)` → `Subscription.cancel()` / `close` | 本仓给 sync + async 两版 |

---

## 🧪 测试

```bash
mvn -o test                     # 139 支（复算口径排除本仓 README 自守卫）；JDK 8 编译产物，不起端口
```

- 分布（复算口径排除本仓 README 自守卫 `ReadmeClaimsTest.java`）：`tool` 28 / `agent` 19 / `llm` 22 / `tts` 18 / `asr` 12 / `app` 11 / `mcp` 9 / `types` 8 / `skill` 4 / `realtime` 4 / `credential` 2 / `state` 2，合计 139 支。
- **唯一碰操作系统的是 `mcp` 模块那 9 支**（`StdioMcpTransportTest` 3 支 + `StdioMcpTransportTimeoutTest` 6 支）：
  它们拿 `/bin/cat` 当"回声 MCP server"起子进程（`new StdioMcpTransport(Arrays.asList("/bin/cat"))`）。
  ⇒ 这 9 支在无 POSIX shell 的环境下会红，不是网络红；`llm` 的 7 个 provider 完全不进测试，因此本仓测试**不会发出任何外网请求**。
- **14** 个模块零测试（含 `pipeline` / `rag` / `permission` / `embedding` 等纯接口模块，接口无逻辑可测）；完整名单见上方「哪些"面"是有名字没东西的」第 2 条。

---

## 📦 发布

无 `Dockerfile` / `docker-compose*.yml` / `deploy/` / `k8s/` / `Makefile`（实测目录树为空），
本仓唯一产物是上传 Maven Central 的 jar。发布走根 POM 的 `central` profile：

```bash
mvn -P central deploy           # source jar + javadoc jar + gpg 签名 + central-publishing-maven-plugin 0.8.0
```

该 profile 把 `maven-deploy-plugin` 显式 `skip=true`，实际上传交给
`org.sonatype.central:central-publishing-maven-plugin`（`autoPublish=true`、`waitUntil=uploaded`）。
签名口令、`CENTRAL_USERNAME` / `CENTRAL_TOKEN` 等一律由发布机的环境变量/密钥环提供，**不得出现在仓内文件**。

---

## 🐞 已知未收口的缺陷（读过代码确认在，不是测出来的红）

- `StdioMcpTransport.request()` 的响应配对仍用 `trimmed.contains("\"id\":" + id)`（`trimmed = line.trim()`，`:107`），
  server 回**非紧凑 JSON**（`"id": 7` 带空格）就永不命中 —— 读线程会一直跳过那一行。
  复算：`sed -n '92,137p' z-agent-kernel-mcp/src/main/java/com/zifang/z/agent/kernel/mcp/StdioMcpTransport.java`
- 旧缺陷里的"超时管不住、会永久挂住"那一半**已修复**：读 stdout 已搬到独立 daemon 线程，
  主线程用 `BlockingQueue.poll(剩余时间)` 真等（`:96` / `:125`），server 不回话时 30s 超时抛
  `TimeoutException`（`:127`），不再卡死；该模块也补了 `StdioMcpTransportTimeoutTest`（6 支）覆盖这条。
- 同文件 `:66` 的 `initialize` 握手把 `clientInfo.version` 写死 `"0.2.0"`，而本仓 `<revision>` 已是
  `0.2.4`（Central 上已发布的最新仍是 `0.2.3`）。复算：`grep -n 'clientInfo' .../StdioMcpTransport.java`
- 上述 `clientInfo` 那条不被该模块 9 支测试覆盖。
- 根 POM `<description>` 的"1:1 对齐 agentScope"没有任何机器检查，别当验收结论。
- **语音三契约（`asr` / `realtime` / `tts`）在本仓仍只有契约没有实现**：0.2.4 之前它们是零测试、零消费者的死面，现在契约补齐并有 34 支契约测试（`asr` 12 / `tts` 18 / `realtime` 4），但**仓内仍无实现方** —— 唯一实现是仓外的 `z-voice-demo`。
  ⇒ 下游要接语音，仍需自己实现这三个契约，或直接依赖 demo。
- **`FailoverAsr` 只在"建连阶段"挑一家**：ASR 是**有状态**的（音频要连续喂给同一家），中途换厂商等于放弃已积累的上下文，
  ⇒ 契约**不做"谁先出结果就用谁"的竞速**，只做"按可用性选一家"。
- **`FailoverLlmProvider` / `FailoverTts` 的熔断计数是进程内的**（内存里的 `consecutiveFailures`），多副本部署时各副本各算各的。
  ⇒ 需要全局熔断/全局额度感知的话，得在消费侧（网关层）实现，本仓只保证**单进程内**不反复撞同一家。
- **`FailoverTts` 的"换家"是文本重放，不是音频续接**：换过去后新的一家**从零合成**已喂过的文本 ⇒ 音色/语速与前一家可能不同（但用户一个字的没听到，听不出来）；已经吐过音频则**不换**。
- **`RuleBasedLlmProvider` 的匹配是朴素 `contains`**：没有同义词、没有意图、没有多轮上下文 ⇒ 只能当第一档兜底 / FAQ，不能替代大模型。
- `Asr` 的 `endTurn()` 与"服务端自带 VAD 断句"**没有仲裁机制**：百炼 `paraformer-realtime` 自带 VAD，会在静音后自行断句，与上层按静音时长调用的 `endTurn()` 可能重复触发同一句。  ⇒ 实现方需自行去重（本仓契约不替实现方做这个决定）。
- `Tts.openStream(...)` 的**默认实现是整段缓冲到 `finish()` 再合成**（语义对、首字延迟高）；真增量要实现方覆写它。⇒ 延迟敏感的场景不能只用默认实现。
- `TtsOptions.pitch` 曾经是 `Float` 且默认 `1.0f` —— MiniMax `t2a_v2` 的 `voice_setting.pitch` **只接受整数**，
  传浮点一律被拒成 `2013 invalid params, invalid message format`（2026-10-23 真实链路实测：浮点 `0.0`/`1.0`/`2.0` 全被拒，整型 `0`/`1`/`2` 全通过）。
  ⇒ 0.2.4 已把 `pitch` 改成 **`int` 半音偏移、默认 0**，从**类型上**杜绝实现方把它序列化成浮点。
- 根 POM 注释（`pom.xml:143-146`）仍写"`z-boot-fleet` 里 kernel 槽位钉的是 0.1.1（0.2.0/0.2.1 实测 404）"（该注释已陈旧：fleet 文件夹随 2026-10-08 火车制拓扑退役，槽位并进了 `z-boot-parent`），
  已过期两重：`z-boot-fleet` 仓已退役（版本表并进 `z-boot-parent:1.2.0`，该格现钉 `0.2.1`），
  且 `0.2.1` / `0.2.2` / `0.2.3` 现在都在 repo1 上（`maven-metadata.xml` 实测）。

---

## 文档目录

本项目文档统一收口在 `_doc/` 下（八类固定编号表，桶按需存在）:

- [`_doc/001_arch/`](_doc/001_arch/) — 架构、接口、DB schema、协议、路线图

---

## 📄 License

MIT，见仓库根 [`LICENSE`](LICENSE)（`Copyright (c) 2026 z-opc-foundation`），根 POM `<licenses>` 同声明。

_Maintained by the z-opc-foundation organization._
