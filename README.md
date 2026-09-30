# z-agent-kernel

> Agent 框架基座 —— 25 个 jar 模块的 SPI + 轻量默认实现：纯 POJO、不带 Spring、不建库表、不起服务。

它解决的是"上层各写一套 agent 抽象"的问题。`z-llm` / `z-mcp` / `z-skill` / `z-bot` / `z-agent` /
`z-agent-proxy` / `z-opc` 需要的不是同一个 agent 运行时，而是同一套**消息、工具、记忆、provider、
会话**契约。本仓把这些契约连同可复用的默认实现（工具注册表、迭代预算、多 key 凭据池、6 个 LLM
provider、stdio MCP 传输）一次性收口，上层只声明自己用到的那几个坐标。

本仓**不是应用**：全仓无 `src/main/resources`、无 `application*.yml`、无端口，
`@RequestMapping` / `@RestController` / `@SpringBootApplication` 实测 **0 命中**
（复算：`rg --no-ignore -c '@RequestMapping|@RestController|@GetMapping|@PostMapping' --glob '*.java' .`）。

---

## 📋 基本信息

| 字段 | 值 |
|------|-----|
| **仓库** | `z-agent-kernel`（`packaging=pom` 聚合根 + 25 个 `jar` 子模块） |
| **Maven 坐标** | `io.github.yuku123:z-agent-kernel:${revision}` |
| **当前版本** | `0.2.1`（根 POM `<revision>`，CI-friendly + flatten-maven-plugin 1.7.3，`flattenMode=oss`） |
| **父项目** | `io.github.yuku123:z-boot-parent:1.0.21`（`<relativePath/>` 留空，parent 在 repo1 不在磁盘；实测 200） |
| **父链下发** | `z-boot-parent:1.0.21` → `<parent>` 地板 `z-boot-dependencies:1.0.20`（okhttp 4.12.0 / jackson 2.18.6 / okio 3.9.0 / spring-boot 2.7.18）+ import `z-boot-fleet:1.0.1`，该 fleet 里 `<z-agent-kernel.version>` 已是 **0.2.1**（本地与 repo1 双向核对一致） |
| **Maven Central** | **`0.2.1` 已发布**：26 个坐标（聚合根 + 25 模块）`.pom` 逐个实测 **200**，`-sources.jar` 200；`maven-metadata.xml` 列 `0.1.0 / 0.1.1 / 0.2.1`，`lastUpdated=20260929000844`。**`0.2.0` 从未发布**（全坐标实测 404） |
| **默认端口** | 无（库仓，不启动进程） |
| **运行口径** | Java 8（`java.version=1.8`、`maven.compiler.source/target=8` 由父链下发）· Spring Boot **未使用**（`org.springframework` 命中 **0 个文件**） |
| **最近更新** | 2026-09-30 |

---

## 🎯 能力清单（每条都对应到类）

