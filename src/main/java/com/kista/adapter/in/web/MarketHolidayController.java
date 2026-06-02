package com.kista.adapter.in.web;

import com.kista.domain.port.in.GetMarketHolidaysUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "시장 캘린더", description = "미국 시장 휴장일 조회")
@RestController
@RequestMapping("/api/market")
@RequiredArgsConstructor
public class MarketHolidayController {

    private final GetMarketHolidaysUseCase getMarketHolidaysUseCase;

    @Operation(summary = "월별 미국 시장 휴장일 조회")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping("/holidays")
    public List<String> getHolidays(
            @Parameter(description = "연도", example = "2026") @RequestParam int year,
            @Parameter(description = "월 (1-12)", example = "6") @RequestParam int month) {
        // LocalDate → ISO 문자열 변환으로 직렬화 방식 명시
        return getMarketHolidaysUseCase.getMonthlyHolidays(year, month)
                .stream().map(Object::toString).toList();
    }
}
