
package com.traffic.rag.repository;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.core.IndexResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.traffic.rag.common.DocumentAlreadyExistsException;
import com.traffic.rag.common.OptimisticLockException;
import com.traffic.rag.dto.KnowledgeQueryResponse;
import com.traffic.rag.model.KnowledgeDocument;
import com.traffic.rag.model.KnowledgeUpdateResult;
import org.elasticsearch.client.ResponseException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class EsDocumentRepository {

    private final ElasticsearchClient client;
    private final String indexName;

    public EsDocumentRepository(
            ElasticsearchClient client,
            @Value("${rag.elasticsearch.index}") String indexName) {
        this.client = client;
        this.indexName = indexName;
    }

    /**
     * 仅创建文档：ID 已存在时由 Elasticsearch 返回冲突。
     */
    public String create(KnowledgeDocument document) throws IOException {
        try {
            IndexResponse response = client.index(request -> request
                    .index(indexName)
                    .id(document.chunkId())
                    .opType(co.elastic.clients.elasticsearch._types.OpType.Create)
                    .document(toSource(document)));

            return response.id();

        } catch (ResponseException ex) {
            if (ex.getResponse().getStatusLine().getStatusCode() == 409) {
                throw new DocumentAlreadyExistsException(document.chunkId());
            }

            throw ex;
        }
    }

    /**
     * 创建或更新：ID 不存在则创建，存在则替换文档。
     */
    public String upsert(KnowledgeDocument document) throws IOException {
        IndexResponse response = client.index(request -> request
                .index(indexName)
                .id(document.chunkId())
                .document(toSource(document)));

        return response.id();
    }


    /**
     * 基于读取时的版本执行乐观并发控制更新。
     */
    public KnowledgeUpdateResult updateWithOCC(
            KnowledgeDocument document,
            long seqNo,
            long primaryTerm) throws IOException{

        try {
            IndexResponse response = client.index(request -> request
                    .index(indexName)
                    .id(document.chunkId())
                    .ifSeqNo(seqNo)
                    .ifPrimaryTerm(primaryTerm)
                    .document(toSource(document)));

            return new KnowledgeUpdateResult(
                    response.id(),
                    response.seqNo(),
                    response.primaryTerm()
            );
        } catch (ResponseException ex) {
            if (ex.getResponse().getStatusLine().getStatusCode() == 409) {
                throw new OptimisticLockException(document.chunkId());
            }
            throw ex;
        }
    }

    public KnowledgeQueryResponse findById(String chunkId)
            throws IOException {

        GetResponse<Map> response = client.get(
                request -> request
                        .index(indexName)
                        .id(chunkId),
                Map.class
        );

        if (!response.found()) {
            return null;
        }

        Map<?, ?> source = response.source();

        return new KnowledgeQueryResponse(
                chunkId,
                (String) source.get("title"),
                (String) source.get("content"),
                (String) source.get("category"),
                (List<Double>) source.get("embedding"),
                response.seqNo(),
                response.primaryTerm()
        );
    }


    public List<Map<String, Object>> knnSearch(
            List<Float> queryVector,
            int k,
            int numCandidates
    ) throws IOException {


        SearchResponse<Map> response = client.search(
                request -> request
                        .index(indexName)
                        .size(k)
                        .source(source -> source.filter(filter -> filter
                                .includes(
                                        "chunk_id",
                                        "title",
                                        "content",
                                        "category"
                                )//_source 过滤：只决定响应中返回哪些原始文档字段。
                        ))
                        .knn(knn -> knn
                                .field("embedding")
                                .queryVector(queryVector)
                                .k(k)
                                .numCandidates(numCandidates)
                        ),
                Map.class
        );


        List<Map<String, Object>> results = new ArrayList<>();

        for (Hit<Map> hit : response.hits().hits()) {
            Map<String, Object> item = new HashMap<>();

            item.put("id", hit.id());
            item.put("score", hit.score());
            item.put("source", hit.source());

            results.add(item);
        }

        return results;
    }




    /**
     * 保留原有 save 方法，兼容现有调用。
     */
    public String save(KnowledgeDocument document) throws IOException {
        return upsert(document);
    }

    private Map<String, Object> toSource(KnowledgeDocument document) {
        Map<String, Object> source = new HashMap<>();
        source.put("title", document.title());
        source.put("content", document.content());
        source.put("category", document.category());
        source.put("chunk_id", document.chunkId());
        source.put("embedding", document.embedding());
        return source;
    }
}
