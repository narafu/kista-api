package com.kista.tradingcore.trading.adapter.in.web;

import com.kista.shared.contract.trading.TradeCorrectionRequest;
import com.kista.shared.contract.trading.TradeCorrectionResponse;
import com.kista.tradingcore.trading.application.usecase.ManualTradeCorrectionUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

// admin의 TradingCommandHttpAdapter가 소비하는 내부 전용 쓰기 엔드포인트 — X-Internal-Token 인증
@Tag(name = "내부 API", description = "서버 간 내부 호출 전용 엔드포인트 (X-Internal-Token 인증)")
@RestController
@RequestMapping("/api/internal/trading")
@RequiredArgsConstructor
public class TradingInternalCommandController {

    private final ManualTradeCorrectionUseCase manualTradeCorrectionUseCase;

    @Operation(summary = "관리자 수동 체결 보정")
    @PostMapping("/trade-corrections")
    public TradeCorrectionResponse correctManualFills(@RequestBody @Valid TradeCorrectionRequest request) {
        return TradingContractMapper.toResponse(
                manualTradeCorrectionUseCase.correctManualFills(TradingContractMapper.toCommand(request)));
    }
}
