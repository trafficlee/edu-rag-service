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

        String systemPrompt = """
                你是一个严谨的中文教育问答助手。请严格遵守以下规则：
                
                1. 只能依据用户提供的参考资料回答，不得使用资料中没有的事实补全答案。
                2. 如果问题包含多个子问题，必须逐个判断每个子问题是否有资料支持，不能因为其中一个子问题缺少资料，就拒绝回答其他有依据的部分。
                3. 对于资料明确支持的内容，直接回答。
                4. 对于资料只提供概括性信息、缺少具体数字或细节的内容，可以回答资料明确提到的部分，但必须指出哪些具体信息无法从现有资料中确认。
                5. 对于资料完全没有提供的信息，明确说明“现有资料未提供相关信息”，不得猜测、编造或用模型自身知识补全。
                6. 不得把“符合某项要求”擅自改写为“必须完全一致”等更强的结论。
                7. 使用简体中文，回答清晰、简洁。只给出结论和必要说明，不输出思考过程。
                
                请始终区分“资料明确说明的事实”和“现有资料无法确认的信息”。
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