| 能力 | 所在模块 / 类 | 实测说明 |
|------|---------------|----------|
| 工具注册与生命周期 | `tool`：`ToolRegistry` | owner 所有权校验、`deregisterByToolset` 整组注销、`generation()` 计数器、可用性探测 TTL + 时间窗宽限、`maxResultChars()` 单工具结果上限、`LinkedHashMap` 保序（schema 前缀可逐字节重放） |
| 工具 schema 与入参规范化 | `tool`：`ToolSchemaBuilder` / `ToolArguments` / `ToolDescriptor` / `ToolsetDistributions` | 输出 OpenAI function-calling 格式 Map；`ToolArguments.of(Map)` 把 `"300"` 之类字符串规范化回整型/布尔 |
| 多 provider LLM 调用 | `llm`：`LlmProvider` + `OpenAI/Anthropic/DeepSeek/Qwen/DashScope/Gemini Provider` | 6 个 provider 类，`chat()` 同步 + `streamChat()` SSE 流式；`DeepSeekProvider` / `QwenProvider` 直接继承 `OpenAIProvider` 复用兼容协议；默认 base URL 分别是 `api.openai.com/v1`、`api.anthropic.com`、`api.deepseek.com/v1`、`dashscope.aliyuncs.com/compatible-mode/v1`、`dashscope.aliyuncs.com/api/v1`、`generativelanguage.googleapis.com` |
| agent 运行契约 | `agent`：`Agent`(`run`/`streamRun`/`reset`) / `AgentRequest` / `AgentResponse.Step` | `AgentResponse` 带 `steps` 轨迹、累计 `TokenUsage`、`finishedReason` |
| 预算 / 中断 / 插话 / 委派 | `agent`：`IterationBudget` / `InterruptFlag` / `SteerQueue` / `DelegateSpec` / `AgentContext.newChild` | 迭代与 token 双上限 + grace 次收尾调用；软中断只在迭代/工具边界 `checkpoint()` 生效；子代理预算按 fraction 裁剪、深度 +1 |
| 上下文压缩 | `agent`：`ContextEngine`（含内嵌 `Summarizer`） | 只有契约，压缩策略由上层实现/整体替换 |
| 会话与状态快照 | `state`：`SessionStore` / `InMemorySessionStore` / `State` / `StateManager` / `StateSnapshot` | 内存版 `search` 为朴素 `contains`；带 WAL+FTS5 的生产实现不在本仓 |
| 记忆存储与编排 | `memory`：`MemoryStore` / `InMemoryMemoryStore` / `TextMemoryItem` / `MemoryProvider` | Provider 管时机（turn 前 `prefetch`、turn 后 `sync`、主动 `save`），Store 只管存取 |
| 事件分发 | `event`：`EventBus` + `SyncEventBus` / `AsyncEventBus` | 同步版调用线程内派发；异步版默认 4 线程池，type-prefix 订阅，`close()` 后拒绝 publish |
| 调用链拦截 | `middleware`：`Middleware` / `MiddlewareChain` | `before` 按 order 正序、`after` 逆序，任一 `before` 返回 false 即断链 |
| SKILL.md 装载 | `skill`：`MarkdownSkillLoader` / `SkillDocument` / `SkillLoader` | 零三方依赖 frontmatter 解析，递归扫 `<root>/<category>/<skill>/SKILL.md` 两层 |
| MCP stdio 客户端 | `mcp`：`StdioMcpTransport` / `McpTransport` / `McpClient` | `ProcessBuilder` 拉起 server 子进程，newline-delimited JSON-RPC，`protocolVersion=2024-11-05` 握手，stderr 由守护线程丢弃 |
| 凭据轮换 | `credential`：`CredentialPool` / `CredentialStore` | 池只做"多把 key 按健康度挑选 + 失败计数 + 冷却"；SPI 实现方是 z-mist |

### 关于凭据（只写名字，不写值）

- 本仓**不读任何环境变量**：`System.getenv` 实测 **0 命中**。API key 一律由调用方从外部（消费仓的
  yml / 环境变量 / 密钥服务）取出后经**构造器参数**传入，例如 `new OpenAIProvider(apiKey, apiBase)`。
- `CredentialStore` 的契约键名写在 javadoc 里，形如 `openai.api_key` / `anthropic.api_key`；
  `get(key)` 返回明文，`list()` 只返回 key 不返回值，`isEncrypted()` 表示是否加密落盘。
- 鉴权头形态（读代码可知，不含任何值）：OpenAI 兼容协议走 `Authorization: Bearer <apiKey>`，
  Anthropic 走 `x-api-key`。**任何 key 值都不得写进本仓文件、README 或镜像层。**

---

## 🏗️ 项目结构

`<modules>` 共 **25** 条，与磁盘目录**逐字一致**（复算：
`diff <(rg -o '<module>[^<]+</module>' pom.xml | sed 's/.*<module>//;s/<.*//' | sort) <(ls -d z-agent-kernel-* | sort)` ⇒ 空）。

