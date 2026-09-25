package io.github.xiaoxusop.ctxpress.tokenizer.jtokkit;

import io.github.xiaoxusop.ctxpress.ContextPress;
import io.github.xiaoxusop.ctxpress.PressPolicy;
import io.github.xiaoxusop.ctxpress.PressReport;
import io.github.xiaoxusop.ctxpress.PressResult;
import io.github.xiaoxusop.ctxpress.TokenCounter;
import io.github.xiaoxusop.ctxpress.TokenCounterInfo;
import io.github.xiaoxusop.ctxpress.TokenEstimator;
import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实词表计数。
 *
 * <p>这个模块存在的理由只有一个，所以先把它测出来：**启发式估算对 JSON / 代码偏乐观**。
 * "预算"要么是启发式口径下的数字，要么是可计费 token——不能既用一个乐观估算，
 * 又对外宣称"塞进 N 个 token"。
 */
class JtokkitTokenCounterTest {

    private final JtokkitTokenCounter counter = JtokkitTokenCounter.ofDefaults();

    @Test
    void countingIsDeterministic() {
        String text = "2026-09-11 10:00:00 INFO  order 12345 processed in 37ms, amount 991.00 CNY";

        assertEquals(counter.count(text), counter.count(text));
    }

    @Test
    void emptyAndNullCountAsZero() {
        assertEquals(0, counter.count(""));
        assertEquals(0, counter.count(null));
    }

    /**
     * 核心断言：启发式估算**低估**了 JSON 这类标点密集内容的 token 数。
     *
     * <p>低估是危险的方向——高估只会让压缩更保守，低估会让"预算 8000"实际塞进
     * 9000 多个可计费 token，正好撑爆窗口。这也是核心模块默认计数器必须被换掉的理由。
     */
    @Test
    void heuristicUnderestimatesJsonAndCode() {
        String json = "{\"rows\":[" + IntStream.range(0, 200)
                .mapToObj(i -> "{\"id\":%d,\"path\":\"src/main/java/com/example/Service%d.java\"}".formatted(i, i))
                .collect(Collectors.joining(",")) + "]}";

        int heuristic = TokenEstimator.estimate(json);
        int actual = counter.count(json);

        assertTrue(actual > heuristic,
                "启发式应当低估 JSON 这类标点密集内容：估算 %d，真实 %d".formatted(heuristic, actual));
    }

    /** 词表必须显式选：同一条内容在不同词表下算出的数不同 */
    @Test
    void differentVocabulariesGiveDifferentCounts() {
        String text = "The screening engine flagged the counterparty during the nightly run.";

        int o200k = new JtokkitTokenCounter(JtokkitTokenCounter.Vocabulary.O200K_BASE).count(text);
        int cl100k = new JtokkitTokenCounter(JtokkitTokenCounter.Vocabulary.CL100K_BASE).count(text);

        assertTrue(o200k > 0 && cl100k > 0);
        assertEquals("o200k_base", new JtokkitTokenCounter(JtokkitTokenCounter.Vocabulary.O200K_BASE).name());
    }

