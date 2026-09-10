package io.github.xiaoxusop.ctxpress;

import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 门面层测试。两条最重要的性质在这里：
 * <b>确定性</b>（同输入必然同输出）与<b>体裁自动判定</b>。
 */
class ContextPressTest {

    private final ContextPress press = ContextPress.withDefaults();

    // ---------- 体裁判定 ----------

    @Test
    void detectsJsonByFirstNonWhitespaceCharacter() {
        assertEquals(ContextKind.JSON, ContextKind.detect("  {\"a\":1}"));
        assertEquals(ContextKind.JSON, ContextKind.detect("[1,2,3]"));
    }

    @Test
    void detectsLogByTimestampAndLevelDensity() {
        String log = IntStream.range(0, 6)
                .mapToObj(i -> "2026-09-11 10:00:0" + i + " INFO something happened")
                .collect(Collectors.joining("\n"));

        assertEquals(ContextKind.LOG, ContextKind.detect(log));
    }

    @Test
    void fallsBackToText() {
        assertEquals(ContextKind.TEXT, ContextKind.detect("这是一段普通的说明文字。没有时间戳也没有级别标记。"));
        assertEquals(ContextKind.TEXT, ContextKind.detect(""));
    }

    // ---------- 确定性 ----------

    @Test
    void sameInputProducesIdenticalOutput() {
        String content = IntStream.range(0, 400)
                .mapToObj(i -> "2026-09-11 10:00:00 INFO 第 " + i + " 条日志，内容各不相同")
                .collect(Collectors.joining("\n"));
        PressPolicy policy = PressPolicy.builder().maxTokens(300).build();

        PressResult first = ContextPress.with(policy).press(content);
        PressResult second = ContextPress.with(policy).press(content);

        // 压缩必须可复现：不同时刻、不同实例，结果逐字节相同
        assertEquals(first.content(), second.content());
        assertEquals(first.report().compressedTokens(), second.report().compressedTokens());
        assertEquals(first.report().actions(), second.report().actions());
    }

    @Test
    void reportIsPopulatedForAuditing() {
        String content = IntStream.range(0, 500)
                .mapToObj(i -> "2026-09-11 INFO 行 " + i)
                .collect(Collectors.joining("\n"));

        PressResult result = press.press(content);

        assertTrue(result.report().originalTokens() > 0);
        assertTrue(result.report().reductionPercent() >= 0);
        assertFalse(result.report().summary().isBlank());
    }

    // ---------- 边界 ----------

    @Test
    void emptyAndNullContentAreHandled() {
        assertEquals("", press.press("").content());
        assertEquals("", press.press(null).content());
    }

    @Test
    void crlfLineEndingsDoNotBreakDetectionOrCompression() {
        // 真实缺陷回归：Windows 采集的日志是 CRLF，而 Java 正则的 `.` 不匹配 \r，
        // 导致 ^\d{4}-\d{2}-\d{2}.* 这类按行判定全部失配，体裁被判成 TEXT。
        String log = IntStream.range(0, 3000)
                .mapToObj(i -> "2026-09-11 10:00:00 INFO 行 " + i + " 内容各不相同")
                .collect(Collectors.joining("\r\n"));

        assertEquals(ContextKind.LOG, ContextKind.detect(log), "CRLF 日志应仍被判为 LOG");

        // 预算必须小于内容体积，否则按契约本来就不该压缩
        PressResult result = ContextPress.with(PressPolicy.builder().maxTokens(500).build()).press(log);
        assertTrue(result.report().effective(), "CRLF 日志同样应能压缩：" + result.report().summary());
    }

    /** 最早没有这条契约时，一份 8528 token 的内容在 20000 预算下照样被压——内容放得下却付了信息损失的代价 */
    @Test
    void contentThatAlreadyFitsTheBudgetIsLeftUntouched() {
        String content = IntStream.range(0, 50)
                .mapToObj(i -> "2026-09-11 10:00:00 INFO 行 " + i)
                .collect(Collectors.joining("\n"));

        PressResult result = ContextPress.with(PressPolicy.builder().maxTokens(10_000).build()).press(content);

        assertEquals(content, result.content(), "放得下就不该动它");
        assertFalse(result.report().effective());
        assertFalse(result.reversible(), "没压缩就不该占归档");
    }

    @Test
    void customMustKeepRuleIsHonoured() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            sb.append("2026-09-11 INFO 普通行 ").append(i).append('\n');
        }
        sb.append("2026-09-11 INFO TRACE-9F3A 业务追踪标记\n");

        PressResult result = ContextPress.with(PressPolicy.builder()
                .maxTokens(150)
                .mustKeep("TRACE-[0-9A-F]+")
                .build()).press(sb.toString());

        assertTrue(result.content().contains("TRACE-9F3A"), "自定义保护规则应生效：" + result.content());
    }
}
