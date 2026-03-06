package com.collabdoc.collab;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.Map;

public record InsertBlockRequest(
    @Min(value = 0, message = "index 不能为负数")
    int index,
    @NotBlank(message = "type 不能为空")
    String type,
    String content,
    Map<String, Object> props
) {}
