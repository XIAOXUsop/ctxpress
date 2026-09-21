#!/usr/bin/env python3
"""离线保真评测：压掉之后，该留下的还在不在？

这是 README「已知限制」里那条最大缺口的一半答案。之所以不去做 GSM8K / SQuAD 那类
下游精度评测：那需要 API key、要花钱，而且**对抽取式压缩器用错了指标**——
GSM8K 量的是数学推理能力，跟"日志里那个错误码还在不在"没有关系。
真正相关的量是**关键信息召回**与**输出保真**，两者都不需要模型、几秒跑完。

三个指标：

  ① needle recall @ budget  —— 埋进去的关键信息有多少逐字留在了输出里
  ② 对照组                  —— 同样预算下，朴素头截断 / 均匀行采样能留下多少
  ③ 保真证书                —— 输出的每一行要么逐字来自输入，要么匹配已声明的标记文法

第 ② 项不可省：**没有对照组的召回率不可解释**。"召回 67%"是好是坏，
取决于"什么都不做"能拿多少。

计数口径说明：ctxpress 与两个对照组都用 `--tokenizer heuristic`
（CJK 1 token、其余 4 字符 1 token）。三个方法用同一把尺子，比较才成立；
真实词表下的预算契约另见 budget_matrix.py。

用法：
    python benchmarks/fidelity.py
"""
import io
import os
import random
from decimal import Decimal
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAR = os.path.join(ROOT, "ctxpress-cli", "target", "ctxpress.jar")
REPORT_PATH = os.path.join(ROOT, "benchmarks", "fidelity-report.json")

SEED = 20260911
BUDGETS = [500, 1000, 2000, 4000, 8000, 16000]
LINES = 3000

# 命中默认保护规则的关键信息（编号类 / 长十六进制）
PROTECTED_NEEDLES = ["AML-70000007", "CVE-2020-1967", "a3f19c2e7b4d6081"]

# 不命中任何保护规则的普通标识符——用来分别观察"保护"与"采样"各自的作用。
#
# 数量取 32 而不是三五个：召回率是比例量，样本太小时"0% 对 25%"这种差异
# 完全可能是运气，据此下结论会误导改进方向。
PLAIN_NEEDLES = ["%s%04d" % ("".join(chr(ord("A") + (i * 7 + j * 3) % 26) for j in range(4)), i * 137 % 10000)
                 for i in range(32)]

MARKER = re.compile(r"^\.\.\. \[省略 \d+ 行(；[^\]]*)?\] \.\.\.$")

# 折叠标记：`<原文行>   [重复 N 次]`。
#
# 它**不在**上面那条 MARKER 里，于是这条路径上会出一类很隐蔽的误报：
# 折叠后的行既不等于任何一行原文、又不匹配 MARKER，就被判成“凭空生成”。
# 实测（2026-09-22）：拿一份含 300 行相邻重复的日志跑，证书报的那一行正是
# 压缩器自己产出的折叠标记——**证书在冤枉压缩器**。
#
# 而主语料（build_corpus）每一行都带不同的 order 号，永远产生不出相邻重复，
# 所以这条路径从来没被走到过：被测集合是空的，检查却一直绿。
FOLDED = re.compile(r"^(?P<base>.+?)\s+\[重复 (?P<count>\d+) 次\]$")


def estimate(text):
    """与 ctxpress 的启发式计数器同口径：CJK 码点 1 token，其余 4 字符 1 token"""
    cjk = sum(1 for ch in text if "一" <= ch <= "鿿" or "　" <= ch <= "〿"
              or "＀" <= ch <= "￯")
    other = len(text) - cjk
    return cjk + (other + 3) // 4


