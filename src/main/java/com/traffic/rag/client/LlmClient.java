package com.traffic.rag.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class LlmClient {

    private static final String CHAT_URL =
            "http://localhost:11434/api/chat";

    private static final String MODEL =
            "qwen3.5:2b-q4_K_M";

    private static final String DEFAULT_SYSTEM_PROMPT = """
        你是一个严谨的中文教育问答助手。请严格遵守以下规则：

        1. 只能依据用户提供的参考资料回答，不得使用资料中没有的事实补全答案。
        2. 如果问题包含多个子问题，必须逐个判断每个子问题是否有资料支持。
        3. 对于资料明确支持的内容，直接回答。
        4. 对于资料缺少具体数字或细节的内容，明确指出无法确认的部分。
        5. 对于资料完全没有提供的信息，明确说明“现有资料未提供相关信息”。
        6. 不得擅自强化资料中的结论。
        7. 使用简体中文，回答清晰、简洁，不输出思考过程。
        """;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public LlmClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * 根据给定上下文生成回答。
     *
     * @param question 用户问题
     * @param context  检索与上下文构建阶段提供的资料
     * @return 模型生成的最终回答
     */
    public String generate(String question, String context)
            throws IOException {
        return generate(question, context, DEFAULT_SYSTEM_PROMPT);
    }

    public String generate(
            String question,
            String context,
            String systemPrompt
    ) throws IOException {
        log.info("开始生成回答，问题长度: {}, 上下文长度: {}",
                question != null ? question.length() : 0,
                context != null ? context.length() : 0);

        if (question == null || question.isBlank()) {
            log.error("生成失败：question 不能为空");
            throw new IllegalArgumentException("question 不能为空");
        }

        if (context == null || context.isBlank()) {
            log.error("生成失败：context 不能为空");
            throw new IllegalArgumentException("context 不能为空");
        }

        String userPrompt = """
                请根据以下资料回答问题。

                【参考资料】
                %s

                【用户问题】
                %s
                """.formatted(context, question);

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", MODEL);

        List<Map<String, String>> messages = new ArrayList<>();

        messages.add(Map.of(
                "role", "system",
                "content", systemPrompt
        ));

        messages.add(Map.of(
                "role", "user",
                "content", userPrompt
        ));

        requestBody.put("messages", messages);
        requestBody.put("stream", false);
        requestBody.put("think", false);

        requestBody.put("options", Map.of(
                "temperature", 0.2,
                "num_predict", 512
        ));

        String requestJson =
                objectMapper.writeValueAsString(requestBody);

        log.info("发送 LLM 请求，URL: {}, Payload: {}", CHAT_URL, requestJson);
        //完整的

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(CHAT_URL))
                .timeout(Duration.ofSeconds(120))
                .header(
                        "Content-Type",
                        "application/json; charset=utf-8"
                )
                .POST(HttpRequest.BodyPublishers.ofString(
                        requestJson,
                        StandardCharsets.UTF_8
                ))
                .build();

        final HttpResponse<String> response;

        try {
            log.info("正在调用 LLM 服务...");
            response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(
                            StandardCharsets.UTF_8
                    )
            );
        } catch (InterruptedException e) {
            log.error("LLM 调用被中断", e);
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "调用 Ollama 被中断", e
            );
        }

        log.info("LLM 响应状态码: {}", response.statusCode());

        if (response.statusCode() != 200) {
            log.error("LLM 调用失败，状态码: {}, 响应体: {}",
                    response.statusCode(), response.body());
            throw new IllegalStateException(
                    "Ollama 调用失败，HTTP "
                            + response.statusCode()
                            + ": " + response.body()
            );
        }

        Map<String, Object> responseBody =
                objectMapper.readValue(
                        response.body(),
                        new TypeReference<Map<String, Object>>() {}
                );

        Object messageObject = responseBody.get("message");

        if (!(messageObject instanceof Map<?, ?> message)) {
            log.error("LLM 响应格式异常，缺少 message 对象: {}", response.body());
            throw new IllegalStateException(
                    "Ollama 响应缺少 message 对象"
            );
        }

        Object contentObject = message.get("content");

        if (!(contentObject instanceof String answer)
                || answer.isBlank()) {
            log.error("LLM 未返回有效答案，响应体: {}", response.body());
            throw new IllegalStateException(
                    "Ollama 未返回最终答案，响应为："
                            + response.body()
            );
        }

        String finalAnswer = answer.trim();
        log.info("LLM 回答生成成功，回答长度: {}", finalAnswer.length());
        log.info("LLM 回答内容: {}", finalAnswer);

        return finalAnswer;
    }
}
