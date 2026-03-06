package com.collabdoc.permission;

import java.util.UUID;

public record PermissionResponse(
    UUID id,
    UUID userId,
    String username,
    Permission permission
) {}
