package io.github.xiaoxusop.ctxpress;

/**
 * 报告里"这份 token 数字是按什么口径算的"。
 *
 * <p>为什么必须写进报告：本库有两个计数器——启发式的 {@link TokenEstimator} 与
 * 引入 {@code ctxpress-tokenizer-jtokkit} 后的真实 BPE 词表。同一份内容在两者下的
 * 数字可以差 60%（实测同一份日志：启发式 68,571、{@code o200k_base} 109,398）。
 * 报告里只写"压缩到 6,000 token"而不说口径，读的人没法判断这个 6,000 能不能塞进模型，
 * 也没法判断该不该调预算。
 *
 * <p>这不是"多打一个字段"的洁癖：估算是**偏乐观**的，也就是说按估算算出来刚好卡预算的内容，
 * 真实计费很可能是超的。这个差别只有在报告里写清楚才可能被发现。
 *
 * @param name  计数器名字（例如 {@code heuristic}、{@code heuristic×1.60}、{@code o200k_base}）
 * @param exact true 表示真实词表口径，false 表示启发式估算
 */
public record TokenCounterInfo(String name, boolean exact) {

    public TokenCounterInfo {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("token 计数器的名字不能为空：报告必须能说明数字口径");
        }
    }

    /** 取某个计数器当时的名字与口径 */
    public static TokenCounterInfo of(TokenCounter counter) {
        return new TokenCounterInfo(counter.name(), counter.exact());
    }

    /** 是否来自启发式估算——调用方据此决定要不要留余量 */
    public boolean estimated() {
        return !exact;
    }

    /** 报告里的一行说明 */
    public String describe() {
        return name + (exact ? "（真实词表口径）" : "（启发式估算口径，偏乐观）");
    }
}
