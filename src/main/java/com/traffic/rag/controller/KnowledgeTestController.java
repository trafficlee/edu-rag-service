
package com.traffic.rag.controller;

import com.traffic.rag.client.EmbeddingClient;
import com.traffic.rag.common.ApiResponse;
import com.traffic.rag.dto.*;
import com.traffic.rag.fusion.RrfFusion;
import com.traffic.rag.model.KnowledgeDocument;
import com.traffic.rag.model.KnowledgeUpdateResult;
import com.traffic.rag.repository.EsDocumentRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeTestController {

    private final EmbeddingClient embeddingClient;
    private final EsDocumentRepository repository;

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

}
