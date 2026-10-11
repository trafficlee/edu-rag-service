package com.traffic.rag.dto;

import java.util.List;

public record KnowledgeQueryResponse(
        String chunkId,
        String title,
        String content,
        String category,
        List<Double> embedding,
        long seqNo,
        long primaryTerm
) {}