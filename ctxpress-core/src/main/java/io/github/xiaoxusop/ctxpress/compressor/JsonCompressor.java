package io.github.xiaoxusop.ctxpress.compressor;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
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
import io.github.xiaoxusop.ctxpress.TokenCounterInfo;
import io.github.xiaoxusop.ctxpress.PressResult;
import io.github.xiaoxusop.ctxpress.TokenCounter;
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
 * <p>三档，按信息损失从少到多：
 * <ol>
 *   <li><b>仅去空白</b>——只去掉缩进与换行，不改动任何结构。JSON 的缩进往往占掉可观比例，
 *       去掉它们常常就够装进预算了；</li>
 *   <li><b>截断超长字符串</b>——保留首尾并标注省略字数；</li>
 *   <li><b>采样长数组</b>——保留首尾样本 + {@code {"_omitted": N}} 计数节点，
 *       采样上限由预算反推，而不是一个固定数字。</li>
 * </ol>
 *
 * <p>对象**保留全部键**（键名本身信息密度极高，删键会让模型误解数据形状）；
 * 数值/布尔/null 一律保留。
 *
 * <p>解析失败时先判断是不是日志，是日志交 {@link LogCompressor}，
 * 否则回退 {@link TextCompressor}——绝不抛异常中断 Agent 流程。
 */
public final class JsonCompressor implements Compressor {

    /**
     * 四个开关都是为了**不静默改数据**：
     *
     * <ul>
     *   <li>{@code FAIL_ON_TRAILING_TOKENS}——不检查尾随内容时，NDJSON
     *       （{@code {"a":1}\n{"b":2}\n{"c":3}}）会被解析成第一个文档，后面全部无声消失。
     *       实测 420 token 的 30 行 NDJSON 被"压缩"到 14 token，而报告里
     *       {@code truncated=false}、动作为空——看不出丢了东西。宁可判为"不是单个 JSON"
     *       交给日志/文本压缩器，也不能假装压缩成功。</li>
     *   <li>{@code STRICT_DUPLICATE_DETECTION}——重复键默认保留最后一个、丢弃前面的值。
     *       JSON 规范没有定义该行为，与其替调用方选一个，不如判为非法。</li>
     *   <li>{@code USE_BIG_DECIMAL_FOR_FLOATS} + {@code withExactBigDecimals}——
     *       **小数一律走 BigDecimal，不走 double**。默认行为会把小数读成 double 再写回，
     *       于是超出 double 精度的值会被改写：实测 {@code 998877665544332211.99} →
     *       {@code 9.988776655443322E17}、{@code 1e400} → {@code "Infinity"}（还变成了字符串）、
     *       {@code 1e-400} → {@code 0.0}。而报告里的动作是
     *       {@code ARRAY_SAMPLED_LIMIT} / {@code NO_TRIMMABLE_STRUCTURE_LEFT} 之类，
     *       **没有任何一条说"数值被改过"**。
     *       BigDecimal 是精确十进制，`1e400` 与 `1e-400` 都能原样承载，
     *       所以这两条修的是"值"而不是"字节"——对第二、三档（按设计就是有损的）来说，
     *       值保真是该守的那条线。第一档另走纯文本扫描，连字节都不动（见 {@code minifyTextually}）。</li>
     * </ul>
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));

    /** 单个字符串值超过此长度才考虑截断 */
    private static final int STRING_TRUNCATE_THRESHOLD = 512;
    private static final int STRING_HEAD = 320;
    private static final int STRING_TAIL = 96;

    /** 数组采样上限的搜索上界。真实文档里数组极少超过这个量级，再往上搜只是白费轮数。 */
    private static final int MAX_ARRAY_LIMIT = 8192;

    private final Compressor textFallback = new TextCompressor();
    private final Compressor logFallback = new LogCompressor();

