package io.github.xiaoxusop.ctxpress;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;

/**
 * 压缩归档：保存被压掉的原文，使压缩<b>可逆</b>。
 *
 * <p>为什么可逆是必需的而不是锦上添花：压缩的本质是"赌这段内容用不上"。
 * 赌对了省 token，**赌错了模型就永远拿不到那一行**——而 Agent 恰恰经常在后续步骤里
 * 需要前面被压掉的细节（一个错误码、一个字段值）。没有取回通道的压缩器，
 * 是在用"可能答错"换"一定更便宜"。
 *
 * <p>引用是**内容寻址**的：{@code ORIG-} + SHA-256 前 16 位。同一段原文重复压缩只存一份，
 * 且引用稳定——换了后端、重启了进程，同一个 ref 依然指向同一段内容。
 * 这正是归档能落盘、能换实现的前提。
 *
 * @see InMemoryContextArchive 默认实现，有界内存
 * @see FileContextArchive 追加式文件，跨进程有效
 */
public interface ContextArchive {

    /**
     * 存入原文并返回引用。
     *
     * @return 内容寻址的引用，形如 {@code ORIG-3f9a2b7c1d4e5f60}；原文为 null/空时返回 null
     */
    String store(String original);

    /** 取回原文；不存在时返回空（例如超出容量被淘汰，或来自另一次进程） */
    Optional<String> retrieve(String ref);

    /** 丢弃一个引用（用于"存了但最终没压缩"的场景，避免留下无用条目） */
    default void discard(String ref) {
        // 默认什么都不做：追加式后端删不掉已写下的行，而多留一条无害
    }

    default boolean contains(String ref) {
        return retrieve(ref).isPresent();
    }

    /** 当前条目数；后端不支持计数时返回 -1 */
    default int size() {
        return -1;
    }

    /** 默认实现：有界内存 */
    static ContextArchive inMemory() {
        return new InMemoryContextArchive();
    }

    static ContextArchive inMemory(int capacity) {
        return new InMemoryContextArchive(capacity);
    }

    /** 内容寻址引用：同一段原文恒得同一 ref */
    static String refOf(String original) {
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
