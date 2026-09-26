# z-agent-kernel

Agent 框架基座：**25 个只放接口与轻量默认实现的小模块**，不带 Spring、不建库表、不起服务。
上层 `z-llm` / `z-mcp` / `z-skill` / `z-bot` / `z-agent` / `z-agent-proxy` 共用这一套 SPI。

"不联网"这句要说清楚：**全仓 0 个文件引用 `java.sql` / `jdbc` / `DataSource` / `sqlite`**
（复算：`grep -rl 'java.sql\|jdbc\|DataSource\|sqlite' --include='*.java' . | wc -l` ⇒ `0`），
但**会真发 HTTP / 真起子进程的有两个模块**——`z-agent-kernel-llm`（provider 打上游）与
`z-agent-kernel-mcp`（`ProcessBuilder` 起 MCP server）。复算：
`grep -rl 'okhttp3\|java.net.http\|HttpURLConnection\|Socket' --include='*.java' */src/main`
与 `grep -rl 'ProcessBuilder' --include='*.java' */src/main`。

| 项 | 值 | 怎么自己验一遍 |
|---|---|---|
| 坐标 | `io.github.yuku123:z-agent-kernel`（`packaging=pom` 聚合） | `grep -n '<packaging>' pom.xml` |
| 当前源码版本 | `0.2.1`（`<revision>`，唯一真源） | `grep -n '<revision>' pom.xml` |
| Central 上实际有的版本 | **`0.1.0`、`0.1.1` 两支**（2026-09-27 实测）⇒ `0.2.0`/`0.2.1` 只在源码仓，**下游拉不到** | `curl -s https://repo1.maven.org/maven2/io/github/yuku123/z-agent-kernel/maven-metadata.xml \| grep -o '<version>[^<]*'` |
| 模块数 | 25 | `grep -c '<module>' pom.xml` |
| JDK | Java 8 | `grep -n 'maven.compiler' pom.xml` |
| 源码规模 | main 94 个 `.java`（含每模块 1 份 `package-info.java`）/ test 8 个 | `find . -path '*/target' -prune -o -name '*.java' -print \| grep -c '/src/main/'`（test 同形改 `/src/test/`） |
| 顶层类型分布 | 28 `interface` + 39 `class` + 2 `enum` + 25 `package-info` = 94 | 见下"这一面有多大"一节给的脚本 |
| 测试数 | **47 支 `@Test`** | `grep -rho '@Test' --include='*.java' . \| wc -l` |
| Spring 依赖 | **0 个文件**引用 `org.springframework`（纯 POJO/SPI 基座） | `grep -rl 'org.springframework' --include='*.java' . \| wc -l` |

**要用 `0.2.1` 就自己 install**（Central 没有）：

```bash
mvn -o install          # 装进本机 ~/.m2，之后 z-bot / z-agent 才能按 0.2.1 解析
mvn -o test             # 47 支：不发网络请求、不起端口
```

`mvn -o test` 里唯一碰操作系统的是 `StdioMcpTransportTest`——它拿 **`/bin/cat` 当"回声 MCP server"** 起子进程
（复算：`grep -n '/bin/cat' z-agent-kernel-mcp/src/test/java/com/zifang/z/agent/kernel/mcp/StdioMcpTransportTest.java`）；
⇒ 这 3 支在 no-POSIX-shell 的环境下会红，不是网络红。

2026-09-27 04:4x 清树（我用的命令是 `rm -rf target */target`；更稳的写法是 `mvn -o clean test`——
zsh 下 `*/target` 无匹配会整行静默中止，别把"没跑"读成"跑绿了"）实测：
类级 8 个测试类求和 = **47**，模块级 6 条汇总行 `28 + 8 + 2 + 4 + 3 + 2` = **47**（两把尺同数），
`Failures=Errors=Skipped=0`，`BUILD SUCCESS`。

## 25 个模块都是什么

下表不是手抄的，是从每个模块的 `package-info.java` 第 2 行现读的；复算：

```bash
for m in z-agent-kernel-*; do printf "| \`%s\` | %s |\n" "${m#z-agent-kernel-}" \
  "$(find "$m/src/main/java" -name package-info.java -exec sed -n '2p' {} \; | sed 's/^ \* //;s/\.$//')"; done
```

| 模块 | 自述 |
|---|---|
| `agent` | agent — agent 编排 |
| `app` | app — app 框架 |
| `classifier` | classifier — classifier 抽象 |
| `console` | console — terminal UI 接口 |
| `credential` | credential — 凭据存储接口 (SPI only) |
| `embedding` | embedding — embedding 接口 (SPI only) |
| `event` | event — 事件分发 |
| `exception` | exception — 异常基类 |
| `formatter` | formatter — 格式化 |
| `llm` | llm — LLM 调用 SPI + OpenAI/Anthropic/DeepSeek/Qwen/DashScope/Gemini 多 provider 默认实现 |
| `mcp` | mcp — MCP client 接口 (SPI only) |
| `memory` | memory — 记忆抽象 |
| `message` | message — 消息抽象 |
| `middleware` | middleware — 中间件 |
| `permission` | permission — 权限检查接口 (SPI only) |
| `pipeline` | pipeline — 工作流 |
| `rag` | rag — RAG pipeline 接口 (SPI only) |
| `realtime` | realtime — Realtime 接口 (SPI only) |
| `skill` | skill — Skill 接口 (SPI only) |
| `state` | state — 状态管理 |
| `tool` | tool — 工具调用抽象 |
| `tts` | tts — TTS 接口 (SPI only) |
| `tui` | tui — terminal UI 接口 |
| `types` | types — 通用类型 |
| `workspace` | workspace — 工作区 |

