package com.traffic.rag.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traffic.rag.model.EmbeddingRequest;
import com.traffic.rag.model.EmbeddingResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

@Slf4j
@Component
public class EmbeddingClient {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String model;

    public EmbeddingClient(
            ObjectMapper objectMapper,
            @Value("${rag.embedding.base-url}") String baseUrl,
            @Value("${rag.embedding.model}") String model) {

        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.model = model;

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public List<Double> embed(String text) {
        if (text == null || text.isBlank()) {
            log.warn("向量化请求被拒绝：文本为空");
            throw new IllegalArgumentException("待向量化的文本不能为空");
        }

        try {
            log.info("开始向量化，模型: {}, 文本长度: {}", model, text.length());

            EmbeddingRequest payload =
                    new EmbeddingRequest(model, List.of(text));

            String requestBody = objectMapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/embed"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );

            log.info("Ollama 响应状态码: {}", response.statusCode());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.error("Ollama 调用失败，状态码: {}, 响应体: {}",
                        response.statusCode(), response.body());
                throw new IllegalStateException(
                        "Ollama 调用失败，HTTP 状态码：" + response.statusCode()
                );
            }

            EmbeddingResponse result = objectMapper.readValue(
                    response.body(),
                    EmbeddingResponse.class
            );

            if (result.embeddings() == null
                    || result.embeddings().isEmpty()
                    || result.embeddings().get(0) == null) {
                log.error("Ollama 返回的向量为空，响应体: {}", response.body());
                throw new IllegalStateException("Ollama 返回的向量为空");
            }

            List<Double> vector = result.embeddings().get(0);

            if (vector.size() != 1024) {
                log.error("向量维度不符合预期，实际维度: {}", vector.size());
                throw new IllegalStateException(
                        "向量维度不符合预期，实际维度：" + vector.size()
                );
            }

            log.info("向量化成功，维度: {}", vector.size());
            return vector;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("调用 Ollama 时线程被中断", e);
            throw new IllegalStateException("调用 Ollama 时线程被中断", e);
        } catch (java.io.IOException e) {
            log.error("调用 Ollama 或解析响应失败", e);
            throw new IllegalStateException("调用 Ollama 或解析响应失败", e);
        }
    }
}