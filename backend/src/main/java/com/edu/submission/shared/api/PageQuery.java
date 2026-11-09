package com.edu.submission.shared.api;

public record PageQuery(int page, int size) {

    public static PageQuery of(Integer page, Integer size) {
        int safePage = page == null ? 1 : Math.max(1, page);
        int safeSize = size == null ? 20 : Math.min(100, Math.max(1, size));
        return new PageQuery(safePage, safeSize);
    }

    public int offset() {
        return (page - 1) * size;
    }
}

