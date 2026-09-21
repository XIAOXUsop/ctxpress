package io.github.xiaoxusop.ctxpress;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 压缩策略。
 *
 * <p>最重要的一个字段是 {@code mustKeep}：**命中即不裁剪**。
 * 上下文压缩最容易出的问题不是"压得不够"，而是"把关键信息压没了"——
 * 错误码、金额、法规编号、证据 ID 被截断后，模型会基于残缺事实推理，
 * 而这种错误在输出里看不出来。
 *
 * <p>因此本库把"保护"作为一等公民：正则命中即该行/该字段原样保留，
 * 并在 {@link PressReport} 里报告保护了多少处。
 */
public final class PressPolicy {

    /** 默认保护模式：常见的关键事实线索（错误级别、编号、金额、UUID、长十六进制） */
    public static final List<String> DEFAULT_MUST_KEEP = List.of(
            "\\b(ERROR|FATAL|SEVERE|EXCEPTION|Caused by)\\b",
            "\\b[A-Z]{2,}[-_]\\d+\\b",              // 编号类：AML-001 / KB-OFFICIAL-TEST-001
            "\\b[0-9a-fA-F]{16,}\\b",               // 哈希 / 十六进制 ID
            "\\b\\d+(?:\\.\\d+)?\\s*(?:USD|CNY|EUR|万元|亿元)\\b",
            "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b"
    );

    /**
     * 最高优先级保护：真正的故障线索。
     * 当普通保护规则覆盖过广时降级到只用这一组——见 {@link #isProtected(String, boolean)}。
     */
    private static final Pattern CRITICAL =
            Pattern.compile("\\b(ERROR|FATAL|SEVERE|EXCEPTION|Caused by|FAILED|panic)\\b");

    private final int maxTokens;
    private final List<Pattern> mustKeep;
    private final int headLines;
    private final int tailLines;
    private final int maxArrayItems;
    private final double maxProtectedRatio;
    private final TokenCounter tokenCounter;

    private PressPolicy(Builder builder) {
        this.maxTokens = builder.maxTokens;
        this.mustKeep = List.copyOf(builder.mustKeep);
        this.headLines = builder.headLines;
        this.tailLines = builder.tailLines;
        this.maxArrayItems = builder.maxArrayItems;
        this.maxProtectedRatio = builder.maxProtectedRatio;
        this.tokenCounter = builder.tokenCounter;
    }

    /**
     * 本次压缩使用的 token 计数器。预算、报告里的 token 数都由它算出来——
     * 换一个计数器，同一份内容的数字会变，但"输出 ≤ 预算"这条契约不变。
     */
    public TokenCounter tokenCounter() {
        return tokenCounter;
    }

    /** 保护占比上限：超过它说明规则失去区分度，应降级为只保护故障线索 */
    public double maxProtectedRatio() {
        return maxProtectedRatio;
    }

    public int maxTokens() {
        return maxTokens;
    }

    public List<Pattern> mustKeep() {
        return mustKeep;
    }

    public int headLines() {
        return headLines;
    }

    public int tailLines() {
        return tailLines;
    }

    public int maxArrayItems() {
        return maxArrayItems;
    }

    /** 该行是否命中普通保护规则（命中即不参与裁剪） */
    public boolean isProtected(String line) {
        return isProtected(line, false);
    }

