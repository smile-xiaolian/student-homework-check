package com.edu.submission.shared.api;

import java.util.List;

public record PageResult<T>(List<T> items, Pagination pagination) {
}

