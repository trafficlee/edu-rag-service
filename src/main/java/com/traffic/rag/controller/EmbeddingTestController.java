package com.traffic.rag.controller;

import com.traffic.rag.client.EmbeddingClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/embedding")
public class EmbeddingTestController {

    private final EmbeddingClient embeddingClient;

    public EmbeddingTestController(EmbeddingClient embeddingClient) {
        this.embeddingClient = embeddingClient;
    }

    @PostMapping("/test")
    public Map<String, Object> test(@RequestBody Map<String, String> request) {
        String text = request.get("text");

        long startTime = System.currentTimeMillis();
        List<Double> vector = embeddingClient.embed(text);
        long elapsedMs = System.currentTimeMillis() - startTime;

        return Map.of(
                "dimensions", vector.size(),
                "firstFiveValues", vector.subList(0, Math.min(5, vector.size())),
                "elapsedMs", elapsedMs
        );
    }
}