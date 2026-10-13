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
public class RerankerClient {

    private static final String RERANK_URL =
            "http://127.0.0.1:8001/rerank";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public RerankerClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * 使用 Python Reranker 对候选文档重新排序。
     *
     * @param query      用户查询
     * @param candidates RRF 融合后的候选文档
     * @return 按 Reranker 相关性分数降序排列的文档
     */
    public List<Map<String, Object>> rerank(
            String query,
            List<Map<String, Object>> candidates) throws IOException {

        log.info("开始重排序，查询词: {}, 候选文档数: {}", query, candidates != null ? candidates.size() : 0);

        if (candidates == null || candidates.isEmpty()) {
            log.warn("候选文档列表为空，跳过重排序");
            return List.of();
        }

        List<String> documents = candidates.stream()
                .map(this::toRerankText)
                .toList();

        Map<String, Object> requestBody = Map.of(
                "query", query,
                "documents", documents
        );

        String requestJson =
                objectMapper.writeValueAsString(requestBody);

        log.info("发送 Reranker 请求，URL: {}, Payload: {}", RERANK_URL, requestJson);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(RERANK_URL))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(
                        requestJson, StandardCharsets.UTF_8))
                .build();

        final HttpResponse<String> response;

        try {
            log.info("正在调用 Reranker 服务...");
            response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(
                            StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            log.error("Reranker 调用被中断", e);
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "调用 Reranker 服务被中断", e);
        }

        log.info("Reranker 响应状态码: {}", response.statusCode());

        if (response.statusCode() != 200) {
            log.error("Reranker 调用失败，状态码: {}, 响应体: {}",
                    response.statusCode(), response.body());
            throw new IllegalStateException(
                    "Reranker 调用失败，HTTP "
                            + response.statusCode()
                            + ": " + response.body());
        }

        Map<String, Object> responseBody =
                objectMapper.readValue(
                        response.body(),
                        new TypeReference<Map<String, Object>>() {});

        Object resultsObject = responseBody.get("results");

        if (!(resultsObject instanceof List<?> rerankResults)) {
            log.error("Reranker 响应格式异常，缺少 results 列表: {}", response.body());
            throw new IllegalStateException(
                    "Reranker 响应缺少合法的 results 列表");
        }

        List<Map<String, Object>> finalResults =
                new ArrayList<>();

        for (Object item : rerankResults) {
            if (!(item instanceof Map<?, ?> rerankResult)) {
                log.warn("跳过非 Map 类型的 Reranker 结果项: {}", item);
                continue;
            }

            Object indexObject = rerankResult.get("index");
            Object scoreObject = rerankResult.get("score");
            Object rankObject = rerankResult.get("rank");

            if (!(indexObject instanceof Number indexNumber)
                    || !(scoreObject instanceof Number scoreNumber)
                    || !(rankObject instanceof Number rankNumber)) {
                log.warn("跳过字段缺失或类型错误的 Reranker 结果项: {}", rerankResult);
                continue;
            }

            int index = indexNumber.intValue();

            if (index < 0 || index >= candidates.size()) {
                log.warn("Reranker 返回的索引 {} 超出候选列表范围 (0-{})", index, candidates.size() - 1);
                continue;
            }

            Map<String, Object> original = candidates.get(index);
            Map<String, Object> result =
                    new LinkedHashMap<>(original);

            result.put("rerankerScore", scoreNumber.doubleValue());
            result.put("rerankerRank", rankNumber.intValue());

            finalResults.add(result);
        }

        log.info("重排序完成，返回结果数: {}", finalResults.size());
        return finalResults;
    }

    @SuppressWarnings("unchecked")
    private String toRerankText(Map<String, Object> hit) {
        Object sourceObject = hit.get("source");

        Map<String, Object> source =
                sourceObject instanceof Map<?, ?>
                        ? (Map<String, Object>) sourceObject
                        : hit;

        String title = String.valueOf(
                source.getOrDefault("title", ""));

        String content = String.valueOf(
                source.getOrDefault("content", ""));

        return "标题：" + title + "\n正文：" + content;
    }
}