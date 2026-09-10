package io.github.xiaoxusop.ctxpress.compressor;

import io.github.xiaoxusop.ctxpress.Compressor;
import io.github.xiaoxusop.ctxpress.ContextKind;
import io.github.xiaoxusop.ctxpress.PressPolicy;
import io.github.xiaoxusop.ctxpress.PressReport;
import io.github.xiaoxusop.ctxpress.PressResult;
import io.github.xiaoxusop.ctxpress.TokenEstimator;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /** 中英文句末标点；保留标点本身 */
    private static final Pattern SENTENCE = Pattern.compile("[^。！？!?\n]+[。！？!?]?");

    @Override
    public ContextKind kind() {
        return ContextKind.TEXT;
    }

    @Override
    public PressResult compress(String content, PressPolicy policy, String archiveRef) {
        int originalTokens = TokenEstimator.estimate(content);
        List<String> actions = new ArrayList<>();

        List<String> sentences = new ArrayList<>();
        Matcher matcher = SENTENCE.matcher(content);
        while (matcher.find()) {
            String sentence = matcher.group().strip();
            if (!sentence.isEmpty()) {
                sentences.add(sentence);
            }
        }
        if (sentences.isEmpty()) {
            return PressResult.unchanged(content, ContextKind.TEXT, originalTokens);
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

        List<Boolean> required = new ArrayList<>(deduped.size());
        int protectedSegments = 0;
        for (String sentence : deduped) {
            boolean isProtected = policy.isProtected(sentence);
            required.add(isProtected);
            if (isProtected) {
                protectedSegments++;
            }
        }
        if (protectedSegments > 0) {
            actions.add("PROTECTED_SENTENCES=" + protectedSegments);
        }

        List<String> kept = selectWithinBudget(deduped, required,
                policy.headLines(), policy.tailLines(), policy.maxTokens());

        boolean dropped = kept.size() < deduped.size();
        int omitted = deduped.size() - kept.size();
        String rebuilt = String.join(" ", kept);
        if (dropped) {
            rebuilt += archiveRef == null
                    ? " …[省略 " + omitted + " 句]"
                    : " …[省略 " + omitted + " 句；原文可经 ctxpress 归档 " + archiveRef + " 取回]";
            actions.add("OMITTED_SENTENCES=" + omitted);
        }

        int compressedTokens = TokenEstimator.estimate(rebuilt);
        // 只有真的丢过句子才给出归档引用：没压缩还为它占一份归档，是白占容量
        return new PressResult(rebuilt, new PressReport(ContextKind.TEXT, originalTokens, compressedTokens,
                TokenEstimator.reductionPercent(originalTokens, compressedTokens), protectedSegments, actions),
                dropped ? archiveRef : null);
    }
}
