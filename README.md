# ctxpress

> Agent 上下文压缩引擎（Java）—— **确定性、可审计、可逆**。
> 在工具输出、日志、RAG 片段进入 LLM 之前，按你给出的 token 预算压缩它们。

<div align="center">

![Java](https://img.shields.io/badge/Java-21-orange?logo=openjdk&logoColor=white)
![Maven](https://img.shields.io/badge/build-maven-C71A36?logo=apachemaven&logoColor=white)
![deps](https://img.shields.io/badge/runtime%20deps-Jackson%20only-2C5E2E)
![License](https://img.shields.io/badge/License-MIT-blue)

</div>

## 一句话

Agent 的上下文会被工具输出迅速撑满：一次查询 4000 行 JSON、一段构建日志 27 万字符。
**ctxpress 让你说"塞进 N 个 token"，它在保住关键信息的前提下做到，并把丢掉的部分存进归档、随时可取回。**

## 实测：压缩量由预算决定，不是一个固定数字

我一度在 README 首屏放"压缩 98.8%"——那是拿一份高度重复的合成日志测出来的，
**是在挑对自己有利的样本**。真实情况是这样（3000 行、277KB 的应用日志，
约 15% 是重复重试，其余每行都不同，零散夹杂 ERROR）：

| 你给的预算 | 压缩后 | 压缩率 |
|---:|---:|---:|
| 2,000 | 2,381 | −96.5% |
| 20,000 | 26,012 | −62.1% |
| 60,000 | 63,999 | **−6.7%** |
| 100,000 | 68,571 | **0.0%** |

**关键性质是最后一行**：内容已经放得下时，ctxpress 不做任何改动。
压缩只在必要时发生，且**只丢预算逼你丢的那部分**——不会因为"反正要压"就多丢。

同一份工具返回 JSON 与 RAG 片段在 20,000 预算下同样保持 0.0%（它们本来就放得下）。

复现（数据由脚本生成，非手工构造）：

```bash
./mvnw package
python benchmarks/generate.py
java -jar ctxpress-cli/target/ctxpress.jar analyze --max-tokens 60000 benchmarks/data/app.log
```

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

命中保护规则（故障级别、编号、哈希、金额、UUID）的内容不参与裁剪，且**不受预算限制**。

但——**保护了全部等于保护不了**。这是实测踩到的坑：某日志每行都含交易编号与金额，
于是保护规则命中全部 4000 行，压缩率归零。因此有**保护占比闸门**：超过阈值（默认 60%）
自动降级为只保护故障线索，并在报告中说明：

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
退出码：`0` 成功 · `1` 用法错误 · `2` 读取失败 —— 可直接用于 CI。

作为库：

```xml
<dependency>
    <groupId>io.github.xiaoxusop</groupId>
    <artifactId>ctxpress-core</artifactId>
    <version>0.1.0</version>
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
  ctxpress 只证明了"结构自洽 + 引用保全 + 可取回"，**没有证明下游任务准确率不变**。
  这是当前最大的缺口。
- **单条消息级压缩，不做会话级调度。** 何时压、压到多少，由调用方决定；
  本库不管理会话历史，也不做类型化保留与依赖感知驱逐。
- **归档默认只存内存。** 进程重启即失效（引用是内容寻址的，所以落盘扩展是天然的下一步）。
- **不做语义摘要。** 要不要"用模型概括历史"是另一个问题；本库只做确定性压缩。

## 测试

```bash
./mvnw test      # 30 项，全部离线
```

覆盖的三条不变量比功能本身更重要：

- **确定性**：同输入两次调用逐字节相同
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

## License

[MIT](LICENSE) © 2026 XIAOXUsop
