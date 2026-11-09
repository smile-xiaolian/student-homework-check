package com.edu.submission.shared.api;

public record Pagination(int page, int size, long total, int totalPages, boolean hasNext) {

    public static Pagination of(int page, int size, long total) {
        int totalPages = size == 0 ? 0 : (int) Math.ceil((double) total / size);
        return new Pagination(page, size, total, totalPages, page < totalPages);
    }
}

