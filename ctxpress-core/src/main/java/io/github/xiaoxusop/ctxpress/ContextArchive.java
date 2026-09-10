package io.github.xiaoxusop.ctxpress;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 压缩归档：保存被压掉的原文，使压缩<b>可逆</b>。
 *
 * <p>为什么可逆是必需的而不是锦上添花：压缩的本质是"赌这段内容用不上"。
 * 赌对了省 token，**赌错了模型就永远拿不到那一行**——而 Agent 恰恰经常在后续步骤里
 * 需要前面被压掉的细节（一个错误码、一个字段值）。没有取回通道的压缩器，
 * 是在用"可能答错"换"一定更便宜"。
 *
 * <p>设计上刻意做成**内容寻址**：ref = SHA-256(原文) 前 16 位。
 * 同一段原文重复压缩只会存一份，且 ref 稳定——重启后若归档落盘，ref 依然有效。
 *
 * <p>默认实现是**有界内存**的：不设上限的归档会变成内存泄漏。
 */
public final class ContextArchive {

    /** 默认保留条目数；超出后按插入顺序淘汰最早的 */
    public static final int DEFAULT_CAPACITY = 256;

    private final int capacity;
    private final Map<String, String> entries;

    public ContextArchive() {
        this(DEFAULT_CAPACITY);
    }

    public ContextArchive(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("归档容量至少为 1：" + capacity);
        }
        this.capacity = capacity;
        // accessOrder=true：retrieve 会把条目移到末尾，淘汰的是最久未用的
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > ContextArchive.this.capacity;
            }
        };
    }

    /**
     * 存入原文并返回引用。
     *
     * @return 内容寻址的引用，形如 {@code ORIG-3f9a2b7c1d4e5f60}；原文为 null/空时返回 null
     */
    public String store(String original) {
        if (original == null || original.isEmpty()) {
            return null;
        }
        String ref = refOf(original);
        synchronized (entries) {
            entries.put(ref, original);
        }
        return ref;
    }

    /** 丢弃一个引用（用于"存了但最终没压缩"的场景，避免留下无用条目） */
    public void discard(String ref) {
        if (ref != null) {
            synchronized (entries) {
                entries.remove(ref);
            }
        }
    }

    /** 取回原文；不存在时返回空（例如超出容量被淘汰，或来自另一次进程） */
    public Optional<String> retrieve(String ref) {
        if (ref == null) {
            return Optional.empty();
        }
        synchronized (entries) {
            return Optional.ofNullable(entries.get(ref));
        }
    }

    public boolean contains(String ref) {
        synchronized (entries) {
            return entries.containsKey(ref);
        }
    }

    public int size() {
        synchronized (entries) {
            return entries.size();
        }
    }

    /** 内容寻址引用：同一段原文恒得同一 ref */
    public static String refOf(String original) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(original.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("ORIG-");
            for (int i = 0; i < 8; i++) {
                sb.append(Character.forDigit((hash[i] >> 4) & 0xF, 16));
                sb.append(Character.forDigit(hash[i] & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("本机不支持 SHA-256", e);
        }
    }
}
