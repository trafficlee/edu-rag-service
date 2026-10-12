package com.traffic.rag.controller;

import com.traffic.rag.client.EmbeddingClient;
import com.traffic.rag.client.RerankerClient;
import com.traffic.rag.context.ContextBuilder;
import com.traffic.rag.fusion.RrfFusion;
import com.traffic.rag.repository.EsDocumentRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/context-test")
public class ContextTestController {

    private final EsDocumentRepository repository;
    private final EmbeddingClient embeddingClient;
    private final RerankerClient rerankerClient;
    private final ContextBuilder contextBuilder;

    public ContextTestController(
            EsDocumentRepository repository,
            EmbeddingClient embeddingClient,
            RerankerClient rerankerClient,
            ContextBuilder contextBuilder) {
        this.repository = repository;
        this.embeddingClient = embeddingClient;
        this.rerankerClient = rerankerClient;
        this.contextBuilder = contextBuilder;
    }

    @GetMapping("/build")
    public Map<String, Object> buildContext(
            @RequestParam("text") String text,
            @RequestParam(defaultValue = "5") int topK,
            @RequestParam(defaultValue = "2000") int maxChars)
            throws IOException {

        // 1. BM25 关键词召回 Top10
        List<Map<String, Object>> bm25Results =
                repository.bm25Search(text, 10);

        // 2. Dense kNN 向量召回 Top30
        List<Double> embedding = embeddingClient.embed(text);

        List<Float> queryVector = embedding.stream()
                .map(Double::floatValue)
                .toList();

        List<Map<String, Object>> knnResults =
                repository.knnSearch(queryVector, 30, 100);

        // 3. RRF 融合，保留最多 30 个候选
        RrfFusion fusion = new RrfFusion(60);

        List<Map<String, Object>> candidates =
                fusion.fuse(bm25Results, knnResults, 30);

        // 4. Reranker 对候选文档重新排序
        List<Map<String, Object>> rerankResults =
                rerankerClient.rerank(text, candidates);

        // 5. 从精排结果中选取 Top-K 并组装 Context
        Map<String, Object> result =
                contextBuilder.build(rerankResults, topK, maxChars);

        // 6. 补充调试信息
        result.put("query", text);
        result.put("bm25Count", bm25Results.size());
        result.put("knnCount", knnResults.size());
        result.put("candidateCount", candidates.size());
        result.put("rerankCount", rerankResults.size());
        result.put("rankingSource", "RERANKER");

        return result;
    }
}