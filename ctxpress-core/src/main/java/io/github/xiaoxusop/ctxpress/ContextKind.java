package io.github.xiaoxusop.ctxpress;

import java.util.regex.Pattern;

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

    private static final String TIMESTAMP = "\\d{4}-\\d{2}-\\d{2}|\\d{2}:\\d{2}:\\d{2}";
    private static final String LEVEL = "TRACE|DEBUG|INFO|WARN|WARNUNG|ERROR|FATAL|SEVERE";

    /**
     * 日志行的特征。允许行首被一层方括号或圆括号包住——
     * logback / log4j2 的默认 ConsoleAppender、Maven 与 Gradle 的输出都是这个形态。
     *
     * <p>构建输出单列几条：它们没有时间戳也没有标准级别前缀，但同样是 Agent 高频抓取的
     * 工具输出，且行数多、重复度高，正是压缩收益最大的形态。
     */
    private static final Pattern LOG_LINE = Pattern.compile(
            "^(?:" + TIMESTAMP + ").*"                                       // 2026-09-11 …
            + "|^(?:" + LEVEL + ")\\b.*"                                     // INFO …
            + "|^\\[(?:" + LEVEL + ")\\]\\s.*"                               // [INFO] …
            + "|^\\[(?:" + TIMESTAMP + ")[^\\]]*\\]\\s*.*"                   // [2026-09-11 10:00:00] …
            + "|^\\((?:" + TIMESTAMP + ")[^)]*\\)\\s*.*"                     // (2026-09-11 …)
            + "|^\\d{1,6}\\s*[:.)\\]]\\s*\\[?(?:" + LEVEL + ")\\b.*"          // 12: [ERROR] …
            + "|^(?:> Task .*|FAILURE:.*|BUILD FAILED|BUILD SUCCESSFUL|\\* What went wrong)"  // Gradle
            + "|^npm (?:warn|error|notice) \\S.*"                            // npm
            + "|^\\s*\\d+%\\].*");                                           // CMake 构建进度

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
        String normalized = stripBom(content.indexOf('\r') < 0 ? content
                : content.replace("\r\n", "\n").replace('\r', '\n'));
        String trimmed = normalized.stripLeading();
        char first = trimmed.charAt(0);

        // 先做一次日志排除，再看首字符。两个开头符号都**有歧义**：
        //   - `[` 后面可能是 JSON 数组，也可能是 `[INFO] …` / `[2026-09-11 10:00:00] …`
        //     这类方括号前缀的日志（logback / log4j2 默认格式、Maven 与 Gradle 输出，
        //     Agent 最高频的工具输出之一）。早先在这里直接判 JSON，于是它们一路
        //     解析失败 → 退回文本压缩 → 压缩率恒为 0%。
        //   - `{` 看着无歧义，但"被截断的 JSON"与"JSON 头 + 日志正文"这类混合内容现实存在，
        //     而且日志正文往往占绝大部分。
        // 日志特征是"扫一遍行首就能确认"的，成本远低于判错体裁的代价，所以先排除它。
        if (looksLikeLog(normalized)) {
            return LOG;
        }
        return (first == '{' || first == '[') ? JSON : TEXT;
    }

    /**
     * 剥掉 UTF-8 BOM。
     *
     * <p>{@code String.stripLeading()} 走的是 {@code Character.isWhitespace}，
     * 而 U+FEFF 不算空白——于是一份带 BOM 的 JSON 会以"首字符是零宽不换行空格"的身份
     * 一路落到文本压缩器，结构压缩完全用不上。Windows 上导出的文件带 BOM 很常见。
     */
    private static final int BOM = 0xFEFF;

    static String stripBom(String content) {
        return !content.isEmpty() && content.charAt(0) == BOM ? content.substring(1) : content;
    }

    /**
     * 日志特征：多行 + 行首时间戳/级别标记的占比达到阈值。
     *
     * <p>采样是**等距**的（头、中、尾各取一段）而不是"只看前 50 行"。
     * 只扫开头会退化：一份"前面是 JSON 头、后面 99% 是日志"的工具输出，
     * 前 50 行一条都不命中，于是被判成 JSON、解析失败、最终走文本压缩——
     * 明明日志压缩器能压掉 60% 以上。
     */
    private static boolean looksLikeLog(String content) {
        int lineCount = countLines(content);
        if (lineCount < 3) {
            return false;
        }
        final int sample = Math.min(150, lineCount);
        int sampled = 0;
        int logLike = 0;
        int nextSample = 0;
        int index = 0;
        int start = 0;
        int length = content.length();
        for (int i = 0; i <= length; i++) {
            if (i != length && content.charAt(i) != '\n') {
                continue;
            }
            if (nextSample < sample && index == samplePosition(nextSample, lineCount, sample)) {
                String line = content.substring(start, i);
                nextSample++;
                if (!line.isBlank()) {
                    sampled++;
                    if (LOG_LINE.matcher(line.stripLeading()).matches()) {
                        logLike++;
                    }
                }
            }
            index++;
            start = i + 1;
        }
        return sampled > 0 && logLike * 100 / sampled >= 40;
    }

    /** 只数行数，不建数组——后面按序号取行，用不着把整份内容切开 */
    private static int countLines(String content) {
        int count = 1;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }

    /** 第 k 个采样点落在第几行：等距，覆盖头、中、尾 */
    private static int samplePosition(int k, int lineCount, int sample) {
        return sample == 1 ? 0 : (int) ((long) k * (lineCount - 1) / (sample - 1));
    }
}
