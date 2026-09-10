package io.github.xiaoxusop.ctxpress;

/**
 * 上下文体裁。压缩策略按体裁分派——**字符串截断对任何体裁都不适用**：
 * 把 JSON 截成一半会得到非法 JSON，把日志按字数切开会把异常栈撕碎。
 */
public enum ContextKind {

    /** 结构化 JSON（工具返回值、API 响应、配置） */
    JSON,

    /** 日志（应用日志、命令输出、构建输出） */
    LOG,

    /** 自由文本（RAG 片段、文档、模型输出） */
    TEXT;

    /**
     * 按内容自动判定体裁。
     *
     * <p>判定只做廉价的形态检查，不做语义推断——猜错体裁的代价是压缩效果变差，
     * 而不是内容损坏（每种压缩器都保证输出仍是合法内容）。
     */
    public static ContextKind detect(String content) {
        if (content == null || content.isBlank()) {
            return TEXT;
        }
        // 换行符归一化：Windows 采的日志是 CRLF，而 Java 正则里 `.` 不匹配 `\r`，
        // 会让按行判定整体失配。detect 是公开 API，必须自己扛住，不能依赖调用方先处理。
        String normalized = content.indexOf('\r') < 0 ? content
                : content.replace("\r\n", "\n").replace('\r', '\n');
        String trimmed = normalized.stripLeading();
        char first = trimmed.charAt(0);
        if (first == '{' || first == '[') {
            return JSON;
        }
        return looksLikeLog(normalized) ? LOG : TEXT;
    }

    /** 日志特征：多行 + 行首时间戳/级别标记的占比达到阈值 */
    private static boolean looksLikeLog(String content) {
        String[] lines = content.split("\n", -1);
        if (lines.length < 3) {
            return false;
        }
        int sampled = 0;
        int logLike = 0;
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            sampled++;
            String s = line.stripLeading();
            if (s.matches("^(\\d{4}-\\d{2}-\\d{2}|\\d{2}:\\d{2}:\\d{2}).*")
                    || s.matches("^(TRACE|DEBUG|INFO|WARN|WARNUNG|ERROR|FATAL|SEVERE)\\b.*")
                    || s.matches("^\\[?(TRACE|DEBUG|INFO|WARN|ERROR|FATAL)\\]?\\s.*")) {
                logLike++;
            }
            if (sampled >= 50) {
                break;
            }
        }
        return sampled > 0 && logLike * 100 / sampled >= 40;
    }
}
