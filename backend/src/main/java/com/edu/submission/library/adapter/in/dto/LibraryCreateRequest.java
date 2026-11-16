package com.edu.submission.library.adapter.in.dto;

import jakarta.validation.constraints.NotBlank;

public record LibraryCreateRequest(
        @NotBlank(message = "库名称不能为空") String name,
        @NotBlank(message = "库类型不能为空") String libraryType
) {
}
