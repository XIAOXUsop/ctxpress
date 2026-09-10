package io.github.xiaoxusop.ctxpress;

import io.github.xiaoxusop.ctxpress.compressor.JsonCompressor;
import io.github.xiaoxusop.ctxpress.compressor.LogCompressor;
import io.github.xiaoxusop.ctxpress.compressor.TextCompressor;

import java.util.EnumMap;
import java.util.Map;

/**
 * 上下文压缩入口。
 *
 * <pre>{@code
 * ContextPress press = ContextPress.withDefaults();
 * PressResult result = press.press(toolOutput);          // 自动判定体裁
 * System.out.println(result.report().summary());         // 审计报告
 * context.add(result.content());
 * }</pre>
 *
 * <p>典型接入点：Agent 每轮把工具返回值 / 命令输出 / 检索片段放进上下文之前。
 * 本类<b>不</b>负责"什么时候压"——那是调用方的策略；它只负责"给定内容与预算，压得正确且可解释"。
 *
 * <p>线程安全：实例不可变，可复用。
 */
public final class ContextPress {

    private final PressPolicy policy;
    private final Map<ContextKind, Compressor> compressors;

    public ContextPress(PressPolicy policy) {
        this.policy = policy;
        Map<ContextKind, Compressor> map = new EnumMap<>(ContextKind.class);
        map.put(ContextKind.JSON, new JsonCompressor());
        map.put(ContextKind.LOG, new LogCompressor());
        map.put(ContextKind.TEXT, new TextCompressor());
        this.compressors = Map.copyOf(map);
    }

    public static ContextPress withDefaults() {
        return new ContextPress(PressPolicy.defaults());
    }

    public static ContextPress with(PressPolicy policy) {
        return new ContextPress(policy);
    }

    public PressPolicy policy() {
        return policy;
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
            return PressResult.unchanged(content, kind, TokenEstimator.estimate(content));
        }
        return compressor.compress(normalizeLineEndings(content), policy);
    }

    /**
     * 归一化换行符。
     *
     * <p>必要性来自一次真实失败：Windows 日志是 CRLF，而 Java 正则里 {@code .} **不匹配 {@code \r}**，
     * 于是按行判定的正则（如 {@code ^\d{4}-\d{2}-\d{2}.*}）全部失配，体裁判定与按行保护一起失效。
     * 在入口处统一成 LF，是让"在 Windows 上采集的日志"和"在 Linux 上采集的日志"行为一致的最小代价。
     */
    private static String normalizeLineEndings(String content) {
        return content.indexOf('\r') < 0 ? content : content.replace("\r\n", "\n").replace('\r', '\n');
    }
}
