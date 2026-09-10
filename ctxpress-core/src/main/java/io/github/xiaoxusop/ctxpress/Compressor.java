package io.github.xiaoxusop.ctxpress;

import java.util.ArrayList;
import java.util.List;

/**
 * 按体裁分派的压缩器。
 *
 * <p>所有实现必须满足四条硬约束：
 * <ol>
 *   <li><b>确定性</b>：同一输入 + 同一策略 → 逐字节相同的输出。不得引入时钟、随机、
 *       或依赖 {@code HashMap} 迭代序。</li>
 *   <li><b>不调用模型</b>：压缩必须是纯计算。用模型做摘要会带来三项代价——
 *       结果不可复现、成本随压缩频率上升、且无法保证"不引入新事实"。</li>
 *   <li><b>结构自洽</b>：输出仍是合法 JSON / 完整行 / 完整句子，不做任意位置截断。</li>
 *   <li><b>预算可交代</b>：输出要么 ≤ {@link PressPolicy#maxTokens()}，
 *       要么在 {@link PressReport#overBudgetBy()} 里如实说明超了多少。
 *       <b>不允许静默超预算</b>——这是本库唯一的产品承诺。</li>
 * </ol>
 */
public interface Compressor {

    /** 把保留集合渲染为最终内容（含省略标记）。{@code kept} 与压缩器持有的条目列表一一对应。 */
    @FunctionalInterface
    interface Renderer {
        String render(boolean[] kept);
    }

    /**
     * 执行压缩。
     *
     * @param archiveRef 归档引用（可为 null）。非 null 时实现方必须把它嵌进省略标记里，
     *                   使读到压缩内容的模型知道"这里省略了东西，以及怎么取回"——
     *                   这正是压缩可逆的关键：模型得先知道入口存在，才可能去要
     */
    PressResult compress(String content, PressPolicy policy, String archiveRef);

    /** 不启用归档时压缩（归档引用是可选的，故提供此重载） */
    default PressResult compress(String content, PressPolicy policy) {
        return compress(content, policy, null);
    }

    /**
     * 接纳 {@code index} 会让省略区段数变化多少。
     *
     * <p>把某个条目从未保留改为保留，等于把它所在的那段省略区一分为二（左半、右半）。
     * 所以变化量 = 左半是否非空 + 右半是否非空 − 1：
     * <ul>
     *   <li><b>−1</b>——两侧都已被保留，它是个单元素空洞：填上它直接消掉一个省略标记；</li>
     *   <li><b>0</b>——一侧被保留，区段只是缩短，数量不变；</li>
     *   <li><b>+1</b>——两侧都是省略区，它会在区段中间凭空开出一个新标记。</li>
     * </ul>
     *
     * <p>这解释了为什么"按条目成本批量撤销"是错的：撤掉一个夹在两个保留段之间的短条目，
     * 减少的只是那一条的成本，却**新增**一个省略标记——净效果可能是变大。
     */
    static int gapDelta(int index, boolean[] kept, int size) {
        boolean leftRemains = index > 0 && !kept[index - 1];
        boolean rightRemains = index < size - 1 && !kept[index + 1];
        return (leftRemains ? 1 : 0) + (rightRemains ? 1 : 0) - 1;
    }