    @Override
    public PressResult compress(String content, PressPolicy policy, String archiveRef) {
        TokenCounter counter = policy.tokenCounter();
        int originalTokens = counter.count(content);

        JsonNode root;
        try {
            root = MAPPER.readTree(content);
        } catch (Exception e) {
            return fallback(content, policy, archiveRef, originalTokens);
        }

        // 档位一：仅去掉缩进与换行。
        //
        // ── 为什么是文本扫描，而不是 root.toString() ────────────────────
        //
        // 这里原先用的是"解析再序列化"（`root.toString()`），注释里写着"值等价，字节不等价"。
        // **"值等价"是错的。** 实测（2026-09-22，预算 64 逼出这一档）：
        //
        //     99999999999999999999.99  ->  1.0E20                 值变了
        //     12345678901234567.89     ->  1.2345678901234568E16  精度丢了
        //     1e400                    ->  "Infinity"             数字变成了字符串
        //     1e-400                   ->  0.0                    下溢成零
        //     100.00                   ->  100.0
        //
        // 而这一档报的动作是 `MINIFIED_ONLY`、`archiveRef` 是 null——
        // **没有任何标记说值被改过**，调用方拿到的是"只去了空白"的报告和一个变了的数。
        // 对账单、金额、ID 这类下游，这是静默的错误数据。
        // README 的契约之一是「输出不含原文没有的内容：每个字节要么逐字来自输入，
        // 要么属于已声明的标记文法」——`"Infinity"` 两条都不满足。
        //
        // 这一档本来就不需要解析：它要做的只是"去掉缩进与换行"，
        // 而"哪些空白在字符串里"用一个字符扫描就能确定。改成文本扫描之后，
        // 输出**逐字节来自输入**，这一档的承诺才真的成立。
        //
        // 这一档早先是完全缺失的，代价荒唐：129997 token 的 JSON 在 128000 预算下
        // 只输出 132 token——而它去掉缩进后是 77494 token，明明装得下，
        // 却把数组里 99.8% 的元素丢掉了。
        String minified = minifyTextually(content);
        int minifiedTokens = counter.count(minified);
        if (minifiedTokens <= policy.maxTokens()) {
            return new PressResult(minified, new PressReport(ContextKind.JSON,
                    originalTokens, minifiedTokens,
                    TokenEstimator.reductionPercent(originalTokens, minifiedTokens),
                    0, List.of("MINIFIED_ONLY"), TokenCounterInfo.of(counter)), null);
        }

        // 档位二/三：按预算裁剪。
        List<String> actions = new ArrayList<>();
        int[] protectedCount = {0};
        boolean[] truncated = {false};
        int largestArray = maxArraySize(root);

        // 归档引用本身也占 token，先从预算里扣掉。
        // 早先是"先按预算检查、检查完再把 _ctxpress_archive 塞进对象"——
        // 塞进去的那一刻就超出了预算。
        int reserved = archiveRef == null ? 0 : archiveFieldCost(counter);
        int budget = Math.max(1, policy.maxTokens() - reserved);

        int[] probeCounters = new int[1];
        boolean[] probeFlags = new boolean[1];
        int limit = chooseArrayLimit(root, policy, archiveRef, largestArray, budget, probeCounters, probeFlags);

        protectedCount[0] = 0;
        truncated[0] = false;
        boolean[] markerUsed = {false};
        JsonNode compressed = shrink(root, policy, limit, protectedCount, truncated, archiveRef, markerUsed);

        if (limit < Math.min(MAX_ARRAY_LIMIT, Math.max(1, largestArray))) {
            actions.add("ARRAY_SAMPLED_LIMIT=" + limit);
        }
        if (protectedCount[0] > 0) {
            actions.add("PROTECTED_JSON_VALUES=" + protectedCount[0]);
        }
        if (truncated[0] && archiveRef != null) {
            embedArchiveRef(compressed, root, limit, archiveRef, markerUsed, actions);
        }

        String rendered = compressed.toString();
        int compressedTokens = counter.count(rendered);
        int over = Math.max(0, compressedTokens - policy.maxTokens());
        if (over > 0) {
            // 超预算有两种成因，报出来的名字必须分得开：
            //
            //   * 命中了保护规则的内容本身就超预算 —— 那就只能放宽保护额度（或接受超标）；
            //   * 压根没有可裁的结构（对象字段一个不能少、字符串都在截断阈值以内、
            //     没有数组可采样）—— 这时去调 maxProtectedRatio **什么也不会变**。
            //
            // 这里原先一律报 NOTHING_LEFT_TO_TRIM_EXCEPT_PROTECTED_VALUES。
            // 实测（2026-09-22）：一份 20 字段 × 短字符串值的 JSON，
            // `protectedSegments=0` 而报的正是"除受保护值外已无可裁剪"——
            // 按这个名字去调保护额度，怎么调都不会动。
            actions.add(protectedCount[0] > 0
                    ? "NOTHING_LEFT_TO_TRIM_EXCEPT_PROTECTED_VALUES"
                    : "NO_TRIMMABLE_STRUCTURE_LEFT");
        }
        return new PressResult(rendered, new PressReport(ContextKind.JSON, originalTokens, compressedTokens,
                TokenEstimator.reductionPercent(originalTokens, compressedTokens),
                protectedCount[0], actions, over, TokenCounterInfo.of(counter)),
                truncated[0] ? archiveRef : null);
    }

