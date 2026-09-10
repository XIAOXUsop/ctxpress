package io.github.xiaoxusop.ctxpress;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文件归档。
 *
 * <p>它存在的全部意义是**跨进程**：内存归档进程一重启就失效，
 * 而"压掉的东西能拿回来"的价值经常体现在后续步骤、甚至另一个进程里。
 * 所以下面每一条都以"重新打开这个文件"为验证方式。
 */
class FileContextArchiveTest {

    @TempDir
    Path dir;

    private static String log(int lines) {
        return IntStream.range(0, lines)
                .mapToObj(i -> "2026-09-11 10:00:00 INFO 第 %d 行内容各不相同".formatted(i))
                .collect(Collectors.joining("\n"));
    }

    /** 写入后**重新打开文件**再取——模拟另一个进程 */
    @Test
    void survivesReopening() throws IOException {
        Path file = dir.resolve("archive.log");
        String original = log(500);

        String ref;
        try {
            ContextArchive writer = FileContextArchive.open(file);
            ref = writer.store(original);
        } catch (IOException e) {
            throw e;
        }

        ContextArchive reader = FileContextArchive.open(file);
        assertEquals(original, reader.retrieve(ref).orElseThrow(),
                "重新打开后取回的内容必须与原文逐字节相同");
    }

    /**
     * 原文里的换行、制表符、中文、引号都不能与格式本身冲突——
     * 格式是"一行头部 + 字节数 + 原文"，所以原文里有什么都不需要转义。
     */
    @Test
    void arbitraryContentDoesNotConfuseTheFormat() throws IOException {
        Path file = dir.resolve("archive.log");
        String nasty = "第一行\t带制表符\nORIG-deadbeefdeadbeef 999\n\"; 引号与分号\n"
                + "emoji: 😀\n" + log(50);

        ContextArchive archive = FileContextArchive.open(file);
        String ref = archive.store(nasty);

        assertEquals(nasty, FileContextArchive.open(file).retrieve(ref).orElseThrow());
    }

    @Test
    void severalEntriesCoexistAndStayDistinct() throws IOException {
        Path file = dir.resolve("archive.log");
        ContextArchive archive = FileContextArchive.open(file);

        String first = log(100);
        String second = log(200);
        String firstRef = archive.store(first);
        String secondRef = archive.store(second);

        ContextArchive reader = FileContextArchive.open(file);
        assertEquals(2, reader.size());
        assertEquals(first, reader.retrieve(firstRef).orElseThrow());
        assertEquals(second, reader.retrieve(secondRef).orElseThrow());
    }

    /** 内容寻址：同一段原文重复压缩只写一份 */
    @Test
    void contentAddressingMakesRepeatsIdempotent() throws IOException {
        Path file = dir.resolve("archive.log");
        ContextArchive archive = FileContextArchive.open(file);
        String original = log(300);

        String first = archive.store(original);
        String second = archive.store(original);

        assertEquals(first, second);
        assertEquals(1, FileContextArchive.open(file).size());
        assertTrue(Files.size(file) > 0);
    }

    @Test
    void unknownOrNullRefYieldsEmptyRatherThanThrowing() throws IOException {
        ContextArchive archive = FileContextArchive.open(dir.resolve("archive.log"));

        assertEquals(Optional.empty(), archive.retrieve(null));
        assertEquals(Optional.empty(), archive.retrieve("ORIG-0000000000000000"));
        assertFalse(archive.contains("ORIG-0000000000000000"));
    }

    @Test
    void emptyContentIsNotStored() throws IOException {
        ContextArchive archive = FileContextArchive.open(dir.resolve("archive.log"));

        assertEquals(null, archive.store(""));
        assertEquals(null, archive.store(null));
        assertEquals(0, archive.size());
    }

    /** 归档文件不完整（上次写到一半被杀）时，能读的照读，不抛异常阻断流程 */
    @Test
    void truncatedFileIsToleratedInsteadOfThrowing() throws IOException {
        Path file = dir.resolve("archive.log");
        ContextArchive archive = FileContextArchive.open(file);
        String ref = archive.store(log(100));
        Files.writeString(file, "ORIG-abc 99999\n只有头部没有正文", java.nio.file.StandardOpenOption.APPEND);

        ContextArchive reader = FileContextArchive.open(file);
        assertEquals(log(100), reader.retrieve(ref).orElseThrow(),
                "完整的那条仍应能取回");
    }
}