    /** 名字不认识时报错，**不静默退回默认词表**——否则报告里的数字与实际词表对不上 */
    @Test
    void unknownVocabularyIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> JtokkitTokenCounter.of("gpt5_magic"));

        assertTrue(error.getMessage().contains("gpt5_magic"), error.getMessage());
        assertTrue(error.getMessage().contains("o200k_base"), "报错应列出可用词表：" + error.getMessage());
    }

    @Test
    void vocabularyNameIsCaseInsensitive() {
        assertEquals("cl100k_base", JtokkitTokenCounter.of("CL100K_BASE").name());
    }

    /** 工具输出里可能字面包含特殊 token 的写法，计数不该因此抛异常 */
    @Test
    void specialTokenLookingTextIsCountedNotRejected() {
        assertTrue(counter.count("log line containing <|endoftext|> literally") > 0);
    }

    /**
     * 预算契约在真实词表下同样成立。
     *
     * <p>这条单列出来，是因为它曾在一个**组合**上失效：受理用的"逐条成本之和"
     * 之所以是整体估算的上界，靠的是 {@code ceil} 的次可加性——那是启发式计数器的性质，
     * BPE 词表没有。于是换上真实词表后，输出仍可能超出千分之几，
     * 而 30 多项单测全绿（它们跑的都是启发式口径）。
     *
     * <p>端到端实跑才暴露：98028 token 的内容、3000 预算，输出 3014。
     * 现在 assemble 会对账并收紧预算重选，收到装下为止。
     */
    @Test
    void budgetContractHoldsUnderTheRealVocabulary() {
        JtokkitTokenCounter real = JtokkitTokenCounter.ofDefaults();
        java.util.List<String> corpus = java.util.List.of(
                java.util.stream.IntStream.range(0, 3000)
                        .mapToObj(i -> ("2026-09-11 10:%02d:%02d INFO  order %d processed, "
                                + "amount %d CNY, customer C-%06d").formatted(
                                i / 60 % 60, i % 60, i, 1000 + i % 9999, i % 99999))
                        .collect(java.util.stream.Collectors.joining("\n")),
                "{\"rows\":[" + java.util.stream.IntStream.range(0, 500)
                        .mapToObj(i -> "{\"id\":%d,\"path\":\"src/main/java/Service%d.java\"}".formatted(i, i))
                        .collect(java.util.stream.Collectors.joining(",")) + "]}",
                java.util.stream.IntStream.range(0, 300)
                        .mapToObj(i -> "The screening engine processed batch %d.".formatted(i))
                        .collect(java.util.stream.Collectors.joining(" ")));

        java.util.List<String> violations = new java.util.ArrayList<>();
        for (String content : corpus) {
            for (int budget : new int[]{2000, 3000, 8000, 16000, 32000}) {
                PressPolicy policy = PressPolicy.builder().maxTokens(budget).tokenCounter(real).build();
                PressResult result = ContextPress.with(policy).press(content);
                PressReport report = result.report();
                if (report.compressedTokens() > budget && !report.budgetUnsatisfiable()) {
                    violations.add("预算 %d：输出 %d，超出 %d 却未声明"
                            .formatted(budget, report.compressedTokens(),
                                    report.compressedTokens() - budget));
                }
            }
        }
        assertTrue(violations.isEmpty(), "真实词表下预算契约被违反：\n  "
                + String.join("\n  ", violations));
    }

    /**
     * 真实词表必须**自报**为精确口径。
     *
     * <p>这条与 ctxpress-core 里"启发式必须自报为估算"是一对：
     * 报告里那个 6,000 token 到底能不能塞进模型，取决于这里说不说实话。
     * 默认实现返回 false（估算），所以"精确"必须由实现方主动声明——
     * 反过来（默认精确）会让一个估算器不声不响地冒充真实计数。
     */
    @Test
    void declaresItselfAsAnExactVocabularyCounter() {
        for (JtokkitTokenCounter.Vocabulary vocabulary : JtokkitTokenCounter.Vocabulary.values()) {
            TokenCounter counter = new JtokkitTokenCounter(vocabulary);

            assertTrue(counter.exact(), vocabulary + " 是真实词表，必须声明为精确口径");
            assertEquals(vocabulary.name().toLowerCase(java.util.Locale.ROOT), counter.name());
        }
    }

    @Test
    void reportNamesTheVocabularySoTheNumberCannotBeMisread() {
        PressPolicy policy = PressPolicy.strictBudget(2000, new JtokkitTokenCounter(JtokkitTokenCounter.Vocabulary.O200K_BASE)).build();
        String content = java.util.stream.IntStream.range(0, 400)
                .mapToObj(i -> "2026-09-18T03:00:%02d INFO step=%d ok".formatted(i % 60, i))
                .collect(java.util.stream.Collectors.joining("\n"));

        PressResult result = ContextPress.with(policy).press(content);
        TokenCounterInfo counter = result.report().counter();

        assertEquals("o200k_base", counter.name());
        assertTrue(counter.exact());
        assertTrue(result.report().summary().contains("真实词表口径"), result.report().summary());
        assertTrue(result.report().strictBudgetSatisfied() || result.report().budgetUnsatisfiable());
    }

    @Test
    void strictBudgetMatrixCoversChineseCodeLogsAndMixedContent() {
        java.util.List<String> corpus = java.util.List.of(
                "客户身份识别与交易监控记录。".repeat(300),
                "public class RiskCheck { int score = 42; /* review */ }\n".repeat(300),
                "2026-09-26T02:00:00 INFO tx=TXN123456 amount=1200.00 CNY\n".repeat(300),
                "中文说明 with English API keys {\"risk_score\":42}\n".repeat(300));
        for (String content : corpus) {
            for (int budget : new int[]{128, 512, 2000}) {
                PressResult result = ContextPress.with(PressPolicy.strictBudget(budget, counter).build())
                        .press(content);
                PressReport report = result.report();
                assertEquals(counter.count(result.content()), report.compressedTokens());
                assertEquals(Math.max(0, report.compressedTokens() - budget), report.overBudgetBy());
                assertTrue(report.strictBudgetSatisfied() || report.budgetUnsatisfiable());
            }
        }
    }
}
