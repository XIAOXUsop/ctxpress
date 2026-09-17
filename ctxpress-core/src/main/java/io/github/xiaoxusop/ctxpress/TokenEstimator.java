package io.github.xiaoxusop.ctxpress;

/**
 * 确定性 token 估算。
 *
 * <p>刻意**不**接任何分词器或 provider 的计费接口：
 * <ul>
 *   <li>压缩必须在**离线、可复现**的前提下成立——接网络会让同一输入在不同时刻得到不同结果；</li>
 *   <li>引分词器（如 BPE ranks）会让这个库被迫绑定某一家模型的词表；</li>
 *   <li>压缩决策对精度的要求是"量级正确"，不是"分毫不差"。</li>
 * </ul>
 *
 * <p>估算规则：CJK 码点按 1 token，其余按 4 字符 1 token 向上取整。
 * 偏向保守（宁可高估），因为高估只会让压缩更积极，低估才会撑爆窗口。
 */
public final class TokenEstimator implements TokenCounter {

    private static final double CHARS_PER_TOKEN = 4.0;

    /** 默认计数器。按接口用，不按实现用——换词表时只需改 {@link PressPolicy.Builder#tokenCounter} */
    private static final TokenCounter DEFAULT = new TokenEstimator();

    private TokenEstimator() {
    }

    public static TokenCounter defaultCounter() {
        return DEFAULT;
    }

    /**
     * 实测倍率：同一份日志本估算器给 <b>68,571</b>，{@code o200k_base} 给 <b>109,398</b>，
     * 即真实值约为估算值的 {@value #BENCHMARK_ESTIMATE_GAP} 倍（低估约 37%）。
     *
     * <p>这个数字来自仓库里 <a href="https://github.com/XIAOXUsop/ctxpress">benchmarks/</a>
     * 的日志基准，是**当前唯一的实测依据**。改它之前请先重跑基准——
     * 拍脑袋调小只会让本来就不准的估算更不准。
     */
    public static final double BENCHMARK_ESTIMATE_GAP = 109_398.0 / 68_571.0;

    /**
     * 带安全余量的估算器：把估算值乘上 {@code multiplier}。
     *
     * <p>什么时候需要它：估算是**偏乐观**的，按估算算出来刚好卡住预算的内容，
     * 真实计费很可能是超的。想用估算口径又不想撑爆窗口时，用一个实测出来的倍数
     * 把估算垫高——但垫高就会多压内容，所以这是**有代价的取舍**，不是免费保险。
     *
     * <p>典型用法是 {@code TokenEstimator.withSafetyMargin(TokenEstimator.BENCHMARK_ESTIMATE_GAP)}。
     * 若你的文本不是标点密集的日志（例如以中文散文为主），这个倍数会明显偏大——
     * 找一个贴近自己语料的倍数，别直接抄。
     *
     * <p>只对启发式口径有意义：真实词表本来就准，再乘一个倍数等于凭空多压内容。
     */
    public static TokenCounter withSafetyMargin(double multiplier) {
        if (!(multiplier > 0) || !Double.isFinite(multiplier)) {
            throw new IllegalArgumentException("安全余量倍数必须是正有限数：" + multiplier);
        }
        return new ScalableTokenEstimator(multiplier);
    }

    /** 估算值 × 倍数的包装；名字里带上倍数，报告里一眼能看出用的是哪一档 */
    private static final class ScalableTokenEstimator implements TokenCounter {

        private final double multiplier;

        private ScalableTokenEstimator(double multiplier) {
            this.multiplier = multiplier;
        }

        @Override
        public int count(String text) {
            return (int) Math.ceil(estimate(text) * multiplier);
        }

        @Override
        public String name() {
            return "heuristic×" + String.format(java.util.Locale.ROOT, "%.2f", multiplier);
        }
    }

    @Override
    public int count(String text) {
        return estimate(text);
    }

    @Override
    public String name() {
        return "heuristic";
    }

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (isCjk(cp)) {
                cjk++;
            } else {
                other++;
            }
            i += Character.charCount(cp);
        }
        int otherTokens = other == 0 ? 0 : (int) Math.ceil(other / CHARS_PER_TOKEN);
        return cjk + otherTokens;
    }

    /** 压缩率百分比（保留一位小数），用于报告 */
    public static double reductionPercent(int originalTokens, int compressedTokens) {
        if (originalTokens <= 0) {
            return 0.0;
        }
        double saved = (originalTokens - compressedTokens) * 100.0 / originalTokens;
        return Math.round(saved * 10.0) / 10.0;
    }

    private static boolean isCjk(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF)
                || (cp >= 0x3400 && cp <= 0x4DBF)
                || (cp >= 0x3000 && cp <= 0x303F)
                || (cp >= 0xFF00 && cp <= 0xFFEF);
    }
}
