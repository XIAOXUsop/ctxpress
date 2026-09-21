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
  （日志：`…[省略 N 行]…`、折叠 `<原行>   [重复 N 次]`；文本：`…[省略 N 句]…`；
  JSON：`…[N 字符已省略]…` / `{"_omitted":N}`；归档引用：`_ctxpress_archive`）。
  这五类都由 `benchmarks/fidelity.py` 的保真证书逐条比对——**每一行的前缀都必须逐字来自输入**。

**不保证：**

- **注入字段会破坏强类型反序列化。** 压缩后的 JSON 里有 `_omitted` 与 `_ctxpress_archive`，
  用 `List<Foo>` 直接接会失败——请用 `JsonNode` 接收。
- **根为数组的 JSON，截断后 schema 不再兼容**（数组里会多出一个计数节点）。
- **"仅去空白"不是逐字节无损。** 它只去缩进换行，但 Jackson 会归一化数值字面量
  （`1.00` → `1.0`），也会还原 Unicode 转义。**值等价，字节不等价**——所以报告里写的是
  `MINIFIED_ONLY`，不是 `LOSSLESS`。
- **预算的计量口径是词表相关的。** 命令行默认用 `o200k_base` 真实词表；
  作为库引用 `ctxpress-core` 时默认是启发式估算（CJK 1 token/字、其余 4 字符 1 token），
  它对日志/JSON/代码**偏乐观**（实测同一份日志低估 **37%**）。
  要真实计数就引 `ctxpress-tokenizer-jtokkit`，或自行实现 `TokenCounter`。

## 实测：压缩量由预算决定，不是一个固定数字

我一度在 README 首屏放"压缩 98.8%"——那是拿一份高度重复的合成日志测出来的，
**是在挑对自己有利的样本**。真实情况是这样（3000 行、277KB 的应用日志，
约 15% 是重复重试，其余每行都不同，零散夹杂 ERROR）：

| 你给的预算 | 压缩后 | 变化 |
|---:|---:|---:|
| 8,000 | 7,920 | −92.8% |
| 20,000 | 19,922 | −81.8% |
| 60,000 | 59,947 | −45.2% |
| 150,000 | 109,398 | **−0.0%** |

**每一行都 ≤ 它对应的预算**，这是上一节那条契约的字面含义。
（token 按 `o200k_base` 真实词表计——同一份日志用启发式估算只有 68,571，
也就是**低估 37%**。差异这么大是因为日志里时间戳与标点密集，
真实约 2.5 字符/token，而不是"4 字符 1 token"。）

再往下压会撞到**受保护内容构成的下界**：这份日志有 60 行 ERROR，
给 2,000 预算时它们本身就占 2,257 token，此时报告直接给出
`预算未满足=+257`，而不是把故障行悄悄丢掉。

**关键性质是最后一行**：内容已经放得下时，ctxpress 不做任何改动。
压缩只在必要时发生，且**只丢预算逼你丢的那部分**——不会因为"反正要压"就多丢。

同一份工具返回 JSON 与 RAG 片段在 20,000 预算下同样保持 0.0%（它们本来就放得下）。

复现（数据由脚本生成，非手工构造）：

```bash
./mvnw package
python benchmarks/generate.py
java -jar ctxpress-cli/target/ctxpress.jar analyze --max-tokens 20000 benchmarks/data/app.log
```

> **上面那张表的四个数字由 `benchmarks/budget_matrix.py` 逐格复算并对账**，抄错了会红。
> （2026-09-22 之前它们就是旧的：8,000 那格写 7,942，实测 **7,920**——
> 压缩器调过之后表没跟着改，而当时的门禁只查 `violated`/`underfilled`、
> **从不比对任何具体数字**，所以永远绿。）

