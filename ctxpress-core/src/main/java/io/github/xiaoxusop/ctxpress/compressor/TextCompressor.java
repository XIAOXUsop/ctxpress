package io.github.xiaoxusop.ctxpress.compressor;

import io.github.xiaoxusop.ctxpress.Assembly;
import io.github.xiaoxusop.ctxpress.Compressor;
import io.github.xiaoxusop.ctxpress.ContextKind;
import io.github.xiaoxusop.ctxpress.PressPolicy;
import io.github.xiaoxusop.ctxpress.PressReport;
import io.github.xiaoxusop.ctxpress.TokenCounterInfo;
import io.github.xiaoxusop.ctxpress.PressResult;
import io.github.xiaoxusop.ctxpress.TokenCounter;
import io.github.xiaoxusop.ctxpress.TokenEstimator;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 文本压缩：**按句子**处理，绝不从句子中间截断。
 *
 * <p>用于 RAG 片段、文档、模型输出。截断到任意字符位置会制造残句，
 * 残句既浪费 token（语义不完整，模型仍需猜）又可能误导读法。
 *
 * <p>三步：去重 → 保护关键句 → 预算内取头尾。
 * 去重对 RAG 尤其有效——检索回来的多个片段经常大段重叠。
 */
public final class TextCompressor implements Compressor {

    /** 中日韩句末标点 */
    private static final String CJK_TERMINATORS = "。！？";

