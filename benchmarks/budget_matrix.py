#!/usr/bin/env python3
"""预算契约矩阵：语料 × 预算，逐格检查「输出 <= 你给的预算」。

这是 ctxpress 唯一可对外引用的核心数字的来源。之所以要单独跑这个矩阵而不是
只跑单元测试：预算是**上下文属性**，同一个压缩器在不同预算下的行为差异极大
（省略标记的数量随预算变化，而标记本身也占 token），固定几组单元测试覆盖不到。

判定口径（三分类）：
  - fits          内容本来就装得下 —— 按契约应当原样返回，不计入遵守率分母
  - ok            压缩后 <= 预算
  - declared      压缩后 > 预算，但报告显式声明了不可满足（受保护内容本身就超预算）
  - violated      压缩后 > 预算，且未声明 —— **这就是违约**

用法：
    python benchmarks/generate.py        # 先生成语料
    ./mvnw -B package                    # 再构建 CLI
    python benchmarks/budget_matrix.py   # 跑矩阵
"""
import json
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA = os.path.join(ROOT, "benchmarks", "data")
JAR = os.path.join(ROOT, "ctxpress-cli", "target", "ctxpress.jar")

BUDGETS = [200, 500, 1000, 2000, 4000, 8000, 16000, 32000, 64000, 128000]

# README 里那张「不同预算下的压缩后 token」表用的档位。
#
# 它与上面那十档**不重合**（矩阵是 200...128000），所以只能单独复算一遍。
# 为什么值得单独做：那张表是本仓库对外引用最多的一组数字，而此前**没有任何东西守着它**
# ——上面的门禁只查 violated / underfilled，从不比对任何具体数字，所以表里的值漂了
# 很久没人发现（2026-09-22：8000 那格写 7,942，实测 7,920）。
README_BUDGETS = [8000, 20000, 60000, 150000]

CORPORA = [
    "app.log",
    "bracketed.log",
    "mcp-search-results.json",
    "big-array.json",
    "rag-chunks.txt",
    "en-prose.txt",
]

# 报告行形如：LOG: 68571 -> 63999 tokens (-6.7%), 保护 60 段, 动作=[...]
REPORT = re.compile(
    r"^(JSON|LOG|TEXT):\s*(\d+)\s*->\s*(\d+)\s*tokens\s*\((-?[\d.]+)%\)")


def run_case(path, budget):
    """跑一次 analyze，返回 (detected_kind, original, compressed, actions)。"""
    proc = subprocess.run(
        ["java", "-jar", JAR, "analyze", "--max-tokens", str(budget), path],
        capture_output=True, timeout=120)
    out = proc.stdout.decode("utf-8", errors="replace").strip()
    line = out.splitlines()[0] if out else ""
    m = REPORT.match(line)
    if not m:
        raise RuntimeError("无法解析报告行：%r" % line)
    kind, orig, comp = m.group(1), int(m.group(2)), int(m.group(3))
    return kind, orig, comp, line


def parse_readme_budget_table():
    """Read the budget table out of README.md -> {budget: compressed}."""
    import io
    path = os.path.join(ROOT, "README.md")
    text = io.open(path, encoding="utf-8").read()
    anchor = text.find("| 你给的预算 | 压缩后 | 变化 |")
    if anchor < 0:
        return None
    rows = {}
    for line in text[anchor:].splitlines()[2:]:
        m = re.match(r"^\|\s*([\d,]+)\s*\|\s*([\d,]+)\s*\|", line)
        if not m:
            break
        rows[int(m.group(1).replace(",", ""))] = int(m.group(2).replace(",", ""))
    return rows


def check_readme_table():
    """Re-run app.log at the README's budgets and compare, cell by cell."""
    claimed = parse_readme_budget_table()
    if claimed is None:
        # 找不到那张表就**明说**，不要静默跳过——"查不动"与"没问题"是两回事
        return ["README budget table not found (anchor row missing) - nothing was checked"]
    app_log = os.path.join(DATA, "app.log")
    if not os.path.exists(app_log):
        return ["benchmarks/data/app.log missing - run benchmarks/generate.py first"]

    problems = []
    for budget in README_BUDGETS:
        if budget not in claimed:
            problems.append("README table has no row for budget %d" % budget)
            continue
        _, _, compressed, _ = run_case(app_log, budget)
        if claimed[budget] != compressed:
            problems.append("budget %d: README says %d, actual %d"
                            % (budget, claimed[budget], compressed))
    return problems


def main():
    if not os.path.exists(JAR):
        sys.exit("未找到 %s —— 先跑 ./mvnw -B package" % JAR)

    rows = []
    for name in CORPORA:
        path = os.path.join(DATA, name)
        if not os.path.exists(path):
            sys.exit("未找到 %s —— 先跑 python benchmarks/generate.py" % path)
        for budget in BUDGETS:
            kind, orig, comp, line = run_case(path, budget)
            if orig <= budget:
                verdict = "fits"
            elif comp <= budget:
                verdict = "ok"
            elif "预算未满足" in line:
                # 压缩器如实声明了"受保护内容本身超预算"——按契约算合规，单独计数
                verdict = "declared"
            else:
                verdict = "violated"
            rows.append({
                "corpus": name, "budget": budget, "kind": kind,
                "original": orig, "compressed": comp,
                "ratio": round(comp / budget, 3),
                "fill": round(comp / budget, 3),
                "verdict": verdict,
            })

    counted = [r for r in rows if r["verdict"] != "fits"]
    violated = [r for r in counted if r["verdict"] == "violated"]
    underfilled = [r for r in counted if r["fill"] < 0.5]
    compliance = (len(counted) - len(violated)) / len(counted) if counted else 0.0

    report = {
        "summary": {
            "cases": len(rows),
            "counted": len(counted),
            "violated": len(violated),
            "declared": sum(1 for r in counted if r["verdict"] == "declared"),
            "underfilled": len(underfilled),
            "compliance": round(compliance, 4),
        },
        "rows": rows,
    }
    readme_problems = check_readme_table()
    report["summary"]["readmeMismatches"] = readme_problems

    out_path = os.path.join(ROOT, "benchmarks", "budget-matrix.json")
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)

    # 控制台在中文 Windows 上是 GBK，这里只输出 ASCII，避免编码崩溃
    print("budget contract matrix")
    print("  cases            : %d" % len(rows))
    print("  counted (compressed) : %d" % len(counted))
    print("  compliant        : %d" % (len(counted) - len(violated)))
    print("  declared-unsat   : %d" % report["summary"]["declared"])
    print("  VIOLATED         : %d" % len(violated))
    print("  underfilled(<50%%): %d" % len(underfilled))
    print("  COMPLIANCE       : %.1f%%" % (compliance * 100))
    print("  README table     : %s" % ("OK" if not readme_problems else "MISMATCH (%d)" % len(readme_problems)))
    for problem in readme_problems:
        print("    ! %s" % problem)

    if violated:
        print("\nworst violations:")
        for r in sorted(violated, key=lambda r: -r["ratio"])[:8]:
            print("  %-24s budget=%-7d out=%-7d %5.1fx  kind=%s"
                  % (r["corpus"], r["budget"], r["compressed"], r["ratio"], r["kind"]))

    print("\nwrote %s" % out_path)


if __name__ == "__main__":
    main()
