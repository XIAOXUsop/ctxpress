package io.github.xiaoxusop.ctxpress.tokenizer.jtokkit;

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
}
