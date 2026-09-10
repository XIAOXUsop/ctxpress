package io.github.xiaoxusop.ctxpress;

import java.util.List;

/**
 * 压缩审计报告。
 *
 * <p>为什么压缩必须可审计：模型看到的是压缩后的内容，一旦结果不对，
 * 必须能回答"它当时看到的是原文还是压缩版、压掉了什么"。
 * 没有这份报告，压缩就是不可解释的黑箱。
 *
 * @param kind             判定出的体裁
 * @param originalTokens   压缩前 token 估算
 * @param compressedTokens 压缩后 token 估算
 * @param reductionPercent 压缩率（%）
 * @param protectedSegments 被 mustKeep 保护而原样保留的片段数
 * @param actions          按发生顺序记录的压缩动作（确定性，便于比对）
 * @param overBudgetBy     超出预算的 token 数；满足预算时为 0。
 *                         <b>非 0 表示"压缩器没能满足你给的预算"</b>——这只可能发生在
 *                         受保护内容本身已超过预算时。此时内容按"保护优先"照常返回，
 *                         但调用方必须能知道这件事：静默超预算比显式声明危险得多。
 */
public record PressReport(
        ContextKind kind,
        int originalTokens,
        int compressedTokens,
        double reductionPercent,
        int protectedSegments,
        List<String> actions,
        int overBudgetBy
) {

    /** 单行摘要里最多列出多少个动作，超出部分折叠——否则一份 3000 行日志能让这行超过 20KB */
    private static final int MAX_ACTIONS_IN_SUMMARY = 16;

    public PressReport {
        actions = List.copyOf(actions);
    }

    /** 满足预算的构造（绝大多数情况） */
    public PressReport(ContextKind kind, int originalTokens, int compressedTokens,
                       double reductionPercent, int protectedSegments, List<String> actions) {
        this(kind, originalTokens, compressedTokens, reductionPercent, protectedSegments, actions, 0);
    }

    /** 是否真的变小了 */
    public boolean effective() {
        return compressedTokens < originalTokens;
    }

    /** 预算是否被满足：输出 <= 预算，或已显式声明不可满足 */
    public boolean budgetSatisfied() {
        return overBudgetBy == 0;
    }

    /**
     * 预算是否无法满足。
     *
     * <p>语义是"**受保护内容本身**就超过了预算"——这不是压缩器偷懒，
     * 而是"保护优先"与"塞进 N 个 token"两条承诺在数学上互斥时的必然结果。
     * 此时压缩器选择保住内容并如实上报，把决策交回调用方。
     */
    public boolean budgetUnsatisfiable() {
        return overBudgetBy > 0;
    }

    /** 机器可读的一行摘要，适合打日志或写评测报告 */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("%s: %d -> %d tokens (-%.1f%%), 保护 %d 段"
                .formatted(kind, originalTokens, compressedTokens, reductionPercent, protectedSegments));
        if (budgetUnsatisfiable()) {
            sb.append(", 预算未满足=+").append(overBudgetBy);
        }
        sb.append(", 动作=");
        if (actions.size() <= MAX_ACTIONS_IN_SUMMARY) {
            sb.append(actions);
        } else {
            sb.append(actions.subList(0, MAX_ACTIONS_IN_SUMMARY))
              .append("…共 ").append(actions.size()).append(" 项");
        }
        return sb.toString();
    }
}
