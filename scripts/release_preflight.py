#!/usr/bin/env python3
"""发版前的两道闸：产物不该带着已知漏洞出门，也不该落后于默认分支。

    python3 scripts/release_preflight.py --alerts-json alerts.json \
        --alerts-http alerts.http --tag v0.5.2 --base origin/master

退出码：0 = 放行 · 1 = 拒绝发布（有告警／落后者动了构建配置／**查不动**）

为什么需要两道闸，而不是一道
----------------------------
第一道查的是**仓库当前**（默认分支）的依赖图，而 release workflow 构建的是
**tag 指向的那个 commit**。两者可以不一致，mcp-sentinel v0.5.2 就是这么发出去的：

    v0.5.2 的 jar 里内嵌 jackson-databind 2.19.0，命中 5 条告警（2 HIGH + 3 MEDIUM）；
    修复 b180618（升到 2.21.5）在 master 上，却比 v0.5.2 晚了 6 个提交。

查仓库告警时它当然是干净的——因为 **master 已经修好了**。
干净的是 master，不是那个产物。所以第二道闸看的是"这个 tag 落后了哪些提交，
落后的那部分有没有动构建配置"。

为什么「查不动」必须和「有告警」一样拒绝
----------------------------------------
两者在流水线里都表现为"没打印出东西"。把 403/404 当成"没有告警"，
正是上面那种事故的形态：**看起来没问题**，东西就这么发出去了。

这里刻意**不依赖 OWASP dependency-check**：它要 NVD 数据，没有 API key 时
一次全量更新要跑几十分钟且频繁 429（姊妹项目 amlagent 的扫描因此永远跑不完）。
而 Dependabot 告警是 GitHub 侧算好的，一个请求就能拿到。

同族仓库（mcp-sentinel / ctxpress / desensitize-spring-boot-starter）各有一份**逐字相同**的
副本——这些仓库相互独立、没法共享代码，所以改动要三处一起改。
"""

import argparse
import json
import os
import re
import subprocess
import sys

SEVERITIES = ["low", "medium", "high", "critical"]

# 落后者动了这些文件，就意味着"产物和 CI 在 master 上验过的那份不是一回事"。
BUILD_FILES = {
    "pom.xml",
    "mvnw",
    "mvnw.cmd",
    "build.gradle",
    "build.gradle.kts",
    "settings.gradle",
    "settings.gradle.kts",
    "gradle.properties",
    "gradle/libs.versions.toml",
}
BUILD_PREFIXES = (".mvn/", "gradle/wrapper/")

_COMMIT_LINE = re.compile(r"^[0-9a-f]{7,40}\t")


class Emitter:
    """输出到 stdout，并顺带写进 Actions 的 job summary（有那个环境变量时）。"""

    def __init__(self, summary_path=None):
        self._summary = []
        self._path = summary_path or os.environ.get("GITHUB_STEP_SUMMARY")

    def __call__(self, line=""):
        print(line)
        self._summary.append(line)

    def flush(self):
        if not self._path or not self._summary:
            return
        try:
            with open(self._path, "a", encoding="utf-8") as fh:
                fh.write("\n".join(self._summary) + "\n")
        except OSError:
            # 写不了 summary 不该改变这次检查的结论——结论只认退出码。
            pass


def _git(*args, cwd=None):
    return subprocess.run(
        ("git",) + args,
        cwd=cwd,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )


def check_alerts(http_path, json_path, fail_on, emit):
    """返回 True 表示放行。"""
    try:
        with open(http_path, encoding="utf-8") as fh:
            code = fh.read().strip()
    except OSError as exc:
        emit(f"[闸一] 读不到 {http_path}（{exc}）——没有 HTTP 状态码就不知道查没查到，拒绝发布")
        return False

    if code != "200":
        detail = ""
        try:
            with open(json_path, encoding="utf-8") as fh:
                body = json.load(fh)
            if isinstance(body, dict):
                detail = str(body.get("message", ""))
        except (OSError, ValueError):
            pass
        emit(f"[闸一] 拿不到 Dependabot 告警：HTTP {code} {detail}".rstrip())
        if code in ("403", "404"):
            emit("[闸一] 403/404 通常是两种情况：仓库的 Dependabot alerts 没开，"
                 "或 workflow 的 GITHUB_TOKEN 缺 `security-events: read` 权限")
        emit("[闸一] 拒绝发布——**查不动不等于没有告警**")
        return False

    try:
        with open(json_path, encoding="utf-8") as fh:
            alerts = json.load(fh)
    except (OSError, ValueError) as exc:
        emit(f"[闸一] HTTP 200 但告警内容读不出来（{exc}）——无法判定，拒绝发布")
        return False

    if not isinstance(alerts, list):
        emit("[闸一] 告警响应不是数组——无法判定，拒绝发布")
        return False

    threshold = SEVERITIES.index(fail_on)
    blocking = []
    unknown = []
    for alert in alerts:
        try:
            severity = alert["security_advisory"]["severity"]
            package = alert["dependency"]["package"]["name"]
            summary = alert["security_advisory"]["summary"]
            ghsa = alert["security_advisory"]["ghsa_id"]
        except (KeyError, TypeError):
            unknown.append(alert)
            continue
        if severity not in SEVERITIES:
            # 出现了不认识的级别：**不能当成低级别放过**，那正是"看起来没问题"。
            unknown.append(alert)
            continue
        if SEVERITIES.index(severity) >= threshold:
            blocking.append((severity, package, ghsa, summary))

    if unknown:
        emit(f"[闸一] 有 {len(unknown)} 条告警的字段缺失或级别不认识——无法判定，拒绝发布")
        return False

    emit(f"[闸一] 开放依赖告警 {len(alerts)} 条，其中 {fail_on} 及以上 {len(blocking)} 条")
    for severity, package, ghsa, summary in blocking:
        emit(f"[闸一]   {severity.upper():<8} {package}  {ghsa}  {summary}")

    if len(alerts) >= 100:
        # per_page=100，满页意味着后面可能还有——但只要有 0 条阻断项，
        # 说明前 100 条都是低级别；这里如实说明，不假装看全了。
        emit("[闸一] 注意：正好取满一页（100 条），后面可能还有未取到的告警")

    if blocking:
        emit(f"[闸一] 拒绝发布：先处理这 {len(blocking)} 条，再打 tag")
        return False
    return True


