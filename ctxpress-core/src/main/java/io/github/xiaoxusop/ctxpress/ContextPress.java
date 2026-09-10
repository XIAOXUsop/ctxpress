package io.github.xiaoxusop.ctxpress;

import io.github.xiaoxusop.ctxpress.compressor.JsonCompressor;
import io.github.xiaoxusop.ctxpress.compressor.LogCompressor;
import io.github.xiaoxusop.ctxpress.compressor.TextCompressor;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * 上下文压缩入口。
 *
 * <pre>{@code
 * // 只压缩（不可逆）
 * ContextPress press = ContextPress.withDefaults();
 *
 * // 可逆压缩：被压掉的原文进归档，可按需取回
 * ContextPress press = ContextPress.withArchive(PressPolicy.defaults());
 *
 * PressResult result = press.press(toolOutput);
 * context.add(result.content());
 * log.info(result.report().summary());
 *
 * // 后续需要时取回原文
 * press.retrieve(result.archiveRef()).ifPresent(context::add);
 * }</pre>
 *
 * <p><b>为什么提供可逆模式</b>：压缩的本质是"赌这段内容用不上"。赌对了省 token，
 * 赌错了模型就永远拿不到那一行——而 Agent 恰恰经常在后续步骤里需要前面被压掉的细节。
 * 归档引用会被嵌进内容的省略标记里，所以**读到压缩内容的模型自己就知道怎么取回**。
 *
 * <p>本类<b>不</b>负责"什么时候压"——那是调用方的策略；它只负责
 * "给定内容与预算，压得正确、可解释、可取回"。线程安全：实例不可变（归档本身线程安全）。
 */
public final class ContextPress {

    private final PressPolicy policy;
    private final Map<ContextKind, Compressor> compressors;
    private final ContextArchive archive;

    public ContextPress(PressPolicy policy) {
        this(policy, null);
    }

    public ContextPress(PressPolicy policy, ContextArchive archive) {
        this.policy = policy;
        this.archive = archive;
        Map<ContextKind, Compressor> map = new EnumMap<>(ContextKind.class);
        map.put(ContextKind.JSON, new JsonCompressor());
        map.put(ContextKind.LOG, new LogCompressor());
        map.put(ContextKind.TEXT, new TextCompressor());
        this.compressors = Map.copyOf(map);
    }

    /** 默认策略，不可逆 */
    public static ContextPress withDefaults() {
        return new ContextPress(PressPolicy.defaults());
    }

    public static ContextPress with(PressPolicy policy) {
        return new ContextPress(policy);
    }

    /** 默认策略 + 可逆归档 */
    public static ContextPress withArchive() {
        return new ContextPress(PressPolicy.defaults(), new ContextArchive());
    }

    public static ContextPress withArchive(PressPolicy policy) {
        return new ContextPress(policy, new ContextArchive());
    }

    public static ContextPress withArchive(PressPolicy policy, ContextArchive archive) {
        return new ContextPress(policy, archive);
    }

    public PressPolicy policy() {
        return policy;
    }

    /** 是否启用了可逆归档 */
    public boolean reversible() {
        return archive != null;
    }

    /** 取回被压缩掉的原文；未启用归档或引用已失效时返回空 */
    public Optional<String> retrieve(String archiveRef) {
        return archive == null ? Optional.empty() : archive.retrieve(archiveRef);
    }

    /** 自动判定体裁后压缩 */
    public PressResult press(String content) {
        return press(content, ContextKind.detect(content));
    }

    /** 指定体裁压缩（用于调用方已知内容类型的场景） */
    public PressResult press(String content, ContextKind kind) {
        if (content == null || content.isEmpty()) {
            return PressResult.unchanged(content == null ? "" : content, kind, 0);
        }
        Compressor compressor = compressors.get(kind);
        if (compressor == null) {
            return PressResult.unchanged(content, kind, policy.tokenCounter().count(content));
        }
        String normalized = normalize(content);

        // 已经放得下就不动它。
        // 这条判断是基准测试逼出来的：早先版本无论预算多大都按压缩器的结构默认值裁剪，
        // 于是一份 8528 token 的内容在 20000 的预算下照样被压到 515 —— 内容明明放得下，
        // 却付了信息损失的代价。「能装下就别动」是压缩器最不该违背的契约。
        int tokens = policy.tokenCounter().count(normalized);
        if (tokens <= policy.maxTokens()) {
            return PressResult.unchanged(normalized, kind, tokens);
        }

        // 归档引用要在压缩前算出来（压缩器需要把它写进省略标记），
        // 若最终没发生压缩则丢弃，避免留下无用条目。
        String ref = archive == null ? null : archive.store(normalized);
        PressResult result = compressor.compress(normalized, policy, ref);
        if (ref != null && !result.reversible()) {
            archive.discard(ref);
        }
        return result;
    }

    /**
     * 入口归一化：换行符统一为 LF，并剥掉 UTF-8 BOM。
     *
     * <p>换行符的必要性来自一次真实失败：Windows 日志是 CRLF，而 Java 正则里
     * {@code .} **不匹配 {@code \r}**，于是按行判定的正则（如 {@code ^\d{4}-\d{2}-\d{2}.*}）
     * 全部失配，体裁判定与按行保护一起失效。在入口处统一成 LF，是让"在 Windows 上采集的日志"
     * 和"在 Linux 上采集的日志"行为一致的最小代价。
     *
     * <p>BOM 同理：{@code String.stripLeading()} 不认为 U+FEFF 是空白，
     * 带 BOM 的 JSON 会以"首字符是 ﻿"的身份一路落到文本压缩器，结构压缩完全用不上。
     * 放在入口一处，体裁判定与三个压缩器一起受益。
     */
    private static String normalize(String content) {
        String result = content.indexOf('\r') < 0 ? content
                : content.replace("\r\n", "\n").replace('\r', '\n');
        return ContextKind.stripBom(result);
    }
}
