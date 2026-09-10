package io.github.xiaoxusop.ctxpress;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 有界内存归档：默认实现，进程内有效。
 *
 * <p>默认实现是**有界**的：不设上限的归档会变成内存泄漏。
 * 超出容量按 LRU 淘汰——{@code retrieve} 会把条目移到末尾，被淘汰的是最久未用的。
 *
 * <p>进程重启即失效。需要跨进程可取回就用 {@link FileContextArchive}。
 */
public final class InMemoryContextArchive implements ContextArchive {

    /** 默认保留条目数 */
    public static final int DEFAULT_CAPACITY = 256;

    private final int capacity;
    private final Map<String, String> entries;

    public InMemoryContextArchive() {
        this(DEFAULT_CAPACITY);
    }

    public InMemoryContextArchive(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("归档容量至少为 1：" + capacity);
        }
        this.capacity = capacity;
        // accessOrder=true：retrieve 会把条目移到末尾，淘汰的是最久未用的
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > InMemoryContextArchive.this.capacity;
            }
        };
    }

    @Override
    public String store(String original) {
        if (original == null || original.isEmpty()) {
            return null;
        }
        String ref = ContextArchive.refOf(original);
        synchronized (entries) {
            entries.put(ref, original);
        }
        return ref;
    }

    @Override
    public void discard(String ref) {
        if (ref != null) {
            synchronized (entries) {
                entries.remove(ref);
            }
        }
    }

    @Override
    public Optional<String> retrieve(String ref) {
        if (ref == null) {
            return Optional.empty();
        }
        synchronized (entries) {
            return Optional.ofNullable(entries.get(ref));
        }
    }

    @Override
    public boolean contains(String ref) {
        synchronized (entries) {
            return entries.containsKey(ref);
        }
    }

    @Override
    public int size() {
        synchronized (entries) {
            return entries.size();
        }
    }
}
