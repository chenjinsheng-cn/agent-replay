# agent-replay

Agent 执行回放与调试器。把一次 agent 运行完整记成**事件流**，之后可以原样重放、比对摘要、从任意一轮分叉出去再跑。

解决的问题很具体：agent 出错时，日志里只有「模型说了什么」，看不到「循环当时是怎么走的」。
一旦改了提示词、换了工具、调了循环逻辑，也没法回答「跟上次比，行为到底变了没有」。

## 跑起来

需要 JDK 17。不用装 Maven（仓库自带 wrapper）。

```bash
./mvnw spring-boot:run
```

默认 **8081** 端口，**离线模式**，不需要任何 API key、不联网。

| 地址 | 用途 |
|------|------|
| `http://localhost:8081/swagger-ui.html` | 接口文档，可以直接点着试 |
| `http://localhost:8081/h2-console` | 看库；JDBC URL `jdbc:h2:file:./data/agent-replay`，用户 `sa`，密码空 |

先跑一次看看：

```bash
curl -X POST http://localhost:8081/api/runs \
  -H "Content-Type: application/json" \
  -d '{"goal":"看看工作区，再算一下 (128+64)*3-72/4"}'
```

工具会**真的执行**（列目录、算数），模型回答来自录制脚本。数据落在 `./data/`，工具能碰的目录是 `./workspace/`。

## 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/runs` | 发起一次运行 |
| GET | `/api/runs` | 运行列表 |
| GET | `/api/runs/{id}` | 运行详情 |
| GET | `/api/runs/{id}/events` | 完整事件流（payload 已解析成 JSON） |
| GET | `/api/runs/{id}/replay` | 重放校验 |
| GET | `/api/runs/{id}/children` | 由它分叉出来的运行 |
| POST | `/api/runs/{id}/fork?atStep=N` | 保留前 N 轮，从第 N 轮重新生成 |
| GET | `/api/runs/diff?left=&right=` | 对比两次运行 |

写操作只有两个：发起运行、分叉。其余全只读——调试工具越只读越好用，因为可以放心反复点。

## 双模式

`agent.llm.mode`：

- **`recorded`（默认）** —— 按顺序吐出 `src/main/resources/events/demo-script.json` 里写死的回答，一次网络都不发。clone 下来直接能跑，结果每次一样。
- **`real`** —— 真请求 DeepSeek（OpenAI 兼容协议）。

切真实模式：

```bash
# PowerShell
$env:DEEPSEEK_API_KEY = "sk-..."
./mvnw spring-boot:run -Dspring-boot.run.arguments=--agent.llm.mode=real
```

key **只从环境变量读**，不写进配置文件，日志和报错里也不会出现。

## 重放到底证明了什么

这是这个项目最容易被误解的地方，先说清楚：

**重放不是让模型重跑一遍。** 模型本身不确定，同一个提示词两次回答不一样，任何声称能复现模型输出的设计都是假的。

重放的做法是：**把当时模型的回答、工具的返回值全部当真值喂回去，只重跑自己写的编排逻辑**（`AgentLoop`）。
所以它证明的是「**我的编排代码还是不是当初那个行为**」——事件顺序、消息拼接、工具调用时机、终止判断。
改了代码之后跑一次重放，摘要（整段对话的 SHA-256）对得上，就说明编排逻辑没被改坏；对不上，说明真改了。

**结构上保证不会产生副作用**：重放用的 `RecordedToolExecutor` 手里**根本没有 `ToolRegistry` 引用**，
就算写错了也执行不了任何工具——不是靠「记得别调」，是靠拿不到。

事件被截断、工具名对不上、工具输出被人改过，都会明确报出来（`notes` 里），不会假装通过。

## 分叉

`POST /api/runs/{id}/fork?atStep=N` —— 保留父运行前 N 轮，从第 N 轮开始重新生成。用途是「如果当时模型选了另一个工具，后面会怎样」。

两个设计点：

