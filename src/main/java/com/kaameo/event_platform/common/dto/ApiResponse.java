package com.kaameo.event_platform.common.dto;

public record ApiResponse<T>(
        boolean success,
        T data,
        String error,
        Meta meta
) {
    public record Meta(long total, int page, int limit) {}

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, null);
    }

    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(false, null, message, null);
    }
}
