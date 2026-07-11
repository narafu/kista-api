package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.MarketSessionResponse;
import com.kista.adapter.in.web.dto.TossCandleResponse;
import com.kista.domain.model.strategy.DstInfo;
import com.kista.domain.port.in.MarketUseCase;
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

@Tag(name = "시장 캘린더", description = "미국 시장 휴장일 조회 및 세션 확인")
@RestController
@RequestMapping("/api/market")
@RequiredArgsConstructor
public class MarketHolidayController {

    private final MarketUseCase marketUseCase;

    @Operation(summary = "월별 미국 시장 휴장일 조회")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping("/holidays")
    public List<String> getHolidays(
            @Parameter(description = "연도", example = "2026") @RequestParam int year,
            @Parameter(description = "월 (1-12)", example = "6") @RequestParam int month) {
        // LocalDate → ISO 문자열 변환으로 직렬화 방식 명시
        return marketUseCase.getMonthlyHolidays(year, month)
                .stream().map(Object::toString).toList();
    }

    // 현재 시장 세션 조회 — UI 수동 실행 버튼 활성화 판단에 사용
    @Operation(
            summary = "현재 시장 세션 조회",
            description = """
                    현재 KST 기준 미국 시장 세션을 반환합니다.
                    DIRECT: 프리마켓+정규장 (주문 가능) — DST: 17:00~05:00 / 비DST: 18:00~06:00 KST
                    BLOCKED: 장마감~프리마켓 전 (주문 불가) — DST: 05:00~17:00 / 비DST: 06:00~18:00 KST
                    """
    )
    @ApiResponse(responseCode = "200", description = "세션 조회 성공")
    @GetMapping("/session")
    public MarketSessionResponse getSession() {
        DstInfo dst = DstInfo.immediate();
        return new MarketSessionResponse(dst.currentSession().name(), dst.isDst());
    }

    @Operation(summary = "종목 일봉 캔들 조회 (최대 200개, 계좌 무관 공용 시세)")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping("/candles")
    public List<TossCandleResponse> getCandles(
            @Parameter(description = "종목코드", example = "QQQ") @RequestParam String ticker,
            @Parameter(description = "캔들 개수 (최대 200)", example = "200") @RequestParam(defaultValue = "200") int count) {
        return TossCandleResponse.fromList(marketUseCase.getDailyCandles(ticker, count));
    }

}
