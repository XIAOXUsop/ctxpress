package io.github.xiaoxusop.ctxpress.compressor;

import io.github.xiaoxusop.ctxpress.PressPolicy;
import io.github.xiaoxusop.ctxpress.PressResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文本压缩。
 *
 * <p>这个测试类早先**根本不存在**——TEXT 是三个体裁里唯一零覆盖的，
 * 而 README 却把「文本不出现残句」列为已测试的不变量。它漏掉的正是一次真实事故：
 * 切句正则不认 ASCII 句点，一篇 10111 token 的英文文档在 2000 预算下输出 **5 token**，
 * 内容只剩一个省略标记，信息全部灭失——而报告里 {@code effective()} 为真，看不出异常。
 */
class TextCompressorTest {

    private final TextCompressor compressor = new TextCompressor();

    private static PressResult press(String content, int maxTokens) {
        return new TextCompressor().compress(content, PressPolicy.builder().maxTokens(maxTokens).build());
    }

    // ---------- 切句 ----------

    /** 英文用 {@code ". "} 断句——早先被当成"一句话" */
    @Test
    void asciiPeriodsEndSentences() {
        List<String> sentences = TextCompressor.splitSentences(
                "The engine flagged it. The reviewer escalated it. The case was archived.");

        assertEquals(3, sentences.size(), String.valueOf(sentences));
        assertEquals("The engine flagged it.", sentences.get(0));
    }

    /** 小数点、版本号、文件名里的点不是句末——切开会把 {@code 3.14} 割成两句 */
    @Test
    void periodsInsideNumbersAndVersionsDoNotEndSentences() {
        List<String> sentences = TextCompressor.splitSentences(
                "The threshold is 3.14 and the build is v1.0.0 today. That is all.");

        assertEquals(2, sentences.size(), String.valueOf(sentences));
        assertTrue(sentences.get(0).contains("3.14"), sentences.get(0));
        assertTrue(sentences.get(0).contains("v1.0.0"), sentences.get(0));
    }

    @Test
    void chinesePunctuationStillEndsSentences() {
        List<String> sentences = TextCompressor.splitSentences("第一句。第二句！第三句？");

        assertEquals(3, sentences.size(), String.valueOf(sentences));
    }

    // ---------- 真实事故的回归 ----------

    /**
     * 一篇英文文档不该塌缩成一个省略标记。
     *
     * <p>实测原实现：10111 token 的英文散文在 2000 预算下输出 5 token。
     */
    @Test
    void englishProseDoesNotCollapseToNothing() {
        String text = IntStream.range(0, 300)
                .mapToObj(i -> "The screening engine processed batch %d during the nightly run.".formatted(i))
                .collect(Collectors.joining(" "));

        PressResult result = press(text, 2000);

        assertTrue(result.report().effective(), result.report().summary());
        assertTrue(result.report().compressedTokens() > 500,
                "整篇塌缩了：" + result.report().summary());
    }

    /**
     * 没有句末标点的长内容不该让正则引擎爆栈。
     *
     * <p>把"非句末字符"写成 {@code (?:[^…]|\.(?!\s|$))*} 这类交替结构后，
     * Java 正则引擎会把每次迭代编成一次递归调用——3800 字符就足以 {@code StackOverflowError}。
     * 逐字符扫描没有这个风险。
     */
    @Test
    void longContentWithoutAnyTerminatorDoesNotOverflowTheStack() {
        String text = "{ this is not json ".repeat(200);   // 3800 字符，无句末标点

        assertDoesNotThrow(() -> press(text, 100));
    }

    // ---------- 不产生原文没有的内容 ----------

    /**
     * 输出的每一句都必须**逐字来自输入**——绝不出现半句或改写过的句子。
     *
     * <p>这是文本体裁的"保真"底线：压缩可以丢句子，但不能造句子。
     */
    @Test
    void everyKeptSentenceComesVerbatimFromTheInput() {
        String text = IntStream.range(0, 200)
                .mapToObj(i -> "第 %d 条记录说明该笔交易已被复核并归档。".formatted(i))
                .collect(Collectors.joining());

        PressResult result = press(text, 150);
        String withoutMarker = result.content().replaceAll("\\s*…\\[省略 \\d+ 句]", "");

        assertTrue(TextCompressor.splitSentences(text)
                        .containsAll(TextCompressor.splitSentences(withoutMarker)),
                "输出里出现了原文没有的句子：" + result.content());
    }

    // ---------- 保护 ----------

    /**
     * 保护占比闸门。文本体裁早先**完全没有**这道闸门（只有日志有），
     * 于是"每句都含编号或金额"的文本会命中全部保护规则，一句都压不掉——保护了全部等于保护不了。
     */
    @Test
    void protectionIsDowngradedWhenItMatchesAlmostEverything() {
        String text = IntStream.range(0, 400)
                .mapToObj(i -> "第%d笔交易金额 12345.67 CNY，编号 AML-2026-%05d。".formatted(i, i))
                .collect(Collectors.joining());

        PressResult result = press(text, 200);

        assertTrue(result.report().actions().stream()
                        .anyMatch(a -> a.startsWith("PROTECTION_DOWNGRADED_TO_CRITICAL_ONLY")),
                result.report().summary());
        assertTrue(result.report().effective(), result.report().summary());
    }

    /** 反向约束：区分度正常时不该降级 */
    @Test
    void normalProseKeepsFullProtection() {
        String text = IntStream.range(0, 300)
                .mapToObj(i -> "第 %d 条为普通叙述，不含任何编号或金额。".formatted(i))
                .collect(Collectors.joining())
                + "本条含故障编号 AML-9999，应当保留。";

        PressResult result = press(text, 200);

        assertTrue(result.report().actions().stream()
                        .noneMatch(a -> a.startsWith("PROTECTION_DOWNGRADED")),
                result.report().summary());
        assertTrue(result.content().contains("AML-9999"), result.content());
    }

    // ---------- 去重 ----------

    @Test
    void duplicateSentencesAreRemoved() {
        String text = "重复的一句话。".repeat(50) + "另一句话。";

        PressResult result = press(text, 200);

        assertTrue(result.report().actions().stream().anyMatch(a -> a.startsWith("DEDUP_SENTENCES=")),
                result.report().summary());
    }

    /** 放得下就一个字都不动 */
    @Test
    void smallTextIsLeftAlone() {
        String text = "很短的一段文本。";

        PressResult result = press(text, 8000);

        assertEquals(text, result.content());
        assertFalse(result.report().effective());
    }
}
