package io.github.xiaoxusop.ctxpress.tokenizer.jtokkit;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import io.github.xiaoxusop.ctxpress.TokenCounter;

import java.util.Locale;

/**
 * 用真实 BPE 词表计数。
 *
 * <p>核心模块默认的 {@link io.github.xiaoxusop.ctxpress.TokenEstimator} 是启发式估算
 * （CJK 码点 1 token、其余 4 字符 1 token），它对 JSON / 代码这类标点密集的文本**偏乐观**
 * ——真实 BPE 大约 3.2–3.7 字符/token。于是"塞进 8000 个 token"在启发式口径下算出来是 8000，
 * 实际计费可能是 9500。换上这个实现之后，预算才是可计费 token 意义上的预算。
 *
 * <p>词表选择：同一条内容在不同词表下的 token 数不同，所以词表必须显式选，
 * 且名字会写进报告——不然"这个数字是按哪个词表算的"根本无从判断。
 */
public final class JtokkitTokenCounter implements TokenCounter {

    /** 懒加载：jtokkit 会把词表打进 jar，一次性全加载会拖慢启动 */
    private static final EncodingRegistry REGISTRY = Encodings.newLazyEncodingRegistry();

    /** 支持的词表。名字与各家 API 的官方叫法一致，便于对照账单 */
    public enum Vocabulary {
        /** GPT-4 / GPT-3.5-turbo */
        CL100K_BASE(EncodingType.CL100K_BASE),
        /** GPT-4o 及之后 */
        O200K_BASE(EncodingType.O200K_BASE),
        /** GPT-3 / 早期 Codex */
        R50K_BASE(EncodingType.R50K_BASE),
        /** Codex / 代码补全 */
        P50K_BASE(EncodingType.P50K_BASE);

        private final EncodingType type;

        Vocabulary(EncodingType type) {
            this.type = type;
        }
    }

    private final Vocabulary vocabulary;
    private final Encoding encoding;

    public JtokkitTokenCounter(Vocabulary vocabulary) {
        this.vocabulary = vocabulary;
        this.encoding = REGISTRY.getEncoding(vocabulary.type);
    }

    /** 默认用 {@code o200k_base}——较新的模型词表，也是当前的主流 */
    public static JtokkitTokenCounter ofDefaults() {
        return new JtokkitTokenCounter(Vocabulary.O200K_BASE);
    }

    /**
     * 按名字构造。名字大小写不敏感，如 {@code "o200k_base"}、{@code "cl100k_base"}。
     *
     * @throws IllegalArgumentException 名字不认识时——**不静默退回默认词表**，
     *         免得报告里的数字与实际词表对不上
     */
    public static JtokkitTokenCounter of(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("词表名不能为空");
        }
        try {
            return new JtokkitTokenCounter(Vocabulary.valueOf(name.strip().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            StringBuilder known = new StringBuilder();
            for (Vocabulary value : Vocabulary.values()) {
                known.append(known.isEmpty() ? "" : " / ").append(value.name().toLowerCase(Locale.ROOT));
            }
            throw new IllegalArgumentException("未知词表 '" + name + "'（可用：" + known + "）");
        }
    }

    /** 全部可用词表名，供 CLI 报错时列出 */
    public static String availableNames() {
        StringBuilder sb = new StringBuilder();
        for (Vocabulary value : Vocabulary.values()) {
            sb.append(sb.isEmpty() ? "" : " / ").append(value.name().toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    @Override
    public int count(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        // countTokensOrdinary 把整串都当普通文本——工具输出里可能字面包含
        // "<|endoftext|>" 之类的串，按特殊 token 解析会抛异常，而我们要的只是计数。
        return encoding.countTokensOrdinary(text);
    }

    @Override
    public String name() {
        return vocabulary.name().toLowerCase(Locale.ROOT);
    }
}
