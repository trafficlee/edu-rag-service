package com.traffic.rag.controller;

import com.traffic.rag.client.EmbeddingClient;
import com.traffic.rag.client.LlmClient;
import com.traffic.rag.client.RerankerClient;
import com.traffic.rag.context.ContextBuilder;
import com.traffic.rag.fusion.RrfFusion;
import com.traffic.rag.repository.EsDocumentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/rag-test")
public class RagTestController {

    private final EsDocumentRepository repository;
    private final EmbeddingClient embeddingClient;
    private final RerankerClient rerankerClient;
    private final ContextBuilder contextBuilder;
    private final LlmClient llmClient;

    public RagTestController(
            EsDocumentRepository repository,
            EmbeddingClient embeddingClient,
            RerankerClient rerankerClient,
            ContextBuilder contextBuilder,
            LlmClient llmClient) {
        this.repository = repository;
        this.embeddingClient = embeddingClient;
        this.rerankerClient = rerankerClient;
        this.contextBuilder = contextBuilder;
        this.llmClient = llmClient;
    }

    @GetMapping("/ask")
    public Map<String, Object> ask(
            @RequestParam("question") String question,
            @RequestParam(defaultValue = "5") int topK,
            @RequestParam(defaultValue = "2000") int maxChars)
            throws IOException {

        log.info("RAG 问答开始，问题长度: {}, topK: {}, maxChars: {}",
                question.length(), topK, maxChars);

        // 1. BM25 关键词召回
        List<Map<String, Object>> bm25Results =
                repository.bm25Search(question, 10);

        // 2. Dense 向量召回
        List<Double> embedding =
                embeddingClient.embed(question);

        List<Float> queryVector = embedding.stream()
                .map(Double::floatValue)
                .toList();

        List<Map<String, Object>> knnResults =
                repository.knnSearch(queryVector, 30, 100);

        // 3. RRF 融合
        RrfFusion fusion = new RrfFusion(60);

        List<Map<String, Object>> candidates =
                fusion.fuse(bm25Results, knnResults, 30);

        // 4. Reranker 精排
        List<Map<String, Object>> rerankResults =
                rerankerClient.rerank(question, candidates);

        // 5. 构建上下文
        Map<String, Object> contextResult =
                contextBuilder.build(rerankResults, topK, maxChars);

        String context = String.valueOf(
                contextResult.getOrDefault("context", ""));

        // 6. 无有效资料时不调用 LLM，避免模型自行编造
        String answer;

        if (context.isBlank()) {
            log.warn("未检索到有效资料，跳过 LLM 调用，问题长度: {}", question.length());
            answer = "当前知识库没有检索到符合审核条件的有效资料，暂时无法回答。";
        } else {
            answer = llmClient.generate(question, context);
        }

        log.info("RAG 问答完成，bm25: {}, knn: {}, 候选: {}, 精排: {}, 回答长度: {}",
                bm25Results.size(), knnResults.size(), candidates.size(),
                rerankResults.size(), answer.length());

        // 7. 返回答案和检索调试信息
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("question", question);
        result.put("answer", answer);
        result.put("model", "qwen3.5:2b-q4_K_M");

        result.put("bm25Count", bm25Results.size());
        result.put("knnCount", knnResults.size());
        result.put("candidateCount", candidates.size());
        result.put("rerankCount", rerankResults.size());
        result.put("rankingSource", "RERANKER");

        result.putAll(contextResult);

        return result;
    }
}