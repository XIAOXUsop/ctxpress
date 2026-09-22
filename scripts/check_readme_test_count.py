#!/usr/bin/env python3
"""README 里的测试条数必须等于真实跑出来的条数。

    python scripts/check_readme_test_count.py

退出码：0 = 一致 · 1 = 不一致或读不到产物

为什么要有它
------------
README「测试」那一节写着 `./mvnw test  # 102 项，全部离线（core 77 + tokenizer 10 + cli 15）`。
实测（2026-09-22）：这句话原先写的是 **98 项（core 73 + …）**，而真实是 102——
本仓库这一轮给 `ctxpress-core` 加了 4 条测试，README 没跟着改。

这类数字没有任何东西守着就一定会漂，本工作区里已经有三次同族记录：
letterpress 的单测数漂到过 263（实际 270）、mcp-sentinel 漂到过 127（实际 131）、
desensitize 同一页上两处互相矛盾（110 vs 107）。**有门禁的那几处就没漂**。

数据从哪来
----------
各模块的 `target/surefire-reports/*.xml` —— Maven 跑完写下的**真实产物**，
不是从日志里抓 `Tests run:` 那行（格式会变，且可能抓到中途的汇总；
多模块时那行还会**分模块各打一次**，很容易只读到最后一个模块）。
**读不到就是失败，不是跳过**：否则把 `./mvnw test` 从流水线里拿掉，
这条检查会安静地不再检查任何东西——那是本工作区反复记的「查不动 ≠ 通过」。

它刻意不做什么
--------------
* **不改 README。** 不一致就报错并把两边的值都打出来，改不改由人决定。
  一个会自己改文档的检查，等于把"文档错了"这件事也一起静默了。
* **不假装能认出所有写法。** 只抓 `# NNN 项` 这一种明确句式。
  认不出一处声称就**失败并提示更新正则**，而不是当成通过。

── 写它的过程中踩过的两次，记在这里 ─────────────────────────────
1. **模块名用 endswith 判简称**：`tokenizer` 对不上 `ctxpress-tokenizer-jtokkit`，
   误报成"没有这个模块的产物"。**那是找不着，不是对不上**——两者要分清。
   改成包含匹配并要求**唯一命中**（命中多个就报出来让人写清楚，不猜）。
2. **负向验证时把 `ctxpress-core` 的产物挪走**，它报的是「声称 102，实测 25」——
   一个**看着像数字对不上**的结论，而真实是那一块**根本没量到**。
   两种失败的处置完全相反（去改 README vs 先把测试跑起来），混成一句就会把人引错。
   现在分开报，并有回归用例钉住。
"""

from __future__ import annotations

import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
README = os.path.join(ROOT, "README.md")

# README 里断言测试条数的那一行，形如：
#     ./mvnw test      # 102 项，全部离线（core 77 + tokenizer 10 + cli 15）
# 括号里的分模块明细也一并抓，这样报错时能直接指出是哪一块对不上。
TOTAL = re.compile(r"#\s*(\d+)\s*项")
BREAKDOWN = re.compile(r"（([^）]*?\+[^）]*?)）")
PART = re.compile(r"([a-zA-Z][\w-]*)\s+(\d+)")


def counts_by_module() -> dict[str, int]:
    """按模块汇总 Surefire XML 的条数。"""
    found: dict[str, int] = {}
    for path in glob.glob(os.path.join(ROOT, "*", "target", "surefire-reports", "*.xml")):
        module = path[len(ROOT) + 1:].replace("\\", "/").split("/")[0]
        root = ET.parse(path).getroot()
        found[module] = found.get(module, 0) + int(root.get("tests", 0))
    return found


