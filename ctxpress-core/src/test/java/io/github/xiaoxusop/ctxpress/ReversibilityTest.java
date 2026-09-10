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
        ContextArchive archive = new ContextArchive();
        String original = bigLog(300);

        String first = archive.store(original);
        String second = archive.store(original);

        assertEquals(first, second, "同一段原文应得到同一引用");
        assertEquals(1, archive.size());
    }

    @Test
    void archiveIsBoundedToAvoidUnboundedMemoryGrowth() {
        ContextArchive archive = new ContextArchive(2);

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
}
