package io.github.xiaoxusop.ctxpress;

/**
 * 压缩结果：压缩后的内容 + 审计报告。
 *
 * <p>{@code content} 永远是一段**自洽**的内容——JSON 仍是合法 JSON，
 * 日志仍按行完整，文本仍是完整句子。压缩只改变"包含多少"，不改变"结构是否合法"。
 */
public record PressResult(String content, PressReport report) {

    /** 未发生压缩时原样返回的快捷构造 */
    public static PressResult unchanged(String content, ContextKind kind, int tokens) {
        return new PressResult(content,
                new PressReport(kind, tokens, tokens, 0.0, 0, java.util.List.of("NO_OP")));
    }
}
