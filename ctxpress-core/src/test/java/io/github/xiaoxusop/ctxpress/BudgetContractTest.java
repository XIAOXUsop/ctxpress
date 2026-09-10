package io.github.xiaoxusop.ctxpress;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预算契约：**输出要么 ≤ 你给的预算，要么明确声明做不到**。
 *
 * <p>这是本库唯一的产品价值，也是它早先唯一没有测试的地方——30 项测试里没有任何一条
 * 断言过 {@code compressedTokens <= maxTokens}，实测 41 个触发压缩的用例中 19 个超预算，
 * 超出量随预算一起涨（项目自带的 3000 行日志：给 8000 输出 10048，给 20000 输出 26012）。
 *
 * <p><b>为什么必须逐格跑矩阵</b>：预算是**上下文属性**。省略标记的数量取决于保留集合把原文
 * 切成了几段，而段数又随预算变化——"某个预算下守约"推不出"所有预算下都守约"。
 * 固定几组单元测试正是当初让这个缺陷逃逸的原因。
 */
class BudgetContractTest {

    private static final int[] BUDGETS = {64, 128, 200, 500, 1000, 2000, 4000, 8000, 16000, 32000};

    private static String join(List<String> lines) {
        return String.join("\n", lines);
    }

    /** 每句都不同——句末用 ASCII 句点，正是早先会把整篇切成"一句话"的形态 */
    private static String englishProse() {
        String[] verbs = {"flagged", "escalated", "archived", "queued", "rejected", "confirmed"};
        String[] objects = {"the counterparty", "the transaction batch", "the risk score",
                "the supporting document", "the regulatory filing", "the audit trail"};
        return IntStream.range(0, 300)
                .mapToObj(i -> "The screening engine %s %s during run %d."
                        .formatted(verbs[i % verbs.length], objects[i % objects.length], i))
                .collect(Collectors.joining(" "));
    }

    /**
     * 高度冗余的形态：填充率对它们**没有意义**。
     * 1000 行完全相同的日志被折叠成 1 行 + {@code [重复 1000 次]}，
     * 300 句相同的句子被去重成 1 句——那正是去重与折叠该有的效果，不是"没把预算用满"。
     * 所以 {@link #complianceIsNotAchievedByKeepingAlmostNothing()} 跳过它们。
     */
    private static final List<String> REDUNDANT_SHAPES = List.of("重复行日志", "全相同句子的文本");

    private static Map<String, String> corpus() {
        Map<String, String> corpus = new LinkedHashMap<>();
        corpus.put("普通日志", join(IntStream.range(0, 3000)
                .mapToObj(i -> "2026-09-11 10:%02d:%02d INFO  第 %d 条记录，内容各不相同".formatted(i / 60 % 60, i % 60, i))
                .toList()));
        corpus.put("超短行日志", join(IntStream.range(0, 500).mapToObj(i -> "l" + i).toList()));
        corpus.put("重复行日志", join(IntStream.range(0, 1000)
                .mapToObj(i -> "2026-09-11 10:00:00 WARN heartbeat ok").toList()));
        corpus.put("保护命中密集的日志", join(IntStream.range(0, 2000)
                .mapToObj(i -> "2026-09-11 10:00:00 INFO  processed txn %05d amount 12345.67 CNY".formatted(i))
                .toList()));
        corpus.put("方括号前缀日志（logback 默认格式）", join(IntStream.range(0, 2000)
                .mapToObj(i -> "[2026-09-11 10:%02d:%02d] INFO  txn=TXN%06d ok".formatted(i / 60 % 60, i % 60, i))
                .toList()));
        corpus.put("对象根 JSON（长数组）", "{\"rows\":[" + IntStream.range(0, 500)
                .mapToObj(i -> "{\"id\":%d,\"name\":\"record-%d\"}".formatted(i, i))
                .collect(Collectors.joining(",")) + "]}");
        corpus.put("数组根 JSON", "[" + IntStream.range(0, 500)
                .mapToObj(i -> "{\"id\":%d,\"score\":%d}".formatted(i, i * 7 % 100))
                .collect(Collectors.joining(",")) + "]");
        corpus.put("含超长字符串的 JSON", "{\"note\":\"" + "x".repeat(4000) + "\",\"tail\":\"end\"}");
        corpus.put("英文散文（ASCII 句点断句）", englishProse());
        corpus.put("全相同句子的文本", "The screening engine flagged the counterparty. ".repeat(300));
        corpus.put("中文文本", join(IntStream.range(0, 400)
                .mapToObj(i -> "第%d节围绕客户身份识别展开。监管要求金融机构结合客户行业、地区与业务类型判断风险，措施强度需与风险相适应。".formatted(i))
                .toList()));
        return corpus;
    }

