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
