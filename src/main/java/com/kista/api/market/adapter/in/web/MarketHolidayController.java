package com.kista.api.market.adapter.in.web;

import com.kista.api.market.adapter.in.web.dto.MarketSessionResponse;
import com.kista.api.market.adapter.in.web.dto.TossCandleResponse;
import com.kista.api.market.application.port.output.MarketCalendarQueryPort;
import com.kista.api.market.application.usecase.MarketUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Tag(name = "시장 캘린더", description = "미국 시장 휴장일 조회 및 세션 확인")
@RestController
@RequestMapping("/api/market")
@RequiredArgsConstructor
public class MarketHolidayController {

    private static final CacheControl HOLIDAYS_CACHE = CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic();

    private final MarketUseCase marketUseCase;
    private final MarketCalendarQueryPort marketCalendarQueryPort;

    @Operation(summary = "월별 미국 시장 휴장일 조회")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping("/holidays")
    public ResponseEntity<List<String>> getHolidays(
            @Parameter(description = "연도", example = "2026") @RequestParam int year,
            @Parameter(description = "월 (1-12)", example = "6") @RequestParam int month) {
        // LocalDate → ISO 문자열 변환으로 직렬화 방식 명시
        List<String> holidays = marketUseCase.getMonthlyHolidays(year, month)
                .stream().map(Object::toString).toList();
        return ResponseEntity.ok().cacheControl(HOLIDAYS_CACHE).body(holidays);
    }

    // 현재 KST 거래일·휴장 여부 조회 — UI 휴장일 안내 판단에 사용
    @Operation(
            summary = "현재 거래일 조회",
            description = """
                    현재 KST 기준 거래일과 그 거래일의 미국 장 개장 여부를 반환합니다.
                    tradeDateKst: 현재 시각 기준 KST 거래일 (그 날짜 실제 마감 30분 전부터 익일, 휴장일은 04:30 KST 기준)
                    tradingDay: tradeDateKst에 미국 장이 열리는지 (휴장일·주말이면 false)
                    """
    )
    @ApiResponse(responseCode = "200", description = "세션 조회 성공")
    @GetMapping("/session")
    public MarketSessionResponse getSession() {
        var session = marketCalendarQueryPort.currentSession();
        return new MarketSessionResponse(session.tradeDateKst(), session.tradingDay());
    }

    @Operation(summary = "종목 일봉 캔들 조회 (계좌 무관 공용 시세)",
            description = "from·to를 함께 주면 기간(양끝 포함, 최대 5년) 조회 — 200개 한도 없음. 없으면 최신 count개(최대 600)")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping("/candles")
    public List<TossCandleResponse> getCandles(
            @Parameter(description = "종목코드", example = "QQQ") @RequestParam String ticker,
            @Parameter(description = "캔들 개수 (최대 600, 초과 시 600으로 잘림, from/to 지정 시 무시)", example = "200") @RequestParam(defaultValue = "200") int count,
            @Parameter(description = "시작일 (to와 함께 지정)", example = "2025-01-01") @RequestParam(required = false) LocalDate from,
            @Parameter(description = "종료일 (from과 함께 지정)", example = "2026-10-14") @RequestParam(required = false) LocalDate to) {
        if ((from == null) != (to == null)) throw new IllegalArgumentException("from과 to는 함께 지정해야 합니다.");
        return TossCandleResponse.fromList(from != null
                ? marketUseCase.getDailyCandles(ticker, from, to)
                : marketUseCase.getDailyCandles(ticker, count));
    }

}