预算契约不是靠几组单元测试保证的，而是靠一张**矩阵**：`benchmarks/budget_matrix.py`
把 6 类语料 × 10 档预算全部跑一遍，逐格断言"输出 ≤ 预算，或如实声明做不到"。
CI 把这张矩阵当门禁跑（`violated == 0`、`underfilled == 0`）。

## 评测：压掉之后，该留下的还在不在

`benchmarks/fidelity.py`，3000 行日志，把 35 个关键信息均匀埋进去——
3 个命中保护规则（放在 ERROR 行），32 个不命中（放在 INFO 行）。
两组必须分开，否则量不出"保护"与"采样"各自的贡献。**不需要模型、不需要网络、几秒跑完。**

| 你给的预算 | ctxpress 保护/普通 | 头截断 保护/普通 | 均匀行采样 保护/普通 |
|---:|---|---|---|
| 500 | **100%** / 0% | 33% / 0% | 33% / 3% |
| 2,000 | **100%** / 0% | 33% / 0% | 33% / 3% |
| 8,000 | **100%** / 6% | 100% / 3% | 33% / 9% |
| 16,000 | **100%** / 16% | 100% / 16% | 67% / 19% |

**保真证书：6 个用例共 0 行凭空生成。** 输出的每一行要么逐字来自输入，
要么匹配已声明的省略标记文法——这是抽取式压缩相对摘要式压缩的硬优势
（ACL 2020 的测量显示 >70% 的单句摘要含幻觉）。

三件必须一起看的事：

1. **命中保护规则的关键信息，任何预算下召回都是 100%**，而头截断在小预算下只有 33%
   ——因为它必须把预算花到文件末尾才够得着埋在那里的信息。
2. **未受保护的关键信息，召回与均匀行采样相当（略低）。** 差距来自省略标记：
   一个标记约 7 token，而一行普通日志才 11 token——标记开销接近内容本身。
   **这是"可审计 + 可取回"的代价**：均匀采样不留标记，代价是模型不知道有东西被省略过、
   也无从取回。这个数字不加粉饰。
3. **对照组不可省。** 没有"什么都不做能拿多少"作为基准，"召回 67%"是好是坏根本无从判断。

## 可逆：压掉的东西能拿回来

压缩的本质是**赌这段内容用不上**。赌对了省 token，赌错了模型就永远拿不到那一行——
而 Agent 恰恰经常在后续步骤里需要前面被压掉的细节（一个错误码、一个字段值）。

```java
import io.github.xiaoxusop.ctxpress.ContextPress;
import io.github.xiaoxusop.ctxpress.PressPolicy;

// 预算的口径必须写清楚：这里的 2000 是**启发式估算**单位，不是可计费 token。
// 要当硬约束用，走 hardBudget 显式给计数器（见下「token 口径」）。
ContextPress press = ContextPress.withArchive(PressPolicy.builder().maxTokens(2000).build());

PressResult result = press.press(toolOutput);
context.add(result.content());          // 内容里已嵌入归档引用

press.retrieve(result.archiveRef())     // 后续需要时取回原文，逐字节一致
     .ifPresent(context::add);
```

归档引用会被**写进压缩内容本身**，所以**读到这段内容的模型自己就知道有东西被省略了、
以及怎么要回来**。只标注"省略了 N 行"而不说怎么取回，等于让模型明知有缺失却无从补救。

承载位置按压缩后的结构分三种，**取得到就用第一种**：

| 压缩后的根 | 引用写在哪 |
|---|---|
| 对象 | 顶层加一个 `_ctxpress_archive` 字段 |
| 数组（数组本身被采样截断） | 写进那个 `{"_omitted":N}` 记账节点 |
| 数组（数组没被截断，只截了内层字符串） | 写进**第一条**字符串省略标记：`…[19584 字符已省略；原文可经 ctxpress 归档 ORIG-… 取回]…` |

