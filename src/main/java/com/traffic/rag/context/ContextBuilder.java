package com.traffic.rag.context;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 将 Reranker 排序后的候选文档转换为 LLM 可使用的上下文。
 */
@Component
public class ContextBuilder {

    /**
     * 构建最终上下文。
     *
     * @param rerankResults Reranker 返回的排序结果，默认按相关性从高到低排列
     * @param topK          最多保留多少篇文档
     * @param maxChars      Context 最大字符数
     * @return 包含 selectedDocuments 和 context 的结果
     */
    public Map<String, Object> build(
            List<Map<String, Object>> rerankResults,
            int topK,
            int maxChars) {

        if (topK <= 0) {
            throw new IllegalArgumentException("topK 必须大于 0");
        }

        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars 必须大于 0");
        }

        List<Map<String, Object>> selectedDocuments =
                new ArrayList<>();

        Set<String> seenIds = new HashSet<>();
        StringBuilder context = new StringBuilder();

        for (Map<String, Object> hit : rerankResults) {
            if (selectedDocuments.size() >= topK) {
                break;
            }

            Object sourceObject = hit.get("source");

            Map<String, Object> source =
                    sourceObject instanceof Map<?, ?>
                            ? castMap(sourceObject)
                            : hit;

            String id = stringValue(hit.get("id"));
            String title = stringValue(source.get("title"));
            String content = stringValue(source.get("content"));

            // 标题和正文都为空，没有必要进入上下文。
            if (title.isBlank() && content.isBlank()) {
                continue;
            }

            // 有 ID 时按 ID 去重；没有 ID 的文档暂时不做 ID 去重。
            if (!id.isBlank() && !seenIds.add(id)) {
                continue;
            }

            int documentNumber = selectedDocuments.size() + 1;

            String block =
                    "[资料" + documentNumber + "]\n"
                            + "文档ID：" + id + "\n"
                            + "标题：" + title + "\n"
                            + "内容：" + content + "\n\n";

            // 第一版采用严格字符预算，不截断单篇文档。
            if (context.length() + block.length() > maxChars) {
                break;
            }

            Map<String, Object> selected = new LinkedHashMap<>();
            selected.put("id", id);
            selected.put("title", title);
            selected.put("content", content);

            if (hit.containsKey("rerankerScore")) {
                selected.put("rerankerScore", hit.get("rerankerScore"));
            }

            if (hit.containsKey("rerankerRank")) {
                selected.put("rerankerRank", hit.get("rerankerRank"));
            }

            selectedDocuments.add(selected);
            context.append(block);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("selectedDocuments", selectedDocuments);
        result.put("context", context.toString());
        result.put("documentCount", selectedDocuments.size());
        result.put("contextChars", context.length());

        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}