# ctxpress

> 在工具输出、日志、RAG 片段进入 LLM 上下文之前压缩它们 —— **确定性、可审计、关键信息零丢失**。

<div align="center">

![Java](https://img.shields.io/badge/Java-21-orange?logo=openjdk&logoColor=white)
![Maven](https://img.shields.io/badge/build-maven-C71A36?logo=apachemaven&logoColor=white)
![deps](https://img.shields.io/badge/runtime%20deps-Jackson%20only-2C5E2E)
![License](https://img.shields.io/badge/License-MIT-blue)

</div>

## 解决什么问题

Agent 的上下文会被工具输出迅速撑满：一次数据库查询返回 4000 行 JSON，一段构建日志 20 万字符，
一次检索带回十个大段重叠的文档片段。这些内容里**真正有用的往往只有几行**——错误堆栈、
异常计数、关键字段——但按 token 计费的是全部。

多数解法是"截断"或"调模型摘要"，两者都有明显代价：

| 做法 | 问题 |
|---|---|
| 字符串截断 | 把 JSON 切成非法 JSON，把异常栈撕成半行，模型基于残缺事实推理 |
| 调用模型做摘要 | **不可复现**（同输入不同输出）、成本随压缩频率上升、且无法保证"不引入新事实" |
| 简单丢弃旧内容 | 关键信息静默消失，出问题时无法解释"模型当时看到了什么" |

**ctxpress 的做法**：按体裁做**结构感知**的确定性压缩，把关键信息显式保护起来，并给出可审计报告。

## 实测效果

对一份 4000 行、263,980 字节的真实应用日志：

```bash
java -jar ctxpress.jar compress --max-tokens 800 big.log > small.log
```

```
original  :  263980 bytes
compressed:    3297 bytes
reduction :    98.8%

LOG: 65995 -> 830 tokens (-98.7%), 保护 6 段,
     动作=[PROTECTION_DOWNGRADED_TO_CRITICAL_ONLY=4000/4000, PROTECTED_LINES=6,
           OMITTED_LINES=460, OMITTED_LINES=499, ..., OMITTED_LINES=999]
```

而末尾那行 `ERROR Caused by java.sql.SQLTransientConnectionException: pool exhausted`
**完整保留**——这正是模型真正需要的。

## 三条设计原则

### 1. 不调用模型

压缩是纯计算。这样压缩结果**可复现**（同输入必然同输出，可写断言测试）、**零增量成本**、
且不会引入原文没有的事实。整个运行时依赖只有 Jackson（JSON 结构压缩需要真正的解析器）。

### 2. 按体裁处理，不切字符串

| 体裁 | 做法 |
|---|---|
| **JSON** | 对象**保留全部键**（键名信息密度极高）；超长数组保留首尾样本 + `{"_omitted": N}` 计数节点；输出**仍是合法 JSON** |
| **LOG** | **按行**处理，永不破坏单行；相邻重复折叠为 `[重复 N 次]`；按省略区段插入 `[省略 N 行]` 标记 |
| **TEXT** | **按句**处理，绝不从句子中间截断；句子级去重（RAG 片段大段重叠是主要浪费来源） |

### 3. 保护关键信息，但保护要有区分度

命中保护规则的内容**不参与裁剪**，且不受预算限制。默认规则覆盖故障级别、编号、
哈希、金额、UUID 等关键线索。

但——**保护了全部等于保护不了**。这是实测中真实踩到的坑：某日志每行都含交易编号与金额，
于是"保护规则"命中了全部 4000 行，压缩率归零。因此本库有保护占比闸门：
超过阈值（默认 60%）时自动降级为只保护故障线索，并在报告中说明：

```
PROTECTION_DOWNGRADED_TO_CRITICAL_ONLY=4000/4000
```

## 快速开始

### 命令行（无需写代码）

```bash
# 打包
./mvnw package

# 看能省多少（不产出内容）
java -jar ctxpress-cli/target/ctxpress.jar analyze app.log

# 压缩，结果走 stdout、报告走 stderr —— 因此重定向是安全的
cat huge.json | java -jar ctxpress-cli/target/ctxpress.jar compress --max-tokens 2000 > small.json

# 自定义保护：命中 TRACE-xxxx 的内容不参与裁剪
java -jar ctxpress-cli/target/ctxpress.jar compress --must-keep 'TRACE-[0-9A-F]+' app.log
```

选项：`--max-tokens N` · `--kind JSON|LOG|TEXT` · `--must-keep REGEX` · `--head N` · `--tail N`

退出码：`0` 成功 · `1` 用法错误 · `2` 读取失败 —— 可直接用于 CI。

### 作为库

```xml
<dependency>
    <groupId>io.github.xiaoxusop</groupId>
    <artifactId>ctxpress-core</artifactId>
    <version>0.1.0</version>
</dependency>
```

```java
ContextPress press = ContextPress.withDefaults();

// 放在把内容加入上下文之前
PressResult result = press.press(toolOutput);

context.add(result.content());
log.info(result.report().summary());
```

自定义策略：

```java
ContextPress press = ContextPress.with(PressPolicy.builder()
        .maxTokens(4_000)
        .mustKeep("订单号\\s*[:：]\\s*\\d+")   // 追加保护规则
        .headLines(60)
        .tailLines(30)
        .build());
```

## 典型接入点

```java
// 工具返回值
String raw = tool.execute(...);
context.add(press.press(raw, ContextKind.JSON).content());

// 命令 / 构建输出
context.add(press.press(commandOutput, ContextKind.LOG).content());

// RAG 检索片段（多个片段拼接后去重收益最大）
context.add(press.press(String.join("\n", chunks), ContextKind.TEXT).content());
```

## 模块

```
ctxpress/
├── ctxpress-core     压缩核心：体裁判定 + 三种压缩器 + 保护策略 + 审计报告
└── ctxpress-cli      命令行：管道友好，打成可执行 fat jar
```

## 测试

```bash
./mvnw test
```

21 项测试，全部离线（无网络、无模型）。覆盖的重点不是"能不能跑"，而是三条不变量：

- **确定性**：同输入两次调用逐字节相同
- **结构自洽**：JSON 压缩产物可被解析；日志不会出现半行
- **保护有效**：预算再小，命中保护规则的内容也不会被裁掉

## 边界与不做的事

- **不做语义摘要**。要不要"用模型概括历史对话"是另一个问题，本库只做确定性压缩。
- **不保证信息无损**。压缩就是有损的；本库的承诺是"关键信息零丢失 + 丢了什么可审计"，
  不是"什么都没丢"。
- **不做上下文窗口调度**。什么时候压、压到多少，由调用方决定。

## License

[MIT](LICENSE) © 2026 XIAOXUsop
