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
    而"只做抽取 + 只加已声明的记账标记"在构造上就产生不了原文没有的内容。
    """
    source = set(text.split("\n"))
    fabricated = [line for line in output.split("\n")
                  if line and line not in source and not MARKER.match(line)]
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

    import json
    io.open(REPORT_PATH, "w", encoding="utf-8").write(
        json.dumps({"placements": placements, "rows": rows},
                   ensure_ascii=False, indent=2))
    print("wrote %s" % REPORT_PATH)

    assert total_fabricated == 0, "输出里出现了原文没有的内容"


if __name__ == "__main__":
    main()
