package io.github.xiaoxusop.ctxpress.cli;

import io.github.xiaoxusop.ctxpress.ContextArchive;
import io.github.xiaoxusop.ctxpress.ContextKind;
import io.github.xiaoxusop.ctxpress.ContextPress;
import io.github.xiaoxusop.ctxpress.FileContextArchive;
import io.github.xiaoxusop.ctxpress.PressPolicy;
import io.github.xiaoxusop.ctxpress.PressResult;
import io.github.xiaoxusop.ctxpress.tokenizer.jtokkit.JtokkitTokenCounter;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

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
 * <p>退出码：0 成功；1 用法错误；2 读取失败；3 内部错误。可安全用于 CI。
 */
public final class Main {

    private static final int EXIT_USAGE = 1;
    private static final int EXIT_READ_FAILED = 2;
    private static final int EXIT_INTERNAL = 3;
    /** 取回时引用不存在（或归档被清理过）——与"用法错"区分开，脚本才能分别处理 */
    private static final int EXIT_REF_NOT_FOUND = 4;

    /** 退回核心模块的启发式估算（CJK 1 token/字，其余 4 字符/token） */
    private static final String HEURISTIC_TOKENIZER = "heuristic";

    private Main() {
    }

    public static void main(String[] args) {
        // 必须显式固定输出编码：默认走平台编码（中文 Windows 是 GBK），
        // 会让压缩结果在 UTF-8 管道/文件里变成乱码——实测中确实出现了非法 UTF-8 字节。
        PrintStream utf8Out = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out),
                true, StandardCharsets.UTF_8);
        PrintStream utf8Err = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.err),
                true, StandardCharsets.UTF_8);
        int code;
        try {
            code = run(args, System.in, utf8Out, utf8Err);
        } catch (Throwable t) {
            // 异常逃出 main 会由 JVM 默认处理器写到**平台编码**的 System.err 上——
            // 于是上面那个 utf8Err 只挡住了正常路径，"控制台中文乱码"在异常路径上依然存在
            // （实测 `--max-tokens 0` 输出 `maxTokens ����Ϊ 64`）。这里统一收口。
            utf8Err.println("ctxpress: 内部错误: " + t);
            code = EXIT_INTERNAL;
        }
        System.exit(code);
    }

    /** 与 {@link #main} 分离，便于测试（不调用 System.exit） */
    static int run(String[] args, InputStream in, PrintStream out, PrintStream err) {
        if (args.length == 0 || "-h".equals(args[0]) || "--help".equals(args[0])) {
            printUsage(err);
            return args.length == 0 ? EXIT_USAGE : 0;
        }

        String command = args[0];
        // 命令先校验：早先未知命令会被当成文件名，报"读取失败"（rc=2）而不是用法错误（rc=1）
        if (!"compress".equals(command) && !"analyze".equals(command) && !"retrieve".equals(command)) {
            err.println("ctxpress: 未知命令 '" + command + "'（可用：compress / analyze / retrieve）");
            return EXIT_USAGE;
        }

        Options options = new Options();
        Path file = null;
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--max-tokens" -> {
                    Integer value = intArg(args, i + 1, err, arg);
                    if (value == null) {
                        return EXIT_USAGE;
                    }
                    options.maxTokens = value;
                    i++;
                }
                case "--kind" -> {
                    String value = nextArg(args, i + 1, err, arg);
                    if (value == null) {
                        return EXIT_USAGE;
                    }
                    try {
                        options.kind = ContextKind.valueOf(value.toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        err.println("ctxpress: 未知体裁 '" + value + "'（可用：JSON / LOG / TEXT）");
                        return EXIT_USAGE;
                    }
                    i++;
                }
                case "--must-keep" -> {
                    String value = nextArg(args, i + 1, err, arg);
                    if (value == null) {
                        return EXIT_USAGE;
                    }
                    options.mustKeep = value;
                    i++;
                }
                case "--tokenizer" -> {
                    String value = nextArg(args, i + 1, err, arg);
                    if (value == null) {
                        return EXIT_USAGE;
                    }
                    options.tokenizer = value;
                    i++;
                }
                case "--archive" -> {
                    String value = nextArg(args, i + 1, err, arg);
                    if (value == null) {
                        return EXIT_USAGE;
                    }
                    options.archivePath = Path.of(value);
                    i++;
                }
                case "--ref" -> {
                    String value = nextArg(args, i + 1, err, arg);
                    if (value == null) {
                        return EXIT_USAGE;
                    }
                    options.ref = value;
                    i++;
                }
                case "--head" -> {
                    Integer value = intArg(args, i + 1, err, arg);
                    if (value == null) {
                        return EXIT_USAGE;
                    }
                    options.headLines = value;
                    i++;
                }
                case "--tail" -> {
                    Integer value = intArg(args, i + 1, err, arg);
                    if (value == null) {
                        return EXIT_USAGE;
                    }
                    options.tailLines = value;
                    i++;
                }
                default -> {
                    if (arg.startsWith("-")) {
                        err.println("ctxpress: 未知选项 '" + arg + "'");
                        return EXIT_USAGE;
                    }
                    file = Path.of(arg);
                }
            }
        }

        if ("retrieve".equals(command)) {
            return runRetrieve(options, out, err);
        }

        String content;
        try {
            content = file == null ? new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    : Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            err.println("ctxpress: 读取失败: " + e.getMessage());
            return EXIT_READ_FAILED;
        }

        ContextPress press;
        try {
            PressPolicy.Builder builder = PressPolicy.builder()
                    .maxTokens(options.maxTokens)
                    .headLines(options.headLines)
                    .tailLines(options.tailLines);
            if (options.mustKeep != null) {
                builder.mustKeep(options.mustKeep);
            }
            // 默认走真实词表：命令行用户拿到的是可执行 jar，没有"再加一个依赖"的机会，
            // 而按乐观估算算出来的"预算 8000"实际会撑爆窗口。
            if (!HEURISTIC_TOKENIZER.equals(options.tokenizer)) {
                builder.tokenCounter(JtokkitTokenCounter.of(options.tokenizer));
            }
            press = options.archivePath == null
                    ? ContextPress.with(builder.build())
                    : ContextPress.withArchive(builder.build(), FileContextArchive.open(options.archivePath));
        } catch (IOException e) {
            err.println("ctxpress: 打开归档失败: " + e.getMessage());
            return EXIT_READ_FAILED;
        } catch (RuntimeException e) {
            // 参数越界由策略层抛 IllegalArgumentException，这里转成用法错误而不是内部错误
            err.println("ctxpress: " + e.getMessage());
            return EXIT_USAGE;
        }

        PressResult result = options.kind == null ? press.press(content) : press.press(content, options.kind);
        String report = result.report().summary() + ", tokenizer=" + options.tokenizer;
        if (result.reversible()) {
            // 把引用单独放进报告行，脚本可以直接取走——内容里的省略标记是给人/模型看的，
            // 从文本里正则抠引用既脆弱又容易在标记格式变动时静默失效
            report += ", 归档=" + result.archiveRef();
        }

        if ("analyze".equals(command)) {
            out.print(report);
            out.print('\n');
            return 0;
        }

        // 显式写 LF 而不是 println：println 走 System.lineSeparator()，
        // 在 Windows 上会追加 \r\n，于是同一份输入在不同平台上得到不同的字节，
        // 与"确定性 / 可复现"的承诺冲突（实测 CRLF 归一化后仍会多出 1 个 CRLF）。
        //
        // 而**连 `'\n'` 也不该补**。压缩内容原样写出，一个字节都不加——因为契约里
        // 写着「输出不含原文没有的内容：每个字节要么逐字来自输入，要么属于已声明的
        // 标记文法」。实测补的那一个换行两条都不满足：输入不以换行结尾时它凭空多出来，
        // 输入以换行结尾时它是第二个。前者还会让「内容放得下时不做任何改动」
        // 这句（README「实测」一节）在字节层面不成立。
        //
        // 这条和 retrieve 那个是同一族的：都靠 `out.print('\n')` 让终端好看，
        // 代价是产物与原文不再逐字节相同。CLI 的排版便利不该改写被处理的数据。
        out.print(result.content());
        err.print(report);
        err.print('\n');
        return 0;
    }

    /**
     * 取回被压掉的原文。
     *
     * <p>没有这条路径时，"可逆"只有在写 Java 代码时才用得到——而 README 里整节「可逆」
     * 面向的正是命令行用户：他们拿到的是一个 fat jar，压完就再也拿不回被压掉的部分。
     * 那等于把最核心的差异化能力锁在了 API 里。
     */
    private static int runRetrieve(Options options, PrintStream out, PrintStream err) {
        if (options.archivePath == null) {
            err.println("ctxpress: retrieve 需要 --archive <归档路径>");
            return EXIT_USAGE;
        }
        if (options.ref == null) {
            err.println("ctxpress: retrieve 需要 --ref <引用>");
            return EXIT_USAGE;
        }
        try {
            ContextArchive archive = FileContextArchive.open(options.archivePath);
            java.util.Optional<String> content = archive.retrieve(options.ref);
            if (content.isEmpty()) {
                err.println("ctxpress: 归档里没有 " + options.ref + "（当前共 " + archive.size() + " 条）");
                return EXIT_REF_NOT_FOUND;
            }
            /*
             * 取回的内容**原样写出，一个字节都不加**。
             *
             * 这里曾经无条件 `out.print('\n')`（大约是为了让终端提示符另起一行）。
             * 代价是取回的文件永远比原文多一个换行——而 README 三处承诺
             * 「逐字节一致」，归档引用的 ORIG-xxx 本身就是原文内容的 sha256 前缀，
             * 用户拿它一校验就会发现对不上。
             *
             * 这条 bug 之所以长期隐身，是因为测试里写的是
             * `assertEquals(original, retrieved.out().stripTrailing(), "必须逐字节相同")`
             * ——**断言信息写着「逐字节」，代码却把尾部空白剥掉再比**，
             * 正好把多出来的换行抹平。它还顺带掩盖了更糟的情况：
             * 原文末尾有多个换行时，少写一个也不会被发现。
             */
            out.print(content.get());
            return 0;
        } catch (IOException e) {
            err.println("ctxpress: 打开归档失败: " + e.getMessage());
            return EXIT_READ_FAILED;
        }
    }

    /**
     * 取下一个位置参数。
     *
     * <p>早先是直接 {@code args[++i]}，缺参时抛 {@code ArrayIndexOutOfBoundsException}，
     * 而且 catch 块里又访问了一次 {@code args[index]}，二次抛出——用户看到的是裸 Java 栈迹。
     */
    private static String nextArg(String[] args, int index, PrintStream err, String option) {
        if (index >= args.length) {
            err.println("ctxpress: " + option + " 需要一个参数");
            return null;
        }
        return args[index];
    }

    /** 解析整数参数；缺失或非法时报告并返回 null，由调用方转成用法错误 */
    private static Integer intArg(String[] args, int index, PrintStream err, String option) {
        String value = nextArg(args, index, err, option);
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            err.println("ctxpress: " + option + " 需要整数，收到 '" + value + "'");
            return null;
        }
    }

    private static void printUsage(PrintStream err) {
        err.println("""
                ctxpress - Agent 上下文压缩

                用法:
                  ctxpress compress [选项] [文件]    压缩并输出到 stdout（报告到 stderr）
                  ctxpress analyze  [选项] [文件]    只输出压缩效果报告
                  ctxpress retrieve --archive F --ref R   取回被压掉的原文（逐字节一致）

                选项:
                  --max-tokens N     压缩目标上限（默认 8000，最少 64）
                  --kind K           JSON | LOG | TEXT（默认自动判定）
                  --must-keep REGEX  追加保护正则；命中内容不参与裁剪
                  --head N           保留头部行数（默认 40）
                  --tail N           保留尾部行数（默认 20）
                  --tokenizer T      o200k_base（默认）| cl100k_base | r50k_base | p50k_base | heuristic
                                     token 以此为计数口径；报告里会标注实际用词表
                  --archive F        归档文件（追加式）。指定后可逆：
                                     报告行里给出 归档=ORIG-xxxx，随时可用 retrieve 取回

                不指定文件时从 stdin 读取。全离线，不调用任何模型。
                退出码：0 成功 · 1 用法错误 · 2 读取失败 · 3 内部错误 · 4 引用不存在
                """);
    }

    private static final class Options {
        int maxTokens = 8_000;
        int headLines = 40;
        int tailLines = 20;
        ContextKind kind;
        String mustKeep;
        String tokenizer = "o200k_base";
        Path archivePath;
        String ref;
    }
}