    /**
     * 切句。
     *
     * <p><b>为什么手写扫描而不用正则</b>：句末标点有两类——中日韩标点，以及
     * **后接空白或文本结尾的 ASCII 句点**。后者无法用单个字符类表达（"点、但后面不能紧跟非空白"），
     * 写成 {@code (?:[^…]|\.(?!\s|$))*} 这样的交替结构后，Java 正则引擎会把每次迭代编成一次递归调用，
     * 一段 3800 字符、没有句末标点的内容直接 {@code StackOverflowError}。
     * 逐字符扫描没有这个风险，也更快。
     *
     * <p>ASCII 句点必须看下一个字符，否则 {@code 3.14}、{@code v1.0}、文件名里的点都会被切开。
     * 早先则相反——只认中日韩标点，一篇用 {@code ". "} 断句的英文文档会被当成"一句话"，
     * 整篇塌缩成一个省略标记（实测 10111 token 的英文文档在 2000 预算下输出 5 token，信息全部灭失）。
     *
     * <p>已知不足：{@code e.g. }、{@code Mr. } 这类缩写点后接空白，仍会被切开。
     * 切开只是让句子更碎，不会丢信息，属于可接受的取舍。
     */
    static List<String> splitSentences(String content) {
        List<String> sentences = new ArrayList<>();
        int length = content.length();
        int start = 0;
        for (int i = 0; i < length; i++) {
            char c = content.charAt(i);
            boolean terminator = CJK_TERMINATORS.indexOf(c) >= 0
                    || c == '!' || c == '?' || c == '\n' || c == '\r'
                    || (c == '.' && endsSentence(content, i));
            if (terminator) {
                addIfNotBlank(sentences, content.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < length) {
            addIfNotBlank(sentences, content.substring(start));
        }
        return sentences;
    }

    /** ASCII 句点只有后接空白或文本结尾时才算句末 */
    private static boolean endsSentence(String content, int index) {
        int next = index + 1;
        return next >= content.length() || Character.isWhitespace(content.charAt(next));
    }

    private static void addIfNotBlank(List<String> sentences, String candidate) {
        String stripped = candidate.strip();
        if (!stripped.isEmpty()) {
            sentences.add(stripped);
        }
    }

    @Override
    public PressResult compress(String content, PressPolicy policy, String archiveRef) {
        TokenCounter counter = policy.tokenCounter();
        int originalTokens = counter.count(content);
        List<String> actions = new ArrayList<>();

        List<String> sentences = splitSentences(content);
        if (sentences.isEmpty()) {
            return PressResult.unchanged(content, ContextKind.TEXT, originalTokens, TokenCounterInfo.of(counter));
        }

        // 句子级去重：RAG 片段之间的大段重叠是 token 浪费的主要来源
        Set<String> seen = new LinkedHashSet<>();
        List<String> deduped = new ArrayList<>(sentences.size());
        int duplicates = 0;
        for (String sentence : sentences) {
            if (seen.add(sentence)) {
                deduped.add(sentence);
            } else {
                duplicates++;
            }
        }
        if (duplicates > 0) {
            actions.add("DEDUP_SENTENCES=" + duplicates);
        }

        // 保护占比闸门。文本体裁早先**完全没有**这道闸门（只有日志有），
        // 于是"每句都含金额或编号"的文本会命中全部保护规则，一句都压不掉。
        // 保护了全部等于保护不了。
        int candidates = 0;
        for (String sentence : deduped) {
            if (policy.isProtected(sentence)) {
                candidates++;
            }
        }
        boolean downgrade = !deduped.isEmpty()
                && (double) candidates / deduped.size() > policy.maxProtectedRatio();
        if (downgrade) {
            actions.add("PROTECTION_DOWNGRADED_TO_CRITICAL_ONLY=" + candidates + "/" + deduped.size());
        }

        boolean[] required = new boolean[deduped.size()];
        int protectedSegments = 0;
        for (int i = 0; i < deduped.size(); i++) {
            required[i] = policy.isProtected(deduped.get(i), downgrade);
            if (required[i]) {
                protectedSegments++;
            }
        }
        if (protectedSegments > 0) {
            actions.add("PROTECTED_SENTENCES=" + protectedSegments);
        }

        // 文本体裁的渲染只在末尾追加**一个**省略标记，与区段数无关，
        // 所以按固定开销预留，而不是按区段计。
        int markerCost = counter.count(omissionMarker(1, archiveRef)) + 1;
        Assembly assembly = assemble(deduped, required, policy, markerCost, 0,
                kept -> render(deduped, kept, archiveRef));

        int omitted = 0;
        for (boolean keep : assembly.kept()) {
            if (!keep) {
                omitted++;
            }
        }
        if (omitted > 0) {
            actions.add("OMITTED_SENTENCES=" + omitted);
        }
        if (assembly.unsatisfiable()) {
            actions.add("PROTECTED_CONTENT_EXCEEDS_BUDGET");
        }

        String rebuilt = assembly.content();
        int compressedTokens = counter.count(rebuilt);
        // 只有真的丢过句子才给出归档引用：没压缩还为它占一份归档，是白占容量
        return new PressResult(rebuilt, new PressReport(ContextKind.TEXT, originalTokens, compressedTokens,
                TokenEstimator.reductionPercent(originalTokens, compressedTokens), protectedSegments,
                actions, assembly.overBudgetBy(), TokenCounterInfo.of(counter)),
                omitted > 0 ? archiveRef : null);
    }

    /** 保留的句子以空格相连；有丢弃时在末尾给出省略标记与取回入口 */
    static String render(List<String> sentences, boolean[] kept, String archiveRef) {
        StringBuilder sb = new StringBuilder();
        int omitted = 0;
        for (int i = 0; i < sentences.size(); i++) {
            if (kept[i]) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(sentences.get(i));
            } else {
                omitted++;
            }
        }
        if (omitted > 0) {
            sb.append(omissionMarker(omitted, archiveRef));
        }
        return sb.toString();
    }

    /**
     * 省略标记。带归档引用时同时给出取回入口——
     * 只标注"省略了 N 句"而不说"怎么拿回来"，等于让模型明知有缺失却无从补救。
     */
    static String omissionMarker(int omitted, String archiveRef) {
        if (archiveRef == null) {
            return " …[省略 " + omitted + " 句]";
        }
        return " …[省略 " + omitted + " 句；原文可经 ctxpress 归档 " + archiveRef + " 取回]";
    }
}
