#!/usr/bin/env python3
"""release_preflight 的回归用例。

    python3 -m unittest discover -s scripts -p 'test_*.py' -v

每条断言都对应一次**真实事故或一个真实的误读风险**，不是凑覆盖率。特别是
`test_cannot_check_is_not_a_pass` 那一组：闸一和闸二都有可能"查不动"，
而查不动的表现和"没问题"完全一样——这正是 v0.5.2 那种事故的形态。
"""

import json
import os
import subprocess
import tempfile
import unittest

import release_preflight as rp


def _alert(severity, package="com.example:lib", ghsa="GHSA-0000-0000-0000"):
    return {
        "security_advisory": {
            "severity": severity,
            "summary": "test advisory",
            "ghsa_id": ghsa,
        },
        "dependency": {"package": {"name": package}},
    }


class AlertsGate(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="preflight-")
        self.http = os.path.join(self.dir, "alerts.http")
        self.json = os.path.join(self.dir, "alerts.json")
        self.lines = []

    def emit(self, line=""):
        self.lines.append(line)

    def write(self, code, body):
        with open(self.http, "w", encoding="utf-8") as fh:
            fh.write(str(code))
        with open(self.json, "w", encoding="utf-8") as fh:
            if isinstance(body, str):
                fh.write(body)
            else:
                json.dump(body, fh)

    def run_gate(self, fail_on="high"):
        return rp.check_alerts(self.http, self.json, fail_on, self.emit)

    def text(self):
        return "\n".join(self.lines)

    def test_no_alerts_passes(self):
        self.write(200, [])
        self.assertTrue(self.run_gate())

    def test_high_blocks(self):
        self.write(200, [_alert("high")])
        self.assertFalse(self.run_gate())
        self.assertIn("GHSA-0000-0000-0000", self.text())

    def test_critical_blocks(self):
        self.write(200, [_alert("critical")])
        self.assertFalse(self.run_gate())

    def test_medium_does_not_block_at_default_threshold(self):
        self.write(200, [_alert("medium")])
        self.assertTrue(self.run_gate())

    def test_medium_blocks_when_threshold_raised_to_medium(self):
        # 阈值是可配的，但改动它必须真的改变结论——否则这个参数就是摆设。
        self.write(200, [_alert("medium")])
        self.assertFalse(self.run_gate(fail_on="medium"))

    def test_cannot_check_is_not_a_pass(self):
        """403 = 没开 Dependabot 或 token 缺权限。它和"没有告警"都表现为查不到东西。"""
        self.write(403, {"message": "Dependabot alerts are disabled for this repository."})
        self.assertFalse(self.run_gate())
        self.assertIn("security-events", self.text())

    def test_404_is_not_a_pass(self):
        self.write(404, {"message": "Not Found"})
        self.assertFalse(self.run_gate())

    def test_missing_http_file_is_not_a_pass(self):
        # curl 没跑成功时连状态码文件都没有——这同样不是"通过"。
        self.assertFalse(rp.check_alerts(
            os.path.join(self.dir, "nope.http"), self.json, "high", self.emit))

    def test_http_200_with_unparseable_body_is_not_a_pass(self):
        self.write(200, "{ this is not json")
        self.assertFalse(self.run_gate())

    def test_http_200_with_object_instead_of_array_is_not_a_pass(self):
        self.write(200, {"message": "Not Found"})
        self.assertFalse(self.run_gate())

    def test_unknown_severity_is_not_a_pass(self):
        """出现不认识的级别时，不能当成低级别放过——那正是"看起来没问题"。"""
        self.write(200, [_alert("HIGH")])
        self.assertFalse(self.run_gate())

    def test_alert_missing_fields_is_not_a_pass(self):
        self.write(200, [{"security_advisory": {"severity": "high"}}])
        self.assertFalse(self.run_gate())

    def test_full_page_is_called_out(self):
        # per_page=100：取满一页时如实说明"后面可能还有"，不假装看全了。
        self.write(200, [_alert("low") for _ in range(100)])
        self.assertTrue(self.run_gate())
        self.assertIn("100", self.text())


class BehindGate(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="preflight-git-")
        self.lines = []
        self.git("init", "-q")
        self.git("config", "user.email", "t@example.com")
        self.git("config", "user.name", "t")
        self.git("config", "commit.gpgsign", "false")
        self.git("config", "core.autocrlf", "false")

    def git(self, *args):
        return subprocess.run(("git",) + args, cwd=self.dir, capture_output=True,
                              text=True, encoding="utf-8", errors="replace")

    def write(self, name, content):
        path = os.path.join(self.dir, name)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(content)

    def commit(self, message):
        self.git("add", "-A")
        self.git("commit", "-q", "-m", message)
        return self.git("rev-parse", "--short", "HEAD").stdout.strip()

    def emit(self, line=""):
        self.lines.append(line)

    def text(self):
        return "\n".join(self.lines)

    def check(self, tag, base):
        return rp.check_behind(tag, base, self.emit, cwd=self.dir)

    def test_tag_at_tip_passes(self):
        self.write("pom.xml", "<project/>")
        self.commit("init")
        self.git("tag", "v1")
        self.assertTrue(self.check("v1", "HEAD"))

    def test_behind_on_build_file_blocks(self):
        self.write("pom.xml", "<project/>")
        self.commit("init")
        self.git("tag", "v0.5.2")
        self.write("pom.xml", "<project><version>2</version></project>")
        self.commit("fix(deps): bump")
        self.assertFalse(self.check("v0.5.2", "HEAD"))
        self.assertIn("pom.xml", self.text())

    def test_behind_on_docs_only_passes_but_says_so(self):
        self.write("pom.xml", "<project/>")
        self.commit("init")
        self.git("tag", "v1")
        self.write("README.md", "hi")
        self.commit("docs: readme")
        self.assertTrue(self.check("v1", "HEAD"))
        self.assertIn("落后", self.text())

    def test_behind_on_mvnw_blocks(self):
        # mvnw 决定的是"用什么构建"，同样会让产物和 CI 验过的那份不同。
        self.write("pom.xml", "<project/>")
        self.commit("init")
        self.git("tag", "v1")
        self.write("mvnw", "#!/bin/sh\n")
        self.commit("build: wrapper")
        self.assertFalse(self.check("v1", "HEAD"))

    def test_unknown_base_is_not_a_pass(self):
        self.write("pom.xml", "<project/>")
        self.commit("init")
        self.git("tag", "v1")
        self.assertFalse(self.check("v1", "origin/nope"))
        self.assertIn("fetch-depth", self.text())


if __name__ == "__main__":
    unittest.main()
