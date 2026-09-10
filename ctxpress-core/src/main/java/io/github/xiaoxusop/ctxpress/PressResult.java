package io.github.xiaoxusop.ctxpress;

import java.util.List;

/**
 * 压缩结果：压缩后的内容 + 审计报告 + 归档引用。
 *
 * <p>{@code content} 永远是一段**自洽**的内容——JSON 仍是合法 JSON，
 * 日志仍按行完整，文本仍是完整句子。压缩只改变"包含多少"，不改变"结构是否合法"。
 *
 * @param archiveRef 归档引用；非 null 表示原文已存入 {@link ContextArchive}，
 *                   可用 {@link ContextPress#retrieve(String)} 按需取回。
 *                   引用同时被嵌进内容的省略标记里，因此**读到这段话的模型也知道怎么取回**。
 */
public record PressResult(String content, PressReport report, String archiveRef) {

    /** 未启用归档时的构造（保持兼容） */
    public PressResult(String content, PressReport report) {
        this(content, report, null);
    }

    /** 未发生压缩时原样返回的快捷构造 */
    public static PressResult unchanged(String content, ContextKind kind, int tokens) {
        return new PressResult(content,
                new PressReport(kind, tokens, tokens, 0.0, 0, List.of("NO_OP")), null);
    }

    /** 是否可用归档取回原文 */
    public boolean reversible() {
        return archiveRef != null;
    }
}
