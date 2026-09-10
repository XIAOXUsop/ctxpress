package io.github.xiaoxusop.ctxpress;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 追加式文件归档：**跨进程取回**。
 *
 * <p>默认的 {@link InMemoryContextArchive} 进程一重启就失效，而"压掉的东西能拿回来"
 * 这件事的价值恰恰经常体现在后续步骤、甚至另一个进程里（先压缩写入、模型要用时再取回）。
 * 内容寻址的引用天然支持落盘：{@code ORIG-} 是原文的 SHA-256 前 16 位，
 * 换后端、重启进程，同一个 ref 依然指向同一段内容。
 *
 * <p>格式是**追加式**的，一行头部 + 原文：
 * <pre>
 * ORIG-3f9a2b7c1d4e5f60 1204
 * …1204 字节的 UTF-8 原文…
 * </pre>
 * 头部给出字节数，因此原文里有什么字符都不需要转义，也不会与分隔符冲突。
 * 追加式意味着**只增不改**：同一条目重复写入是幂等的（ref 相同），而删除不做——
 * 归档的价值在于"还在"，为了省一点磁盘去重写整个文件不划算。
 *
 * @see #open(Path) 打开（文件不存在时创建）
 */
public final class FileContextArchive implements ContextArchive {

    private final Path path;
    private final Map<String, long[]> index = new HashMap<>();
    private final Object lock = new Object();

    private FileContextArchive(Path path) {
        this.path = path;
    }

    /**
     * 打开（或创建）归档文件，并扫描一遍已有内容建立索引。
     *
     * <p>索引常驻内存（每条一个引用 + 两个偏移量），**原文不常驻**——按需从文件读取。
     * 建立索引需要把文件扫一遍，所以初始化的代价与归档大小成正比；
     * 追加写入本身仍是 O(1)。
     */
    public static FileContextArchive open(Path path) throws IOException {
        FileContextArchive archive = new FileContextArchive(path);
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (Files.exists(path)) {
            archive.buildIndex();
        } else {
            Files.createFile(path);
        }
        return archive;
    }

    private void buildIndex() throws IOException {
        byte[] all = Files.readAllBytes(path);
        int cursor = 0;
        while (cursor < all.length) {
            int newline = indexOfNewline(all, cursor);
            if (newline < 0) {
                return;   // 末行不完整，停止索引（可能是上次写到一半被杀）
            }
            String header = new String(all, cursor, newline - cursor, StandardCharsets.UTF_8);
            int space = header.lastIndexOf(' ');
            if (space < 0) {
                return;   // 格式不认识，停止索引而不是抛异常——归档不该阻断压缩
            }
            int length;
            try {
                length = Integer.parseInt(header.substring(space + 1).strip());
            } catch (NumberFormatException e) {
                return;
            }
            int contentStart = newline + 1;
            if (length < 0 || contentStart + length > all.length) {
                return;
            }
            index.put(header.substring(0, space), new long[]{contentStart, length});
            cursor = contentStart + length + 1;   // 跳过原文与它后面那个换行
        }
    }

    private static int indexOfNewline(byte[] bytes, int from) {
        for (int i = from; i < bytes.length; i++) {
            if (bytes[i] == '\n') {
                return i;
            }
        }
        return -1;
    }

    @Override
    public String store(String original) {
        if (original == null || original.isEmpty()) {
            return null;
        }
        String ref = ContextArchive.refOf(original);
        synchronized (lock) {
            if (index.containsKey(ref)) {
                return ref;   // 内容寻址：同一段原文只写一次
            }
            byte[] content = original.getBytes(StandardCharsets.UTF_8);
            byte[] header = (ref + " " + content.length + "\n").getBytes(StandardCharsets.UTF_8);
            long contentStart;
            try {
                long base = Files.size(path);
                byte[] record = new byte[header.length + content.length + 1];
                System.arraycopy(header, 0, record, 0, header.length);
                System.arraycopy(content, 0, record, header.length, content.length);
                record[record.length - 1] = '\n';
                Files.write(path, record, StandardOpenOption.APPEND);
                contentStart = base + header.length;
            } catch (IOException e) {
                throw new UncheckedIOException("写入归档失败：" + path, e);
            }
            index.put(ref, new long[]{contentStart, content.length});
        }
        return ref;
    }

    @Override
    public Optional<String> retrieve(String ref) {
        if (ref == null) {
            return Optional.empty();
        }
        long[] location;
        synchronized (lock) {
            location = index.get(ref);
        }
        if (location == null) {
            return Optional.empty();
        }
        try {
            synchronized (lock) {
                try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(path.toFile(), "r")) {
                    byte[] content = new byte[(int) location[1]];
                    file.seek(location[0]);
                    file.readFully(content);
                    return Optional.of(new String(content, StandardCharsets.UTF_8));
                }
            }
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    @Override
    public int size() {
        synchronized (lock) {
            return index.size();
        }
    }

    public Path path() {
        return path;
    }
}