> **第三种是 2026-09-22 补的。** 它此前是漏的：日志与文本压缩器一直把引用写进各自的
> 省略标记，只有 JSON 的字符串截断标记没写，于是那条路径上的动作是
> `ARCHIVE_REF_NOT_EMBEDDABLE`——**内容里既没有 `ORIG-…` 也没有 `_ctxpress_archive`**，
> 而本节这句话当时是无条件承诺。复现输入很常见：根为数组、2 个元素、
> 每个元素带一段 2 万字符的字符串（Agent 工具输出最朴素的形态之一）。
> 现在只有"连字符串都没被截断"时才真的没有承载位置，那一种会如实记为
> `ARCHIVE_REF_NOT_EMBEDDABLE`——报告里看得见，不是静默丢弃。

归档是**内容寻址**（`ORIG-` + SHA-256 前 16 位）：同一段原文重复压缩只存一份，
且引用稳定——换了后端、重启了进程，同一个 ref 依然指向同一段内容。这正是它能落盘的前提。

两种后端：

| 后端 | 生命周期 | 适用 |
|---|---|---|
| `ContextArchive.inMemory()`（默认） | 进程内，有界 256 条 LRU | 压缩与取回在同一次运行里完成 |
| `FileContextArchive.open(path)` | 跨进程，追加式 | 先压缩写入、之后（甚至另一个进程）再取回 |

```java
ContextArchive archive = FileContextArchive.open(Path.of(".ctxpress/archive.log"));
ContextPress press = ContextPress.withArchive(Policy.builder().maxTokens(2000).build(), archive);
```

### token 口径：估算还是真实词表

同一份内容在两种口径下的数字能差 60%（实测同一份日志：启发式 68,571、`o200k_base` 109,398）。
所以报告里**永远**写着这次用的是哪一种：

```
LOG: 68571 -> 6048 tokens (-91.2%), 保护 12 段, 计数口径=heuristic（启发式估算口径，偏乐观）
LOG: 109398 -> 9612 tokens (-91.2%), 保护 12 段, 计数口径=o200k_base（真实词表口径）
```

估算偏乐观，意味着"按估算卡住预算"的内容真实计费可能超。要在意这一点时有两种做法：

```java
// ① 把预算当硬约束：计数器必须显式给出，省不掉
PressPolicy policy = PressPolicy.hardBudget(8000, new JtokkitTokenCounter(Vocabulary.O200K_BASE)).build();

// ② 继续用轻量的启发式，但按实测倍率垫高（倍数来自 benchmarks/ 的日志基准，别拍脑袋填）
TokenCounter padded = TokenEstimator.withSafetyMargin(TokenEstimator.BENCHMARK_ESTIMATE_GAP);
PressPolicy policy2 = PressPolicy.hardBudget(8000, padded).build();
```

| 计数器 | 报告里的名字 | 口径 | 依赖 |
|---|---|---|---|
| `TokenEstimator.defaultCounter()` | `heuristic` | 估算（偏乐观） | 无（核心模块自带） |
| `TokenEstimator.withSafetyMargin(x)` | `heuristic×1.60` | 估算 × 实测倍率 | 无 |
| `new JtokkitTokenCounter(Vocabulary.O200K_BASE)` | `o200k_base` | 真实词表 | `ctxpress-tokenizer-jtokkit` |

> 垫高倍率是**有代价的取舍**：估算垫得越高，压缩越激进。若你的文本不是标点密集的日志
> （例如以中文散文为主），`BENCHMARK_ESTIMATE_GAP` 会明显偏大，请换一个贴近自己语料的值。

> 归档里存的是**调用方给的原文**，不是归一化之后的副本——CRLF 输入经归一化后每行少一个字节，
> 存副本会让"逐字节取回"名不副实（实测 3000 行日志差 2999 字节）。

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

