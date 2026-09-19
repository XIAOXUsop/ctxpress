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


def check_alerts(http_path, json_path, fail_on, emit, optional=False):
    """返回 True 表示放行。

    `optional=True` 时，「查不动」降级为**大声告警后放行**，而不是拒绝发布。
    这个开关是为一个实测出来的平台限制准备的，**不是给"懒得查"用的**：

        GitHub 的 Dependabot 告警接口（`GET /repos/{o}/{r}/dependabot/alerts`）
        **不接受 Actions 的 `GITHUB_TOKEN`**——即便授予 `security-events: read`，
        实测仍然 403 `Resource not accessible by integration`；匿名访问则是 401。
        它只认 PAT 或 GitHub App token。

    所以 CI 里要用闸一，必须配一个 PAT secret（下面 workflow 里叫 `PREFLIGHT_TOKEN`）。
    没配时只有两条路：整条发布流程永久卡死，或者明确降级并说清楚。
    这里选后者——但**降级只对"接口够不到"生效**：
    真读到了告警、且达到阈值，照样拒绝（那条路径不受 optional 影响）。
    """
    try:
        with open(http_path, encoding="utf-8") as fh:
            code = fh.read().strip()
    except OSError as exc:
        emit(f"[闸一] 读不到 {http_path}（{exc}）——没有 HTTP 状态码就不知道查没查到")
        return _unchecked(optional, emit, "连状态码文件都没有")
    except Exception as exc:  # noqa: BLE001 - 任何读取失败都应走同一条降级路径
        emit(f"[闸一] 读 {http_path} 出错：{exc}")
        return _unchecked(optional, emit, "状态码文件读不出来")

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
        if code in ("401", "403"):
            emit("[闸一] 401/403 是 **GITHUB_TOKEN 够不到这个接口**的典型表现"
                 "（实测：授权 security-events: read 也一样）。")
            emit("[闸一] 修法：给仓库加一个 PAT secret（workflow 里读 `PREFLIGHT_TOKEN`），"
                 "或在打 tag 前本地跑一遍 `--tag <tag> --base origin/<分支>`。")
        elif code == "404":
            emit("[闸一] 404 多半是仓库的 Dependabot alerts 没开。")
        # 401/403 = 平台不让这个 token 查；404 = 仓库的 Dependabot alerts 没开。
        # 两者都是"这个仓库/这次运行拿不到告警"，可以由 optional 降级。
        # 其余状态码（5xx、连不上时的 000）是**临时故障**，一律拒绝——
        # 网络抖一下就把检查放过去，等于没有检查。
        platform_limited = code in ("401", "403", "404")
        return _unchecked(optional, emit, f"HTTP {code}", platform_limited=platform_limited)

    try:
        with open(json_path, encoding="utf-8") as fh:
            alerts = json.load(fh)
    except (OSError, ValueError) as exc:
        emit(f"[闸一] HTTP 200 但告警内容读不出来（{exc}）——无法判定")
        return _unchecked(optional, emit, "响应体解析失败")

    if not isinstance(alerts, list):
        emit("[闸一] 告警响应不是数组——无法判定")
        return _unchecked(optional, emit, "响应不是数组")

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
        emit(f"[闸一] 有 {len(unknown)} 条告警的字段缺失或级别不认识——无法判定")
        return _unchecked(optional, emit, f"{len(unknown)} 条告警字段缺失")

    emit(f"[闸一] 开放依赖告警 {len(alerts)} 条，其中 {fail_on} 及以上 {len(blocking)} 条")
    for severity, package, ghsa, summary in blocking:
        emit(f"[闸一]   {severity.upper():<8} {package}  {ghsa}  {summary}")

    if len(alerts) >= 100:
        # per_page=100，满页意味着后面可能还有——但只要有 0 条阻断项，
        # 说明前 100 条都是低级别；这里如实说明，不假装看全了。
        emit("[闸一] 注意：正好取满一页（100 条），后面可能还有未取到的告警")

    if blocking:
        # **这条路径不受 optional 影响**：真读到了高危告警就必须拦。
        emit(f"[闸一] 拒绝发布：先处理这 {len(blocking)} 条，再打 tag")
        return False
    return True


def _unchecked(optional, emit, why, platform_limited=False):
    """「查不动」的统一出口——两种处理方式在这里分叉。

    默认：拒绝发布。**查不动不等于没有告警**，两者在日志里长得一模一样，
    当成通过就是把风险静默放行。

    `optional=True` **且 `platform_limited=True`**：放行——但只有这一种情形算数：
    **平台不让这个 token 查**（401/403）。其余的「查不动」都不是平台问题，
    而是**我们自己的管道坏了**（状态码文件没写出来、响应不是数组、字段缺失），
    那些必须一律拦住——把脚本自己的故障当成"环境限制"放过去，
    等于给了一个"把检查弄坏就能绕过"的后门。
    """
    if not (optional and platform_limited):
        emit("[闸一] 拒绝发布——**查不动不等于没有告警**")
        return False
    emit(f"[闸一] !! 无法检查依赖告警（{why}），本次**没有**做这项检查")
    emit("[闸一] !! 按 `--alerts-optional` 放行——这一轮的开销由调用方承担")
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
    parser.add_argument("--alerts-optional", action="store_true",
                        help="接口够不到时降级为告警而不是拒绝发布。"
                             "只在确实拿不到 PAT 时用——详见 check_alerts 的说明。"
                             "**注意：读到高危告警时照样拒绝，这个开关管不着那条路径。**")
    args = parser.parse_args(argv)

    emit = Emitter()
    ok_alerts = check_alerts(args.alerts_http, args.alerts_json, args.fail_on, emit,
                             optional=args.alerts_optional)
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
