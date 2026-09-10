package io.github.xiaoxusop.ctxpress.compressor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.xiaoxusop.ctxpress.PressPolicy;
import io.github.xiaoxusop.ctxpress.PressResult;
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