> ✅ **v0.4.3 修掉了 v0.4.2 的两处问题**，下载最新版即可：
>
> | v0.4.2 的问题 | v0.4.3 |
> |---|---|
> | `retrieve` 取回多写 1 个字节，「逐字节一致」不成立 | 已修（`ef382a4`）。**发布前对最终 jar 实跑过**：277282 → 277282 字节，sha256 一致，`cmp` 无差异 |
> | 内嵌 jackson-databind **2.19.0**，命中 5 条公告（2 HIGH） | 内嵌 **2.21.5**（`e7594a0`）。解压 jar 核对过 `pom.properties` |
>
> v0.4.2 是「产物落后于 master」的实例：它的 jar 里是修复之前的代码，而 master 上早已修好。
> 现在 `release.yml` 里有两道发版闸（依赖告警 + tag 是否落后且动了构建配置），
> 这类情况不会再静默发出。
>
> 如果你确实要用 v0.4.2：
> - **别信它的「逐字节一致」**——取回的文件会比原文多一个换行字节；
> - 它的内嵌 Jackson 建议在宿主工程里显式钉 `jackson-bom` ≥ 2.21.5。

### 方式二：从源码构建

```bash
./mvnw package

# 看能省多少（不产出内容）
java -jar ctxpress-cli/target/ctxpress.jar analyze --max-tokens 8000 app.log

# 压缩；结果走 stdout、报告走 stderr，因此重定向是安全的
cat huge.json | java -jar ctxpress-cli/target/ctxpress.jar compress --max-tokens 2000 > small.json

# 自定义保护规则
java -jar ctxpress-cli/target/ctxpress.jar compress --must-keep 'TRACE-[0-9A-F]+' app.log

# 可逆：压掉的原文进归档，报告行里给出引用，随时取回（逐字节一致）
java -jar ctxpress-cli/target/ctxpress.jar compress --max-tokens 2000 \
    --archive .ctxpress/archive.log app.log > small.log
#   → 报告(stderr)：LOG: 97999 -> 1973 tokens (-98.0%), …, 计数口径=o200k_base（真实词表口径）, 归档=ORIG-30f2518ba6415194

java -jar ctxpress-cli/target/ctxpress.jar retrieve \
    --archive .ctxpress/archive.log --ref ORIG-30f2518ba6415194 > restored.log
```

选项：`--max-tokens N` · `--kind JSON|LOG|TEXT` · `--must-keep REGEX` · `--head N` · `--tail N`
· `--tokenizer o200k_base|cl100k_base|r50k_base|p50k_base|heuristic`
退出码：`0` 成功 · `1` 用法错误 · `2` 读取失败 · `3` 内部错误 · `4` 引用不存在 —— 可直接用于 CI。

token 默认按 `o200k_base` 真实词表计，报告里会标注实际用的是哪个词表——
不标的话，"这个数字是按什么算的"根本无从判断。

输出**只含 LF**（不随平台变化），因为"确定性"包括跨平台字节一致。

作为库：

> ⚠️ **尚未发布到 Maven Central。** 下面的坐标只是本项目的 groupId/artifactId，
> 直接粘进 `pom.xml` 会**解析失败**（`repo1.maven.org/maven2/io/github/xiaoxusop/` 目前是 404）。
> 想现在就用，走下面「从 Release 安装」的两步。
>
> ```xml
> <dependency>
>     <groupId>io.github.xiaoxusop</groupId>
>     <artifactId>ctxpress-core</artifactId>
>     <version>0.4.3</version>
> </dependency>
>
> <!-- 可选：接真实 BPE 词表，让 token 预算变成可计费口径 -->
> <dependency>
>     <groupId>io.github.xiaoxusop</groupId>
>     <artifactId>ctxpress-tokenizer-jtokkit</artifactId>
>     <version>0.4.3</version>
> </dependency>
> ```

**从 Release 安装**（在 Maven Central 就绪之前，这是唯一能真正装上的方式）：

