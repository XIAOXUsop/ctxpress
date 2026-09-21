package io.github.xiaoxusop.ctxpress.compressor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.xiaoxusop.ctxpress.PressPolicy;
import io.github.xiaoxusop.ctxpress.PressResult;
import io.github.xiaoxusop.ctxpress.TokenEstimator;
import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JSON 压缩：结构自洽、数组采样、键不丢、关键值保护。 */
class JsonCompressorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final JsonCompressor compressor = new JsonCompressor();

    @Test
    void outputIsAlwaysValidJson() throws Exception {
        String json = "{\"items\":[" + IntStream.range(0, 500)
                .mapToObj(i -> "{\"id\":" + i + ",\"name\":\"record-" + i + "\"}")
                .collect(Collectors.joining(",")) + "]}";

        PressResult result = compressor.compress(json, PressPolicy.builder().maxTokens(200).build());

        // 关键性质：压缩产物仍是合法 JSON —— 字符串截断做不到这一点
        assertDoesNotThrow(() -> MAPPER.readTree(result.content()));
        assertTrue(result.report().effective(), result.report().summary());
    }

    /**
     * 预算装得下"仅去掉空白"的结果时，**一个元素都不该丢**。
     *
     * <p>这条契约早先根本不存在：压缩器从 {@code maxArrayItems}（默认 8）起步、循环只会
     * {@code limit / 2} 缩小从不增长，于是采样上限与预算无关——8528 token 的检索结果在
     * 2500 / 4000 / 8000 三个预算下输出**完全相同**的 515 token，预算给多了也不会多留内容。
     */
    @Test
    void minifiesWithoutDroppingElementsWhenWhitespaceAloneFitsTheBudget() throws Exception {
        String json = "{\"rows\":[\n" + IntStream.range(0, 100)
                .mapToObj(i -> "  {\"id\":" + i + "}").collect(Collectors.joining(",\n")) + "\n]}";

        PressResult result = compressor.compress(json, PressPolicy.builder().maxTokens(300).build());
        JsonNode rows = MAPPER.readTree(result.content()).get("rows");

        assertEquals(100, rows.size(), "去掉空白就装得下，不该丢任何元素：" + result.report().summary());
        assertTrue(result.report().actions().contains("MINIFIED_ONLY"), result.report().summary());
    }

    @Test
    void shrinksLongArraysWithExplicitOmittedCount() throws Exception {
        // 预算必须小到"仅去空白"也装不下，才谈得上截断——
        // 早先这条测试给的预算（120）其实装得下全部 100 条，于是它把
        // "预算够却硬要截断"这个缺陷固化成了正确行为。
        String json = "{\"rows\":[" + IntStream.range(0, 100)
                .mapToObj(i -> String.valueOf(i)).collect(Collectors.joining(",")) + "]}";

        PressResult result = compressor.compress(json, PressPolicy.builder().maxTokens(64).build());
        JsonNode rows = MAPPER.readTree(result.content()).get("rows");

        assertTrue(rows.size() < 100, "数组应被采样：" + result.content());
        boolean hasOmittedMarker = false;
        for (JsonNode element : rows) {
            if (element.has("_omitted")) {
                hasOmittedMarker = true;
                assertTrue(element.get("_omitted").asInt() > 0);
            }
        }
        assertTrue(hasOmittedMarker, "必须用显式计数节点说明省略了多少条：" + result.content());
    }

    /** 没被截断的数组不该被注入 {@code {"_omitted":0}}——那既凭空多出元素，又把没动过的数组标成"已截断" */
    @Test
    void shortArraysAreLeftIntactEvenAtTinyArrayLimit() throws Exception {
        String json = "{\"big\":[" + IntStream.range(0, 200)
                .mapToObj(i -> String.valueOf(i)).collect(Collectors.joining(","))
                + "],\"small\":[1,2]}";

        PressResult result = compressor.compress(json, PressPolicy.builder().maxTokens(64).build());
        JsonNode small = MAPPER.readTree(result.content()).get("small");

        assertEquals(2, small.size(), "没超上限的数组不该被注入记账节点：" + result.content());
        for (JsonNode element : small) {
            assertFalse(element.isObject() && element.has("_omitted"),
                    "小数组里出现了 _omitted 节点：" + result.content());
        }
    }

    @Test
    void keepsAllObjectKeys() throws Exception {
        // 键名信息密度高：删键会让模型误解数据形状
        String json = "{\"alpha\":1,\"beta\":2,\"gamma\":3,\"delta\":4,\"epsilon\":5}";

        PressResult result = compressor.compress(json, PressPolicy.builder().maxTokens(100).build());
        JsonNode node = MAPPER.readTree(result.content());

        for (String key : new String[]{"alpha", "beta", "gamma", "delta", "epsilon"}) {
            assertTrue(node.has(key), "键 " + key + " 不应被删除：" + result.content());
        }
    }

    @Test
    void protectsValuesMatchingMustKeep() throws Exception {
        String hash = "71b39e0804a17ef7cdc8508545810921059266fbc43fdb4446d90f10c235595b";
        String json = "{\"note\":\"" + "x".repeat(900) + "\",\"evidence\":\"AML-001:" + hash + "\"}";

        PressResult result = compressor.compress(json, PressPolicy.builder().maxTokens(200).build());

        assertTrue(result.content().contains(hash), "命中保护规则的哈希不得被截断：" + result.content());
        assertTrue(result.report().protectedSegments() > 0, result.report().summary());
    }

    @Test
    void malformedJsonFallsBackToTextWithoutThrowing() {
        String broken = "{ this is not json ".repeat(200);

        PressResult result = compressor.compress(broken, PressPolicy.builder().maxTokens(100).build());

        assertTrue(result.report().actions().contains("JSON_PARSE_FAILED_FALLBACK_TO_TEXT"),
                result.report().summary());
    }

    /**
     * `maxArrayItems` 是**天花板**，不是起点——而它此前是个**空选项**。
     *
     * <p>字段存了、getter 也导出了，但没有任何生产代码读它：`.maxArrayItems(50)` 静默无效。
     * 而默认值当时是 `8`，所以"顺手把它接上"会立刻退回另一个更糟的状态——
     * 每个数组都被固定截到 8 条、预算再宽也留不住，
     * 那正是 README 里记为 P0 的「采样上限固定从 8 起步、只会缩小不会增长」。
     * 所以接上它的同时必须把默认值改成不收紧，两件事是一件事。
     */
    @Test
    void maxArrayItemsIsACeilingNotAStartingPoint() throws Exception {
        String json = "{\"items\":[" + IntStream.range(0, 500)
                .mapToObj(String::valueOf).collect(Collectors.joining(",")) + "]}";

        // ⓵ 预算很宽时走的是「仅去空白」那一档，默认不收紧 → 一个元素都不该丢。
        //    这条同时钉住"把默认值改成 8 会立刻退回 P0"这件事。
        PressResult wide = compressor.compress(json, PressPolicy.builder().maxTokens(100_000).build());
        assertFalse(wide.content().contains("_omitted"),
                "预算够时不该丢元素：" + wide.report().summary());

        // ⓶ 预算逼到要采样，默认上限不收紧 → 留几条由预算决定
        int byDefault = retainedCount(compressor.compress(json,
                PressPolicy.builder().maxTokens(64).build()).content());

        // ⓷ 显式收紧到 3 条 → 采样条数不得超过它
        PressResult capped = compressor.compress(json,
                PressPolicy.builder().maxTokens(64).maxArrayItems(3).build());
        int cappedCount = retainedCount(capped.content());

        assertTrue(byDefault > 3,
                "默认不该被收紧到 3 条，否则下面那条断言证明不了任何东西（实际留了 " + byDefault + " 条）");
        assertTrue(cappedCount <= 3,
                "显式设了上限就该按它采样：留了 " + cappedCount + " 条 —— " + capped.content());
        assertTrue(capped.report().actions().stream().anyMatch(a -> a.startsWith("ARRAY_SAMPLED_LIMIT=")),
                capped.report().summary());
    }

    /** 数一数数组里**保留下来的元素**（不含 {@code {"_omitted":N}} 记账节点） */
    private static int retainedCount(String content) throws Exception {
        int kept = 0;
        for (JsonNode element : MAPPER.readTree(content).get("items")) {
            if (!(element.isObject() && element.has("_omitted"))) {
                kept++;
            }
        }
        return kept;
    }

    /**
     * **「仅去空白」这一档不许改写数值字面量。**
     *
     * <p>这一档原先实现成"解析再序列化"（`root.toString()`），Jackson 会顺手把数值
     * 重新格式化。实测（2026-09-22，预算 64 逼出这一档）：
     *
     * <pre>
     *   99999999999999999999.99  ->  1.0E20                 值变了
     *   12345678901234567.89     ->  1.2345678901234568E16  精度丢了
     *   1e400                    ->  "Infinity"             数字变成了字符串
     *   1e-400                   ->  0.0                    下溢成零
     * </pre>
     *
     * <p>而报告里写的是 `MINIFIED_ONLY`（"只去了空白"）、`archiveRef` 是 null——
     * **没有任何标记说值被改过**。对账单、金额、ID 这类下游，那就是静默的错误数据。
     * README 的契约之一是「输出不含原文没有的内容」，`"Infinity"` 既不是输入里的字节，
     * 也不是已声明的标记。现在这一档改成纯文本扫描，输出逐字节来自输入。
     */
    @Test
    void minifyOnlyTierNeverRewritesNumberLiterals() {
        String[] literals = {"99999999999999999999.99", "12345678901234567.89",
            "1e400", "1e-400", "100.00", "-0.0"};

        for (String number : literals) {
            StringBuilder json = new StringBuilder("{\n");
            for (int i = 0; i < 12; i++) {
                json.append("                    \"f").append(i).append("\": \"v").append(i).append("\",\n");
            }
            json.append("                    \"amount\": ").append(number).append("\n}");

            PressResult result = compressor.compress(json.toString(),
                    PressPolicy.hardBudget(64, TokenEstimator.withSafetyMargin(1.0)).build());

            assertTrue(result.report().actions().contains("MINIFIED_ONLY"), result.report().summary());
            assertTrue(result.content().contains(number),
                    "报的是「仅去空白」，数值字面量却变了：" + number + " → " + result.content());
        }
    }

    /** 空白确实被去掉了，而字符串内部的空格要原样保留。 */
    @Test
    void minifyOnlyTierRemovesStructuralWhitespaceOnly() {
        StringBuilder json = new StringBuilder("{\n");
        for (int i = 0; i < 12; i++) {
            json.append("                    \"f").append(i).append("\": \"v  ").append(i).append("\",\n");
        }
        json.append("                    \"amount\": \"x\"").append("\n}");

        PressResult result = compressor.compress(json.toString(),
                PressPolicy.hardBudget(64, TokenEstimator.withSafetyMargin(1.0)).build());

        assertTrue(result.report().actions().contains("MINIFIED_ONLY"), result.report().summary());
        assertFalse(result.content().contains("\n"), "结构空白应当被去掉：" + result.content());
        assertTrue(result.content().contains("\"v  0\""), "字符串内部的空格不该动：" + result.content());
    }

    @Test
    void smallJsonIsNotInflated() {
        String json = "{\"a\":1}";

        PressResult result = compressor.compress(json, PressPolicy.defaults());

        // 微小 JSON 上压缩可能反而变大，此时应原样返回而不是"越压越大"
        assertTrue(result.report().compressedTokens() <= result.report().originalTokens(),
                result.report().summary());
        assertEquals("{\"a\":1}", result.content());
    }
}