    /**
     * 去掉 JSON 结构里的空白，**其余字符逐字节保留**。
     *
     * <p>只在字符串字面量之外删空白：扫描时跟踪"是否在字符串内"与转义状态，
     * 因此 `{"a": "x  y"}` 里的两个空格原样留着，而字段之间的缩进与换行被去掉。
     *
     * <p>这是"仅去空白"这一档唯一诚实的实现方式——任何"解析再序列化"的写法
     * 都会顺手改写数值字面量（见 {@link #compress} 里的实测清单）。
     */
    private static String minifyTextually(String json) {
        StringBuilder out = new StringBuilder(json.length());
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                out.append(c);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                out.append(c);
            } else if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * 把归档引用嵌进内容，让读到它的模型知道怎么取回被压掉的原文。
     *
     * <p>只嵌在**根部**——字段是给"整份内容的取回入口"用的，嵌进每个嵌套对象既冗余又费 token。
     *
     * <p>根是数组时没有"字段"可写，退而把引用放进**根数组自己的 {@code _omitted} 计数节点**里。
     * 这比包一层 {@code {"data": [...]}} 好：包一层会把根类型从数组变成对象，
     * 强类型反序列化直接失败；而 {@code _omitted} 已经是个记账节点，再挂一个键只是元素级污染。
     * 若根数组没被截断（只截了内层数组或字符串），引用已经由 {@code shrink} 写进
     * 第一条字符串省略标记里（见 {@code ARCHIVE_REF_EMBEDDED_IN_STRING_MARKER}）；
     * 连字符串都没截断时才真的没有承载位置，如实记为不可嵌入。
     */
    private static void embedArchiveRef(JsonNode compressed, JsonNode root, int limit,
                                        String archiveRef, boolean[] markerUsed, List<String> actions) {
        if (compressed.isObject()) {
            ((ObjectNode) compressed).put("_ctxpress_archive", archiveRef);
            actions.add("ARCHIVE_REF_EMBEDDED");
            return;
        }
        int head = Math.max(1, limit / 2);
        int tail = Math.max(1, limit - head);
        if (compressed.isArray() && root.isArray() && root.size() > head + tail
                && compressed.size() > head && compressed.get(head).isObject()) {
            ((ObjectNode) compressed.get(head)).put("_ctxpress_archive", archiveRef);
            actions.add("ARCHIVE_REF_EMBEDDED_IN_OMITTED_NODE");
            return;
        }
        if (markerUsed[0]) {
            // 根数组没被截断，但**内层字符串**被截断了——引用已经在第一条省略标记里
            // （由 shrink 写入）。日志与文本压缩器一直这么做，JSON 这边原先漏了：
            // 实测根为数组、2 个元素、每个带一个 2 万字符的字符串时，动作是
            // ARCHIVE_REF_NOT_EMBEDDABLE，内容里既没有 ORIG-… 也没有 _ctxpress_archive
            // ——README 那句"读到这段内容的模型自己就知道怎么要回来"在这条路径上是假的。
            actions.add("ARCHIVE_REF_EMBEDDED_IN_STRING_MARKER");
            return;
        }
        actions.add("ARCHIVE_REF_NOT_EMBEDDABLE");
    }