```bash
V=0.4.3
curl -LO https://github.com/XIAOXUsop/ctxpress/releases/download/v$V/ctxpress-core-$V.jar
curl -LO https://github.com/XIAOXUsop/ctxpress/releases/download/v$V/ctxpress-tokenizer-jtokkit-$V.jar

mvn install:install-file -Dfile=ctxpress-core-$V.jar \
  -DgroupId=io.github.xiaoxusop -DartifactId=ctxpress-core -Dversion=$V -Dpackaging=jar
mvn install:install-file -Dfile=ctxpress-tokenizer-jtokkit-$V.jar \
  -DgroupId=io.github.xiaoxusop -DartifactId=ctxpress-tokenizer-jtokkit -Dversion=$V -Dpackaging=jar
```

装完之后上面的 `<dependency>` 就能解析了。

- jar 内嵌的 POM 是**自包含**的：构建时 `flatten-maven-plugin` 会把父 POM 的继承与
  属性（如 `jackson.version`）解析进去，所以宿主工程**不需要**再手动声明
  Jackson、jtokkit 这类传递依赖。
  > **0.4.1 及更早的版本在这里是坏的**：那时内嵌的 POM 还带着
  > `<parent>ctxpress-parent</parent>`，而父 POM 并不随 Release 发布，
  > 宿主工程会直接报 `Could not find artifact io.github.xiaoxusop:ctxpress-parent:pom:<版本>`
  > ——也就是说，「从 Release 安装」这条路当时根本走不通。v0.4.2 起修复。
  > 这一条是照本文档亲手做了一遍才发现的。
- 装的是**本机仓库**，别人 clone 你的项目后同样要跑一遍上面的命令。
  在 CI 里用的话，把这两条 `install-file` 放进构建脚本的前置步骤。

**为什么还没上 Central——以及还差什么**

发布所需的配置已经全部就位，缺的只有凭据：

| 项 | 状态 |
|---|---|
| groupId `io.github.xiaoxusop` | 已确认（与 GitHub 账号对应，namespace 校验走这条路） |
| POM 元数据 | 已补 `licenses` / `scm` / `developers`，`mvn -Prelease -Dgpg.skip=true package` 会产出 `-sources.jar` 与 `-javadoc.jar` |
| 签名 | `maven-gpg-plugin` 已挂在 `release` profile 的 `verify` 阶段 |
| 上传 | `central-publishing-maven-plugin`，`autoPublish=false`（先停在 Portal 让人确认再发） |
| 工作流 | `.github/workflows/publish-central.yml`，**只能手动触发**且要求手打确认词 |

差的是三个 secrets：`MAVEN_CENTRAL_USERNAME`、`MAVEN_CENTRAL_PASSWORD`（Sonatype 用户令牌）
与 `GPG_PRIVATE_KEY` / `MAVEN_GPG_PASSPHRASE`。配好之后
`.github/workflows/publish-central.yml` 跑一次即可，之后本文档的 `<dependency>` 才算数。

> 发布流程刻意**不挂在 tag 上自动跑**：Central 的版本发布后不能撤下，
> 自动发布的代价是"打错一次 tag 就永久留痕"。

**发版前的两道闸**（`.github/workflows/release.yml`，打 tag 时跑）

打 tag 会触发构建，但**不是打了就一定发**——`scripts/release_preflight.py` 要先过两道：

| 闸 | 查什么 |
|---|---|
| 一 | 仓库里还有 `high`/`critical` 的开放依赖告警 → 拒绝发布 |
| 二 | 这个 tag 落后默认分支，且落后的提交动过 `pom.xml`/`mvnw`/`gradle` 配置 → 拒绝发布 |

两道都要，是因为**只查一道会漏掉这次真实发生的事**：仓库告警查的是默认分支当前的依赖图，
而 release 构建的是 tag 指向的那个 commit。v0.4.2 就是落在两道之间——
它的 jar 里是 jackson 2.19.0，而 master 上早已升到 2.21.5，查仓库告警时看到的是
**已经修好的 master**。闸二会把这个 tag 落下的 6 个提交逐条列出来，其中就有
`e7594a0 fix(deps): jackson 2.19.0 → 2.21.5` 和 `ef382a4`（逐字节一致那个修复）。
干净的是 master，不是那个产物。

