#!/usr/bin/env python3
"""生成基准测试输入。

刻意用**真实形态**的输入，而不是高度重复的合成数据：
早先版本用「4000 行完全相同的重试行」测出 98.8% 的压缩率并放在 README 首屏，
那是在挑对自己有利的样本——同样的算法在真实日志上做不到那个数字。

用法：
    python benchmarks/generate.py      # 生成到 benchmarks/data/
"""
import json
import os
import random

SEED = 20260911
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data")


def mcp_search_results(path):
    """MCP 工具返回的搜索结果：字段多样、长度不一、有嵌套——真实检索输出的形态。"""
    random.seed(SEED)
    results = []
    for i in range(100):
        results.append({
            "path": f"src/main/java/com/example/{random.choice(['service', 'controller', 'domain'])}/Handler{i}.java",
            "line": random.randint(1, 800),
            "score": round(random.uniform(0.5, 0.99), 3),
            "snippet": f"public void handle{i}(Request req) {{ validate(req); repository.save(transform(req)); }}",
            "metadata": {
                "language": "java",
                "size": random.randint(120, 9000),
                "lastModified": "2026-08-1%d" % random.randint(0, 9),
            },
        })
    with open(path, "w", encoding="utf-8") as f:
        json.dump({"results": results}, f, indent=2)


def app_log(path):
    """真实应用日志：约 15% 是重复重试，其余每行都不同，零散夹杂 ERROR。"""
    random.seed(SEED)
    lines = []
    for i in range(3000):
        r = random.random()
        if r < 0.15:
            lines.append("2026-09-11 10:%02d:%02d WARN  connection pool at %d%% capacity, retrying"
                         % (i // 60 % 60, i % 60, random.randint(70, 95)))
        elif r < 0.17:
            lines.append("2026-09-11 10:%02d:%02d ERROR Failed to process order %d: "
                         "java.sql.SQLTransientException: timeout" % (i // 60 % 60, i % 60, i))
        else:
            lines.append("2026-09-11 10:%02d:%02d INFO  order %d processed in %dms, amount %.2f CNY, customer C-%06d"
                         % (i // 60 % 60, i % 60, i, random.randint(5, 900),
                            random.uniform(10, 99999), random.randint(1, 99999)))
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))


def rag_chunks(path):
    """RAG 检索片段：同一主题、表述不同，重叠约 30%——不是 94% 那种人为重复。"""
    topics = ["客户身份识别", "受益所有人核实", "可疑交易报告", "持续尽职调查", "交易记录保存", "风险等级划分"]
    chunks = []
    for i in range(12):
        topic = topics[i % len(topics)]
        chunks.append(
            f"第{i + 1}节围绕{topic}展开。"
            f"监管要求金融机构在开展{topic}时，应当结合客户行业、地区与业务类型判断风险，措施强度需与风险相适应。"
            f"就{topic}而言，本行制度规定一线业务人员承担初次识别责任，合规部门负责复核与抽查。"
            f"当{topic}相关指标触发预警时，应当在规定时限内完成核查并留存工作记录，以备后续审计追溯。"
        )
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n\n".join(chunks))


def en_prose(path):
    """英文散文：句末用 ASCII 句点。

    这一形态曾让压缩器把整篇文档压成 5 个 token——切句正则不认 ASCII 句点，
    整篇被当成"一句话"，头尾循环又一条都装不下，于是只剩一个省略标记。
    英文技术文档、README、模型输出都是这个形态。
    """
    random.seed(SEED)
    subjects = ["the screening engine", "the case reviewer", "the reconciliation job",
                "the alert pipeline", "the audit trail", "the rule evaluator"]
    verbs = ["flagged", "escalated", "rejected", "confirmed", "queued", "archived"]
    objects = ["the counterparty", "the transaction batch", "the supporting document",
               "the risk score", "the regulatory filing", "the customer profile"]
    sentences = []
    for _ in range(400):
        sentences.append("%s %s %s during the nightly run." % (
            random.choice(subjects), random.choice(verbs), random.choice(objects)))
    with open(path, "w", encoding="utf-8") as f:
        f.write(" ".join(sentences))


def bracketed_log(path):
    """方括号前缀日志：logback / log4j2 默认 ConsoleAppender 的格式。

    也曾是最常见的构建输出格式（Maven / Gradle 的 [INFO] / [ERROR]）。
    这一形态曾被判成 JSON——首字符是 `[`，于是走 JSON 分支、解析失败、退回 TEXT，
    压缩率 0.0%；而按 LOG 处理能压掉 60% 以上。
    """
    random.seed(SEED)
    levels = ["INFO", "INFO", "INFO", "WARN", "ERROR"]
    lines = []
    for i in range(3000):
        lines.append("[2026-09-11 10:%02d:%02d] %-5s txn=TXN%06d amount %d CNY counterparty=CP%04d" % (
            i // 60 % 60, i % 60, random.choice(levels), i,
            random.randint(100, 999999), i % 500))
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))


def big_array(path):
    """根为数组的大 JSON：工具返回值最朴素的形态。

    这一形态暴露过两个问题：预算给到 128000 却只输出 132 token（数组采样上限
    固定为 8，循环只会缩小不会增长）；以及根是数组时归档引用无处可写——
    `_ctxpress_archive` 是作为字段注入的，数组没有字段。
    """
    random.seed(SEED)
    rows = [{"id": i, "score": round(random.uniform(0, 1), 4),
             "path": "src/main/java/com/example/Service%d.java" % i}
            for i in range(5000)]
    with open(path, "w", encoding="utf-8") as f:
        json.dump(rows, f, indent=2)


def main():
    os.makedirs(OUT, exist_ok=True)
    names = {
        "mcp-search-results.json": mcp_search_results,
        "app.log": app_log,
        "rag-chunks.txt": rag_chunks,
        "en-prose.txt": en_prose,
        "bracketed.log": bracketed_log,
        "big-array.json": big_array,
    }
    for name, fn in names.items():
        fn(os.path.join(OUT, name))
    for name in names:
        p = os.path.join(OUT, name)
        print("%-28s %8d bytes" % (name, os.path.getsize(p)))


if __name__ == "__main__":
    main()
