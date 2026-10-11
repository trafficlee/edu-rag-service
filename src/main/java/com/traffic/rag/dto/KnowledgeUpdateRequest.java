
package com.traffic.rag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record KnowledgeUpdateRequest(
        @NotBlank(message = "chunkId 不能为空")
        String chunkId,

        @NotBlank(message = "title 不能为空")
        String title,

        @NotBlank(message = "content 不能为空")
        String content,

        @NotBlank(message = "category 不能为空")
        String category,

        @NotNull(message = "seqNo 不能为空")
        @PositiveOrZero(message = "seqNo 不能小于 0")
        Long seqNo,

        @NotNull(message = "primaryTerm 不能为空")
        @PositiveOrZero(message = "primaryTerm 不能小于 0")
        Long primaryTerm
) {}