def build_corpus():
    """一份逼真的应用日志，把 needle 埋在头部、中部、尾部"""
    random.seed(SEED)
    lines = []
    for i in range(LINES):
        lines.append(
            "2026-09-11 10:%02d:%02d INFO  order %d processed in %dms, amount %d.%02d CNY, customer C-%06d"
            % (i // 60 % 60, i % 60, i, 5 + i % 900, 10 + i % 99999, i % 100, i % 99999))
    # 埋点：位置固定，便于复现与定位。
    #
    # 两组必须落在**不同保护状态**的行上，否则量不出"保护"与"采样"各自的贡献：
    # 保护组放在 ERROR 行（命中故障级别规则），普通组放在 INFO 行（不命中任何规则）。
    # 早先把两组都放在 ERROR 行，结果两组都是 100%，评测毫无区分度。
    placements = {}
    all_needles = PROTECTED_NEEDLES + PLAIN_NEEDLES
    for index, needle in enumerate(all_needles):
        position = (LINES - 1) * index // len(all_needles)
        level = "ERROR" if needle in PROTECTED_NEEDLES else "INFO "
        lines[position] = (
            "2026-09-11 10:00:00 %s trace %s at offset %d" % (level, needle, position))
        placements[needle] = position
    return "\n".join(lines), placements


# ---------- 对照组：同样预算下，不用 ctxpress 能做到多少 ----------

def head_truncate(text, budget):
    """最朴素的做法：从开头往下塞，塞不下就停"""
    kept, used = [], 0
    for line in text.split("\n"):
        cost = estimate(line) + 1
        if used + cost > budget:
            break
        kept.append(line)
        used += cost
    return "\n".join(kept)


def uniform_sample(text, budget):
    """均匀行采样：按固定步长取行——比头截断"公平"，但同样没有保护概念"""
    lines = text.split("\n")
    for stride in range(1, max(2, len(lines))):
        chosen = lines[::stride]
        if sum(estimate(line) + 1 for line in chosen) <= budget:
            return "\n".join(chosen)
    return lines[0]


def ctxpress(path, budget):
    """压缩结果走 stdout —— 拿它当输出；报告走 stderr，这里丢弃"""
    with open(path + ".out", "w", encoding="utf-8") as sink:
        subprocess.run(
            ["java", "-jar", JAR, "compress", "--max-tokens", str(budget),
             "--tokenizer", "heuristic", path],
            timeout=180, check=True, stdout=sink, stderr=subprocess.DEVNULL)
    return io.open(path + ".out", encoding="utf-8").read()


def recall(output, needles):
    hit = [n for n in needles if n in output]
    return len(hit) / len(needles) if needles else 0.0

def provenance(text, output):
    """保真证书：输出的每一行要么逐字来自输入，要么匹配已声明的省略标记文法。

    这条是抽取式压缩相对摘要式压缩的硬优势——摘要可以有幻觉，
    而“只做抽取 + 只加已声明的记账标记”在构造上就产生不了原文没有的内容。
    """
    source = set(text.split("\n"))
    fabricated = []
    for line in output.split("\n"):
        if not line or line in source or MARKER.match(line):
            continue
        folded = FOLDED.match(line)
        # 折叠行只有在**它的前缀逐字来自输入**时才算合规——
        # 不比这一条的话，任何「<随便什么>   [重复 N 次]」都能混过去
        if folded and folded.group("base") in source:
            continue
        fabricated.append(line)
    return fabricated


def check_folding_provenance():
    """专门走**折叠**那条路径：证书此前对它误报，而主语料根本产生不出折叠。"""
    block = "2026-09-11 10:00:00 WARN  connection pool at 90% capacity, retrying"
    filler = [
        "2026-09-11 10:0%d:%02d INFO  order %d processed in %dms, customer C-%06d"
        % (i // 60 % 60, i % 60, i, 5 + i % 900, i % 99999)
        for i in range(400)
    ]
    text = "\n".join([block] * 300 + filler)
    path = os.path.join(ROOT, "benchmarks", "data", "fidelity-folding.log")
    io.open(path, "w", encoding="utf-8").write(text)
    out = ctxpress(path, 300)
    return provenance(text, out), out

# ── 另外两档的标记文法：JSON 与 TEXT ──────────────────────────────────
#
# 证书的判据是「输出的每个字节要么逐字来自输入，要么属于已声明的标记文法」。
# 日志那一档早就有 MARKER / FOLDED 了，而 **JSON 与 TEXT 两档的标记从来没进过证书**——
# 两组语料都是日志。这正是漏掉下面那个缺陷的盲区：
# 「仅去空白」那一档会**静默改写数值字面量**（`1e400` -> `"Infinity"`、
# `99999999999999999999.99` -> `1.0E20`），而证书看不见 JSON 语料。
#
# 补的时候要一并补正则——不补就直接喂，会像 FOLDED 那次一样**冤枉压缩器**。
JSON_STRING_MARKER = re.compile(r"…\[\d+ 字符已省略(；[^\]]*)?\]…")
# 注意用**非捕获组** (?:...)：`re.split` 会把它捕获到的组也放进结果里，
# 于是 `piece` 可能是 None——第一次跑就是这么崩的（好在那是一声闷响，不是静默通过）。
TEXT_SENTENCE_MARKER = re.compile(r" ?…\[省略 \d+ 句(?:；[^\]]*)?\]")

# 压缩器自己写进 JSON 的记账键——属于已声明的文法，不算"原文没有的内容"
JSON_DECLARED_KEYS = ("_omitted", "_ctxpress_archive")

# 数值字面量：**档位一按字面保，档位二/三按值保**。
#
# 这几个是精选的：前两个超出 double 精度（会被改写成 `1.0E20` / `1.2345678901234568E16`）、
# `1e400` 超上界（double 变 inf，序列化出来是字符串 `"Infinity"`）、`1e-400` 下溢成 0、
# `100.00` 尾随零（丢零不改变值，只有 `compare_total` 抓得住）。
#
# 它们各自代表一类"看起来差不多、其实变了"的形态，而两个档位要守的线不一样：
# 档位一（仅去空白）守**字节**，档位二/三（解析再序列化）守**值**。
# 混用会让检查对正确行为喊狼来了——详见 `check_number_literals` 的文档串。
NUMBER_FIXTURE = [
    "99999999999999999999.99",
    "12345678901234567.89",
    "1e400",
    "1e-400",
    "100.00",
]


class _Number(str):
    """`parse_float` / `parse_int` 交出来的**字面**。

    刻意做成 `str` 的子类：只有这样才能在树里把「数值」和「字符串」分开认。
    不加这一层的话，`parse_float=str` 把两者都变成 `str`，
    `walk` 里那个数值分支**永远走不到**（`isinstance(value, str)` 先命中）——
    数值改写会从"字符串"那条分支报出来，措辞还写着「的字符串」。
    实测（2026-09-22）：`1E+400` 被报成 `$.amount02 的字符串 '1E+400'`，
    看报告的人会以为压缩器把数字变成了字符串。
    """


def check_json_provenance(text, output, numbers_by_value=False):
    """JSON 路径的保真证书：**值级**比对。

    不能像日志那样按行比——JSON 压完通常是一整行，而且"去掉空白"本身就改变了字节。
    改成走一遍输出的树：每个键、每个字符串、每个数值都必须在**输入文本里**
    逐字找得到（字符串额外接受它的 JSON 转义写法）。

    数值用 parse_float=str / parse_int=str 取**字面**，不做数值归一——
    这一条正是为了抓住 "1e400 -> Infinity" 那类改写：值旁边看着差不多、字面完全不同，
    而它既不是输入的字节、也不是任何标记。

    `numbers_by_value=True` 用在**第二、三档**（有损裁剪档）上。那两档走的是
    "解析再序列化"，数值的**拼写**会被归一（实测 `1e400` → `1E+400`、`1e-400` → `1E-400`），
    而 README 给这两档写明的契约是保**值**不是保字节。在那里继续按字面判，
    就会把压缩器**已经写明的**行为报成缺陷——一条对正确行为喊狼来了的检查，
    最后会被人关掉。所以按值比：交给 `check_number_values` 的 `compare_total` 逐字段严格比。

    注意 `compare_total` 不比"字面相等"松，反而更严：`100.00` → `100.0` 这种**尾随零丢失**
    在字面比法下会漏（`"100.0"` 是输入 `100.00` 的子串），按值比才抓得住。
    """
    import json

    stripped = JSON_STRING_MARKER.sub("", output)
    try:
        node = json.loads(stripped, parse_float=_Number, parse_int=_Number)
    except ValueError as error:
        return ["<输出不是合法 JSON：%s>" % error]

    fabricated = []

    def walk(value, path):
        if isinstance(value, dict):
            for key, sub in value.items():
                if key in JSON_DECLARED_KEYS:
                    continue
                if key not in text:
                    fabricated.append("%s 的键 %r 不在输入里" % (path, key))
                walk(sub, path + "." + key)
        elif isinstance(value, list):
            for index, sub in enumerate(value):
                walk(sub, "%s[%d]" % (path, index))
        elif isinstance(value, _Number):
            # **数值**：先认 `_Number` 再认 `str`，两者是父子关系，顺序不能反。
            if numbers_by_value:
                # 有损档（解析再序列化）：比**值**。字面拼写可以归一——
                # 那是这两档写明的行为，不是缺陷。`compare_total` 连精度与尾随零一起管住。
                if value in text:
                    return
                if not any(_same_number(value, candidate)
                           for candidate in NUMBER_TOKEN.findall(text)):
                    fabricated.append("%s 的数值 %s 与任何输入字面都不等值" % (path, value))
            elif value not in text:
                # 无损档（仅去空白）：比**字面**。这一条正是为抓住
                # "1e400 -> Infinity" 那类改写：值旁边看着差不多、字面完全不同。
                fabricated.append("%s 的数值 %r 不在输入里" % (path, value))
        elif isinstance(value, str):
            if not value:
                return
            escaped = json.dumps(value, ensure_ascii=False)[1:-1]
            if value not in text and escaped not in text:
                fabricated.append("%s 的字符串 %r 不在输入里" % (path, value[:50]))

    walk(node, "$")
    return fabricated


def check_json_corpus():
    """真实工具输出形态的 JSON：跑证书，也顺带看它认不认得已声明的标记。"""
    path = os.path.join(ROOT, "benchmarks", "data", "mcp-search-results.json")
    text = io.open(path, encoding="utf-8").read()
    out = ctxpress(path, 800)
    fabricated = check_json_provenance(text, out)
    os.remove(path + ".out")
    return fabricated


# 输入 JSON 里形如数值的那些 token（够用了：这份语料里的数值都是简单字面）。
NUMBER_TOKEN = re.compile(r"-?\d+(?:\.\d+)?(?:[eE][-+]?\d+)?")


def _same_number(left, right):
    """两个数值字面是不是同一个值——**精度与尾随零也算**。

    `compare_total` 而不是 `==`：`Decimal("100.00") == Decimal("100.0")` 是 True，
    但那是精度丢失；`compare_total` 会把它判为不同。
    """
    try:
        return Decimal(left).compare_total(Decimal(right)) == 0
    except Exception:
        return False


def check_number_values(output):
    """第二、三档的**值**保真：逐字段比，值 / 精度 / 尾随零都要对上。

    字段被整段截掉**不算错**——那两档按设计就是有损的，丢字段是它们的正常工作。
    这里管的是"留下来的那些有没有被改过"。
    """
    found = dict(AMOUNT_FIELD.findall(output))
    bad = []
    for index, literal in enumerate(NUMBER_FIXTURE):
        key = "%02d" % index
        if key not in found:
            continue
        raw = found[key]
        if raw.startswith('"'):
            bad.append("amount%s 从数值变成了字符串 %s" % (key, raw))
        elif not _same_number(raw, literal):
            bad.append("amount%s 的值被改写：%s → %s" % (key, literal, raw))
    return bad


AMOUNT_FIELD = re.compile(r'"amount(\d{2})"\s*:\s*("(?:[^"\\]|\\.)*"|[^,}\s]+)')


def check_number_literals():
    """数值保真：**档位一逐字节不动，档位二/三逐值不动**。

    ── 这条检查被三次发现是空的，三次都记在这里 ──────────────────────
    第一次：语料一次造好就去跑，40 个长字符串值把预算占满，`amountNN` 那几个字段
      在三条预算下**全被截掉了**。证书的判据是「输出里出现的值必须来自输入」，
      输出里根本没有它们，于是**无论压缩器改不改写都恒绿**。
      实测（2026-09-22）：把「仅去空白」改回旧的解析再序列化实现，依旧报 0 处被改写。
    第二次：`"amount00":"9999…"` 写成了**字符串**——字符串在"解析再序列化"里
      是原样搬运的，改不改写数值都测不出东西。一个字符的差别让整条检查失去意义。
    第三次（同一天）：小语料缩进太浅，232 字节 / 58 token，而 CLI 的 `--max-tokens`
      下限是 **64**——**任何合法预算下**压缩器都判定"本来就装得下"，直接 NO_OP 原样返回。
      扫遍 64..3200 全预算也挑不出一个"确实做了事"的档位：检查看着在跑，一次都没量到东西。

    所以现在拆成两半，每一半都**先证明尺子量到了东西**：
      ① 小语料——每行缩进 600 空格，原始 783 token → 最小化 31 token，
         预算 64 稳稳落进「仅去空白」档（动作 `MINIFIED_ONLY`）。
         这一档承诺**逐字节不动**：五条字面量必须都在，且原样。
         输出与输入相同（说明根本没压）或少了字面量，都判为"尺子没量到"，直接失败。
      ② 大语料——长字符串垫底把预算占满，逼出解析再序列化。
         这一档承诺的是**值不动**，字节形态可以归一（`1e400` → `1E+400`，
         这是重新序列化的固有代价，README 已按这个分寸写明）。
    """
    # **必须是裸数值，不能写成字符串。**（见上面第二次的记录）
    body = ",".join('"amount%02d":%s' % (i, literal)
                    for i, literal in enumerate(NUMBER_FIXTURE))
    # 直接拼字面量，**不经过 Python 的 float**——
    # json.dumps({"a": 99999999999999999999.99}) 写出来的时候它就已经变质了，
    # 拿那样的输入去测，等于用一把没验过的尺子。（这不是假设，是踩过的。）
    #
    # 缩进够深是**硬约束**（见上面第三次的记录）：`--max-tokens` 下限 64，
    # 而「仅去空白」只在 原始 token > 预算 ≥ 最小化后 token 时才被走到。
    INDENT = " " * 600
    small = (
        "{\n"
        + ",\n".join(
            '%s"amount%02d": %s' % (INDENT, i, lit)
            for i, lit in enumerate(NUMBER_FIXTURE)
        )
        + "\n}"
    )
    pad = "".join('"pad%02d":"%s",' % (i, "x" * 400) for i in range(8))
    large = "{" + pad + body + "}"

    path = os.path.join(ROOT, "benchmarks", "data", "fidelity-numbers.json")
    fabricated = []

    # ① 档位一：逐字节不动。先扫出一个**真的做了事**的预算，再验字节。
    #
    # 两步分开判，别混：混在一起的话，字面量真被改写时也会报成"没找到档位"，
    # 把实质问题说成尺子问题——**这两种失败要能分辨，否则下次又不知道信哪句**。
    io.open(path, "w", encoding="utf-8").write(small)
    exercised = None
    for budget in (64, 96, 128, 200, 400, 800, 1600, 3200):
        out = ctxpress(path, budget)
        os.remove(path + ".out")
        if out.rstrip("\n") != small:
            exercised = (budget, out)
            break
    if exercised is None:
        fabricated.append(
            "扫遍 64..3200 的预算，小语料上输出始终与输入逐字节相同"
            "——「仅去空白」这一档没被走到，检查没量到东西")
    else:
        budget, out = exercised
        missing = [lit for lit in NUMBER_FIXTURE if lit not in out]
        if missing:
            fabricated.append(
                "小语料预算 %d：档位一承诺逐字节不动，但 %s 在输出里找不到原文形态"
                % (budget, "、".join(missing)))
        fabricated += ["小语料预算 %d：%s" % (budget, f)
                       for f in check_json_provenance(small, out)]

    # ② 档位二/三：值不动。留下来的每一个 amountNN 都要与原字面**等值且等精度**。
    io.open(path, "w", encoding="utf-8").write(large)
    for budget in (200, 800):
        out = ctxpress(path, budget)
        os.remove(path + ".out")
        fabricated += ["大语料预算 %d：%s" % (budget, f)
                       for f in check_json_provenance(large, out, numbers_by_value=True)]
        fabricated += ["大语料预算 %d：%s" % (budget, f)
                       for f in check_number_values(out)]

    return fabricated


def check_text_provenance():
    """TEXT 路径：压完保留整句，用 `…[省略 N 句]` 连接。

    剥掉标记之后，剩下的每一段都应当**逐字来自输入**。
    """
    path = os.path.join(ROOT, "benchmarks", "data", "en-prose.txt")
    text = io.open(path, encoding="utf-8").read()
    out = ctxpress(path, 300)
    fabricated = []
    for piece in TEXT_SENTENCE_MARKER.split(out):
        piece = piece.strip()
        if piece and piece not in text:
            fabricated.append(piece[:80])
    os.remove(path + ".out")
    return fabricated


def main():
    if not os.path.exists(JAR):
        sys.exit("未找到 %s —— 先跑 ./mvnw -B package" % JAR)

    text, placements = build_corpus()
    path = os.path.join(ROOT, "benchmarks", "data", "fidelity.log")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    io.open(path, "w", encoding="utf-8").write(text)

    rows = []
    for budget in BUDGETS:
        out = ctxpress(path, budget)
        head = head_truncate(text, budget)
        uni = uniform_sample(text, budget)
        fabricated = provenance(text, out)
        rows.append({
            "budget": budget,
            "ctxpress_protected": recall(out, PROTECTED_NEEDLES),
            "ctxpress_plain": recall(out, PLAIN_NEEDLES),
            "head_protected": recall(head, PROTECTED_NEEDLES),
            "head_plain": recall(head, PLAIN_NEEDLES),
            "uniform_protected": recall(uni, PROTECTED_NEEDLES),
            "uniform_plain": recall(uni, PLAIN_NEEDLES),
            "fabricated_lines": len(fabricated),
            "output_bytes": len(out.encode("utf-8")),
        })
        os.remove(path + ".out")

    header = ("  budget  | ctxpress P/P  | head P/P | uniform P/P | 保真证书\n"
              "          | 保护/普通    | 保护/普通 | 保护/普通   |\n"
              + "-" * 62)
    print("needle recall @ budget  (P=命中保护规则的关键信息, 普通=不命中)")
    print(header)
    for r in rows:
        print("  %6d  |  %3.0f%% / %3.0f%%  | %3.0f%% /%3.0f%% | %3.0f%% /%3.0f%%  | %d 行凭空生成"
              % (r["budget"],
                 r["ctxpress_protected"] * 100, r["ctxpress_plain"] * 100,
                 r["head_protected"] * 100, r["head_plain"] * 100,
                 r["uniform_protected"] * 100, r["uniform_plain"] * 100,
                 r["fabricated_lines"]))

    worst_protected = min(r["ctxpress_protected"] for r in rows)
    worst_head = max(r["head_protected"] for r in rows)
    total_fabricated = sum(r["fabricated_lines"] for r in rows)
    print()
    print("关键信息召回最低值: ctxpress %.0f%%  vs  头截断最好情况 %.0f%%"
          % (worst_protected * 100, worst_head * 100))
    print("保真证书: 全部 %d 个用例共 %d 行凭空生成" % (len(rows), total_fabricated))

    # ── 折叠路径要**单独**跑一遍 ──────────────────────────────────────
    #
    # 上面那组用例的语料每一行都带不同的 order 号，**永远产生不出相邻重复**，
    # 所以压缩器的折叠分支（`<行>   [重复 N 次]`）从来没有被走到过——
    # 而证书此前对它**误报**（把压缩器自己产出的折叠标记判成"凭空生成"），
    # 只是因为走不到，所以一直没红。这是"被测集合是空的"那一族。
    folding_fabricated, _ = check_folding_provenance()
    print("保真证书（折叠路径）: %d 行凭空生成" % len(folding_fabricated))

    # ── JSON 与 TEXT 两档也要各跑一遍 ────────────────────────────────
    #
    # 上面的主语料与折叠用例**都是日志**，JSON / TEXT 两条路径从来没进过证书。
    # 而"会凭空造字节"的那个缺陷恰好长在 JSON 上（数值被静默改写），
    # 于是证书对它是沉默的。下面三组把另外两档的标记文法也覆盖上。
    json_fabricated = check_json_corpus()
    print("保真证书（JSON 语料）: %d 处凭空生成" % len(json_fabricated))

    number_fabricated = check_number_literals()
    print("保真证书（数值：档位一逐字节 / 档位二三逐值）: %d 处被改写" % len(number_fabricated))

    text_fabricated = check_text_provenance()
    print("保真证书（TEXT 语料）: %d 段凭空生成" % len(text_fabricated))

    import json
    io.open(REPORT_PATH, "w", encoding="utf-8").write(
        json.dumps({"placements": placements, "rows": rows},
                   ensure_ascii=False, indent=2))
    print("wrote %s" % REPORT_PATH)

    assert total_fabricated == 0, "输出里出现了原文没有的内容"
    assert not folding_fabricated, (
        "折叠路径上出现了原文没有的内容：%s" % folding_fabricated[:3])
    assert not json_fabricated, (
        "JSON 路径上出现了原文没有的内容：%s" % json_fabricated[:3])
    assert not number_fabricated, (
        "数值保真被破坏——档位一承诺逐字节不动、档位二/三承诺逐值不动：%s"
        % number_fabricated[:3])
    assert not text_fabricated, (
        "TEXT 路径上出现了原文没有的内容：%s" % text_fabricated[:3])


if __name__ == "__main__":
    main()
