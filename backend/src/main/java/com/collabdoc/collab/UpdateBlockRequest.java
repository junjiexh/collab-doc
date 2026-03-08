package com.collabdoc.collab;

import java.util.Map;

public record UpdateBlockRequest(
    String type,
    String content,
    Map<String, Object> props
) {}
