package com.traffic.rag.eval;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RagAnswerEvaluatorTest {

    private final RagAnswerEvaluator evaluator =
            new RagAnswerEvaluator();

    @Test
    @DisplayName("所有关键事实均出现时，覆盖率为 100%")
    void shouldReturnFullCoverage() {

        RagEvalCase testCase = new RagEvalCase(
                "TEST-A",
                "ANSWERABLE",
                "查询支持哪些方式？",
                "测试资料",
                "支持关键词查询和语义查询。",
                List.of("关键词查询", "语义查询"),
                List.of()
        );

        var result = evaluator.evaluate(
                testCase,
                "系统支持关键词查询和语义查询。"
        );

        assertThat(result.matchedCount()).isEqualTo(2);
        assertThat(result.coverageRate()).isEqualTo(1.0);
        assertThat(result.missingKeyPoints()).isEmpty();
        assertThat(result.emptyAnswer()).isFalse();
    }

    @Test
    @DisplayName("遗漏一个关键事实时，覆盖率为 50%")
    void shouldDetectMissingKeyPoint() {

        RagEvalCase testCase = new RagEvalCase(
                "TEST-B",
                "ANSWERABLE",
                "查询支持哪些方式？",
                "测试资料",
                "支持关键词查询和语义查询。",
                List.of("关键词查询", "语义查询"),
                List.of()
        );

        var result = evaluator.evaluate(
                testCase,
                "系统支持关键词查询。"
        );

        assertThat(result.matchedCount()).isEqualTo(1);
        assertThat(result.coverageRate()).isEqualTo(0.5);
        assertThat(result.missingKeyPoints())
                .containsExactly("语义查询");
    }

    @Test
    @DisplayName("答案为 null 时，覆盖率为 0 且所有要点缺失")
    void shouldHandleNullAnswer() {

        RagEvalCase testCase = new RagEvalCase(
                "TEST-C",
                "UNANSWERABLE",
                "用户数量是多少？",
                "没有用户数量信息",
                "无法确认",
                List.of("现有资料未提供注册用户数量"),
                List.of()
        );

        var result = evaluator.evaluate(testCase, null);

        assertThat(result.emptyAnswer()).isTrue();
        assertThat(result.coverageRate()).isEqualTo(0.0);
        assertThat(result.missingKeyPoints())
                .containsExactly("现有资料未提供注册用户数量");
    }

    @Test
    @DisplayName("答案中的空格差异不影响匹配")
    void shouldIgnoreWhitespaceDifferences() {

        RagEvalCase testCase = new RagEvalCase(
                "TEST-D",
                "ANSWERABLE",
                "查询超时时间是多少？",
                "测试资料",
                "关键词查询超时为 2 秒。",
                List.of("关键词查询超时为 2 秒"),
                List.of()
        );

        var result = evaluator.evaluate(
                testCase,
                "关键词查询超时为2秒。"
        );

        assertThat(result.coverageRate()).isEqualTo(1.0);
    }
}

