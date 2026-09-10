package io.github.xiaoxusop.ctxpress.compressor;

import io.github.xiaoxusop.ctxpress.Compressor;
import io.github.xiaoxusop.ctxpress.ContextKind;
import io.github.xiaoxusop.ctxpress.PressPolicy;
import io.github.xiaoxusop.ctxpress.PressReport;
import io.github.xiaoxusop.ctxpress.PressResult;
import io.github.xiaoxusop.ctxpress.TokenEstimator;

import java.util.ArrayList;
import java.util.List;

/**
 * 日志压缩：**按行**处理，永不破坏单行完整性（堆栈行不会被切一半）。
 *
 * <p>三步，顺序固定：
 * <ol>
 *   <li><b>折叠连续重复</b>——重试风暴、心跳日志占掉大半上下文，折叠成一行并标注次数；</li>
 *   <li><b>保护命中行</b>——ERROR / 编号 / 哈希等命中 mustKeep 的行**无条件保留**，
 *       不受预算限制。堆栈里的 {@code Caused by} 恰恰是模型最需要的信息；</li>
 *   <li><b>预算内取头尾</b>——剩余预算优先给开头（启动上下文）与结尾（最新状态）。</li>
 * </ol>
 */
public final class LogCompressor implements Compressor {

    @Override
    public ContextKind kind() {
        return ContextKind.LOG;
    }

    @Override
    public PressResult compress(String content, PressPolicy policy) {
        int originalTokens = TokenEstimator.estimate(content);
        List<String> actions = new ArrayList<>();

        List<String> raw = List.of(content.split("\n", -1));
        List<String> folded = foldConsecutiveDuplicates(raw, actions);

        // 保护占比闸门：若普通规则几乎命中全部行，说明它已失去区分度。
        // 保护了全部等于保护不了——此时降级为只保护故障线索，否则压缩率会归零。
        int candidates = 0;
        for (String line : folded) {
            if (policy.isProtected(line)) {
                candidates++;
            }
        }
        boolean downgrade = !folded.isEmpty()
                && (double) candidates / folded.size() > policy.maxProtectedRatio();
        if (downgrade) {
            actions.add("PROTECTION_DOWNGRADED_TO_CRITICAL_ONLY=" + candidates + "/" + folded.size());
        }

        List<Boolean> required = new ArrayList<>(folded.size());
        int protectedSegments = 0;
        for (String line : folded) {
            boolean isProtected = policy.isProtected(line, downgrade);
            required.add(isProtected);
            if (isProtected) {
                protectedSegments++;
            }
        }
        if (protectedSegments > 0) {
            actions.add("PROTECTED_LINES=" + protectedSegments);
        }

        List<String> kept = selectWithinBudget(folded, required,
                policy.headLines(), policy.tailLines(), policy.maxTokens());

        String rebuilt = rebuild(folded, kept, actions);
        int compressedTokens = TokenEstimator.estimate(rebuilt);
        return new PressResult(rebuilt, new PressReport(ContextKind.LOG, originalTokens, compressedTokens,
                TokenEstimator.reductionPercent(originalTokens, compressedTokens), protectedSegments, actions));
    }

    /** 折叠**连续**重复行：只折叠相邻的，避免把不同时间点的同类事件混为一谈 */
    private List<String> foldConsecutiveDuplicates(List<String> lines, List<String> actions) {
        List<String> result = new ArrayList<>();
        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            int run = 1;
            while (i + run < lines.size() && lines.get(i + run).equals(line)) {
                run++;
            }
            if (run > 1) {
                result.add(line + "   [重复 " + run + " 次]");
                actions.add("FOLD_DUPLICATE_LINES=" + run);
            } else {
                result.add(line);
            }
            i += run;
        }
        return result;
    }

    /** 按原始顺序重建，并在被丢弃的区段插入省略标记（让模型知道"这里有东西被省略了"） */
    private String rebuild(List<String> all, List<String> kept, List<String> actions) {
        if (kept.size() == all.size()) {
            return String.join("\n", all);
        }
        // 按保留顺序与原顺序依次对齐（重复行已折叠，故不存在歧义）
        List<String> keptCopy = new ArrayList<>(kept);
        StringBuilder sb = new StringBuilder();
        int dropped = 0;
        for (String line : all) {
            int idx = keptCopy.indexOf(line);
            if (idx >= 0) {
                if (dropped > 0) {
                    sb.append("... [省略 ").append(dropped).append(" 行] ...\n");
                    actions.add("OMITTED_LINES=" + dropped);
                    dropped = 0;
                }
                keptCopy.remove(idx);
                sb.append(line).append('\n');
            } else {
                dropped++;
            }
        }
        if (dropped > 0) {
            sb.append("... [省略 ").append(dropped).append(" 行] ...\n");
            actions.add("OMITTED_LINES=" + dropped);
        }
        return sb.toString().stripTrailing();
    }
}
