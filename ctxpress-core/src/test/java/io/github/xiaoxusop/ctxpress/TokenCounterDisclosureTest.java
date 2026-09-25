package io.github.xiaoxusop.ctxpress;

import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 计数口径必须写在报告里，而且可以被调用方显式选择。
 *
 * <p>要防的具体故障：库默认用启发式估算，它对标点密集的文本**偏乐观**——
 * 同一份日志它给 68,571，真实词表给 109,398。如果报告里只写"压缩到 6,000 token"，
 * 读的人无从判断这个 6,000 能不能塞进模型；更糟的是按估算卡住预算的内容，
 * 真实计费很可能是超的，而这件事**在程序里不会以任何形式报错**。
 *
 * <p>所以这组测试盯三件事：报告一定带口径、默认口径被标为估算、
 * 预算要当硬约束时无法"忘了"指定计数器。
 */
class TokenCounterDisclosureTest {

    private static String sampleLog() {
        // 时间戳与标点密集，正是启发式估算偏差最大的那类文本
        return IntStream.range(0, 400)
                .mapToObj(i -> "2026-09-18T03:%02d:%02d INFO  tx=%08d amount=%.2f CNY status=OK".formatted(
                        i % 60, (i * 7) % 60, i, i * 13.5))
                .reduce("", (a, b) -> a + b + "\n");
    }

    // ---------- 报告带口径 ----------

    @Test
    void reportAlwaysCarriesTheCounterIdentity() {
        PressResult result = ContextPress.with(PressPolicy.defaults()).press(sampleLog(), ContextKind.LOG);

        TokenCounterInfo counter = result.report().counter();

        assertNotNull(counter, "报告必须说明 token 数字的口径");
        assertEquals("heuristic", counter.name());
        assertFalse(counter.exact(), "默认计数器是启发式估算，不能被当作精确口径");
        assertTrue(counter.estimated());
    }

    @Test
    void summarySpellsOutTheCounterSoLogsAreSelfExplanatory() {
        PressResult result = ContextPress.with(PressPolicy.defaults()).press(sampleLog(), ContextKind.LOG);

        String summary = result.report().summary();

        assertTrue(summary.contains("计数口径="), summary);
        assertTrue(summary.contains("启发式估算"), "摘要必须点明是估算口径：" + summary);
    }

    @Test
    void reportCarriesTheCounterEvenWhenNothingWasCompressed() {
        // 未压缩的捷径分支同样要带口径，否则"没有 token 数"看起来像"没有口径"
        PressResult result = ContextPress.with(PressPolicy.defaults()).press("短内容", ContextKind.TEXT);

        assertEquals("heuristic", result.report().counter().name());
    }

    // ---------- 安全余量 ----------

    @Test
    void safetyMarginScalesTheEstimateAndSaysSoInItsName() {
        String log = sampleLog();
        TokenCounter plain = TokenEstimator.defaultCounter();
        TokenCounter padded = TokenEstimator.withSafetyMargin(TokenEstimator.BENCHMARK_ESTIMATE_GAP);

        assertEquals((int) Math.ceil(plain.count(log) * TokenEstimator.BENCHMARK_ESTIMATE_GAP), padded.count(log));
        assertEquals("heuristic×1.60", padded.name());
        assertFalse(padded.exact(), "加了余量的估算仍然是估算，不能冒充真实词表");
    }

    @Test
    void benchmarkGapMatchesTheRecordedMeasurement() {
        // 基准里记的是：同一份日志 68,571（估算）对 109,398（o200k_base）。
        // 这条断言把"默认倍数从哪来"钉在测试里，免得以后被人拍脑袋改掉。
        assertEquals(109_398.0 / 68_571.0, TokenEstimator.BENCHMARK_ESTIMATE_GAP, 1e-12);
        assertTrue(TokenEstimator.BENCHMARK_ESTIMATE_GAP > 1.5 && TokenEstimator.BENCHMARK_ESTIMATE_GAP < 1.7,
                "实测倍率应在 1.5~1.7 之间，实际 " + TokenEstimator.BENCHMARK_ESTIMATE_GAP);
    }

    @Test
    void safetyMarginMustBeAPositiveFiniteNumber() {
        assertThrows(IllegalArgumentException.class, () -> TokenEstimator.withSafetyMargin(0));
        assertThrows(IllegalArgumentException.class, () -> TokenEstimator.withSafetyMargin(-1.2));
        assertThrows(IllegalArgumentException.class, () -> TokenEstimator.withSafetyMargin(Double.NaN));
    }

    @Test
    void aPaddedCounterProducesTheSamePolicyContractJustFewerTokens() {
        String log = sampleLog();
        PressPolicy padded = PressPolicy.builder()
                .maxTokens(4_000)
                .tokenCounter(TokenEstimator.withSafetyMargin(TokenEstimator.BENCHMARK_ESTIMATE_GAP))
                .build();

        PressResult result = ContextPress.with(padded).press(log, ContextKind.LOG);

        // 合约不变：输出要么满足预算，要么显式声明做不到
        assertTrue(result.report().budgetSatisfied() || result.report().budgetUnsatisfiable());
        assertEquals("heuristic×1.60", result.report().counter().name());
    }

    // ---------- 硬预算必须显式给计数器 ----------

    @Test
    void hardBudgetCarriesTheCounterIntoTheReport() {
        TokenCounter counter = TokenEstimator.withSafetyMargin(TokenEstimator.BENCHMARK_ESTIMATE_GAP);
        PressPolicy policy = PressPolicy.hardBudget(4_000, counter).build();

        PressResult result = ContextPress.with(policy).press(sampleLog(), ContextKind.LOG);

        assertEquals(counter.name(), result.report().counter().name());
        assertEquals("heuristic×1.60", policy.tokenCounter().name());
    }

    @Test
    void hardBudgetRefusesToGuessTheCounter() {
        // 与 Builder.tokenCounter 一样用 requireNonNull：null 参数就是 null 参数，
        // 关键是消息要说清楚"为什么这里不能省"
        NullPointerException failure =
                assertThrows(NullPointerException.class, () -> PressPolicy.hardBudget(4_000, null));

        assertTrue(failure.getMessage().contains("必须显式指定"), failure.getMessage());
    }

    @Test
    void estimatedCounterCannotClaimStrictBudget() {
        TokenCounter estimate = TokenEstimator.defaultCounter();
        assertThrows(IllegalArgumentException.class, () -> PressPolicy.strictBudget(8000, estimate));
        PressReport report = ContextPress.with(PressPolicy.defaults()).press("短内容").report();
        assertTrue(report.budgetSatisfied());
        assertFalse(report.strictBudgetSatisfied(), "估算口径不能证明模型 token 上界");
    }
}
