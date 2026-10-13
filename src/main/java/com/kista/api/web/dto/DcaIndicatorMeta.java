package com.kista.api.web.dto;

import com.kista.shared.kernel.DcaIndicator;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record DcaIndicatorMeta(
        @Schema(description = "enum name() 값", example = "SMA")
        String code,                // enum name() 값
        @Schema(description = "한국어 표시 이름")
        String label,               // 한국어 표시 이름
        @Schema(description = "비교 방식 설명")
        String description,         // 비교 방식 설명
        @Schema(description = "SMA 기간 옵션 (SMA 외에는 빈 목록)")
        List<Integer> smaPeriods    // SMA 기간 옵션 — SMA만 값
) {
    public static DcaIndicatorMeta from(DcaIndicator i) {
        return new DcaIndicatorMeta(i.name(), i.getLabel(), i.getDescription(),
                i == DcaIndicator.SMA ? DcaIndicator.SMA_PERIODS : List.of());
    }
}
