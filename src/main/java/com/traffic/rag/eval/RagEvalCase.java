package com.traffic.rag.eval;


import java.util.List;

public record RagEvalCase(
        String id,
        String type,
        String question,
        String context,
        String expectedAnswer,
        List<String> keyPoints,
        List<String> forbiddenClaims
) {
}

