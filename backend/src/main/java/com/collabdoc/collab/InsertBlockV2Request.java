package com.collabdoc.collab;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Map;

public record InsertBlockV2Request(
    @NotBlank(message = "type 不能为空")
    String type,
    String content,
    Map<String, Object> props,
    @NotBlank(message = "position 不能为空")
    @Pattern(regexp = "start|end|after_block", message = "position 必须是 start, end, 或 after_block")
    String position,
    String afterId,
    List<BlockChild> children
) {
    public record BlockChild(
        @NotBlank(message = "type 不能为空")
        String type,
        String content,
        Map<String, Object> props
    ) {}
}
