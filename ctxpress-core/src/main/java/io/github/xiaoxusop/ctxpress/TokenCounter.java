package io.github.xiaoxusop.ctxpress;

/**
 * token 计数器。
 *
 * <p>做成可插拔的，是因为"塞进 N 个 token"里的 N 到底以什么为准，本来就不该由本库单方面决定：
 * 默认的 {@link TokenEstimator} 是启发式估算（CJK 码点 1 token、其余 4 字符 1 token），
 * 它对 JSON / 代码这类标点密集的文本**偏乐观**（真实 BPE 约 3.2–3.7 字符/token）。
 * 换上真实词表的实现后，预算才是**可计费 token** 意义上的预算。
 *
 * <p>之所以不把词表直接塞进核心模块：核心的「运行时依赖只有 Jackson、完全离线」
 * 是它相对需要下载模型权重的方案的真实优势，不该为了计数器破掉。
 * 需要真实计数的场景引入 {@code ctxpress-tokenizer-jtokkit} 模块即可。
 */
public interface TokenCounter {

    /** 估算文本的 token 数。实现必须是**确定性**的：同输入恒返回同值。 */
    int count(String text);

    /** 标注在报告里的名字，便于排查"这个数字是按哪个词表算的" */
    default String name() {
        return getClass().getSimpleName();
    }

    /**
     * 这个计数器给的是**真实词表**的结果（true），还是启发式估算（false）。
     *
     * <p>默认 false：新增的实现如果不主动声明，就会被当作估算口径写进报告。
     * 反过来的默认值很危险——一个估算器只要不吭声就会被当成精确计数，
     * 而"预算 8000 实际花 11000"这种事不会以任何形式报错。
     */
    default boolean exact() {
        return false;
    }
}
