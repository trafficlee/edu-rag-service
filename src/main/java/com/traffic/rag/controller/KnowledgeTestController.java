
package com.traffic.rag.controller;

import com.traffic.rag.client.EmbeddingClient;
import com.traffic.rag.common.ApiResponse;
import com.traffic.rag.dto.*;
import com.traffic.rag.fusion.RrfFusion;
import com.traffic.rag.model.KnowledgeDocument;
import com.traffic.rag.model.KnowledgeUpdateResult;
import com.traffic.rag.repository.EsDocumentRepository;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeTestController {

    private final EmbeddingClient embeddingClient;
    private final EsDocumentRepository repository;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String RERANK_URL =
            "http://127.0.0.1:8001/rerank";

    public KnowledgeTestController(
            EmbeddingClient embeddingClient,
            EsDocumentRepository repository) {
        this.embeddingClient = embeddingClient;
        this.repository = repository;
    }

    @PostMapping("/test")
    public ApiResponse<KnowledgeIngestResponse> test(
            @Valid @RequestBody KnowledgeIngestRequest request)
            throws IOException {

        String chunkId = request.chunkId();

        List<Double> vector = embeddingClient.embed(request.content());

        KnowledgeDocument document = new KnowledgeDocument(
                request.title(),
                request.content(),
                request.category(),
                chunkId,
                vector
        );

        String savedId = switch (request.mode()) {
            case CREATE -> repository.create(document);
            case UPSERT -> repository.upsert(document);
        };

        log.info("知识写入成功，chunkId: {}, mode: {}, 向量维度: {}",
                chunkId, request.mode(), vector.size());

        KnowledgeIngestResponse data = new KnowledgeIngestResponse(
                savedId,
                "SUCCESS",
                vector.size()
        );

        return ApiResponse.success(data);
    }


    @PutMapping("/update")
    public ApiResponse<KnowledgeUpdateResponse> updateWithOCC(
            @Valid @RequestBody KnowledgeUpdateRequest request)
            throws IOException {

        // 1. 重新生成当前内容对应的向量
        List<Double> embedding = embeddingClient.embed(request.content());

        // 2. 构造完整知识文档
        KnowledgeDocument document = new KnowledgeDocument(
                request.title(),
                request.content(),
                request.category(),
                request.chunkId(),
                embedding
        );

        // 3. 使用客户端提交的版本条件执行更新
        KnowledgeUpdateResult result = repository.updateWithOCC(
                document,
                request.seqNo(),
                request.primaryTerm()
        );

        log.info("知识乐观锁更新成功，chunkId: {}, 新 seqNo: {}, 新 primaryTerm: {}",
                result.chunkId(), result.seqNo(), result.primaryTerm());

        // 4. 返回统一响应
        return ApiResponse.success(new KnowledgeUpdateResponse(
                result.chunkId(),
                "SUCCESS",
                embedding.size(),
                result.seqNo(),
                result.primaryTerm()
        ));
    }

    @GetMapping("/{chunkId}")
    public ApiResponse<KnowledgeQueryResponse> getById(
            @PathVariable String chunkId) throws IOException {

        KnowledgeQueryResponse result = repository.findById(chunkId);

        if (result == null) {
            log.warn("知识切片不存在，chunkId: {}", chunkId);
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "知识切片不存在，chunkId=" + chunkId
            );
        }

        return ApiResponse.success(result);
    }


    @GetMapping("/embedding")
    public ApiResponse<List<Double>> getEmbedding(
            @RequestParam String text) {

        List<Double> vector = embeddingClient.embed(text);
        return ApiResponse.success(vector);
    }


    @GetMapping("/knn-test")
    public ApiResponse<List<Map<String, Object>>> knnTest(
            @RequestParam String text
    ) throws IOException {

        List<Double> queryVector = embeddingClient.embed(text);

        List<Float> floatVector = queryVector.stream()
                .map(Double::floatValue)
                .toList();

        List<Map<String, Object>> results =
                repository.knnSearch(floatVector, 10, 100);

        return ApiResponse.success(results);
    }

    @GetMapping("/bm25-test")
    public ApiResponse<List<Map<String, Object>>> bm25Test(
            @RequestParam String text) throws IOException {

        List<Map<String, Object>> results =
                repository.bm25Search(text, 10);

        return ApiResponse.success(results);
    }


    @GetMapping("/rrf-demo")
    public List<Map<String, Object>> rrfDemo() {

        List<Map<String, Object>> bm25 = List.of(
                Map.of("id", "A", "source", Map.of("title", "报名条件")),
                Map.of("id", "B", "source", Map.of("title", "报名材料")),
                Map.of("id", "C", "source", Map.of("title", "工作年限"))
        );

        List<Map<String, Object>> knn = List.of(
                Map.of("id", "B", "source", Map.of("title", "报名材料")),
                Map.of("id", "A", "source", Map.of("title", "报名条件")),
                Map.of("id", "D", "source", Map.of("title", "资格审核"))
        );

        RrfFusion fusion = new RrfFusion(60);

        return fusion.fuse(bm25, knn, 10);
    }


    @GetMapping("/rrf-test")
    public List<Map<String, Object>> rrfTest(@RequestParam("text") String text) throws IOException {

        // 1. BM25 关键词召回
        List<Map<String, Object>> bm25Results =
                repository.bm25Search(text, 10);

        // 2. Embedding 向量化
        List<Double> embedding = embeddingClient.embed(text);

        List<Float> queryVector = embedding.stream()
                .map(Double::floatValue)
                .toList();

        // 3. kNN 语义召回
        List<Map<String, Object>> knnResults =
                repository.knnSearch(queryVector, 10, 100);

        // 4. RRF 排名融合
        RrfFusion fusion = new RrfFusion(60);

        return fusion.fuse(
                bm25Results,
                knnResults,
                10
        );
    }


    @GetMapping("/embedding-compare")
    public ApiResponse<Map<String, Double>> embeddingCompare() {
        String query = "一级建造师报名需要满足哪些条件";

        // 文档 1：正式的报名条件文档
        String title1 = "一级建造师考试报名条件";
        String content1 = "报考一级建造师需要满足相应的学历、专业和工作年限要求。"
                + "考生应根据自己的学历背景、所学专业和工作经历，核对当年度官方考试报名条件。";

        // 文档 4：测试文档
        String title4 = "测试文档";
        String content4 = "一级建造师报名条件测试";

        List<Double> queryVector = embeddingClient.embed(query);

        List<Double> a1 = embeddingClient.embed(content1);
        List<Double> b1 = embeddingClient.embed(title1 + "\n" + content1);

        List<Double> a4 = embeddingClient.embed(content4);
        List<Double> b4 = embeddingClient.embed(title4 + "\n" + content4);

        Map<String, Double> result = new LinkedHashMap<>();
        result.put("jz1_001_content_only",
                cosineSimilarity(queryVector, a1));
        result.put("jz1_001_title_plus_content",
                cosineSimilarity(queryVector, b1));
        result.put("jz1_004_content_only",
                cosineSimilarity(queryVector, a4));
        result.put("jz1_004_title_plus_content",
                cosineSimilarity(queryVector, b4));

        return ApiResponse.success(result);
    }

    private double cosineSimilarity(List<Double> a, List<Double> b) {
        if (a.size() != b.size()) {
            throw new IllegalArgumentException("向量维度不一致");
        }

        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < a.size(); i++) {
            dot += a.get(i) * b.get(i);
            normA += a.get(i) * a.get(i);
            normB += b.get(i) * b.get(i);
        }

        if (normA == 0.0 || normB == 0.0) {
            throw new IllegalArgumentException("向量模长不能为 0");
        }

        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    @GetMapping("/rrf-test-30")
    public List<Map<String, Object>> rrfTest30(
            @RequestParam("text") String text) throws IOException {

        List<Map<String, Object>> bm25Results =
                repository.bm25Search(text, 10);

        List<Double> embedding = embeddingClient.embed(text);
        List<Float> queryVector = embedding.stream()
                .map(Double::floatValue)
                .toList();

        // 将向量召回候选数从 10 增加到 30
        List<Map<String, Object>> knnResults =
                repository.knnSearch(queryVector, 30, 100);

        RrfFusion fusion = new RrfFusion(60);
        return fusion.fuse(bm25Results, knnResults, 10);
    }


    @GetMapping("/rrf-rerank-test")
    public List<Map<String, Object>> rrfRerankTest(
            @RequestParam("text") String text) throws IOException {

        // 1. BM25 召回 Top10
        List<Map<String, Object>> bm25Results =
                repository.bm25Search(text, 10);

        // 2. Dense kNN 召回 Top30
        List<Double> embedding = embeddingClient.embed(text);
        List<Float> queryVector = embedding.stream()
                .map(Double::floatValue)
                .toList();

        List<Map<String, Object>> knnResults =
                repository.knnSearch(queryVector, 30, 100);

        // 3. RRF 融合：保留最多 30 个候选，供 Reranker 重排
        RrfFusion fusion = new RrfFusion(60);
        List<Map<String, Object>> candidates =
                fusion.fuse(bm25Results, knnResults, 30);

        if (candidates.isEmpty()) {
            log.info("RRF 融合后无候选文档，跳过重排序");
            return List.of();
        }

        // 4. 提取候选文档文本，保留原始候选列表用于结果映射
        List<String> documents = candidates.stream()
                .map(this::toRerankText)
                .toList();

        Map<String, Object> requestBody = Map.of(
                "query", text,
                "documents", documents
        );

        // 5. 调用本地 Python Reranker
        String requestJson = objectMapper.writeValueAsString(requestBody);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(RERANK_URL))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(
                        requestJson, StandardCharsets.UTF_8))
                .build();

        final HttpResponse<String> response;

        try {
            response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(
                            StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("调用 Reranker 服务被中断", e);
            throw new IllegalStateException(
                    "调用 Reranker 服务被中断", e);
        }

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

        List<Map<String, Object>> rerankResults =
                (List<Map<String, Object>>) responseBody.get("results");

        // 6. 根据原始 index 找回候选文档，保留 ID 和 RRF 分数
        List<Map<String, Object>> finalResults = new ArrayList<>();

        for (Map<String, Object> rerankResult : rerankResults) {
            int index = ((Number) rerankResult.get("index")).intValue();

            if (index < 0 || index >= candidates.size()) {
                continue;
            }

            Map<String, Object> original = candidates.get(index);
            Map<String, Object> result = new LinkedHashMap<>(original);

            result.put(
                    "rerankerScore",
                    ((Number) rerankResult.get("score")).doubleValue());

            result.put(
                    "rerankerRank",
                    ((Number) rerankResult.get("rank")).intValue());

            finalResults.add(result);
        }

        // Python 已按 Reranker 分数降序返回，取最终 Top10
        return finalResults.stream()
                .limit(10)
                .toList();
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

        // 标题和正文一起交给 Reranker 判断相关性
        return "标题：" + title + "\n正文：" + content;
    }



}