另一条同样重要的约束：**查不动不等于通过**。HTTP 403/404（Dependabot 没开或
`GITHUB_TOKEN` 缺 `security-events: read`）、响应不是数组、级别字段不认识、
git 历史取不到——一律拒绝发布。这些情况在日志里和"没有告警"长得一模一样。

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
| 评测 | 下游精度（GSM8K / SQuAD / BFCL）| 保真（关键信息召回 + 保真证书，**离线可跑**）|

**选 ctxpress 的理由只有一条，但成立**：你在 Java / Spring Boot / LangChain4j 工程里，
而这套生态目前没有对标物——GitHub 上「上下文/记忆」主题的 Java 仓库**总共只有 23 个**，
headroom 用不了。

## 已知限制

- **没有下游任务的准确性评测。** headroom 用 GSM8K / SQuAD / BFCL 证明压缩不损失下游精度；
  ctxpress 有的是**保真评测**（关键信息召回 + 保真证书，见上文），
  证明的是"该留下的留下了、没留下原文没有的"，**不等于下游任务准确率不变**。
  差距在于：前者离线几秒可跑，后者要真调模型、要花钱。这是当前最大的缺口。
- **核心模块默认的 token 计数是启发式估算，不是可计费 token。** CJK 码点按 1 token、
  其余按 4 字符 1 token；实测同一份 3000 行日志它给 68,571 而 `o200k_base` 给 109,398，
  **低估 37%**。CLI 默认已换成真实词表；把 `ctxpress-core` 当库用时，
  引 `ctxpress-tokenizer-jtokkit`（约 3.2MB，自带 BPE 词表）。
- **fat jar 约 5.6MB。** 其中约 3.2MB 是 BPE 词表。想要更小就自建，
  或只用 `ctxpress-core`（约 35KB + Jackson）。
- **受保护内容是预算的下界。** 一份每行都含 ERROR 的日志在那部分内容装进预算之前，
  任何预算都满足不了——此时报告会给出 `overBudgetBy` 而不是硬压。
  调高 `maxProtectedRatio` 可以放宽保护范围。
- **压缩会注入字段，破坏强类型反序列化**（`_omitted` / `_ctxpress_archive`），请用 `JsonNode` 接收。
- **NDJSON / 一行一文档的 JSON 被当作非法 JSON。** 解析器开了尾随 token 检查——
  早先不检查时，`{"a":1}\n{"b":2}\n{"c":3}` 会被静默截成第一个文档，
  报告里却是"压缩成功、无省略、不可逆"。宁可判为非法交给日志压缩器，也不静默丢数据。
- **单条消息级压缩，不做会话级调度。** 何时压、压到多少，由调用方决定；
  本库不管理会话历史，也不做类型化保留与依赖感知驱逐。
- **归档默认只存内存。** 需要跨进程取回就用 `FileContextArchive.open(path)`（追加式文件，
  命令行加 `--archive`）。文件后端只增不改：删除不做，因为归档的价值在于"还在"。
- **不做语义摘要。** 要不要"用模型概括历史"是另一个问题；本库只做确定性压缩。

## 测试

