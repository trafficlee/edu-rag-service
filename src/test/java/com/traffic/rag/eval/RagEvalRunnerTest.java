package com.traffic.rag.eval;


import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traffic.rag.client.LlmClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagEvalRunnerTest {

    private static final String RESULTS_FILE =
            "rag-eval-results.json";

    private final ObjectMapper objectMapper =
            new ObjectMapper();

    @Mock
    private LlmClient llmClient;

    private RagEvalRunner runner;

    @BeforeEach
    void setUp() throws IOException {
        // 每个测试开始前，清理上一次遗留的结果文件
        Files.deleteIfExists(Path.of(RESULTS_FILE));
        ObjectMapper objectMapper = new ObjectMapper();
        runner = new RagEvalRunner(llmClient,objectMapper);
    }

    @AfterEach
    void tearDown() throws IOException {
        // 每个测试结束后清理结果文件
        Files.deleteIfExists(Path.of(RESULTS_FILE));
    }

    @Test
    @DisplayName("正常执行：读取数据集、调用模型、生成结果文件")
    void run_shouldLoadDatasetCallLlmAndWriteResults()
            throws Exception {

        // given：模拟模型对所有问题返回固定答案
        when(llmClient.generate(anyString(), anyString()))
                .thenReturn("mock 生成的答案");

        // when：执行评估任务
        runner.run();

        // then 1：读取原始评估数据集
        List<RagEvalCase> dataset;

        try (InputStream inputStream = getClass()
                .getClassLoader()
                .getResourceAsStream("rag-eval-dataset.json")) {

            assertThat(inputStream)
                    .as("评估数据集必须存在")
                    .isNotNull();

            dataset = objectMapper.readValue(
                    inputStream,
                    new TypeReference<List<RagEvalCase>>() {}
            );
        }

        assertThat(dataset).hasSize(6);

        // then 2：模型调用次数与数据集条数一致
        verify(llmClient, times(dataset.size()))
                .generate(anyString(), anyString());

        // then 3：捕获每次调用的问题和上下文
        ArgumentCaptor<String> questionCaptor =
                ArgumentCaptor.forClass(String.class);

        ArgumentCaptor<String> contextCaptor =
                ArgumentCaptor.forClass(String.class);

        verify(llmClient, times(dataset.size())).generate(
                questionCaptor.capture(),
                contextCaptor.capture()
        );

        // 每个问题都必须与数据集中的原始问题一致
        assertThat(questionCaptor.getAllValues())
                .containsExactlyElementsOf(
                        dataset.stream()
                                .map(RagEvalCase::question)
                                .toList()
                );

        // 每份上下文都必须与数据集中的原始上下文一致
        assertThat(contextCaptor.getAllValues())
                .containsExactlyElementsOf(
                        dataset.stream()
                                .map(RagEvalCase::context)
                                .toList()
                );

        // then 4：结果文件必须生成且非空
        Path resultPath = Path.of(RESULTS_FILE);

        assertThat(resultPath).exists();
        assertThat(Files.size(resultPath)).isGreaterThan(0);

        // then 5：结果文件必须能够反序列化
        List<RagEvalRunner.EvalResult> results =
                objectMapper.readValue(
                        resultPath.toFile(),
                        new TypeReference<
                                List<RagEvalRunner.EvalResult>
                                >() {}
                );

        assertThat(results).hasSize(6);

        // then 6：结果顺序、ID 和模型答案必须正确
        assertThat(results)
                .extracting(RagEvalRunner.EvalResult::id)
                .containsExactlyElementsOf(
                        dataset.stream()
                                .map(RagEvalCase::id)
                                .toList()
                );

        assertThat(results)
                .extracting(
                        RagEvalRunner.EvalResult::actualAnswer
                )
                .containsOnly("mock 生成的答案");
    }

    @Test
    @DisplayName("模型调用失败：异常向上传递且不生成结果文件")
    void run_shouldPropagateExceptionWhenLlmFails()
            throws Exception {

        // given：模拟 Ollama 调用失败
        when(llmClient.generate(anyString(), anyString()))
                .thenThrow(
                        new RuntimeException("Ollama 不可用")
                );

        // when：执行任务并验证异常
        RuntimeException ex = assertThrows(
                RuntimeException.class,
                () -> runner.run()
        );

        // then 1：异常信息符合预期
        assertThat(ex)
                .hasMessage("Ollama 不可用");

        // then 2：第一条调用失败后，循环不再继续
        verify(llmClient, times(1))
                .generate(anyString(), anyString());

        // then 3：由于结果文件只在循环结束后写入，
        // 本次执行不应该产生结果文件
        assertThat(Path.of(RESULTS_FILE)).doesNotExist();
    }

    @Test
    @DisplayName("模型返回 null：当前实现仍然保存结果")
    void run_shouldWriteResultsEvenWhenLlmReturnsNull()
            throws Exception {

        // given：模拟模型返回 null
        when(llmClient.generate(anyString(), anyString()))
                .thenReturn(null);

        // when：执行任务
        runner.run();

        // then 1：6 条用例均被调用
        verify(llmClient, times(6))
                .generate(anyString(), anyString());

        // then 2：结果文件正常生成
        Path resultPath = Path.of(RESULTS_FILE);

        assertThat(resultPath).exists();
        assertThat(Files.size(resultPath)).isGreaterThan(0);

        // then 3：6 条结果都存在，答案字段为 null
        List<RagEvalRunner.EvalResult> results =
                objectMapper.readValue(
                        resultPath.toFile(),
                        new TypeReference<
                                List<RagEvalRunner.EvalResult>
                                >() {}
                );

        assertThat(results).hasSize(6);

        assertThat(results)
                .extracting(
                        RagEvalRunner.EvalResult::actualAnswer
                )
                .containsOnlyNulls();
    }
}