package io.github.xiaoxusop.ctxpress;

import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 体裁判定。判错的代价不是"压得差一点"而是"完全不压"或"压坏结构"，
 * 所以这里覆盖的是现实中高频、而早先全部判错的形态。
 */
class KindDetectionTest {

    private static String lines(java.util.function.IntFunction<String> format, int count) {
        return IntStream.range(0, count).mapToObj(format).collect(Collectors.joining("\n"));
    }

    // ---------- 早先判错、实测压缩率为 0 的形态 ----------

    /**
     * {@code [时间戳] 级别 消息}——logback / log4j2 的默认 ConsoleAppender 格式。
     *
     * <p>它以 {@code [} 开头，而旧实现看到 {@code [} 就直接判 JSON；解析失败后退回文本压缩，
     * 于是这类输入恒为 0% 压缩率。实测 3000 行 59912 token 的方括号日志在预算 2000 下
     * 输出 58497 token（0.0%），而按 LOG 处理能压掉 73.7%。
     */
    @Test
    void bracketedTimestampLogsAreDetectedAsLog() {
        String log = lines(i -> "[2026-09-11 10:%02d:%02d] INFO  txn=TXN%06d ok"
                .formatted(i / 60 % 60, i % 60, i), 200);

        assertEquals(ContextKind.LOG, ContextKind.detect(log));
    }

    /** Maven / Gradle 的 {@code [INFO]} / {@code [ERROR]} 前缀——同样是构建输出的默认形态 */
    @Test
    void mavenStyleBracketedLevelsAreDetectedAsLog() {
        String maven = lines(i -> "[INFO] Compiling %d source files to target/classes".formatted(i), 200);

        assertEquals(ContextKind.LOG, ContextKind.detect(maven));
    }

    /** Gradle 输出没有时间戳也没有标准级别前缀，靠的是自己的标记行 */
    @Test
    void gradleOutputIsDetectedAsLog() {
        String gradle = lines(i -> {
            if (i % 100 == 0) {
                return "> Task :app:compileJava FAILED";
            }
            return "> Task :app:processResources %d".formatted(i);
        }, 200);

        assertEquals(ContextKind.LOG, ContextKind.detect(gradle));
    }

    /**
     * 前 50 行是 JSON 头、后面才是日志。
     *
     * <p>旧实现只采样前 50 行，于是这类输入判成 JSON → 解析失败 → 退文本压缩，
     * **永远到不了日志压缩器**。采样改成等距（头/中/尾各取一段）后不再退化。
     */
    @Test
    void logsAreStillDetectedWhenTheHeadLooksLikeJson() {
        String head = lines(i -> "  {\"id\": %d},".formatted(i), 60);
        String body = lines(i -> "2026-09-11 10:00:00 INFO 第 %d 条记录".formatted(i), 3000);

        assertEquals(ContextKind.LOG, ContextKind.detect(head + "\n" + body));
    }

    // ---------- 真 JSON 不能被误判 ----------

    @Test
    void jsonObjectsAndArraysAreDetectedAsJson() {
        assertEquals(ContextKind.JSON, ContextKind.detect("{\"a\":1}"));
        assertEquals(ContextKind.JSON, ContextKind.detect("[1,2,3]"));
        assertEquals(ContextKind.JSON, ContextKind.detect("  \n  [\n  {\"a\":1},\n  {\"a\":2}\n]"));
    }

    /**
     * 元素是日志样字符串的 JSON 数组——最容易把 {@code [} 判定骗过去的一类。
     *
     * <p>之所以不会误判：有效 JSON 的每个元素都以引号开头，而日志特征全部锚定在行首。
     */
    @Test
    void jsonArraysOfLogLikeStringsAreNotMistakenForLogs() {
        String json = "[\n" + lines(i -> "  \"2026-09-11 10:00:%02d INFO record %d\",".formatted(i % 60, i), 200)
                .stripTrailing().replaceAll(",$", "") + "\n]";

        assertEquals(ContextKind.JSON, ContextKind.detect(json));
    }

    /** 带 BOM 的 JSON：`stripLeading()` 不认为 U+FEFF 是空白，早先会一路落到文本压缩 */
    @Test
    void byteOrderMarkDoesNotHideAJsonDocument() {
        assertEquals(ContextKind.JSON, ContextKind.detect("﻿{\"a\":1}"));
        assertEquals(ContextKind.JSON, ContextKind.detect("﻿[1,2,3]"));
    }

    // ---------- 兜底 ----------

    @Test
    void plainProseFallsBackToText() {
        assertEquals(ContextKind.TEXT, ContextKind.detect("这是一段普通的说明文字。没有时间戳也没有级别标记。"));
        assertEquals(ContextKind.TEXT, ContextKind.detect("A short sentence. Another one. And a third."));
        assertEquals(ContextKind.TEXT, ContextKind.detect(""));
        assertEquals(ContextKind.TEXT, ContextKind.detect(null));
    }

    /** 单行与两行内容不足以做密度判定，一律不当日志 */
    @Test
    void tooFewLinesIsNeverDetectedAsLog() {
        assertEquals(ContextKind.TEXT, ContextKind.detect("2026-09-11 10:00:00 INFO only one line"));
    }
}