```
z-agent-kernel/
├── pom.xml                    # 聚合根：25 个 <module> + 26 条 reactor 自钉 DM + central profile
├── z-agent-kernel-types/      # MessageRole（5 值）· TokenUsage —— 全仓共享基础类型
├── z-agent-kernel-exception/  # 仅 package-info，0 类型（各模块异常实际是自己的内嵌类）
├── z-agent-kernel-classifier/ # 仅 package-info，0 类型
├── z-agent-kernel-message/    # Msg · ToolCall · MessageType —— 内部统一消息
├── z-agent-kernel-tool/       # Tool · BaseTool · ToolRegistry · ToolSchemaBuilder · ToolArguments
│                              #   · ToolDescriptor · ToolsetDistributions · ToolResult
├── z-agent-kernel-llm/        # LlmProvider · Model(Capability) · ChatCompletions{Request,Response}
│   └── .../provider/, .../support/   # 6 provider + LlmHttp(okhttp+SSE) + LlmException
├── z-agent-kernel-memory/     # MemoryStore · MemoryItem · TextMemoryItem · InMemoryMemoryStore · MemoryProvider
├── z-agent-kernel-agent/      # Agent · AgentRequest/Response · AgentContext · ContextEngine
│                              #   · IterationBudget · InterruptFlag · SteerQueue · DelegateSpec
├── z-agent-kernel-event/      # Event · EventEnvelope · EventBus · SyncEventBus · AsyncEventBus
├── z-agent-kernel-middleware/ # Middleware · MiddlewareChain(+MiddlewareAbortedException)
├── z-agent-kernel-state/      # State · StateManager · StateSnapshot · SessionStore · InMemorySessionStore
├── z-agent-kernel-pipeline/   # Pipeline · PipelineStep —— 只有接口，0 实现（多步/多 agent 编排序列）
├── z-agent-kernel-workspace/  # Workspace(read/write/list/exists/exec + ExecResult) —— 只有接口
├── z-agent-kernel-formatter/  # Formatter<T>(Direction 枚举) —— 只有接口
├── z-agent-kernel-embedding/  # Embedding SPI（实现方 z-vector）
├── z-agent-kernel-rag/        # RagPipeline(retrieve/buildContext) + Document（实现方 z-kb）
├── z-agent-kernel-tts/        # Tts + TtsOptions（实现方 z-msg）
├── z-agent-kernel-realtime/   # Realtime + RealtimeSession + RealtimeEvent（实现方 z-msg）
├── z-agent-kernel-skill/      # Skill · SkillLoader · SkillDocument · MarkdownSkillLoader（有实现）
├── z-agent-kernel-mcp/        # McpClient · McpTransport · StdioMcpTransport（有实现：子进程 stdio）
├── z-agent-kernel-credential/ # CredentialStore SPI · CredentialPool（有实现：轮换/冷却）
├── z-agent-kernel-permission/ # PermissionCheck SPI + PermissionDeniedException（实现方 z-ctc）
├── z-agent-kernel-console/    # 仅 package-info，0 类型
├── z-agent-kernel-tui/        # 仅 package-info，0 类型
└── z-agent-kernel-app/        # 仅 package-info，0 类型
```

规模实测：main `94` 个 `.java`（= 25 `package-info` + 28 `interface` + 39 `class` + 2 `enum`）共
5524 行；test `8` 个 `.java` 共 996 行。**没有任何模块设 `maven.deploy.skip`**（实测 grep 0 命中），
所以 26 个坐标全部可发布 —— 这点与 z-ctc 的"演示应用永不上传"模型不同。

### 运行时契约：代码真正支持的 vs 只写了接口的

本仓**不含** agent 主循环实现，`pipeline → tool → memory/rag → llm` 的串联发生在消费仓。
main 代码里的跨模块 `import` 边只有这些（逐文件实测）：

```
agent      -> message(3)  tool(1)  types(1)
llm        -> message(11) tool(1)  types(10)
memory     -> message(3)
message    -> types(1)
```

`pipeline` / `rag` / `state` / `workspace` / `formatter` / `mcp` / `credential` 之间**零跨模块引用**：
它们各自定义契约，不认识彼此。真正的组装证据在仓外——组织内 `implements Agent|Pipeline|LlmProvider|
MemoryStore|SessionStore|McpClient|CredentialStore|Skill|Tool` 的类分布为
`z-bot-core` 35 处、`z-agent` 21 处、`z-middleware-integration-test` 5 处、`z-agent-core` 4 处、
`z-llm-core` 2 处。

### 哪些"面"是有名字没东西的

1. **5 个模块只有 `package-info.java`**：`app` / `classifier` / `console` / `exception` / `tui`。
   其中 `exception` 的自述是"所有 kernel 子模块异常的根"——但本仓没有任何异常基类，异常是各模块的
   内嵌类（`LlmException`、`CredentialStore.CredentialNotFoundException`、
   `PermissionCheck.PermissionDeniedException`、`MiddlewareChain.MiddlewareAbortedException`、
   `InterruptFlag.AgentInterruptedException`）。
2. **47 支 `@Test` 只落在 6 个模块**：`tool` 28 / `agent` 8 / `skill` 4 / `mcp` 3 / `credential` 2 /
   `state` 2；其余 **19 个模块零测试**，包括 13 文件的 `llm`（6 个 provider 一支没测）。
   复算：`for m in z-agent-kernel-*; do printf "%-14s %s\n" "${m#z-agent-kernel-}" "$(rg --no-ignore -c '@Test' "$m" 2>/dev/null | awk -F: '{s+=$2} END {print s+0}')"; done`
