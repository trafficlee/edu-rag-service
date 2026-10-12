
package com.traffic.rag.controller;

import com.traffic.rag.client.EmbeddingClient;
import com.traffic.rag.common.ApiResponse;
import com.traffic.rag.dto.*;
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
        }; savedId = repository.save(document);

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



}
