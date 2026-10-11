package com.traffic.rag.dto;

public record KnowledgeIngestResponse(
        String chunkId,
        String status,
        int vectorDimensions
) {
}