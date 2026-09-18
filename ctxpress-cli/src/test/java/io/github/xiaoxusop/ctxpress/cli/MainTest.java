package io.github.xiaoxusop.ctxpress.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 命令行。
 *
 * <p>这个测试类早先**根本不存在**（cli 模块连 test 目录都没有），
 * 而 README 却把「CLI 输出走平台编码产出非法 UTF-8」列为已修并"已补回归用例"。
 * 实测未覆盖的问题：缺参抛裸 Java 栈迹、异常路径中文乱码、输出在 Windows 上多出 CRLF、
 * 未知命令被当成文件名报"读取失败"。
 */
class MainTest {

    private record Invocation(int code, String out, String err) {
    }

    private static Invocation invoke(String... args) {
        return invoke(new byte[0], args);
    }

    private static Invocation invoke(byte[] stdin, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(args, new ByteArrayInputStream(stdin),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Invocation(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static String log(int lines) {
        return IntStream.range(0, lines)
                .mapToObj(i -> "2026-09-11 10:00:00 INFO 第 %d 条记录，内容各不相同".formatted(i))
                .collect(Collectors.joining("\n"));
    }

    // ---------- 用法错误必须是用法错误，而不是栈迹 ----------

    /**
     * 缺参曾抛 {@code ArrayIndexOutOfBoundsException}：{@code args[++i]} 没有边界检查，
     * catch 块里又访问了一次 {@code args[index]} 二次抛出，用户看到的是裸 Java 栈迹。
     */
    @Test
    void missingOptionArgumentReportsUsageInsteadOfAStackTrace() {
        for (String option : new String[]{"--max-tokens", "--kind", "--must-keep", "--head", "--tail"}) {
            Invocation result = invoke("compress", option);
            assertEquals(1, result.code(), option + " 应返回用法错误码");
            assertTrue(result.err().contains("需要一个参数"), option + " 的错误信息：" + result.err());
            assertFalse(result.err().contains("Exception"), option + " 泄漏了栈迹：" + result.err());
            assertFalse(result.err().contains("\tat "), option + " 泄漏了栈迹：" + result.err());
        }
    }

    @Test
    void malformedOptionValuesReportUsage() {
        assertEquals(1, invoke("compress", "--max-tokens", "abc").code());
        assertEquals(1, invoke("compress", "--kind", "XML").code());
    }

    /**
     * 参数越界由策略层抛异常。早先它会逃出 main、由 JVM 默认处理器写到**平台编码**的
     * System.err 上——中文 Windows 是 GBK，于是错误信息本身变成乱码
     * （实测 {@code maxTokens 至少为 64} 输出成 {@code maxTokens ����Ϊ 64}）。
     */
    @Test
    void outOfRangeValuesReportReadableUsageErrors() {
        Invocation result = invoke("compress", "--max-tokens", "0");

        assertEquals(1, result.code());
        assertTrue(result.err().contains("maxTokens"), result.err());
        assertFalse(result.err().contains("�"), "错误信息出现乱码替换字符：" + result.err());
    }

    @Test
    void unknownOptionAndCommandAreUsageErrors() {
        assertEquals(1, invoke("compress", "--bogus").code());
        // 未知命令早先被当成文件名，报"读取失败"（rc=2）而不是用法错误（rc=1）
        Invocation unknown = invoke("frobnicate", "x");
        assertEquals(1, unknown.code(), unknown.err());
        assertTrue(unknown.err().contains("未知命令"), unknown.err());
    }

    @Test
    void noArgumentsPrintsUsage() {
        Invocation result = invoke();

        assertEquals(1, result.code());
        assertTrue(result.err().contains("用法"), result.err());
    }

    // ---------- 输出契约 ----------

    /**
     * 输出必须只含 LF。
     *
     * <p>早先用 {@code println}，走的是 {@code System.lineSeparator()}——Windows 上追加 {@code \r\n}，
     * 于是同一份输入在不同平台得到不同的字节，与"确定性 / 可复现"的承诺冲突
     * （实测 CRLF 归一化后仍会多出 1 个 CRLF）。
     */
    @Test
    void outputUsesLineFeedOnlySoResultsArePlatformIndependent() {
        Invocation result = invoke(log(2000).getBytes(StandardCharsets.UTF_8),
                "compress", "--max-tokens", "200");

        assertEquals(0, result.code(), result.err());
        assertFalse(result.out().contains("\r"), "输出里出现了 CR");
        assertTrue(result.err().contains("->"), "报告应走 stderr：" + result.err());
    }

    /** 压缩结果走 stdout、报告走 stderr——重定向才可能是安全的 */
    @Test
    void compressedContentGoesToStdoutAndTheReportToStderr() {
        Invocation result = invoke(log(2000).getBytes(StandardCharsets.UTF_8),
                "compress", "--max-tokens", "200");

        assertTrue(result.out().startsWith("2026-09-11"), result.out().substring(0, 40));
        assertFalse(result.out().contains("->"), "报告混进了内容：" + result.out());
    }

    @Test
    void analyzePrintsOnlyTheReport() {
        Invocation result = invoke(log(2000).getBytes(StandardCharsets.UTF_8),
                "analyze", "--max-tokens", "200");

        assertEquals(0, result.code());
        assertTrue(result.out().contains("->"), result.out());
        assertTrue(result.out().startsWith("LOG: "), result.out());
    }

    /** 一份放得下的内容不该被改动 */
    @Test
    void contentThatFitsIsEmittedUnchanged() {
        String small = log(3);

        Invocation result = invoke(small.getBytes(StandardCharsets.UTF_8), "compress", "--max-tokens", "8000");

        assertEquals(0, result.code());
        assertEquals(small, result.out().stripTrailing());
    }

    // ---------- 可逆：命令行也能取回 ----------

    private static final java.util.regex.Pattern ARCHIVE_REF =
            java.util.regex.Pattern.compile("归档=(ORIG-[0-9a-f]{16})");

    /**
     * 压缩 → 取回，**逐字节一致**。
     *
     * <p>这条路径早先是完全不存在的：归档只在 Java API 可用，命令行压缩完就再也拿不回
     * 被压掉的部分——而 README 里整节「可逆」面向的正是命令行用户。
     * 等于把最核心的差异化能力锁在了 API 里。
     */
    @Test
    void compressedContentCanBeRetrievedVerbatimFromTheCli(@TempDir java.nio.file.Path dir) {
        String original = log(2000);
        String archive = dir.resolve("archive.log").toString();

        Invocation compressed = invoke(original.getBytes(StandardCharsets.UTF_8),
                "compress", "--max-tokens", "200", "--archive", archive);
        assertEquals(0, compressed.code(), compressed.err());

        java.util.regex.Matcher matcher = ARCHIVE_REF.matcher(compressed.err());
        assertTrue(matcher.find(), "报告行里应给出归档引用：" + compressed.err());
        String ref = matcher.group(1);

        // 另起一次调用取回——归档的意义就在于跨进程有效
        Invocation retrieved = invoke("retrieve", "--archive", archive, "--ref", ref);

        assertEquals(0, retrieved.code(), retrieved.err());
        /*
         * **不调 stripTrailing**。
         *
         * 这里原先是 `assertEquals(original, retrieved.out().stripTrailing(), "…逐字节相同")`
         * ——断言信息写着「逐字节」，代码却把尾部空白剥掉再比。于是
         * `retrieve` 无条件追加的那个换行被完全抹平，bug 一直测不出来；
         * 更糟的是，原文末尾有多个换行时少写一个也照样通过。
         */
        assertEquals(original, retrieved.out(), "取回的内容必须与原文逐字节相同");
    }

    /**
     * 原文**以换行结尾**时同样逐字节一致。
     *
     * <p>这是真实日志文件的常态（编辑器与日志框架都会写结尾换行），
     * 而上面那条用例的输入恰好不以换行结尾——两者各覆盖追加与丢失的方向。
     */
    @Test
    void retrieveIsByteFaithfulWhenTheSourceEndsWithANewline(@TempDir java.nio.file.Path dir) {
        String original = log(2000) + "\n";
        String archive = dir.resolve("archive.log").toString();

        Invocation compressed = invoke(original.getBytes(StandardCharsets.UTF_8),
                "compress", "--max-tokens", "200", "--archive", archive);
        assertEquals(0, compressed.code(), compressed.err());

        java.util.regex.Matcher matcher = ARCHIVE_REF.matcher(compressed.err());
        assertTrue(matcher.find(), "报告行里应给出归档引用：" + compressed.err());

        Invocation retrieved = invoke("retrieve", "--archive", archive, "--ref", matcher.group(1));
        assertEquals(0, retrieved.code(), retrieved.err());
        assertEquals(original, retrieved.out(), "结尾的换行不该被多加一次，也不该被吃掉");
    }

    /** 没指定归档时不该冒出引用——那会让调用方以为能取回 */
    @Test
    void noArchiveRefIsReportedWhenArchiveIsNotConfigured() {
        Invocation result = invoke(log(2000).getBytes(StandardCharsets.UTF_8),
                "compress", "--max-tokens", "200");

        assertEquals(0, result.code());
        assertFalse(result.err().contains("归档="), result.err());
    }

    @Test
    void retrieveRequiresBothArchiveAndRef() {
        Invocation noRef = invoke("retrieve", "--archive", "whatever.log");
        assertEquals(1, noRef.code());
        assertTrue(noRef.err().contains("--ref"), noRef.err());

        Invocation noArchive = invoke("retrieve", "--ref", "ORIG-0000000000000000");
        assertEquals(1, noArchive.code());
        assertTrue(noArchive.err().contains("--archive"), noArchive.err());
    }

    /** 引用不存在时用独立退出码，好让脚本把它与"用法错"分开处理 */
    @Test
    void unknownRefGetsItsOwnExitCode(@TempDir java.nio.file.Path dir) {
        Invocation result = invoke("retrieve",
                "--archive", dir.resolve("archive.log").toString(),
                "--ref", "ORIG-0000000000000000");

        assertEquals(4, result.code(), result.err());
        assertTrue(result.err().contains("ORIG-0000000000000000"), result.err());
    }
}
