package com.collabdoc.document;

import jakarta.validation.constraints.Size;

public record CreateDocumentRequest(
    @Size(max = 200, message = "标题不能超过200个字符")
    String title,
    String parentId
) {}
