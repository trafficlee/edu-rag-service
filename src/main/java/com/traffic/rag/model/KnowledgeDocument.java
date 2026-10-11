package com.traffic.rag.model;

import java.util.List;

public record KnowledgeDocument(
        String title,
        String content,
        String category,
        String chunkId,
        List<Double> embedding
) {
}