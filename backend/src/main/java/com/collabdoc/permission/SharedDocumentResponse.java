package com.collabdoc.permission;

import java.util.UUID;

public record SharedDocumentResponse(
    UUID id,
    String title,
    String permission
) {}