    /**
     * 预算内选择：返回每个条目是否保留。
     *
     * <p>准入优先级为 <b>受保护 → 补洞 → 头部 → 尾部 → 均匀采样</b>。
     * 预算不足时优先级低的先被放弃——这个顺序就是"什么更该留"的判断本身。
     *
     * <p><b>记账口径</b>：每条按 {@code estimate(item) + 1} 计（+1 覆盖行分隔符），
     * 每段省略标记按 {@code markerCost} 计。逐条之和是"渲染结果整体估算"的**上界**
     * ——因为 {@code ceil(a+b) ≤ ceil(a) + ceil(b)}，逐条的 ceil 余数只会多算不会少算。
     * 于是"受理时 <= 预算"就能推出"渲染出来 <= 预算"，不需要先渲染再回退。
     * 早先的实现用逐条口径受理、却把省略标记留在受理之后才拼上，口径裂缝正是超预算的来源。
     *
     * @param required  与 {@code items} 等长，标记哪些条目命中保护规则
     * @param reserved  与区段数无关的固定开销，先行扣除（例如文本体裁至多只有一个省略标记）
     * @param markerCost 单个省略标记的成本；0 表示该体裁的标记开销已由 {@code reserved} 覆盖
     */
    default boolean[] selectWithinBudget(List<String> items, boolean[] required, PressPolicy policy,
                                         int reserved, int markerCost) {
        int n = items.size();
        boolean[] kept = new boolean[n];
        if (n == 0) {
            return kept;
        }
        int maxTokens = policy.maxTokens();
        int head = policy.headLines();
        int tail = policy.tailLines();
        TokenCounter counter = policy.tokenCounter();
        int[] cost = new int[n];
        for (int i = 0; i < n; i++) {
            cost[i] = counter.count(items.get(i)) + 1;
        }

        long renderCost = reserved;
        // 空保留集 = 整篇是一个省略区段
        int gaps = 1;

        // 第一轮：受保护内容无条件保留。
        // 这里不检查预算，是刻意的——保护优先于预算。装不下时由 assemble() 如实上报。
        for (int i = 0; i < n; i++) {
            if (required[i]) {
                renderCost += cost[i] + (long) gapDelta(i, kept, n) * markerCost;
                kept[i] = true;
            }
        }

        // 第二轮：补洞。夹在两个保留段之间的单元素空洞，填上它消掉一个标记——
        // 条目小时这一步**倒赚**（填 1 token 的内容，省掉 7 token 的标记）。
        // 必须排在按优先级买项之前，否则预算会被别的项用光。
        for (boolean changed = true; changed; ) {
            changed = false;
            for (int i = 1; i < n - 1; i++) {
                if (kept[i] || !kept[i - 1] || !kept[i + 1]) {
                    continue;
                }
                long delta = cost[i] + (long) gapDelta(i, kept, n) * markerCost;
                if (renderCost + delta <= maxTokens) {
                    renderCost += delta;
                    kept[i] = true;
                    changed = true;
                }
            }
        }

        // 第三轮：头部。装不起的条目**跳过而不是停止**——早先用 break，
        // 一条超长行就让它后面的条目连尝试的机会都没有。
        int headKept = 0;
        for (int i = 0; i < n && headKept < head; i++) {
            if (kept[i]) {
                continue;
            }
            long delta = cost[i] + (long) gapDelta(i, kept, n) * markerCost;
            if (renderCost + delta > maxTokens) {
                continue;
            }
            renderCost += delta;
            kept[i] = true;
            headKept++;
        }

        // 第四轮：尾部（近期信息通常更重要）
        int tailKept = 0;
        for (int i = n - 1; i >= 0 && tailKept < tail; i--) {
            if (kept[i]) {
                continue;
            }
            long delta = cost[i] + (long) gapDelta(i, kept, n) * markerCost;
            if (renderCost + delta > maxTokens) {
                continue;
            }
            renderCost += delta;
            kept[i] = true;
            tailKept++;
        }

        // 第五轮：均匀采样填补中段。
        // 少了这一步会出现荒唐结果：预算给到 60000、内容 68547，却因为头尾是固定条数
        // 而被压到 3000——只超一点预算却付了极大的信息代价。
        List<Integer> leftover = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (!kept[i]) {
                leftover.add(i);
            }
        }
        if (!leftover.isEmpty()) {
            int m = leftover.size();
            for (int target = m; target >= 1; target = target > 1 ? Math.max(1, target / 2) : 0) {
                boolean any = false;
                for (int k = 0; k < target; k++) {
                    int position = (int) ((long) k * m / target);
                    int index = leftover.get(Math.min(position, m - 1));
                    if (kept[index]) {
                        continue;
                    }
                    long delta = cost[index] + (long) gapDelta(index, kept, n) * markerCost;
                    if (renderCost + delta > maxTokens) {
                        continue;
                    }
                    renderCost += delta;
                    kept[index] = true;
                    any = true;
                }
                if (!any) {
                    break;   // 这一密度一个都塞不进，再加密也没用
                }
            }
        }
        return kept;
    }

    /**
     * 在预算内组装最终内容。
     *
     * <p>受理判据在 {@link #selectWithinBudget} 内部就已完成，这里只做一次终态校验：
     * 若结果仍超预算，只可能是**受保护内容本身就超预算**——那不是压缩器偷懒，
     * 而是"保护优先"与"塞进 N 个 token"两条承诺在数学上互斥。
     * 此时如实上报 {@link Assembly#unsatisfiable()}，把决策交回调用方，
     * **既不静默丢弃受保护内容，也不静默超标**。
     */
    default Assembly assemble(List<String> items, boolean[] required, PressPolicy policy,
                              int reserved, int markerCost, Renderer renderer) {
        boolean[] kept = selectWithinBudget(items, required, policy, reserved, markerCost);
        String rendered = renderer.render(kept);
        int maxTokens = policy.maxTokens();
        int cost = policy.tokenCounter().count(rendered);
        return cost <= maxTokens
                ? new Assembly(rendered, kept, false, 0)
                : new Assembly(rendered, kept, true, cost - maxTokens);
    }
}