- **按轮次切，不按事件序号切。** 事件序号可能落在一轮中间（记了 `LLM_RESPONDED`、还没记 `TOOL_COMPLETED`），从中间切出来的是半个循环，接着跑状态是残缺的。轮次边界才是天然可续的地方。
- **复制前缀事件，不引用父事件的链。** 引用的话查一次运行要顺父链往上爬，父运行删了子运行就残缺。复制让每次运行自成一体，代价是存储翻倍——这个规模下完全可以接受。

**工具也必须一起混合。** 如果只把模型换成「前 N 轮回放、之后真实」，前缀里的工具调用会被**真的执行第二遍**（`write_file` 会写两次）。所以 `HybridLlmClient` 和 `HybridToolExecutor` 用**同一个 N**，前缀阶段的工具结果从记录里取，不碰真实文件系统。

## 对比

`GET /api/runs/diff?left=&right=` 给出工具序列差异、最终答案是否相同，以及**第一个分歧在第几条**。

分歧位置最有信息量：一改就从第 0 条不一样，说明动的是系统提示词或起始上下文；跑到第 6 条才分叉，说明动的是某个中途判断。

比对时**排除两个字段**——`mode`（这一步由哪个客户端答的）和 `idempotencyKey`（生成规则是 `runId:step:callId`，里面就带着 runId）。这两个天然每次都不同，比了只会淹没真实差异：实跑时一个「什么都没改的分叉」被报成「第 7 条开始分叉」，note 里那两条 payload 除了 idempotencyKey 逐字相同。

## 代码结构

```
agent/       AgentLoop（ReAct 循环）+ 工具 + 两种 LlmClient
  LlmClient:  DeepSeekLlmClient(真)  RecordedLlmClient(假)  ReplayLlmClient(还原)  Hybrid(前 N 轮还原)
  tool/:      list_files  read_file  write_file  calc
replay/      ReplayEngine(重放校验)  RunDiffer(对比)  Transcript(摘要)
service/     RunService(门面)  ForkService(分叉)
domain/      AgentRun  AgentEvent  EventType  RunStatus
```

几个值得单独说的取舍：

**没有 `@Transactional` 包住一次运行。** 包住的话事件要等方法结束才真正落库，进程中途崩掉，最需要留下的那部分日志正好丢了。现在每条事件各自提交，代价是运行期间多几次事务，换来「崩溃前发生的事一定查得到」。

**`AgentLoop` 不是 Spring bean。** 它要 `new` 出来配不同的 client / executor / sink（新建运行、重放、分叉三种组合），注册成单例反而别扭。

**工具路径是唯一一处信任边界。** 模型给的字符串能直接变成磁盘路径。校验不是 `contains("..")`——那挡不住 `a/../../etc`，也挡不住符号链接。做法是**先 normalize 成绝对路径，再判断是不是以 workspace 开头**。

**`calc` 自己写了表达式解析器。** JDK 17 移除了 Nashorn，`ScriptEngine` 拿不到 JavaScript；`BigDecimal` + `MathContext(10)` 处理浮点。

## 测试

```bash
./mvnw test
```

34 个用例，全部离线、不碰真实数据（测试用内存库 + `target/` 下的临时工作区）：

| 测试 | 覆盖 |
|------|------|
| `CalcToolTest` | 运算优先级、除法精度、非法输入 |
| `AgentLoopTest` | 无工具直答、单轮工具、多轮、超步数失败 |
| `WorkspacePathTest` | 路径穿越（`../`、`a/../../x`、符号规范化） |
| `ReplayEngineTest` | 一致、篡改工具输出、篡改工具名、记录被截断、无摘要 |
| `RunDifferTest` | 无差异、只有元数据不同、参数变了、输出变了、长度不一致 |
| `AgentReplayIntegrationTest` | 端到端：跑一次 → 重放 → 分叉 → 对比 |

## 已知边界

- 单进程、无鉴权，只适合本机。要上多人用，先加鉴权和运行隔离。
- 录制脚本只有 3 条回答，走完就报错（明确报错，不拿最后一条糊弄）。要演示更长链路往里加即可。
- 工具只有 4 个，全在本地文件系统上。接外部 API 的话，重放要额外考虑「记录里存的响应可能已经过期」。
- 事件流按 `(run_id, seq)` 存，没有做压缩和归档。一次运行几十条事件无所谓，长期保存要另想办法。
