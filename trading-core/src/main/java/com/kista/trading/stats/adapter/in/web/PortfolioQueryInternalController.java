package com.kista.trading.stats.adapter.in.web;

import com.kista.sharedkernel.StrategyTicker;
import com.kista.trading.domain.model.CyclePositionHistoryEntry;
import com.kista.trading.domain.model.Order;
import com.kista.trading.stats.application.usecase.PortfolioUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// notify(TelegramBotService)가 PortfolioUseCase를 직접 참조하지 않도록 내부 API로 노출 — X-Internal-Token 인증
@Tag(name = "내부 API", description = "서버 간 내부 호출 전용 엔드포인트 (X-Internal-Token 인증)")
@RestController
@RequestMapping("/api/internal/trading/stats/portfolio")
@RequiredArgsConstructor
public class PortfolioQueryInternalController {

    private final PortfolioUseCase portfolioUseCase;

    @Operation(summary = "현재 포트폴리오 현황 조회", description = "가장 최근 포지션 1건. 없으면 404. X-Internal-Token 헤더 필수.")
    @GetMapping("/current")
    public CurrentResponse getCurrent(@RequestParam UUID userId) {
        CyclePositionHistoryEntry entry = portfolioUseCase.getCurrent(userId);
        return new CurrentResponse(entry.ticker(), entry.holdings(), entry.avgPrice(), entry.usdDeposit(), entry.closingPrice());
    }

    @Operation(summary = "거래 내역 조회", description = "기간·종목별 주문 내역. X-Internal-Token 헤더 필수.")
    @GetMapping("/history")
    public List<OrderResponse> getHistory(
            @RequestParam UUID userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam StrategyTicker ticker) {
        return portfolioUseCase.getHistory(userId, from, to, ticker).stream()
                .map(o -> new OrderResponse(o.tradeDate(), o.ticker(), o.direction(), o.orderType(), o.quantity(), o.price()))
                .toList();
    }

    // com.kista.notify.application.port.output.PortfolioQueryPort.PortfolioCurrentView와 byte-identical own-type
    record CurrentResponse(StrategyTicker ticker, int holdings, BigDecimal avgPrice,
                            BigDecimal usdDeposit, BigDecimal closingPrice) {}

    // com.kista.notify.application.port.output.PortfolioQueryPort.PortfolioOrderView와 byte-identical own-type
    record OrderResponse(LocalDate tradeDate, StrategyTicker ticker, com.kista.sharedkernel.OrderDirection direction,
                          com.kista.sharedkernel.OrderType orderType, Integer quantity, BigDecimal price) {}
}
