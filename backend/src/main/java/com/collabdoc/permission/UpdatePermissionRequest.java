package com.collabdoc.permission;

import jakarta.validation.constraints.NotNull;

public record UpdatePermissionRequest(
    @NotNull Permission permission
) {}
