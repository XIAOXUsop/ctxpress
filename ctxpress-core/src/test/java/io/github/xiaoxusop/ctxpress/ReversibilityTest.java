package io.github.xiaoxusop.ctxpress;

import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可逆压缩测试。
 *
 * <p>为什么这件事值得单独测：压缩的本质是"赌这段内容用不上"。赌错了，模型就永远
 * 拿不到那一行——而 Agent 恰恰经常在后续步骤里需要前面被压掉的细节。
 * 可逆性把"赌"变成"可补救"。
 */
class ReversibilityTest {

    /**
     * 根是**数组**、数组本身没被截断、但内层**字符串**被截断时，
     * 归档引用同样必须出现在压缩内容里。
     *
     * <p>这条路径此前是漏的，实测（根为数组、2 个元素、每个带一个 2 万字符的字符串）：
     * 动作是 `ARCHIVE_REF_NOT_EMBEDDABLE`，内容里既没有 `ORIG-…` 也没有
     * `_ctxpress_archive`。也就是说**读到这段内容的模型没有任何线索**知道有东西被省略、
     * 以及怎么要回来——而 README 把"归档引用会被写进压缩内容本身"写成了无条件承诺。
     *
     * <p>日志与文本压缩器一直把引用写进各自的省略标记（"…[省略 N 行；原文可经
     * ctxpress 归档 ORIG-… 取回]…"），只有 JSON 的字符串截断标记漏了。这条用例把它钉住。
     */
    @Test
    void archiveRefIsEmbeddedWhenOnlyInnerJsonStringsAreTruncated() {
        ContextPress press = ContextPress.withArchive(PressPolicy.builder().maxTokens(9000).build());
        // 根数组只有 2 个元素（远小于数组采样上限），截的是元素**里面**那两段长字符串
        String original = "[{\"id\":0,\"note\":\"" + "x".repeat(20_000) + "\"},"
                + "{\"id\":1,\"note\":\"" + "y".repeat(20_000) + "\"}]";

        PressResult result = press.press(original, ContextKind.JSON);

        assertNotNull(result.archiveRef(), "发生压缩应给出归档引用");
        assertTrue(result.content().contains(result.archiveRef()),
                "归档引用必须出现在压缩内容里——否则读到它的模型无从知道怎么取回原文。"
                        + "实际动作：" + result.report().actions());
        assertTrue(result.content().contains("原文可经 ctxpress 归档"),
                "省略标记里应当写明怎么取回，而不只是一个裸引用");
    }

    private static String bigLog(int lines) {
        return IntStream.range(0, lines)
                .mapToObj(i -> "2026-09-11 10:00:00 INFO 第 " + i + " 行内容各不相同")
                .collect(Collectors.joining("\n"));
    }

    @Test
    void compressedContentCanBeRetrievedVerbatim() {
        ContextPress press = ContextPress.withArchive(PressPolicy.builder().maxTokens(200).build());
        String original = bigLog(500);

        PressResult result = press.press(original);

        assertTrue(result.reversible(), "发生压缩时应给出归档引用");
        assertEquals(original, press.retrieve(result.archiveRef()).orElseThrow(),
                "取回的内容必须与原文逐字节相同");
    }

    @Test
    void archiveRefIsEmbeddedSoTheModelKnowsHowToAskForIt() {
        ContextPress press = ContextPress.withArchive(PressPolicy.builder().maxTokens(200).build());

        PressResult result = press.press(bigLog(500));

        // 只标注"省略了 N 行"而不说"怎么拿回来"，等于让模型明知有缺失却无从补救
        assertTrue(result.content().contains(result.archiveRef()),
                "归档引用必须出现在压缩内容里：" + result.content());
        assertTrue(result.content().contains("取回"), result.content());
    }

    @Test
    void jsonCompressionEmbedsArchiveRefInTheDocument() {
        ContextPress press = ContextPress.withArchive(PressPolicy.builder().maxTokens(200).build());
        String json = "{\"rows\":[" + IntStream.range(0, 300)
                .mapToObj(i -> "{\"id\":" + i + "}").collect(Collectors.joining(",")) + "]}";

        PressResult result = press.press(json);

        assertTrue(result.reversible());
        // JSON 的省略标记不能是额外的一行文本（那会破坏结构），必须以字段形式写进文档
        assertTrue(result.content().contains("_ctxpress_archive"), result.content());
        assertEquals(json, press.retrieve(result.archiveRef()).orElseThrow());
    }

    @Test
    void nothingIsArchivedWhenNothingWasCompressed() {
        ContextPress press = ContextPress.withArchive(PressPolicy.defaults());

        PressResult result = press.press("很短的一段内容，不需要压缩。");

        assertFalse(result.reversible(), "没发生压缩就不该占用归档");
        assertNull(result.archiveRef());
    }

    @Test
    void withoutArchiveConfiguredCompressionStillWorksButIsIrreversible() {
        // 预算须小于内容，否则按契约本来就不压缩（放得下就别动）
        ContextPress press = ContextPress.with(PressPolicy.builder().maxTokens(300).build());

        PressResult result = press.press(bigLog(500));

        assertFalse(press.reversible());
        assertFalse(result.reversible());
        assertTrue(press.retrieve("ORIG-0000000000000000").isEmpty());
        // 不可逆模式下压缩本身照常生效
        assertTrue(result.report().effective(), result.report().summary());
    }

    @Test
    void archiveRefIsContentAddressedSoRepeatsShareOneEntry() {
        ContextArchive archive = ContextArchive.inMemory();
        String original = bigLog(300);

        String first = archive.store(original);
        String second = archive.store(original);

        assertEquals(first, second, "同一段原文应得到同一引用");
        assertEquals(1, archive.size());
    }

    @Test
    void archiveIsBoundedToAvoidUnboundedMemoryGrowth() {
        ContextArchive archive = ContextArchive.inMemory(2);

        archive.store("第一段原文");
        archive.store("第二段原文");
        archive.store("第三段原文");

        assertEquals(2, archive.size(), "归档必须有界，否则会变成内存泄漏");
    }

    @Test
    void retrieveIsSafeForUnknownRefs() {
        ContextPress press = ContextPress.withArchive();

        assertTrue(press.retrieve(null).isEmpty());
        assertTrue(press.retrieve("ORIG-deadbeefdeadbeef").isEmpty());
        assertNotNull(press.retrieve("ORIG-deadbeefdeadbeef"));
    }

    /**
     * CRLF 输入也必须**逐字节**取回。
     *
     * <p>归档早先存的是归一化之后的副本（CRLF 被统一成 LF），而"逐字节一致"是对调用方
     * 给的**原文**说的——3000 行日志差 2999 个字节，这个承诺就不成立了。
     * 归档存原文，归一化只用于压缩过程本身。
     */
    @Test
    void crlfInputIsRetrievedByteForByteAsGiven() {
        ContextPress press = ContextPress.withArchive(PressPolicy.builder().maxTokens(200).build());
        String crlf = bigLog(500).replace("\n", "\r\n");

        PressResult result = press.press(crlf);

        assertTrue(result.reversible());
        assertEquals(crlf, press.retrieve(result.archiveRef()).orElseThrow(),
                "取回的必须是调用方给的原文，而不是归一化之后的副本");
    }
}
