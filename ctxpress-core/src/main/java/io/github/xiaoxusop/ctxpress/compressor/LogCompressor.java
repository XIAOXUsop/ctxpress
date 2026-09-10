package io.github.xiaoxusop.ctxpress.compressor;

import io.github.xiaoxusop.ctxpress.Assembly;
import io.github.xiaoxusop.ctxpress.Compressor;
import io.github.xiaoxusop.ctxpress.ContextKind;
import io.github.xiaoxusop.ctxpress.PressPolicy;
import io.github.xiaoxusop.ctxpress.PressReport;
import io.github.xiaoxusop.ctxpress.PressResult;
import io.github.xiaoxusop.ctxpress.TokenCounter;
import io.github.xiaoxusop.ctxpress.TokenEstimator;

import java.util.ArrayList;
import java.util.List;

/**
 * 日志压缩：**按行**处理，永不破坏单行完整性（堆栈行不会被切一半）。
 *
 * <p>三步，顺序固定：
 * <ol>
 *   <li><b>折叠连续重复</b>——重试风暴、心跳日志占掉大半上下文，折叠成一行并标注次数；</li>
 *   <li><b>保护命中行</b>——ERROR / 编号 / 哈希等命中 mustKeep 的行**无条件保留**。
 *       堆栈里的 {@code Caused by} 恰恰是模型最需要的信息；</li>
 *   <li><b>预算内取头尾 + 均匀采样，再对账</b>——省略标记本身也占 token，
 *       要等保留集合定下来才知道有几段，所以必须渲染出来对账，而不是"选完就完"。</li>
 * </ol>
 */
public final class LogCompressor implements Compressor {

    @Override
    public PressResult compress(String content, PressPolicy policy, String archiveRef) {
        TokenCounter counter = policy.tokenCounter();
        int originalTokens = counter.count(content);
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

        boolean[] required = new boolean[folded.size()];
        int protectedSegments = 0;
        for (int i = 0; i < folded.size(); i++) {
            required[i] = policy.isProtected(folded.get(i), downgrade);
            if (required[i]) {
                protectedSegments++;
            }
        }
        if (protectedSegments > 0) {
            actions.add("PROTECTED_LINES=" + protectedSegments);
        }

        // 省略标记的成本按"每个区段一个"计入预算。带归档引用时标记会长得多
        // （引用本身 + 取回说明），这正是开启可逆后更容易撑爆预算的原因，必须如实计入。
        int markerCost = counter.count(omissionMarker(1, archiveRef)) + 1;
        Assembly assembly = assemble(folded, required, policy, 0, markerCost,
                kept -> render(folded, kept, archiveRef));

        int gaps = 0;
        int omitted = 0;
        for (int i = 0; i < assembly.kept().length; i++) {
            if (!assembly.kept()[i]) {
                omitted++;
                if (i == 0 || assembly.kept()[i - 1]) {
                    gaps++;
                }
            }
        }
        // 逐段追加会让一份 3000 行日志产生上千条动作，summary() 单行超过 20KB ——
        // 审计报告本身成了日志污染源。聚合为一条。
        if (gaps > 0) {
            actions.add("OMITTED_LINES=" + omitted + "_IN_" + gaps + "_GAPS");
        }
        if (assembly.unsatisfiable()) {
            actions.add("PROTECTED_CONTENT_EXCEEDS_BUDGET");
        }

        String rebuilt = assembly.content();
        int compressedTokens = counter.count(rebuilt);
        return new PressResult(rebuilt, new PressReport(ContextKind.LOG, originalTokens, compressedTokens,
                TokenEstimator.reductionPercent(originalTokens, compressedTokens), protectedSegments,
                actions, assembly.overBudgetBy()),
                gaps > 0 ? archiveRef : null);
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

    /**
     * 按原始顺序重建，并在被丢弃的区段插入省略标记。
     *
     * <p>按**下标**对齐而不是按内容查找：早先用 {@code keptCopy.indexOf(line)} 逐个找，
     * 既是指数级的慢（每行一次线性查找），遇到不相邻的重复行还会把保留位置错配。
     */
    static String render(List<String> lines, boolean[] kept, String archiveRef) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < lines.size()) {
            if (kept[i]) {
                sb.append(lines.get(i)).append('\n');
                i++;
            } else {
                int start = i;
                while (i < lines.size() && !kept[i]) {
                    i++;
                }
                sb.append(omissionMarker(i - start, archiveRef)).append('\n');
            }
        }
        return sb.toString().stripTrailing();
    }

    /**
     * 省略标记。带归档引用时同时给出取回入口——
     * 只标注"省略了 N 行"而不说"怎么拿回来"，等于让模型明知有缺失却无从补救。
     */
    static String omissionMarker(int dropped, String archiveRef) {
        if (archiveRef == null) {
            return "... [省略 " + dropped + " 行] ...";
        }
        return "... [省略 " + dropped + " 行；原文可经 ctxpress 归档 " + archiveRef + " 取回] ...";
    }
}
