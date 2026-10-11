
package com.traffic.rag.model;

public record KnowledgeUpdateResult(
        String chunkId,
        long seqNo,
        long primaryTerm
) {}
