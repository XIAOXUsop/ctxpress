# ctxpress

> Agent 上下文压缩引擎（Java）—— **确定性、可审计、可逆**。
> 在工具输出、日志、RAG 片段进入 LLM 之前，按你给出的 token 预算压缩它们。

<div align="center">

![Java](https://img.shields.io/badge/Java-21-orange?logo=openjdk&logoColor=white)
![Maven](https://img.shields.io/badge/build-maven-C71A36?logo=apachemaven&logoColor=white)
![deps](https://img.shields.io/badge/runtime%20deps-Jackson%20only-2C5E2E)
![License](https://img.shields.io/badge/License-MIT-blue)

</div>

## 契约

这个库只承诺一件事，所以先说清楚它做到什么、做不到什么。

**保证：**

- **输出 ≤ 你给的预算。** 做不到时（只有一种情形：命中保护规则的内容本身就超过了预算），
  报告里 `overBudgetBy > 0`，**绝不静默超标**。
- **命中保护规则的内容按构造保留**——它是"不许动"的，不参与取舍。
- **未保护部分可经内容寻址归档逐字节取回**，且归档引用被写进压缩内容本身。
- **输出不含原文没有的内容。** 每个字节要么逐字来自输入，要么属于已声明的标记文法
  （`…[省略 N 行]…` / `{"_omitted":N}` / `_ctxpress_archive`）。

**不保证：**

- **注入字段会破坏强类型反序列化。** 压缩后的 JSON 里有 `_omitted` 与 `_ctxpress_archive`，
  用 `List<Foo>` 直接接会失败——请用 `JsonNode` 接收。
- **根为数组的 JSON，截断后 schema 不再兼容**（数组里会多出一个计数节点）。
- **"仅去空白"不是逐字节无损。** 它只去缩进换行，但 Jackson 会归一化数值字面量
  （`1.00` → `1.0`），也会还原 Unicode 转义。**值等价，字节不等价**——所以报告里写的是
  `MINIFIED_ONLY`，不是 `LOSSLESS`。
- **预算的计量口径是启发式估算**（CJK 码点 1 token、其余 4 字符 1 token），
  不是任何一家模型的实际 BPE。它对 JSON / 代码**偏乐观**，见「已知限制」。

## 实测：压缩量由预算决定，不是一个固定数字

我一度在 README 首屏放"压缩 98.8%"——那是拿一份高度重复的合成日志测出来的，
**是在挑对自己有利的样本**。真实情况是这样（3000 行、277KB 的应用日志，
约 15% 是重复重试，其余每行都不同，零散夹杂 ERROR）：

| 你给的预算 | 压缩后 | 变化 |
|---:|---:|---:|
| 2,000 | 1,853 | −97.3% |
| 8,000 | 7,548 | −89.0% |
| 20,000 | 18,946 | −72.4% |
| 60,000 | 57,198 | −16.6% |
| 100,000 | 68,571 | **0.0%** |

**每一行都 ≤ 它对应的预算**，这是上一节那条契约的字面含义。

**关键性质是最后一行**：内容已经放得下时，ctxpress 不做任何改动。
压缩只在必要时发生，且**只丢预算逼你丢的那部分**——不会因为"反正要压"就多丢。

同一份工具返回 JSON 与 RAG 片段在 20,000 预算下同样保持 0.0%（它们本来就放得下）。

复现（数据由脚本生成，非手工构造）：

```bash
./mvnw package
python benchmarks/generate.py
java -jar ctxpress-cli/target/ctxpress.jar analyze --max-tokens 20000 benchmarks/data/app.log
```

预算契约不是靠几组单元测试保证的，而是靠一张**矩阵**：`benchmarks/budget_matrix.py`
把 6 类语料 × 10 档预算全部跑一遍，逐格断言"输出 ≤ 预算，或如实声明做不到"。
CI 把这张矩阵当门禁跑（`violated == 0`、`underfilled == 0`）。

## 可逆：压掉的东西能拿回来

压缩的本质是**赌这段内容用不上**。赌对了省 token，赌错了模型就永远拿不到那一行——
而 Agent 恰恰经常在后续步骤里需要前面被压掉的细节（一个错误码、一个字段值）。

```java
ContextPress press = ContextPress.withArchive(PressPolicy.builder().maxTokens(2000).build());

PressResult result = press.press(toolOutput);
context.add(result.content());          // 内容里已嵌入归档引用

press.retrieve(result.archiveRef())     // 后续需要时取回原文，逐字节一致
     .ifPresent(context::add);
```

归档引用会被**写进压缩内容本身**——日志的省略标记、JSON 的 `_ctxpress_archive` 字段——
所以**读到这段内容的模型自己就知道有东西被省略了、以及怎么要回来**。
只标注"省略了 N 行"而不说怎么取回，等于让模型明知有缺失却无从补救。

归档是**内容寻址**（`ORIG-` + SHA-256 前 16 位）：同一段原文重复压缩只存一份，
且引用稳定。默认有界（256 条，LRU），因为无上限的归档就是内存泄漏。

> 根为数组的 JSON 没有"字段"可写，引用会被放进数组自己的 `{"_omitted": …}` 计数节点里——
> 那已经是个记账节点，再挂一个键只是元素级污染，而包一层 `{"data": […]}` 会把根类型
> 从数组变成对象，强类型反序列化直接失败。

## 三条设计原则

**1. 不调用模型。** 压缩是纯计算。因此结果可复现（同输入必然同输出，可写断言测试）、
零增量成本、且不会引入原文没有的事实。运行时依赖只有 Jackson。

**2. 按体裁处理，不切字符串。**

| 体裁 | 做法 |
|---|---|
| **JSON** | 对象**保留全部键**（键名信息密度极高）；超长数组留首尾样本 + `{"_omitted": N}`；输出**仍是合法 JSON** |
| **LOG** | **按行**处理，永不破坏单行；相邻重复折叠为 `[重复 N 次]`；按省略区段标注 |
| **TEXT** | **按句**处理，绝不从句中截断；句子级去重（RAG 片段重叠是主要浪费来源） |

**3. 保护关键信息，但保护要有区分度。**

命中保护规则（故障级别、编号、哈希、金额、UUID）的内容不参与取舍。

这与「输出 ≤ 预算」在数学上会冲突：受保护内容本身就超预算时，两条承诺无法同时满足。
**ctxpress 的选择是保住内容、如实上报**——报告里给出 `overBudgetBy`，
把"是放宽保护还是接受超标"的决定交回调用方。**不静默丢弃，也不静默超标。**

而 **保护了全部等于保护不了**：某日志每行都含交易编号与金额，保护规则命中全部 4000 行，
压缩率归零。因此有**保护占比闸门**：超过阈值（默认 60%）自动降级为只保护故障线索，
并在报告中说明：

```
PROTECTION_DOWNGRADED_TO_CRITICAL_ONLY=4000/4000
```

## 快速开始

### 方式一：直接下载（无需构建）

```bash
# 可执行 jar，下载即可用
curl -LO https://github.com/XIAOXUsop/ctxpress/releases/latest/download/ctxpress.jar
java -jar ctxpress.jar analyze --max-tokens 8000 app.log
```

### 方式二：从源码构建

```bash
./mvnw package

# 看能省多少（不产出内容）
java -jar ctxpress-cli/target/ctxpress.jar analyze --max-tokens 8000 app.log

# 压缩；结果走 stdout、报告走 stderr，因此重定向是安全的
cat huge.json | java -jar ctxpress-cli/target/ctxpress.jar compress --max-tokens 2000 > small.json

# 自定义保护规则
java -jar ctxpress-cli/target/ctxpress.jar compress --must-keep 'TRACE-[0-9A-F]+' app.log
```

选项：`--max-tokens N` · `--kind JSON|LOG|TEXT` · `--must-keep REGEX` · `--head N` · `--tail N`
退出码：`0` 成功 · `1` 用法错误 · `2` 读取失败 · `3` 内部错误 —— 可直接用于 CI。

输出**只含 LF**（不随平台变化），因为"确定性"包括跨平台字节一致。

作为库：

```xml
<dependency>
    <groupId>io.github.xiaoxusop</groupId>
    <artifactId>ctxpress-core</artifactId>
    <version>0.2.0</version>
</dependency>
```

## 与 headroom 的关系（以及我不假装的事）

[**headroom**](https://github.com/headroomlabs-ai/headroom)（Python/TS，71k⭐）是这个问题上最成熟的项目，
能力面**明显比 ctxpress 大**：自有 HuggingFace 压缩模型、AST 代码压缩、代理模式、
15 种 agent 的 wrap、KV-cache 对齐、输出 token 削减、跨 agent 记忆，以及 GSM8K / TruthfulQA 等
**准确性评测**证明不损失精度。

**ctxpress 不是"更好的 headroom"，也不该假装是。** 它的定位是：

| | headroom | ctxpress |
|---|---|---|
| 语言 | Python / TypeScript | **Java** |
| 依赖 | 含自有模型下载 | **仅 Jackson，纯离线** |
| 压缩器 | JSON / AST / 训练模型 | JSON / LOG / TEXT（全确定性规则） |
| 可逆取回 | ✅ | ✅ |
| 准确性评测 | ✅ | ❌（**这是真实缺口，未做**） |

**选 ctxpress 的理由只有一条，但成立**：你在 Java / Spring Boot / LangChain4j 工程里，
而这套生态目前没有对标物——GitHub 上「上下文/记忆」主题的 Java 仓库**总共只有 23 个**，
headroom 用不了。

## 已知限制

- **没有准确性评测。** headroom 用 GSM8K / SQuAD / BFCL 证明压缩不损失下游精度；
  ctxpress 只证明了"结构自洽 + 引用保全 + 可取回 + 预算守约"，
  **没有证明下游任务准确率不变**。这是当前最大的缺口。
- **预算的计量是启发式估算，不是可计费 token。** CJK 码点按 1 token、其余按 4 字符 1 token。
  对 JSON / 代码这类标点密集的文本，真实 BPE 约 3.2–3.7 字符/token，**估计值偏乐观**。
  接真实 tokenizer 是下一步。
- **受保护内容是预算的下界。** 一份每行都含 ERROR 的日志在那部分内容装进预算之前，
  任何预算都满足不了——此时报告会给出 `overBudgetBy` 而不是硬压。
  调高 `maxProtectedRatio` 可以放宽保护范围。
- **压缩会注入字段，破坏强类型反序列化**（`_omitted` / `_ctxpress_archive`），请用 `JsonNode` 接收。
- **NDJSON / 一行一文档的 JSON 被当作非法 JSON。** 解析器开了尾随 token 检查——
  早先不检查时，`{"a":1}\n{"b":2}\n{"c":3}` 会被静默截成第一个文档，
  报告里却是"压缩成功、无省略、不可逆"。宁可判为非法交给日志压缩器，也不静默丢数据。
- **单条消息级压缩，不做会话级调度。** 何时压、压到多少，由调用方决定；
  本库不管理会话历史，也不做类型化保留与依赖感知驱逐。
- **归档默认只存内存。** 进程重启即失效（引用是内容寻址的，所以落盘扩展是天然的下一步）。
- **不做语义摘要。** 要不要"用模型概括历史"是另一个问题；本库只做确定性压缩。

## 测试

```bash
./mvnw test      # 64 项，全部离线
```

覆盖的四条不变量比功能本身更重要：

- **预算守约**：语料 × 预算的完整矩阵上，输出要么 ≤ 预算，要么显式声明做不到；
  且守约不能靠"几乎什么都不留"达成（这条来自一次真实的设计失败：按条目成本批量撤销，
  在短行场景下过撤 8 倍，500 行日志预算 375 只剩 7 token）
- **确定性**：同输入两次调用逐字节相同，在整个矩阵上都成立
- **结构自洽**：JSON 压缩产物可被解析；日志不出现半行；文本不出现残句
- **保护有效**：预算再小，命中保护规则的内容也不会被裁掉

开发过程中由**实际运行与基准测试**（而非单元测试）发现并修复的缺陷，均已补回归用例：

1. CRLF 日志被误判为 TEXT —— Java 正则的 `.` 不匹配 `\r`，按行判定整体失配
2. 保护规则命中全部行导致压缩率为 0 —— 保护了全部等于保护不了
3. CLI 输出走平台编码（GBK）产出非法 UTF-8
4. 文本压缩器无条件返回归档引用 —— 没压缩也占归档
5. JSON 压缩器用"actions 非空"推断是否裁剪 —— 数组采样不产生 action，"裁剪了却以为没裁剪"
6. **内容放得下时仍然压缩** —— 8528 token 的内容在 20000 预算下被压到 515
7. **固定头尾不填预算** —— 只超出预算一点，却丢掉 95%

v0.2.0 修复的（这些是上面那些修复**没有覆盖到**的部分）：

8. **预算契约整体不成立** —— 省略标记的成本从未进预算，超出量随预算一起涨：
   同一份日志给 8000 输出 10048、给 20000 输出 26012。修复只做到三分之一
   （LOG 修了、TEXT 半修、JSON 完全没修），且 30 项测试里**没有一条**断言过输出 ≤ 预算
9. **JSON 完全不读预算** —— 采样上限固定从 8 起步、只会缩小不会增长，
   于是 2500 / 4000 / 8000 三个预算输出**完全相同**的 515 token
10. **英文文档整篇塌缩** —— 切句不认 ASCII 句点，10111 token 的英文散文在 2000 预算下输出 **5 token**
11. **方括号前缀日志被判成 JSON** —— `[INFO] …` / `[2026-09-11 10:00:00] …`
    （logback / Maven / Gradle 默认格式）解析失败后退回文本压缩，压缩率恒为 0
12. **NDJSON 被静默截断** —— 420 token 的 30 行 NDJSON "压缩"到 14 token，
    报告里 `truncated=false`、无省略标注，看不出丢了 96.7%
13. **切句正则爆栈** —— 交替结构让正则引擎每次迭代递归一层栈，3800 字符无句末标点的输入直接 `StackOverflowError`
14. **`{"_omitted":0}` 注入** —— 没超上限的数组被塞进一个记账节点，还被标成"已截断"
15. **CLI 缺参抛裸栈迹**、异常路径中文乱码、输出在 Windows 上多出 CRLF、未知命令报错类型错
16. **release 资产路径多一层前缀** —— core 的 jar 永不匹配、静默缺失

## License

[MIT](LICENSE) © 2026 XIAOXUsop
