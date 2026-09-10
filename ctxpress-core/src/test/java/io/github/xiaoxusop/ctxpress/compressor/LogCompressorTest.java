package io.github.xiaoxusop.ctxpress.compressor;

import io.github.xiaoxusop.ctxpress.PressPolicy;
import io.github.xiaoxusop.ctxpress.PressResult;
import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 日志压缩：按行处理、重复折叠、关键行保护。 */
class LogCompressorTest {

    private final LogCompressor compressor = new LogCompressor();

    @Test
    void foldsConsecutiveDuplicateLines() {
        String log = IntStream.range(0, 50)
                .mapToObj(i -> "2026-09-11 10:00:00 INFO heartbeat ok")
                .collect(Collectors.joining("\n"));

        PressResult result = compressor.compress(log, PressPolicy.defaults());

        assertTrue(result.content().contains("[重复 50 次]"), result.content());
        assertTrue(result.report().effective(), result.report().summary());
    }

    @Test
    void neverDropsErrorLinesEvenUnderTinyBudget() {
        // 关键约束：预算再小，命中保护规则的行也必须留下
        StringBuilder log = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            log.append("2026-09-11 10:00:00 INFO 普通日志行 ").append(i).append('\n');
        }
        log.append("2026-09-11 10:00:01 ERROR 关键失败：Caused by java.lang.IllegalStateException\n");

        PressResult result = compressor.compress(log.toString(), PressPolicy.builder().maxTokens(120).build());

        assertTrue(result.content().contains("ERROR 关键失败"), "受保护行不得被裁剪：" + result.content());
        assertTrue(result.content().contains("Caused by"), "异常链不得被裁剪：" + result.content());
    }

    @Test
    void keepsWholeLinesAndMarksOmissions() {
        String log = IntStream.range(0, 300)
                .mapToObj(i -> "line " + i + " 内容")
                .collect(Collectors.joining("\n"));

        PressResult result = compressor.compress(log, PressPolicy.builder().maxTokens(200).build());

        assertTrue(result.content().contains("[省略"), "应显式标注省略了多少行：" + result.content());
        // 每一行都必须完整存在，不能出现半行
        for (String line : result.content().split("\n")) {
            assertTrue(line.startsWith("line ") || line.startsWith("..."), "出现被截断的行：" + line);
        }
    }

    @Test
    void nonConsecutiveDuplicatesAreNotFolded() {
        // 只折叠相邻重复——不同时间点的同类事件不能混为一谈
        String log = "2026-09-11 INFO a\n2026-09-11 INFO b\n2026-09-11 INFO a";

        PressResult result = compressor.compress(log, PressPolicy.defaults());

        assertEquals(3, result.content().split("\n").length, result.content());
    }

    @Test
    void smallLogIsLeftAlone() {
        String log = "2026-09-11 INFO start\n2026-09-11 INFO done";

        PressResult result = compressor.compress(log, PressPolicy.defaults());

        assertTrue(result.content().contains("start"));
        assertTrue(result.content().contains("done"));
    }

    // ---------- 以下两条来自实际运行中发现的缺陷 ----------

    @Test
    void protectionIsDowngradedWhenItMatchesAlmostEverything() {
        // 真实场景：每行都含交易编号与金额，普通保护规则命中全部 →
        // 保护了全部等于保护不了，压缩率会归零。此时应自动降级为只保护故障线索。
        StringBuilder log = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            log.append("2026-09-11 10:00:00 INFO  processed tx AML-2026-")
               .append(String.format("%05d", i)).append(" amount 12345.67 CNY\n");
        }
        log.append("2026-09-11 10:00:01 ERROR Caused by java.sql.SQLException: pool exhausted\n");

        PressResult result = compressor.compress(log.toString(),
                PressPolicy.builder().maxTokens(200).build());

        assertTrue(result.report().actions().stream()
                        .anyMatch(a -> a.startsWith("PROTECTION_DOWNGRADED_TO_CRITICAL_ONLY")),
                "保护规则失去区分度时应降级：" + result.report().summary());
        assertTrue(result.report().effective(), "降级后才可能真正压得下来：" + result.report().summary());
        assertTrue(result.content().contains("ERROR Caused by"), "降级后故障线索仍必须保留");
    }

    @Test
    void normalLogsStillGetFullProtection() {
        // 反向约束：降级只在"几乎命中全部"时触发，正常日志的保护行为不受影响
        StringBuilder log = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            log.append("2026-09-11 10:00:00 INFO 普通行 ").append(i).append('\n');
        }
        log.append("2026-09-11 10:00:01 ERROR 罕见故障 AML-9999\n");

        PressResult result = compressor.compress(log.toString(), PressPolicy.builder().maxTokens(200).build());

        assertTrue(result.report().actions().stream()
                        .noneMatch(a -> a.startsWith("PROTECTION_DOWNGRADED")),
                "区分度正常时不应降级：" + result.report().summary());
        assertTrue(result.content().contains("AML-9999"));
    }
}
