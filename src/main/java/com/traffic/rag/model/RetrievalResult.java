package com.traffic.rag.model;


import java.util.Map;

public record RetrievalResult(
        String id,
        Double score,
        Map<String, Object> source
) {
}