    /**
     * 非法 JSON 的兜底。
     *
     * <p>先判是不是日志：{@code [INFO] ...} / {@code [2026-09-11 10:00:00] ...} 这类
     * 方括号前缀的构建输出**以 {@code [} 开头**，会被当成 JSON 数组去解析、然后失败。
     * 早先这里一律退回文本压缩，于是这类输入（Maven / Gradle / logback 默认格式，
     * Agent 最高频的工具输出之一）压缩率恒为 0。
     */
    private PressResult fallback(String content, PressPolicy policy, String archiveRef, int originalTokens) {
        Compressor delegate = ContextKind.detect(content) == ContextKind.LOG ? logFallback : textFallback;
        PressResult result = delegate.compress(content, policy, archiveRef);
        List<String> actions = new ArrayList<>(result.report().actions());
        actions.add(0, "JSON_PARSE_FAILED_FALLBACK_TO_" + result.report().kind());
        return new PressResult(result.content(), new PressReport(result.report().kind(), originalTokens,
                result.report().compressedTokens(), result.report().reductionPercent(),
                result.report().protectedSegments(), actions, result.report().overBudgetBy(),
                result.report().counter()), result.archiveRef());
    }

    /** 归档字段写进内容时要额外占用的 token（ref 恒为 {@code ORIG-} + 16 位十六进制） */
    private static int archiveFieldCost(TokenCounter counter) {
        // 引用有两个可能的承载位置，预留要**取两者较大的那个**：
        //   · 根是对象 → 写一个顶层字段
        //   · 根是数组且数组本身没被截断 → 写进第一条字符串省略标记
        // 只按前者留，后者那条路径就会越过预算——而"输出 ≤ 预算"是这个工具唯一的硬契约。
        int field = counter.count(",\"_ctxpress_archive\":\"ORIG-0123456789abcdef\"");
        int marker = counter.count("；原文可经 ctxpress 归档 ORIG-0123456789abcdef 取回");
        return Math.max(field, marker);
    }

