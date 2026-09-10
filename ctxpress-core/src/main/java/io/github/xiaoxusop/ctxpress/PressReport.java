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
 */
public record PressReport(
        ContextKind kind,
        int originalTokens,
        int compressedTokens,
        double reductionPercent,
        int protectedSegments,
        List<String> actions
) {

    public PressReport {
        actions = List.copyOf(actions);
    }

    /** 是否真的变小了 */
    public boolean effective() {
        return compressedTokens < originalTokens;
    }

    /** 机器可读的一行摘要，适合打日志或写评测报告 */
    public String summary() {
        return "%s: %d -> %d tokens (-%.1f%%), 保护 %d 段, 动作=%s"
                .formatted(kind, originalTokens, compressedTokens, reductionPercent,
                        protectedSegments, actions);
    }
}