    /**
     * @param criticalOnly 为 true 时只用"故障线索"这一组规则。
     *                     用于保护规则覆盖过广、失去区分度时的降级。
     */
    public boolean isProtected(String line, boolean criticalOnly) {
        if (line == null || line.isEmpty()) {
            return false;
        }
        if (criticalOnly) {
            return CRITICAL.matcher(line).find();
        }
        for (Pattern pattern : mustKeep) {
            if (pattern.matcher(line).find()) {
                return true;
            }
        }
        return false;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 默认策略：8000 token 上限。
     *
     * <p><b>口径提醒</b>：这条便捷路径用的是**启发式估算**（见
     * {@link TokenEstimator}），不是可计费 token。要把预算当硬约束用，请走
     * {@link #hardBudget(int, TokenCounter)} 显式指定计数器——两种口径下
     * 同一份内容的数字能差 60%。
     */
    public static PressPolicy defaults() {
        return builder().build();
    }

    /**
     * 把预算当**硬约束**时的构建入口：计数器必须显式给出。
     *
     * <p>存在这条入口的原因很具体：{@code maxTokens(8000)} 里的 8000 到底是
     * 8000 个启发式估算单位，还是 8000 个可计费 token，只有调用方知道。
     * 沿用默认估算器时，"塞进 8000" 实际可能在模型侧花掉 11000——
     * 而这件事在程序里**不会以任何形式报错**，只会在账单或上下文溢出时暴露。
     *
     * <p>显式写一次计数器，就把"我知道这里用的是哪种口径"变成了代码事实。
     *
     * @param maxTokens 预算上限
     * @param counter   实际用于计数的实现，例如
     *                  {@code new JtokkitTokenCounter(Vocabulary.O200K_BASE)}
     *                  或 {@code TokenEstimator.withSafetyMargin(TokenEstimator.BENCHMARK_ESTIMATE_GAP)}
     */
    public static Builder hardBudget(int maxTokens, TokenCounter counter) {
        Objects.requireNonNull(counter, "预算为硬约束时必须显式指定 token 计数器：否则 8000 这个数字的口径无从得知");
        return builder().tokenCounter(counter).maxTokens(maxTokens);
    }

    public static final class Builder {

        // 注意：不调用 tokenCounter(...) 时用的是启发式估算（偏乐观）。
        // 预算需要硬约束时请从 PressPolicy.hardBudget(...) 进入。
        private int maxTokens = 8_000;
        private List<Pattern> mustKeep = DEFAULT_MUST_KEEP.stream().map(Pattern::compile).toList();
        private int headLines = 40;
        private int tailLines = 20;
        private int maxArrayItems = Integer.MAX_VALUE;
        private double maxProtectedRatio = 0.6;
        private TokenCounter tokenCounter = TokenEstimator.defaultCounter();

        /**
         * 换用别的 token 计数器（例如真实 BPE 词表）。
         *
         * <p>不指定时用启发式估算：CJK 码点 1 token、其余 4 字符 1 token。
         */
        public Builder tokenCounter(TokenCounter tokenCounter) {
            this.tokenCounter = Objects.requireNonNull(tokenCounter, "tokenCounter");
            return this;
        }

        /**
         * 保护占比上限（0..1，默认 0.6）。
         *
         * <p>设这个闸门的原因来自一次真实失败：某日志里每行都含交易编号与金额，
         * 于是"保护规则"命中了全部 4000 行——**保护了全部等于保护不了**，压缩率归零。
         * 超过该比例时自动降级为只保护故障线索（ERROR/EXCEPTION 等），并在报告中说明。
         */
        public Builder maxProtectedRatio(double maxProtectedRatio) {
            if (maxProtectedRatio <= 0 || maxProtectedRatio > 1) {
                throw new IllegalArgumentException("maxProtectedRatio 必须在 (0,1]：" + maxProtectedRatio);
            }
            this.maxProtectedRatio = maxProtectedRatio;
            return this;
        }

        public Builder maxTokens(int maxTokens) {
            if (maxTokens < 64) {
                throw new IllegalArgumentException("maxTokens 至少为 64，否则任何内容都放不下：" + maxTokens);
            }
            this.maxTokens = maxTokens;
            return this;
        }

        /** 追加自定义保护正则（与默认保护规则合并） */
        public Builder mustKeep(String regex) {
            Objects.requireNonNull(regex, "regex");
            Pattern compiled = Pattern.compile(regex);
            this.mustKeep = java.util.stream.Stream.concat(this.mustKeep.stream(), java.util.stream.Stream.of(compiled)).toList();
            return this;
        }

        /** 覆盖默认保护规则 */
        public Builder mustKeepOnly(List<String> regexes) {
            this.mustKeep = regexes.stream().map(Pattern::compile).toList();
            return this;
        }

        public Builder headLines(int headLines) {
            this.headLines = Math.max(0, headLines);
            return this;
        }

        public Builder tailLines(int tailLines) {
            this.tailLines = Math.max(0, tailLines);
            return this;
        }

        /**
         * 数组采样的**上限**，默认 {@link Integer#MAX_VALUE}（不额外收紧）。
         *
         * <p>这是"用户可以收紧、但默认不收紧"的那类旋钮：
         * 采样条数本身是**按预算反推**的（预算够就多留几条，见
         * {@code JsonCompressor#chooseArrayLimit}），这里只是给它加一个天花板。
         *
         * <p>**这个上限此前是个空选项。** 字段存了、getter 也导出了，
         * 但没有任何生产代码读它——`.maxArrayItems(50)` 静默无效。
         * 而默认值当时是 `8`，所以"顺手把它接上"会立刻退回另一个更糟的状态：
         * 每个数组都被固定截到 8 条，预算再宽也留不住——
         * 那正是 README 里记为 P0 的「采样上限固定从 8 起步」。
         * 接上它时把默认值一并改成不收紧，两件事必须同时做。
         */
        public Builder maxArrayItems(int maxArrayItems) {
            this.maxArrayItems = Math.max(1, maxArrayItems);
            return this;
        }

        public PressPolicy build() {
            return new PressPolicy(this);
        }
    }
}
