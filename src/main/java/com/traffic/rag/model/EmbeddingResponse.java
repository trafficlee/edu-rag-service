package com.traffic.rag.model;

import java.util.List;

public record EmbeddingResponse(
        List<List<Double>> embeddings
) {
}