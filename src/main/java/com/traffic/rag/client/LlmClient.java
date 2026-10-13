package com.traffic.rag.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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

@Component
public class LlmClient {

    private static final String CHAT_URL =
            "http://localhost:11434/api/chat";

    private static final String MODEL =
            "qwen3.5:2b-q4_K_M";

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

        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question 不能为空");
        }

        if (context == null || context.isBlank()) {
            throw new IllegalArgumentException("context 不能为空");
        }

        String systemPrompt = """
                你是一个中文教育问答助手。
                请使用简体中文回答。
                只能依据用户提供的上下文回答问题。
                不得编造上下文中没有的事实。
                如果资料不足以回答问题，必须明确说明资料不足。
                回答应简洁、清晰，直接给出结论。
                不要输出思考过程。
                """;

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
            response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(
                            StandardCharsets.UTF_8
                    )
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "调用 Ollama 被中断", e
            );
        }

        if (response.statusCode() != 200) {
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
            throw new IllegalStateException(
                    "Ollama 响应缺少 message 对象"
            );
        }

        Object contentObject = message.get("content");

        if (!(contentObject instanceof String answer)
                || answer.isBlank()) {
            throw new IllegalStateException(
                    "Ollama 未返回最终答案，响应为："
                            + response.body()
            );
        }

        return answer.trim();
    }
}
