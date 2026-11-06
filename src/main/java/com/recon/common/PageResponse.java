package com.recon.common;

import java.util.List;

public record PageResponse<T>(List<T> items, int page, int size, long totalItems, long totalPages) {

    public static <T> PageResponse<T> of(List<T> items, int page, int size, long totalItems) {
        long totalPages = (totalItems + size - 1) / size;
        return new PageResponse<>(items, page, size, totalItems, totalPages);
    }
}
