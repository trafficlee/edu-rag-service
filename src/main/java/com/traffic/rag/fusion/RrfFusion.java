package com.traffic.rag.fusion;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class RrfFusion {

    private final int rankConstant;

    public RrfFusion(int rankConstant) {
        if (rankConstant < 0) {
            throw new IllegalArgumentException(
                    "rankConstant 不能小于 0");
        }
        this.rankConstant = rankConstant;
    }

    public List<Map<String, Object>> fuse(
            List<Map<String, Object>> bm25Results,
            List<Map<String, Object>> knnResults,
            int topK) {

        if (topK <= 0) {
            log.warn("topK 参数非法: {}", topK);
            throw new IllegalArgumentException(
                    "topK 必须大于 0");
        }

        log.info("RRF 融合开始，bm25: {}, knn: {}, topK: {}",
                bm25Results.size(), knnResults.size(), topK);

        Map<String, Map<String, Object>> merged =
                new LinkedHashMap<>();

        mergeOneList(merged, bm25Results);
        mergeOneList(merged, knnResults);

        List<Map<String, Object>> results =
                new ArrayList<>(merged.values());

        results.sort((a, b) -> {
            double scoreA = (double) a.get("rrfScore");
            double scoreB = (double) b.get("rrfScore");
            return Double.compare(scoreB, scoreA);
        });

        List<Map<String, Object>> topResults = results.stream()
                .limit(topK)
                .toList();

        log.info("RRF 融合完成，合并后文档数: {}, 返回数: {}",
                results.size(), topResults.size());

        return topResults;
    }

    private void mergeOneList(
            Map<String, Map<String, Object>> merged,
            List<Map<String, Object>> retrievalResults) {

        for (int i = 0; i < retrievalResults.size(); i++) {
            Map<String, Object> hit = retrievalResults.get(i);

            String id = (String) hit.get("id");
            if (id == null) {
                continue;
            }

            int rank = i + 1;
            double contribution =
                    1.0 / (rankConstant + rank);

            Map<String, Object> item = merged.computeIfAbsent(
                    id, key -> {
                        Map<String, Object> copy =
                                new HashMap<>(hit);
                        copy.put("rrfScore", 0.0);
                        return copy;
                    });

            double oldScore = (double) item.get("rrfScore");
            item.put("rrfScore", oldScore + contribution);
        }
    }
}

