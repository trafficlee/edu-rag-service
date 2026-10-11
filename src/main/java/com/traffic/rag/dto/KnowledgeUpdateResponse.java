
package com.traffic.rag.dto;

public record KnowledgeUpdateResponse(
        String chunkId,
        String status,
        int vectorDimensions,
        long seqNo,
        long primaryTerm
) {}