```bash
./mvnw test      # 98 项，全部离线（core 73 + tokenizer 10 + cli 15）
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
12. **`compress` / `retrieve` 的输出都无条件多一个换行** —— 两处都写了
    `out.print('\n')`（大约是为了让终端提示符另起一行），代价是被处理的数据
    被改写了：
    - `retrieve`：取回的文件永远比原文多一个换行，「逐字节一致」这条承诺
      **从来就不成立**。实测 4000 行日志取回后 251628 字节 vs 原文 251627，
      sha256 对不上——而归档引用 `ORIG-xxx` 正是原文内容的 sha256 前缀，
      **用户拿它一校验就会发现**
    - `compress`：契约里写着「输出不含原文没有的内容：每个字节要么逐字来自输入，
      要么属于已声明的标记文法」，补的那个换行两条都不满足。实测同一份日志
      在足够大的预算下输出 251628 字节（多 1 字节），「内容放得下时不做任何改动」
      在字节层面不成立

    这条之所以长期隐身，是因为两处回归用例都用了 `stripTrailing()`：
    `assertEquals(original, retrieved.out().stripTrailing(), "必须逐字节相同")`
    ——**断言信息写着「逐字节」，代码却把尾部空白剥掉再比**，正好把多出来的换行抹平；
    它还顺带掩盖了更糟的情况：原文末尾有多个换行时少写一个也不会被发现。
    测试数据的构造恰好也不以换行结尾，于是「追加」与「丢失」两个方向全被盖住

    > ⚠️ **这条的修复不在 v0.4.2 及更早的任何版本里。** 它挂在本节「v0.2.0 修复的」分组下，
    > 但那个分组是错的——逐 tag 数过 `Main.java` 里 `out.print` 的出现次数：
    > **v0.1.0 是 2 次、v0.2.0 起 4 次、v0.3.0 起到 v0.4.2 一直是 6 次**。
    > 也就是说这些无条件换行是逐版**加进去**的，**从来没有任何一个版本移除过它**。
    > 真正的修复是 `ef382a4`（2026-09-19），晚于 v0.4.2。
    >
    > 实测确认（2026-09-19，从 Release 下载产物实跑，输入 347652 字节）：
    > **v0.4.2 取回 347653 字节（多 1 字节）**；master 构建的同一链路取回 347652（一致）。
    > 归档引用 `ORIG-xxx` 正是原文内容的 sha256 前缀——用户拿它一校验就会发现。
    >
    > ✅ **`v0.4.3` 起已发布**。发布前对最终 jar 实跑过同一条链路：
    > 277282 → 277282 字节、sha256 一致、`cmp` 无差异。

13. **NDJSON 被静默截断** —— 420 token 的 30 行 NDJSON "压缩"到 14 token，
    报告里 `truncated=false`、无省略标注，看不出丢了 96.7%
14. **切句正则爆栈** —— 交替结构让正则引擎每次迭代递归一层栈，3800 字符无句末标点的输入直接 `StackOverflowError`
15. **`{"_omitted":0}` 注入** —— 没超上限的数组被塞进一个记账节点，还被标成"已截断"
16. **CLI 缺参抛裸栈迹**、异常路径中文乱码、输出在 Windows 上多出 CRLF、未知命令报错类型错
17. **release 资产路径多一层前缀** —— core 的 jar 永不匹配、静默缺失

v0.3.0 的（前两轮的漏网之鱼）：

18. **归档存的是归一化副本而非原文** —— CRLF 输入每行少一个字节，3000 行日志差 2999 字节，
    "逐字节取回"名不副实。端到端实跑（压缩 → 另起进程取回 → 比对字节）才测出来
19. **可逆在命令行完全不可达** —— 归档只在 Java API 可用，而 README 整节「可逆」
    面向的正是命令行用户。等于把最核心的差异化能力锁在了 API 里
20. **中段按"均匀撒孤立点"采样** —— 孤立点夹在两段省略区之间要多付一个省略标记
    （标记约 7 token、一行日志约 11 token），同样预算下能覆盖的位置远少于连续块。
    保真评测量到未受保护召回一度低于最朴素的均匀行采样，改为铺连续块后翻倍
21. **启发式 token 估算低估 37%** —— 同一份日志它给 68,571、`o200k_base` 给 109,398

## License

[MIT](LICENSE) © 2026 XIAOXUsop