    /**
     * 选择数组采样上限。
     *
     * <p><b>不能对整个区间二分</b>：当 limit 跨过某个数组的长度时，那个数组不再被截断、
     * {@code {"_omitted":N}} 计数节点随之消失，渲染结果可能反而**变小**——实测 9 元素数组
     * 在 limit=8 时输出 8 token，limit=9 时只有 5 token。谓词非单调，二分会选错边界。
     *
     * <p>所以先把"完全不截断数组"作为首选端点单独试——它是上面那个非单调点的正上方，
     * 也是信息损失最小的解。不通过才进入搜索。
     *
     * <p>搜索区间取整个 {@code [1, ceiling]} 而不是"所有数组都处于截断态"的子区间：
     * 后者只要文档里存在一个小数组就会塌缩（一个 2 元素数组会把区间压到 {@code [1,1]}，
     * 于是 200 元素的数组被截到 3 个，而预算其实装得下 78 个）。
     *
     * <p>代价是二分会落在偏小的一侧（成本在数组长度边界处小幅回落，谓词不是严格单调）。
     * 但**正确性不受影响**：每次探测都重新核实成本，{@code best} 只被赋值为验证通过的候选，
     * 所以返回值一定满足预算——最坏情况只是截得比必要更狠一点。末尾再加一小段向上线性探测
     * 把边界处的回落补回来。
     *
     * @return 可用的 limit，保证其成本不超过预算
     */
    private int chooseArrayLimit(JsonNode root, PressPolicy policy, String archiveRef, int largestArray,
                                 int budget, int[] counters, boolean[] flags) {
        int ceiling = Math.min(MAX_ARRAY_LIMIT, Math.max(1, largestArray));
        // 用户显式收紧的上限（`PressPolicy.Builder.maxArrayItems`）。
        // 这个是**天花板**，不是起点：采样条数仍然按预算反推（见下面那段二分），
        // 上限只在反推结果比它大时才生效。默认 Integer.MAX_VALUE = 不收紧。
        ceiling = Math.min(ceiling, Math.max(1, policy.maxArrayItems()));
        if (costAt(root, policy, ceiling, counters, flags, archiveRef) <= budget) {
            return ceiling;   // 一个数组元素都不用丢
        }
        if (largestArray == 0) {
            return 1;         // 文档里根本没有数组，没有可调的旋钮
        }
        int lo = 1;
        int hi = Math.max(1, ceiling - 1);
        int best = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (costAt(root, policy, mid, counters, flags, archiveRef) <= budget) {
                best = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        if (best == 0) {
            return 1;         // 已最大程度截断仍装不下，交给上层如实上报
        }
        for (int candidate = best + 1; candidate < ceiling && candidate <= best + 32; candidate++) {
            if (costAt(root, policy, candidate, counters, flags, archiveRef) <= budget) {
                best = candidate;
            }
        }
        return best;
    }

    private int costAt(JsonNode root, PressPolicy policy, int limit, int[] counters, boolean[] flags,
                       String archiveRef) {
        counters[0] = 0;
        flags[0] = false;
        boolean[] markerUsed = {false};
        // **必须用同一套标记**：标记里多出来的那截引用也占 token，
        // 这里不算进去，最终输出就会越过预算——而那正是这个工具唯一的硬契约。
        return policy.tokenCounter()
                .count(shrink(root, policy, limit, counters, flags, archiveRef, markerUsed).toString());
    }

    /** 文档里最长的数组有多长——数组采样上限的搜索上界 */
    private static int maxArraySize(JsonNode node) {
        int max = 0;
        if (node.isArray()) {
            max = node.size();
            for (JsonNode element : node) {
                max = Math.max(max, maxArraySize(element));
            }
        } else if (node.isObject()) {
            Iterator<JsonNode> children = node.elements();
            while (children.hasNext()) {
                max = Math.max(max, maxArraySize(children.next()));
            }
        }
        return max;
    }

    private JsonNode shrink(JsonNode node, PressPolicy policy, int arrayLimit,
                            int[] protectedCounter, boolean[] truncatedFlag,
                            String archiveRef, boolean[] markerUsed) {
        if (node.isObject()) {
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                result.set(field.getKey(), shrink(field.getValue(), policy, arrayLimit, protectedCounter, truncatedFlag, archiveRef, markerUsed));
            }
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = JsonNodeFactory.instance.arrayNode();
            int head = Math.max(1, arrayLimit / 2);
            int tail = Math.max(1, arrayLimit - head);
            // 装得下就整体保留。截断后保留的元素数不比原来少时更不该截——
            // 早先会在这种情形下往数组里插一个 {"_omitted":0}，既凭空多出一个外来元素，
            // 又把一个根本没被动过的数组标成"已截断"。
            if (node.size() <= arrayLimit || node.size() <= head + tail) {
                for (JsonNode element : node) {
                    result.add(shrink(element, policy, arrayLimit, protectedCounter, truncatedFlag, archiveRef, markerUsed));
                }
                return result;
            }
            truncatedFlag[0] = true;
            for (int i = 0; i < head; i++) {
                result.add(shrink(node.get(i), policy, arrayLimit, protectedCounter, truncatedFlag, archiveRef, markerUsed));
            }
            ObjectNode omitted = JsonNodeFactory.instance.objectNode();
            omitted.put("_omitted", node.size() - head - tail);
            result.add(omitted);
            for (int i = Math.max(head, node.size() - tail); i < node.size(); i++) {
                result.add(shrink(node.get(i), policy, arrayLimit, protectedCounter, truncatedFlag, archiveRef, markerUsed));
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
                int omitted = text.length() - STRING_HEAD - STRING_TAIL;
                // 归档引用只嵌**第一处**——与"根是对象时只写一个顶层字段"、
                // "根数组被截断时只写进那一个 _omitted 节点"保持一致。
                // 每个标记都嵌会让开销随截断字符串的数量线性增长，而预算契约是硬的。
                String marker;
                if (archiveRef != null && !markerUsed[0]) {
                    markerUsed[0] = true;
                    marker = "…[" + omitted + " 字符已省略；原文可经 ctxpress 归档 " + archiveRef + " 取回]…";
                } else {
                    marker = "…[" + omitted + " 字符已省略]…";
                }
                return TextNode.valueOf(text.substring(0, STRING_HEAD) + marker
                        + text.substring(text.length() - STRING_TAIL));
            }
            return node;
        }
        return node;
    }
}
