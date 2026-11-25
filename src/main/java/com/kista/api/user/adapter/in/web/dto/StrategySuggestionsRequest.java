package com.kista.api.user.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record StrategySuggestionsRequest(
        @NotNull(message = "운용전략 추천 목록은 필수입니다.") @Size(max = 20, message = "운용전략 추천은 20개 이내여야 합니다.") List<@NotBlank(message = "운용전략 추천은 비워 둘 수 없습니다.") @Size(max = 30, message = "운용전략 추천은 30자 이내여야 합니다.") String> suggestions // 운용전략 추천 목록 (빈 배열 허용)
) {
}
