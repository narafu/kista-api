package com.kista.trading.adapter.in.web;

import com.kista.marketcalendar.application.port.output.MarketCalendarPort;
import com.kista.sharedkernel.TimeZones;
import com.kista.trading.domain.model.DstInfo;
import com.kista.trading.domain.model.ManualTradeCorrectionCommand;
import com.kista.trading.domain.model.ManualTradeCorrectionResult;
import com.kista.trading.domain.model.ReorderCommand;
import com.kista.trading.domain.model.ReorderResult;
import com.kista.trading.application.usecase.ManualTradeCorrectionUseCase;
import com.kista.trading.application.usecase.ReorderUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

// admin의 TradingCommandHttpAdapter가 소비하는 내부 전용 쓰기 엔드포인트 — X-Internal-Token 인증
@Tag(name = "내부 API", description = "서버 간 내부 호출 전용 엔드포인트 (X-Internal-Token 인증)")
@RestController
@RequestMapping("/api/internal/trading")
@RequiredArgsConstructor
public class TradingInternalCommandController {

    private final ReorderUseCase reorderUseCase;
    private final ManualTradeCorrectionUseCase manualTradeCorrectionUseCase;
    private final MarketCalendarPort marketCalendarPort; // 휴장일 판정 — 비개장일엔 3개 boolean 전부 false

    @Operation(summary = "관리자 재주문 접수/취소")
    @PostMapping("/reorder")
    public ReorderResult reorder(@RequestBody @Valid ReorderCommand command) {
        return reorderUseCase.reorder(command);
    }

    @Operation(summary = "관리자 수동 체결 보정")
    @PostMapping("/trade-corrections")
    public ManualTradeCorrectionResult correctManualFills(@RequestBody @Valid ManualTradeCorrectionCommand command) {
        return manualTradeCorrectionUseCase.correctManualFills(command);
    }

    @Operation(summary = "재주문 시점 가용성 조회", description = "비개장일엔 3개 boolean 전부 false를 반환합니다.")
    @GetMapping("/reorder-timing-availability")
    public DstInfo.ReorderTimingAvailability reorderTimingAvailability() {
        if (!marketCalendarPort.isMarketOpen(LocalDate.now(TimeZones.KST))) {
            return new DstInfo.ReorderTimingAvailability(false, false, false);
        }
        return DstInfo.calculate().reorderTimingAvailability();
    }
}
