package com.traffic.rag.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traffic.rag.client.LlmClient;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class RagEvalRunner {

    private final ObjectMapper objectMapper;
    private final LlmClient llmClient;
    private final RagAnswerEvaluator answerEvaluator =
            new RagAnswerEvaluator();

    public RagEvalRunner(LlmClient llmClient,ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }


    public void run() throws Exception {
        // 1. 从 classpath 读取评估数据集
        InputStream inputStream = getClass()
                .getClassLoader()
                .getResourceAsStream("rag-eval-dataset.json");

        if (inputStream == null) {
            throw new IllegalStateException(
                    "未找到 rag-eval-dataset.json，请检查 src/main/resources/"
            );
        }

        List<RagEvalCase> cases;

        try (inputStream) {
            cases = objectMapper.readValue(
                    inputStream,
                    new TypeReference<List<RagEvalCase>>() {}
            );
        }

        System.out.println("评估用例数量：" + cases.size());

        // 2. 逐条调用 LLM，并评估模型答案
        List<EvalResult> results = new ArrayList<>();

        for (int i = 0; i < cases.size(); i++) {
            RagEvalCase testCase = cases.get(i);

            System.out.printf(
                    "%n[%d/%d] 正在执行：%s%n",
                    i + 1,
                    cases.size(),
                    testCase.id()
            );

            // 2.1 调用大模型生成答案
            String answer = llmClient.generate(
                    testCase.question(),
                    testCase.context()
            );

            // 2.2 使用评估器分析答案
            RagAnswerEvaluator.EvaluationResult evaluation =
                    answerEvaluator.evaluate(testCase, answer);

            // 2.3 将原始答案和评估指标组装到同一个结果对象
            EvalResult result = new EvalResult(
                    testCase.id(),
                    testCase.type(),
                    testCase.question(),
                    testCase.context(),
                    testCase.expectedAnswer(),
                    answer,
                    testCase.keyPoints(),
                    testCase.forbiddenClaims(),

                    // 新增：评估指标
                    evaluation.emptyAnswer(),
                    evaluation.totalKeyPoints(),
                    evaluation.matchedCount(),
                    evaluation.coverageRate(),
                    evaluation.matchedKeyPoints(),
                    evaluation.missingKeyPoints()
            );

            results.add(result);

            // 2.4 打印本条用例的执行和评估结果
            System.out.println("模型答案：");
            System.out.println(answer);

            System.out.printf(
                    "关键点覆盖率：%.2f%%（命中 %d / %d）%n",
                    evaluation.coverageRate() * 100,
                    evaluation.matchedCount(),
                    evaluation.totalKeyPoints()
            );

            System.out.println(
                    "已命中关键点：" + evaluation.matchedKeyPoints()
            );
            System.out.println(
                    "遗漏关键点：" + evaluation.missingKeyPoints()
            );
            System.out.println(
                    "是否为空回答：" + evaluation.emptyAnswer()
            );
        }

        // 3. 保存执行结果，供后续分析
        Path outputPath = Path.of("rag-eval-results.json");

        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(outputPath.toFile(), results);

        System.out.println("\n评估执行完成！");
        System.out.println("结果文件：" + outputPath.toAbsolutePath());
    }



    public record EvalResult(
            String id,
            String type,
            String question,
            String context,
            String expectedAnswer,
            String actualAnswer,
            List<String> keyPoints,
            List<String> forbiddenClaims,

            // 新增：评估指标
            boolean emptyAnswer,
            int totalKeyPoints,
            int matchedCount,
            double coverageRate,
            List<String> matchedKeyPoints,
            List<String> missingKeyPoints
    ) {}
}

