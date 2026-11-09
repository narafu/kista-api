package com.kista.tradingcore.stats.adapter.in.web;

import com.kista.shared.contract.stats.PortfolioCurrentResponse;
import com.kista.shared.contract.stats.PortfolioOrderResponse;
import com.kista.tradingcore.stats.application.usecase.PortfolioUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// admin(텔레그램 TelegramBotService)이 PortfolioUseCase를 직접 참조하지 않도록 내부 API로 노출 — X-Internal-Token 인증
@Tag(name = "내부 API", description = "서버 간 내부 호출 전용 엔드포인트 (X-Internal-Token 인증)")
@RestController
@RequestMapping("/api/internal/trading/stats/portfolio")
@RequiredArgsConstructor
public class PortfolioQueryInternalController {

    private final PortfolioUseCase portfolioUseCase;

    @Operation(summary = "현재 포트폴리오 현황 조회", description = "전략별 최신 포지션(코인 계좌 포함, 통화 표기). 없으면 빈 목록. X-Internal-Token 헤더 필수.")
    @GetMapping("/current")
    public List<PortfolioCurrentResponse> getCurrent(@RequestParam UUID userId) {
        return portfolioUseCase.getCurrent(userId).stream()
                .map(StatsContractMapper::toResponse)
                .toList();
    }

    @Operation(summary = "거래 내역 조회", description = "기간별 전 종목 주문 내역(통화 표기). X-Internal-Token 헤더 필수.")
    @GetMapping("/history")
    public List<PortfolioOrderResponse> getHistory(
            @RequestParam UUID userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return portfolioUseCase.getHistory(userId, from, to).stream()
                .map(StatsContractMapper::toResponse)
                .toList();
    }
}
