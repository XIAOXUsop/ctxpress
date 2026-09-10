package io.github.xiaoxusop.ctxpress.compressor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.github.xiaoxusop.ctxpress.Compressor;
import io.github.xiaoxusop.ctxpress.ContextKind;
import io.github.xiaoxusop.ctxpress.PressPolicy;
import io.github.xiaoxusop.ctxpress.PressReport;
import io.github.xiaoxusop.ctxpress.PressResult;
import io.github.xiaoxusop.ctxpress.TokenEstimator;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * JSON 压缩：**在结构上动手，不切字符串**。
 *
 * <p>对工具返回值这类 JSON，字符串截断会产出非法 JSON，模型看到的是残缺括号；
 * 结构压缩则保持 JSON 合法，只是把"长数组"换成"首尾样本 + 计数"——
 * 模型仍能知道"有 500 条，首条长这样"，而不是看到一堆断掉的括号。
 *
 * <p>规则：
 * <ul>
 *   <li>对象：**保留全部键**（键名本身信息密度极高，删键会让模型误解数据形状）</li>
 *   <li>数组：超长时保留首尾样本，中间替换为 {@code {"_omitted": N}} 计数节点</li>
 *   <li>字符串：命中 {@code mustKeep} 的原样保留；过长且未命中的保留首尾并标注省略字数</li>
 *   <li>数值/布尔/null：一律保留（体积小、价值高）</li>
 * </ul>
 *
 * <p>解析失败（非法 JSON）时**回退为文本压缩**，绝不抛异常中断 Agent 流程。
 */
public final class JsonCompressor implements Compressor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 单个字符串值超过此长度才考虑截断 */
    private static final int STRING_TRUNCATE_THRESHOLD = 512;
    private static final int STRING_HEAD = 320;
    private static final int STRING_TAIL = 96;

    private final Compressor fallback = new TextCompressor();

    @Override
    public ContextKind kind() {
        return ContextKind.JSON;
    }

    @Override
    public PressResult compress(String content, PressPolicy policy, String archiveRef) {
        int originalTokens = TokenEstimator.estimate(content);

        JsonNode root;
        try {
            root = MAPPER.readTree(content);
        } catch (Exception e) {
            // 非法 JSON 不阻断流程：按文本处理，行为可预期
            PressResult textResult = fallback.compress(content, policy, archiveRef);
            List<String> actions = new ArrayList<>(textResult.report().actions());
            actions.add(0, "JSON_PARSE_FAILED_FALLBACK_TO_TEXT");
            return new PressResult(textResult.content(),
                    new PressReport(ContextKind.JSON, originalTokens,
                            textResult.report().compressedTokens(),
                            textResult.report().reductionPercent(),
                            textResult.report().protectedSegments(), actions),
                    archiveRef);
        }

        List<String> actions = new ArrayList<>();
        int[] protectedCounter = {0};
        // 是否真的裁剪过内容。不能用"actions 非空"来推断——数组采样本身不产生 action，
        // 会导致"裁剪了却以为没裁剪"，归档引用就不会被写进文档（实测踩到过）。
        boolean[] truncatedFlag = {false};

        // 逐步收紧数组采样上限，直到落入预算；每轮都是纯函数，故整体确定
        int limit = policy.maxArrayItems();
        JsonNode compressed = null;
        while (limit >= 1) {
            protectedCounter[0] = 0;
            compressed = shrink(root, policy, limit, protectedCounter, truncatedFlag);
            String rendered = compressed.toString();
            if (TokenEstimator.estimate(rendered) <= policy.maxTokens()) {
                break;
            }
            limit = limit / 2;
            if (limit < 1) {
                break;
            }
        }
        if (limit < policy.maxArrayItems()) {
            actions.add("ARRAY_LIMIT_REDUCED_TO=" + Math.max(1, limit));
        }
        if (protectedCounter[0] > 0) {
            actions.add("PROTECTED_JSON_VALUES=" + protectedCounter[0]);
        }

        // 真的发生了裁剪时，把归档入口写进结构里（根是对象才行；根是数组则只能通过 API 取回）
        boolean truncated = truncatedFlag[0];
        if (truncated && archiveRef != null && compressed.isObject()) {
            ((ObjectNode) compressed).put("_ctxpress_archive", archiveRef);
            actions.add("ARCHIVE_REF_EMBEDDED");
        }

        String rendered = compressed.toString();
        int compressedTokens = TokenEstimator.estimate(rendered);
        if (compressedTokens > originalTokens) {
            // 极小 JSON 上压缩反而变大（去掉空白与美化后仍可能如此），此时不压
            return PressResult.unchanged(content, ContextKind.JSON, originalTokens);
        }
        return new PressResult(rendered, new PressReport(ContextKind.JSON, originalTokens, compressedTokens,
                TokenEstimator.reductionPercent(originalTokens, compressedTokens),
                protectedCounter[0], actions), truncated ? archiveRef : null);
    }

    private JsonNode shrink(JsonNode node, PressPolicy policy, int arrayLimit,
                            int[] protectedCounter, boolean[] truncatedFlag) {
        if (node.isObject()) {
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                result.set(field.getKey(), shrink(field.getValue(), policy, arrayLimit, protectedCounter, truncatedFlag));
            }
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = JsonNodeFactory.instance.arrayNode();
            if (node.size() <= arrayLimit) {
                for (JsonNode element : node) {
                    result.add(shrink(element, policy, arrayLimit, protectedCounter, truncatedFlag));
                }
                return result;
            }
            truncatedFlag[0] = true;
            int head = Math.max(1, arrayLimit / 2);
            int tail = Math.max(1, arrayLimit - head);
            for (int i = 0; i < head; i++) {
                result.add(shrink(node.get(i), policy, arrayLimit, protectedCounter, truncatedFlag));
            }
            ObjectNode omitted = JsonNodeFactory.instance.objectNode();
            omitted.put("_omitted", node.size() - head - tail);
            result.add(omitted);
            for (int i = node.size() - tail; i < node.size(); i++) {
                result.add(shrink(node.get(i), policy, arrayLimit, protectedCounter, truncatedFlag));
            }
            return result;
        }
        if (node.isTextual()) {
            String text = node.textValue();
            if (policy.isProtected(text)) {
                protectedCounter[0]++;
                return node;
            }
            if (text.length() > STRING_TRUNCATE_THRESHOLD) {
                truncatedFlag[0] = true;
                String truncated = text.substring(0, STRING_HEAD)
                        + "…[" + (text.length() - STRING_HEAD - STRING_TAIL) + " 字符已省略]…"
                        + text.substring(text.length() - STRING_TAIL);
                return TextNode.valueOf(truncated);
            }
            return node;
        }
        return node;
    }
}