def check_behind(tag, base, emit, cwd=None):
    """tag 落后于 base 时，若落后的提交动过构建配置就拒绝。返回 True 表示放行。"""
    probe = _git("rev-parse", "--verify", "--quiet", f"{tag}^{{commit}}", cwd=cwd)
    if probe.returncode != 0:
        emit(f"[闸二] 本地没有 {tag} 这个 commit——无法判定它落后了什么，拒绝发布")
        return False
    probe = _git("rev-parse", "--verify", "--quiet", f"{base}^{{commit}}", cwd=cwd)
    if probe.returncode != 0:
        emit(f"[闸二] 本地没有 {base}——无法判定，拒绝发布"
             "（workflow 需要 actions/checkout 的 fetch-depth: 0）")
        return False

    count = _git("rev-list", "--count", f"{tag}..{base}", cwd=cwd)
    if count.returncode != 0:
        emit(f"[闸二] git rev-list 失败：{count.stderr.strip()}——无法判定，拒绝发布")
        return False
    behind = int(count.stdout.strip() or "0")

    if behind == 0:
        emit(f"[闸二] {tag} 没有落后于 {base}（落后的提交数 0）")
        return True

    log = _git("log", "--format=%h\t%s", "--name-only", f"{tag}..{base}", cwd=cwd)
    if log.returncode != 0:
        emit(f"[闸二] git log 失败：{log.stderr.strip()}——无法判定，拒绝发布")
        return False

    skipped = []
    touched = set()
    for line in log.stdout.splitlines():
        if not line.strip():
            continue
        if _COMMIT_LINE.match(line):
            sha, subject = line.split("\t", 1)
            skipped.append((sha, subject))
        else:
            touched.add(line.strip())

    risky = sorted(
        path for path in touched
        if path in BUILD_FILES or path.startswith(BUILD_PREFIXES)
    )

    emit(f"[闸二] {tag} 落后 {base} {behind} 个提交：")
    for sha, subject in skipped:
        emit(f"[闸二]   {sha} {subject}")

    if risky:
        emit(f"[闸二] 拒绝发布：落后的提交动过构建配置 {risky}——"
             f"这个 tag 构建出来的产物，和 CI 在 {base} 上验过的那份不是一回事")
        return False

    emit(f"[闸二] 落后的提交没有动构建配置（{sorted(touched)}），产物内容不受影响，放行")
    return True


def main(argv=None):
    parser = argparse.ArgumentParser(description="发版前的两道闸")
    parser.add_argument("--alerts-http", default="alerts.http",
                        help="curl -w '%%{http_code}' 写出的状态码文件")
    parser.add_argument("--alerts-json", default="alerts.json",
                        help="Dependabot 告警接口的响应体")
    parser.add_argument("--fail-on", default="high", choices=SEVERITIES,
                        help="从哪一级开始阻断（默认 high）")
    parser.add_argument("--tag", required=True, help="要发布的 tag")
    parser.add_argument("--base", required=True, help="默认分支的 ref，如 origin/master")
    parser.add_argument("--skip-behind-check", action="store_true",
                        help="只跑闸一（本地手测用；CI 里不许加这个）")
    args = parser.parse_args(argv)

    emit = Emitter()
    ok_alerts = check_alerts(args.alerts_http, args.alerts_json, args.fail_on, emit)
    if args.skip_behind_check:
        emit("[闸二] 已按要求跳过")
        ok_behind = True
    else:
        ok_behind = check_behind(args.tag, args.base, emit)

    emit.flush()

    if ok_alerts and ok_behind:
        emit()
        emit("发版前置检查通过")
        return 0
    return 1


if __name__ == "__main__":
    sys.exit(main())
