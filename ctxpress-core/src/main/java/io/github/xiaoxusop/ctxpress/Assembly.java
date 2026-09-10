package io.github.xiaoxusop.ctxpress;

/**
 * 一次预算组装的产物。
 *
 * <p>存在的理由是：**省略标记的成本无法在"选择保留哪些条目"时算出来**。
 * 标记的数量取决于保留下来的条目把原文切成了几段，而段数又取决于保留集合本身——
 * 标记成本与选择结果互相依赖。所以组装必须"先选、再渲染、再对账"，这个记录就是对账的结果。
 *
 * @param content       最终内容（含省略标记）
 * @param kept          最终保留标记（各压缩器据此推导审计动作）
 * @param unsatisfiable 受保护内容本身就超出了预算
 * @param overBudgetBy  超出预算多少 token；满足预算时为 0
 */
public record Assembly(String content, boolean[] kept, boolean unsatisfiable, int overBudgetBy) {

    /**
     * 数组字段的 {@code equals} 是引用比较，这个记录不适合做值比较。
     * 这里显式写明，免得后来者误用。
     */
    @Override
    public boolean equals(Object other) {
        return this == other;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }
}
