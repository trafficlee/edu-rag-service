package com.traffic.rag.model;

import java.util.List;

public record EmbeddingRequest(
        String model,
        List<String> input
) {
}