3. **7 个模块在 foundation 内除 fleet 版本表外没有任何消费者 pom 引用**：
   `app` / `classifier` / `console` / `exception` / `pipeline` / `state` / `tui`。
   复算（foundation 根跑，必须同时排掉本仓与 `z-boot/z-boot-fleet`，否则 25 个全被算成"被引用"）：
   `rg --no-ignore -l --glob 'pom.xml' '<artifactId>z-agent-kernel-<mod></artifactId>' . | grep -v '^\./z-agent-kernel/' | grep -v '^\./z-boot/z-boot-fleet/'`

### 谁在用哪一块（实测，排除本仓与 `z-boot-fleet` 的版本表）

| 被引用的 kernel 模块 | 引用它的仓 |
|---|---|
| `tool` | z-agent · z-bot · z-llm · z-opc · z-skill |
| `types` / `message` | z-agent · z-agent-proxy · z-bot · z-llm · z-skill |
| `llm` | z-agent · z-bot · z-llm |
| `agent` | z-agent · z-agent-proxy · z-bot |
| `memory` / `event` / `middleware` / `formatter` / `workspace` | z-agent 与/或 z-bot |
| `mcp` / `credential` | z-bot · z-opc |
| `skill` | z-opc · z-skill |
| `embedding` / `rag` / `tts` / `realtime` / `permission` | 仅 z-opc |

消费侧版本仍落后：`z-bot` 已钉 `0.2.1`，而 `z-agent` / `z-skill` / `z-llm` 钉 `0.1.0`、`z-opc` 钉 `0.1.1`。

---

## 🔧 技术栈

| 层级 | 技术（全部来自 pom 实测） |
|------|---------------------------|
| 语言 / 运行时 | Java 8（口径由 `z-boot-parent:1.0.21` 下发，本仓不重复声明） |
| 构建 | Maven · CI-friendly `${revision}` + `flatten-maven-plugin:1.7.3`（`updatePomFile=true`, `oss`） |
| HTTP / 流式 | `com.squareup.okhttp3:okhttp` + `okhttp-sse` 4.12.0 —— **仅 `llm` 模块使用** |
| JSON | `jackson-databind` / `jackson-core` 2.18.6 —— **仅 `llm` 模块使用** |
| Kotlin 兜底 | 根 DM 把 `kotlin-stdlib{,-common,-jdk7,-jdk8}` 钉 `1.9.21`，拒绝父链 `spring-boot-dependencies:2.7.18` 带来的 1.6.21 静默降级 |
| 测试 | JUnit 4（`org.junit.Test`，8 个测试类）+ `TemporaryFolder` 规则 |
| 未使用 | Spring / Spring Boot、MyBatis、JDBC、日志框架 —— `java.sql|jdbc|DataSource|sqlite` 命中 **0 文件** |

---

## 🚀 快速开始

### 编译与测试

```bash
mvn clean install -DskipTests   # 全 reactor：聚合根 + 25 模块
mvn test                        # 47 支测试，纯 JVM
```

解析顺序要求能拿到 `io.github.yuku123:z-boot-parent:1.0.21`（实测 repo1 200）。模块 POM 的
`<dependency>` 一律不写字面版本：兄弟模块由根 DM 的 26 条 `${project.version}` 自钉，第三方由地板下发。

### 作为依赖消费

`0.2.1` 已在 Maven Central，下游**不需要**本机 `mvn install`：