    @Test
    void everyCompressedResultEitherFitsTheBudgetOrSaysItCannot() {
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, String> entry : corpus().entrySet()) {
            for (int budget : BUDGETS) {
                PressResult result = ContextPress.with(PressPolicy.builder().maxTokens(budget).build())
                        .press(entry.getValue());
                PressReport report = result.report();
                if (report.compressedTokens() > budget && !report.budgetUnsatisfiable()) {
                    violations.add("%s @%d：输出 %d token，超预算 %d 却未声明"
                            .formatted(entry.getKey(), budget, report.compressedTokens(),
                                    report.compressedTokens() - budget));
                }
            }
        }
        assertTrue(violations.isEmpty(), "预算契约被违反：\n  " + String.join("\n  ", violations));
    }

    /**
     * 反向约束：守约不能靠"几乎什么都不留"来达成。
     *
     * <p>这条来自一次真实的设计失败：按"项成本"批量撤销，而撤一项的实际减少量含省略标记
     * 的变化——短行场景下（项 1 token、标记 7 token）会**过撤 8 倍**，
     * 实测 500 行短日志在预算 375 下只剩 7 个 token（填充率 2%），比不压还糟。
     */
    @Test
    void complianceIsNotAchievedByKeepingAlmostNothing() {
        List<String> underfilled = new ArrayList<>();
        for (Map.Entry<String, String> entry : corpus().entrySet()) {
            if (REDUNDANT_SHAPES.contains(entry.getKey())) {
                continue;
            }
            for (int budget : BUDGETS) {
                PressResult result = ContextPress.with(PressPolicy.builder().maxTokens(budget).build())
                        .press(entry.getValue());
                PressReport report = result.report();
                // 只考察"内容远大于预算且确实压过"的格子：这些格子理应把预算用满
                boolean meaningful = report.originalTokens() > budget * 4
                        && !report.budgetUnsatisfiable()
                        && report.effective();
                if (meaningful && report.compressedTokens() < budget * 0.5) {
                    underfilled.add("%s @%d：只用了 %d/%d（填充率 %.2f）"
                            .formatted(entry.getKey(), budget, report.compressedTokens(), budget,
                                    report.compressedTokens() / (double) budget));
                }
            }
        }
        assertTrue(underfilled.isEmpty(), "守约但几乎没留下内容：\n  " + String.join("\n  ", underfilled));
    }

    /** 同输入 + 同策略 → 逐字节相同，在整个预算矩阵上都成立 */
    @Test
    void determinismHoldsAcrossTheWholeMatrix() {
        for (Map.Entry<String, String> entry : corpus().entrySet()) {
            for (int budget : BUDGETS) {
                PressPolicy policy = PressPolicy.builder().maxTokens(budget).build();
                PressResult first = ContextPress.with(policy).press(entry.getValue());
                PressResult second = ContextPress.with(policy).press(entry.getValue());
                assertEquals(first.content(), second.content(),
                        "%s @%d 两次结果不一致".formatted(entry.getKey(), budget));
                assertEquals(first.report().actions(), second.report().actions());
            }
        }
    }

    /** 内容放得下时一个字节都不该动——这是契约的一部分，不是优化 */
    @Test
    void contentThatAlreadyFitsIsNeverTouched() {
        for (Map.Entry<String, String> entry : corpus().entrySet()) {
            int original = TokenEstimator.estimate(entry.getValue());
            PressResult result = ContextPress.with(
                    PressPolicy.builder().maxTokens(original * 2).build()).press(entry.getValue());
            assertEquals(entry.getValue(), result.content(), entry.getKey() + " 放得下却被改动了");
        }
    }
}