顶层类型怎么数出来的（同一份口径，直接贴）：

```bash
python3 - <<'EOF'
import os,re,collections
c=collections.Counter()
for root,dirs,files in os.walk("."):
    dirs[:]=[x for x in dirs if x not in ("target",".git")]
    for f in files:
        p=os.path.join(root,f)
        if not f.endswith(".java") or "/src/main/" not in p: continue
        s=open(p,encoding="utf-8",errors="replace").read()
        if "package-info" in f: c["package-info"]+=1; continue
        m=re.search(r'^(?:public\s+)?(?:final\s+|abstract\s+)*(class|interface|enum|@interface)\s+\w+',s,re.M)
        c[m.group(1) if m else "other"]+=1
print(dict(c), "total=", sum(c.values()))
EOF
```

## 这一面里哪些是"有名字但没东西"

三件都在源码里可查，别靠印象：

1. **5 个模块里只有 `package-info.java`，一个类型都没有**：`app` / `classifier` / `console` / `exception` / `tui`。
   复算：`for m in z-agent-kernel-*; do n=$(find "$m/src" -name '*.java' 2>/dev/null | wc -l | tr -d ' '); [ "$n" = "1" ] && printf "%s " "${m#z-agent-kernel-}"; done`
2. **47 支测试只落在 6 个模块**（`tool` 28、`agent` 8、`skill` 4、`mcp` 3、`credential` 2、`state` 2），
   其余 **19 个模块零测试**——包括 13 个文件的 `llm`（6 个 provider 实现一支没测）。
   复算：`for m in z-agent-kernel-*; do printf "%-14s %s\n" "${m#z-agent-kernel-}" "$(grep -rho '@Test' --include='*.java' $m 2>/dev/null | wc -l | tr -d ' ')"; done`
3. **7 个模块在 foundation 里没有任何仓外 pom 引用它们**：`app` / `classifier` / `console` / `exception` / `pipeline` / `state` / `tui`。
   其中 `pipeline`（2 个接口）与 `state`（6 个文件、2 支测试）是**有实现但没消费者**。
   复算（foundation 根目录跑；把"全树 pom 引用到的 kernel artifactId"与"25 个模块名"求差集）：
   `comm -13 <(grep -rl --include='pom.xml' 'z-agent-kernel-' . | grep -v '^\./z-agent-kernel/' | xargs grep -ho '<artifactId>z-agent-kernel-[a-z]*</artifactId>' | sed 's/<[^>]*>//g' | sort -u) <(ls -d z-agent-kernel/z-agent-kernel-* | xargs -n1 basename | sort)`
   —— 必须排掉 `./z-agent-kernel/` 自己，否则每个模块的 `<artifactId>` 会算成"被引用"，差集恒空（这条尺的第一版就是这么假绿的）。

`pom.xml` 的 `<description>` 写着"1:1 对齐 agentScope"——这句**没有任何机器检查**，别当作验收结论。

## 谁在用哪一块

`llm` / `tool` / `message` / `types` 被 z-bot、z-llm、z-skill、z-agent、z-agent-proxy 交叉引用；
`credential` / `mcp` 主要由 z-bot 与 z-opc 的中间件集成测试引用；
`embedding` / `permission` / `rag` / `realtime` / `tts` 目前只有 `z-opc/z-middleware-integration-test` 一处引用。
复算（foundation 根目录）：`grep -rl '<artifactId>z-agent-kernel-tool</artifactId>' --include='pom.xml' .`

## 已知未收口的缺陷（记账，不是已完成）

- `z-agent-kernel-mcp` 的 `StdioMcpTransport.request()` 两处对不齐就永远等：响应配对用的是
  `line.trim().contains("\"id\":" + id)`（`:80`），server 回**非紧凑 JSON**（`"id": 7` 带空格）就永不命中；
  而 30s 的 `deadline` 只在两次 `readLine()`（`:75`）之间被检查（`:74`），`readLine()` 自己阻塞时**它管不到** ⇒ 合起来就是
  "对端不回紧凑格式的那一行，这个调用就挂住"。复算：`sed -n '71,85p' z-agent-kernel-mcp/src/main/java/com/zifang/z/agent/kernel/mcp/StdioMcpTransport.java`
- 同一文件 `:60` 的 `initialize` 握手把 `clientInfo.version` 写死成 `"0.2.0"`，而本仓 `<revision>` 已是 `0.2.1`
  （复算：`grep -n 'clientInfo' z-agent-kernel-mcp/src/main/java/com/zifang/z/agent/kernel/mcp/StdioMcpTransport.java`）。
- 本模块 3 支测试（`StdioMcpTransportTest`：握手+回环、未 open 就 request 要失败、空命令要拒）**都不覆盖上面两条**，
  所以它们不是"测出来的红"，是"读过代码确认在"的账。
- 版本发布状态：`0.2.0` / `0.2.1` **没发 Central**（复算见首表），所以 `z-bot` 的 `z-agent-kernel.version=0.2.1`
  只在本机 install 过才解析得到。

## 许可

MIT，见 [`LICENSE`](LICENSE)。