```xml
<dependency>
    <groupId>io.github.yuku123</groupId>
    <artifactId>z-agent-kernel-tool</artifactId>
    <!-- 版本可不写：继承 z-boot-parent 即由 z-boot-fleet 下发 0.2.1 -->
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
| `LlmProvider` | llm | `name` / `listModels` / `supportsModel` / `chat` / `streamChat` / `providerParams` | 本仓已给 6 个 |
| `Tool` | tool | `getName` / `getDescription` / `getSchema` / `execute(Map)` → `ToolResult` | 业务侧 |
| `MemoryStore` / `MemoryProvider` | memory | `getScope` / `save` / `load(id)`→`Optional` / `search(query, limit)` / `delete` / `listRecentMessages`；Provider 侧 `name`/`prefetch`/`sync`/`save` | 本仓给内存版 |
| `SessionStore` | state | `createSession` / `appendMessage` / `messages` / `updateSession` / `session` / `listSessions` / `search(query, limit)` / `deleteSession` / `close` | z-bot（SQLite+WAL） |
| `State` / `StateManager` | state | `getScope`/`get`/`set`/`delete`/`snapshot()`；`newState(scope)`/`snapshot(State)`/`restore(StateSnapshot)` | 上层 |
| `Pipeline` / `PipelineStep` | pipeline | `execute(PipelineContext)` → `PipelineResult`(含 `StepTrace`)；步骤返回 `StepResult(Status, output, nextStepName)` | 上层编排 |
| `Workspace` | workspace | `getScope` / `read` / `write` / `append` / `exists` / `list` / `delete` / `exec(cmd, args, timeoutMs)` → `ExecResult` | sandbox 侧 |
| `RagPipeline` / `Embedding` | rag / embedding | `retrieve(query, topK)` → `List<Document>` / `buildContext(docs)`；`embed`/`embedBatch`/`dim`/`model` | z-kb / z-vector |
| `McpClient` / `McpTransport` | mcp | `connect` / `listTools` / `call` / `disconnect` / `isConnected`；传输层 `open` / `request(json)` / `close` / `isOpen` | z-mcp；本仓给 stdio 版 |
| `CredentialStore` | credential | `get(key)` / `list()` / `isEncrypted()` | z-mist |
| `PermissionCheck` | permission | `isAllowed(subject, action, target)` / `permissionsOf(subject)` + `PermissionDeniedException` | z-ctc |
| `Skill` / `SkillLoader` | skill | `name` / `description` / `tools()` → `Map<String, ? extends Tool>` / `version` / `trigger`；`load` SKILL.md | z-skill |
| `Formatter` | formatter | `getProtocol` / `getDirection` / `format(T)` / `parse(String)`，`Direction ∈ {REQUEST, RESPONSE, BIDIRECTIONAL}` | 上层；当前实际协议转换写在 llm provider 内部 |
| `Tts` / `Realtime` | tts / realtime | `synthesize(text, voice, options)` → `InputStream` / `defaultVoice`；`open(onEvent, onError)` → `RealtimeSession`(`sendAudio`/`sendText`/`interrupt`/`close`) | z-msg |
| `EventBus` | event | `publish(envelope)` / `publish(source, event)` / `subscribe(typePattern, handler)` → `Subscription.cancel()` / `close` | 本仓给 sync + async 两版 |

---

## 🧪 测试

```bash
mvn -o test                     # 47 支；JDK 8 编译产物，不起端口
```

- 分布：`tool` 28 / `agent` 8 / `skill` 4 / `mcp` 3 / `credential` 2 / `state` 2。
- **唯一碰操作系统的是 `StdioMcpTransportTest`**：它拿 `/bin/cat` 当"回声 MCP server"起子进程
  （`new StdioMcpTransport(Arrays.asList("/bin/cat"))`）。⇒ 这 3 支在无 POSIX shell 的环境下会红，
  不是网络红；`llm` 的 6 个 provider 完全不进测试，因此本仓测试**不会发出任何外网请求**。
- 19 个模块零测试（含 `pipeline` / `rag` / `permission` / `embedding` 等纯接口模块，接口无逻辑可测）。

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

- `StdioMcpTransport.request()` 的响应配对用 `line.trim().contains("\"id\":" + id)`（`:80`），
  server 回**非紧凑 JSON**（`"id": 7` 带空格）就永不命中；而 30s `deadline`（`:73`）只在两次
  `readLine()`（`:75`）之间被检查，`readLine()` 自己阻塞时管不到 ⇒ 合起来是"对端不回紧凑格式那一行，
  调用就挂住"。复算：`sed -n '66,86p' z-agent-kernel-mcp/src/main/java/com/zifang/z/agent/kernel/mcp/StdioMcpTransport.java`
- 同文件 `:60` 的 `initialize` 握手把 `clientInfo.version` 写死 `"0.2.0"`，而本仓 `<revision>` 与
  已发布版本都是 `0.2.1`。复算：`grep -n 'clientInfo' .../StdioMcpTransport.java`
- 上述两条不被该模块 3 支测试（握手+回环、未 open 就 request 要失败、空命令要拒）覆盖。
- 根 POM `<description>` 的"1:1 对齐 agentScope"没有任何机器检查，别当验收结论。
- 根 POM 注释里"`z-boot-fleet` 的 kernel 槽位钉 0.1.1、0.2.1 实测 404"已过期：fleet `1.0.1`
  现钉 `0.2.1`，repo1 实测 200。

---

## 📄 License

MIT，见仓库根 [`LICENSE`](LICENSE)（`Copyright (c) 2026 z-opc-foundation`），根 POM `<licenses>` 同声明。

_Maintained by the z-opc-foundation organization._
