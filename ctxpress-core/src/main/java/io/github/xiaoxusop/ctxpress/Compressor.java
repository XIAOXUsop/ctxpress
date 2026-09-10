package io.github.xiaoxusop.ctxpress;

import java.util.ArrayList;
import java.util.List;

/**
 * 按体裁分派的压缩器。
 *
 * <p>所有实现必须满足三条硬约束：
 * <ol>
 *   <li><b>确定性</b>：同一输入 + 同一策略 → 逐字节相同的输出。不得引入时钟、随机、
 *       或依赖 {@code HashMap} 迭代序。</li>
 *   <li><b>不调用模型</b>：压缩必须是纯计算。用模型做摘要会带来三项代价——
 *       结果不可复现、成本随压缩频率上升、且无法保证"不引入新事实"。</li>
 *   <li><b>结构自洽</b>：输出仍是合法 JSON / 完整行 / 完整句子，不做任意位置截断。</li>
 * </ol>
 */
public interface Compressor {

    ContextKind kind();

    /** 执行压缩。实现方负责按 {@link PressPolicy#maxTokens()} 控制规模。 */
    PressResult compress(String content, PressPolicy policy);

    /**
     * 公共骨架：给定"必须保留的片段"与"可裁剪的片段"，在预算内组装结果。
     * 供各体裁复用，保证保护语义在所有实现里一致。
     */
    default List<String> selectWithinBudget(List<String> lines, List<Boolean> required,
                                            int head, int tail, int maxTokens) {
        List<String> kept = new ArrayList<>(lines.size());
        boolean[] used = new boolean[lines.size()];
        int budget = maxTokens;

        // 第一轮：受保护的内容无条件保留（**预算管不到它们**，这正是"零丢失"的含义）
        for (int i = 0; i < lines.size(); i++) {
            if (Boolean.TRUE.equals(required.get(i))) {
                used[i] = true;
                budget -= TokenEstimator.estimate(lines.get(i));
            }
        }

        // 第二轮：头部
        int headKept = 0;
        for (int i = 0; i < lines.size() && headKept < head; i++) {
            if (used[i]) {
                continue;
            }
            int cost = TokenEstimator.estimate(lines.get(i));
            if (budget - cost < 0) {
                break;
            }
            used[i] = true;
            budget -= cost;
            headKept++;
        }

        // 第三轮：尾部（近期信息通常更重要）
        int tailKept = 0;
        for (int i = lines.size() - 1; i >= 0 && tailKept < tail; i--) {
            if (used[i]) {
                continue;
            }
            int cost = TokenEstimator.estimate(lines.get(i));
            if (budget - cost < 0) {
                break;
            }
            used[i] = true;
            budget -= cost;
            tailKept++;
        }

        for (int i = 0; i < lines.size(); i++) {
            if (used[i]) {
                kept.add(lines.get(i));
            }
        }
        return kept;
    }
}
