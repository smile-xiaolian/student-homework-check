package com.edu.submission.admin.adapter.in.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record SystemConfigUpdateRequest(
        @NotEmpty(message = "请至少提交一个参数")
        List<@Valid Item> items
) {
    public record Item(
            @NotBlank(message = "参数键不能为空") String key,
            @NotNull(message = "参数值不能为空") JsonNode value,
            @NotNull(message = "参数版本不能为空") Long version
    ) {
    }
}
