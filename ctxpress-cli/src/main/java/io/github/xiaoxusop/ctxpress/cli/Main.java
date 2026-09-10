package io.github.xiaoxusop.ctxpress.cli;

import io.github.xiaoxusop.ctxpress.ContextKind;
import io.github.xiaoxusop.ctxpress.ContextPress;
import io.github.xiaoxusop.ctxpress.PressPolicy;
import io.github.xiaoxusop.ctxpress.PressResult;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * ctxpress 命令行。
 *
 * <p>设计成**管道友好**的：压缩结果走 stdout，审计报告走 stderr。
 * 因此下面这样用是安全的——重定向不会把报告混进内容里：
 *
 * <pre>
 * cat huge-tool-output.json | ctxpress compress --max-tokens 2000 &gt; small.json
 * ctxpress analyze app.log          # 只看能省多少，不产出内容
 * </pre>
 *
 * <p>退出码：0 成功；1 用法错误；2 读取失败。可安全用于 CI。
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        // 必须显式固定输出编码：默认走平台编码（中文 Windows 是 GBK），
        // 会让压缩结果在 UTF-8 管道/文件里变成乱码——实测中确实出现了非法 UTF-8 字节。
        PrintStream utf8Out = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out),
                true, StandardCharsets.UTF_8);
        PrintStream utf8Err = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.err),
                true, StandardCharsets.UTF_8);
        System.exit(run(args, System.in, utf8Out, utf8Err));
    }

    /** 与 {@link #main} 分离，便于测试（不调用 System.exit） */
    static int run(String[] args, InputStream in, PrintStream out, PrintStream err) {
        if (args.length == 0 || "-h".equals(args[0]) || "--help".equals(args[0])) {
            printUsage(err);
            return args.length == 0 ? 1 : 0;
        }

        String command = args[0];
        Options options = new Options();
        Path file = null;
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--max-tokens" -> options.maxTokens = parseInt(args, ++i, err, options);
                case "--kind" -> options.kind = parseKind(args, ++i, err, options);
                case "--must-keep" -> options.mustKeep = args[++i];
                case "--head" -> options.headLines = parseInt(args, ++i, err, options);
                case "--tail" -> options.tailLines = parseInt(args, ++i, err, options);
                default -> file = Path.of(arg);
            }
            if (options.invalid) {
                return 1;
            }
        }

        String content;
        try {
            content = file == null ? new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    : Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            err.println("ctxpress: 读取失败: " + e.getMessage());
            return 2;
        }

        PressPolicy.Builder builder = PressPolicy.builder()
                .maxTokens(options.maxTokens)
                .headLines(options.headLines)
                .tailLines(options.tailLines);
        if (options.mustKeep != null) {
            builder.mustKeep(options.mustKeep);
        }
        ContextPress press = ContextPress.with(builder.build());

        PressResult result = options.kind == null ? press.press(content) : press.press(content, options.kind);

        if ("analyze".equals(command)) {
            out.println(result.report().summary());
            return 0;
        }
        if (!"compress".equals(command)) {
            err.println("ctxpress: 未知命令 '" + command + "'（可用：compress / analyze）");
            return 1;
        }

        out.println(result.content());
        err.println(result.report().summary());
        return 0;
    }

    private static int parseInt(String[] args, int index, PrintStream err, Options options) {
        try {
            return Integer.parseInt(args[index]);
        } catch (RuntimeException e) {
            err.println("ctxpress: 需要整数参数，收到 '" + args[index] + "'");
            options.invalid = true;
            return 0;
        }
    }

    private static ContextKind parseKind(String[] args, int index, PrintStream err, Options options) {
        try {
            return ContextKind.valueOf(args[index].toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException e) {
            err.println("ctxpress: 未知体裁 '" + args[index] + "'（可用：JSON / LOG / TEXT）");
            options.invalid = true;
            return null;
        }
    }

    private static void printUsage(PrintStream err) {
        err.println("""
                ctxpress - Agent 上下文压缩

                用法:
                  ctxpress compress [选项] [文件]    压缩并输出到 stdout（报告到 stderr）
                  ctxpress analyze  [选项] [文件]    只输出压缩效果报告

                选项:
                  --max-tokens N     压缩目标上限（默认 8000）
                  --kind K           JSON | LOG | TEXT（默认自动判定）
                  --must-keep REGEX  追加保护正则；命中内容不参与裁剪
                  --head N           保留头部行数（默认 40）
                  --tail N           保留尾部行数（默认 20）

                不指定文件时从 stdin 读取。全离线，不调用任何模型。
                """);
    }

    private static final class Options {
        int maxTokens = 8_000;
        int headLines = 40;
        int tailLines = 20;
        ContextKind kind;
        String mustKeep;
        boolean invalid;
    }
}
