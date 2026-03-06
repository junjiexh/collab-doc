package com.collabdoc.document;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record MoveDocumentRequest(
    String parentId,
    @NotNull(message = "sortOrder 不能为空")
    @Min(value = 0, message = "sortOrder 不能为负数")
    Integer sortOrder
) {}