def main() -> int:
    print("\nREADME 测试条数对账")
    print("─" * 64)

    by_module = counts_by_module()
    if not by_module:
        print("  ✗ 一个 surefire-reports 都没找到（未跑过测试？）")
        print("      这个检查读的是 `./mvnw test` 写下的真实产物。先跑一次测试。")
        print("      它**不会**因为读不到就当作通过——那正是「把检查拿掉就静默失效」。")
        return 1

    # **模块缺了不算"少了几条"，算"这一块没量到"。**
    #
    # 实测（2026-09-22，负向验证时发现的）：把 `ctxpress-core/target/surefire-reports`
    # 挪走之后，脚本报的是「声称 102，实测 25」——一个**看着像数字对不上**的结论，
    # 而真实情况是**那个模块根本没量到**。这两种失败的处置完全不同：
    # 前者要去改 README，后者要把测试跑起来。混成一句话就会把人引错方向。
    #
    # 判据：README 的分模块明细里点到的模块，必须在产物里找得到。
    with open(README, encoding="utf-8") as handle:
        text = handle.read()
    hit = TOTAL.search(text)
    detail_hit = BREAKDOWN.search(text[hit.start():hit.start() + 200]) if hit else None
    if detail_hit:
        for name, _ in PART.findall(detail_hit.group(1)):
            if not any(name in k for k in by_module):
                print("  ✗ README 里点到的模块 `%s` 没有测试产物" % name)
                print("      已有产物的模块：%s" % "、".join(sorted(by_module)))
                print("      **这是「没量到」，不是「数字对不上」**——别去改 README，")
                print("      先把那一模块的测试跑出来（`./mvnw test`）。")
                return 1

    actual = sum(by_module.values())
    print("  · 本次实测 %d 项：%s"
          % (actual, " + ".join("%s %d" % (k, v) for k, v in sorted(by_module.items()))))

    with open(README, encoding="utf-8") as handle:
        text = handle.read()

    hit = TOTAL.search(text)
    if not hit:
        print("  ✗ README 里没找到「# NNN 项」这种句式")
        print("      要么句式改了、要么这句话被删了。**这不等于数字是对的**——")
        print("      检查认不出声称，就什么都守不住。请同步更新本脚本的正则。")
        return 1

    claimed = int(hit.group(1))
    line = text[:hit.start()].count("\n") + 1

    problems = []
    if claimed != actual:
        problems.append("总数：README:%d 声称 %d，实测 %d" % (line, claimed, actual))

    # 分模块明细对账。README 那句把明细写在括号里，**明细对不上也算不一致**——
    # 只比总数会漏掉"总数对但某一块错了、另一块恰好抵消"的情形。
    #
    # 模块名**用包含匹配**而不是 endswith：README 写的是简称
    # （`core 77 + tokenizer 10 + cli 15`），而 Maven 模块名是全称
    # （`ctxpress-core` / `ctxpress-tokenizer-jtokkit` / `ctxpress-cli`）。
    # 第一版用 endswith，"tokenizer" 对不上 "ctxpress-tokenizer-jtokkit"
    # 就误报成"没有这个模块的产物"——**那是找不着，不是对不上**，两者要分清。
    # 包含匹配要求**唯一命中**：命中多个就报出来让人去写清楚，不猜。
    detail = BREAKDOWN.search(text[hit.start():hit.start() + 200])
    if detail:
        for name, value in PART.findall(detail.group(1)):
            keys = [k for k in by_module if name in k]
            if len(keys) != 1:
                problems.append(
                    "README 里的 `%s` 对上 %d 个模块（%s）——请把简称写清楚"
                    % (name, len(keys), "、".join(sorted(keys)) or "无"))
                continue
            if by_module[keys[0]] != int(value):
                problems.append("%s：README 声称 %s，实测 %d"
                                % (keys[0], value, by_module[keys[0]]))
    else:
        print("  ⚠ README 那行里没有分模块明细，只比对总数")

    if problems:
        print("\n  实测与 README 对不上：")
        for item in problems:
            print("    ✗ %s" % item)
        print("\n  改不改由人决定；这个脚本只负责把两边的值都摆出来。")
        return 1

    print("  ✓ README:%d 声称 %d 项，与实测一致（含分模块明细）" % (line, claimed))
    return 0


if __name__ == "__main__":
    sys.exit(main())
