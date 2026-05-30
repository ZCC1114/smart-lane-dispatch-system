package com.smartlane.dispatch.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

public record ScreenEventIdsRequest(
        @NotEmpty(message = "告警事件不能为空") List<@NotBlank(message = "告警事件 ID 不能为空") String> ids) {
}
