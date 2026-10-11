package com.traffic.rag.common;

public record ApiResponse<T>(
        String code,
        String message,
        T data
) {
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("0", "success", data);
    }

    public static ApiResponse<Void> success() {
        return new ApiResponse<>("0", "success", null);
    }

    public static ApiResponse<Void> failure(String code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}