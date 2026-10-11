package com.traffic.rag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record KnowledgeIngestRequest(

        @NotBlank(message = "chunkId 不能为空")
        String chunkId,

        @NotBlank(message = "title 不能为空")
        String title,

        @NotBlank(message = "content 不能为空")
        String content,

        @NotBlank(message = "category 不能为空")
        String category,

        @NotNull(message = "mode 不能为空")
        IngestMode mode
) {}