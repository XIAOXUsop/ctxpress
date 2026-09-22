#!/usr/bin/env python3
"""README 测试条数对账的回归用例。

    python3 -m unittest discover -s scripts -p 'test_*.py' -v

每条都对应一次**真实的误报或一次真实的误读**，不是凑覆盖率。
重点是把两类失败分开，它们长得像、处置却完全相反：

  · 「数字对不上」 → 去改 README；
  · 「这一块没量到」 → 先把测试跑起来。

2026-09-22 第一次写这个脚本时就把这两类混成了一句，负向验证时才现形：
把 `ctxpress-core/target/surefire-reports` 挪走，它报的是
「声称 102，实测 25」——看着像数字错了，实际是那一块根本没量到。
"""

import io
import os
import tempfile
import unittest
from unittest import mock

import check_readme_test_count as mod


class TestClaimParsing(unittest.TestCase):
    """README 里那行的句式。实测踩过一次漏抓。"""

    def test_total_is_read_from_the_hash_form(self):
        text = "./mvnw test      # 102 项，全部离线（core 77 + tokenizer 10 + cli 15）"
        self.assertEqual(int(mod.TOTAL.search(text).group(1)), 102)

    def test_breakdown_parts_are_split(self):
        text = "./mvnw test      # 102 项，全部离线（core 77 + tokenizer 10 + cli 15）"
        detail = mod.BREAKDOWN.search(text)
        self.assertIsNotNone(detail, "分模块明细应当被认出")
        self.assertEqual(
            mod.PART.findall(detail.group(1)),
            [("core", "77"), ("tokenizer", "10"), ("cli", "15")],
        )

    def test_a_line_without_the_hash_form_is_not_mistaken_for_a_claim(self):
        # 正文里到处是数字（`97999 -> 1973 tokens`、`8528 token`…），
        # 句式必须是 `# NNN 项` 这种明确的写法，否则会误抓一大片。
        text = "> 这里的数字原先写的是 `97999 -> 1973 tokens (-98.0%)`"
        self.assertIsNone(mod.TOTAL.search(text))


class TestTwoFailureModesAreDistinct(unittest.TestCase):
    """**本文件存在的理由**：把「没量到」与「对不上」分开。"""

    def _run_with(self, readme_text, by_module):
        with tempfile.TemporaryDirectory() as tmp:
            readme = os.path.join(tmp, "README.md")
            with io.open(readme, "w", encoding="utf-8") as handle:
                handle.write(readme_text)
            with (
                mock.patch.object(mod, "README", readme),
                mock.patch.object(mod, "counts_by_module", return_value=by_module),
            ):
                import contextlib

                buffer = io.StringIO()
                with contextlib.redirect_stdout(buffer):
                    code = mod.main()
                return code, buffer.getvalue()

    README_OK = "./mvnw test      # 102 项，全部离线（core 77 + tokenizer 10 + cli 15）"

    def test_all_consistent_passes(self):
        code, _ = self._run_with(
            self.README_OK,
            {"ctxpress-core": 77, "ctxpress-tokenizer-jtokkit": 10, "ctxpress-cli": 15},
        )
        self.assertEqual(code, 0)

    def test_wrong_number_fails_and_points_at_the_readme(self):
        code, out = self._run_with(
            "./mvnw test      # 98 项，全部离线（core 73 + tokenizer 10 + cli 15）",
            {"ctxpress-core": 77, "ctxpress-tokenizer-jtokkit": 10, "ctxpress-cli": 15},
        )
        self.assertEqual(code, 1)
        self.assertIn("实测与 README 对不上", out)
        self.assertIn("声称 98", out)

    def test_total_right_but_a_module_wrong_still_fails(self):
        # 总数恰好抵消不掉的情形：70 + 17 + 15 也是 102，但两块都错。
        # 只比总数会漏掉它。
        code, out = self._run_with(
            "./mvnw test      # 102 项，全部离线（core 70 + tokenizer 17 + cli 15）",
            {"ctxpress-core": 77, "ctxpress-tokenizer-jtokkit": 10, "ctxpress-cli": 15},
        )
        self.assertEqual(code, 1)
        self.assertIn("ctxpress-core", out)
        self.assertIn("ctxpress-tokenizer-jtokkit", out)

    def test_a_missing_module_says_not_measured_instead_of_wrong_number(self):
        """
        **这条是本文件的核心。**

        缺了 `ctxpress-core` 的产物时，绝不能说成「声称 102，实测 25」——
        后者会把人引去改 README。必须点明是**没量到**，并把已有产物的模块列出来。
        """
        code, out = self._run_with(
            self.README_OK,
            {"ctxpress-tokenizer-jtokkit": 10, "ctxpress-cli": 15},
        )
        self.assertEqual(code, 1)
        self.assertIn("没有测试产物", out)
        self.assertIn("没量到", out)
        self.assertIn("别去改 README", out)
        self.assertNotIn("实测与 README 对不上", out)

    def test_no_reports_at_all_fails_rather_than_skips(self):
        code, out = self._run_with(self.README_OK, {})
        self.assertEqual(code, 1)
        self.assertIn("一个 surefire-reports 都没找到", out)

    def test_an_unrecognised_claim_fails_rather_than_passes(self):
        # 句式改了 → 检查认不出声称 → 必须失败。认不出却报通过，
        # 就是本工作区反复记的「检查绿着、被检查的事没发生」。
        code, out = self._run_with(
            "./mvnw 测试     # 全部通过（core + tokenizer + cli）",
            {"ctxpress-core": 77, "ctxpress-tokenizer-jtokkit": 10, "ctxpress-cli": 15},
        )
        self.assertEqual(code, 1)
        self.assertIn("没找到「# NNN 项」", out)

    def test_an_ambiguous_module_name_is_reported_not_guessed(self):
        # 简称命中多个模块时不猜，直接报出来让人写清楚。
        #
        # 断言里刻意**不写死命中几个**：第一版写的是"2 个"，跑出来是 3 个——
        # 因为 `press` 是 `ctxpress-cli` / `ctxpress-core` /
        # `ctxpress-tokenizer-jtokkit` **三个**模块名的子串。
        # 写死数字的断言在测"我猜对了几个"，而不是"有没有报出来"。
        code, out = self._run_with(
            "./mvnw test      # 102 项（core 77 + press 25）",
            {"ctxpress-core": 77, "ctxpress-tokenizer-jtokkit": 10, "ctxpress-cli": 15},
        )
        self.assertEqual(code, 1)
        self.assertIn("对上", out)
        self.assertIn("个模块", out)
        self.assertIn("请把简称写清楚", out)


if __name__ == "__main__":
    unittest.main()
