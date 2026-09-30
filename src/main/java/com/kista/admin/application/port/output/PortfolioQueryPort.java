package com.kista.admin.application.port.output;

import com.kista.contract.stats.PortfolioCurrentResponse;
import com.kista.contract.stats.PortfolioOrderResponse;
import com.kista.sharedkernel.StrategyTicker;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// trading-core 포트폴리오 조회를 admin(텔레그램 봇 명령 /status·/history)이 직접 참조하지 않도록
// 필요한 조회만 담은 전용 포트 — trading-core 내부 HTTP API로 구현(PortfolioQueryHttpAdapter)
public interface PortfolioQueryPort {
    PortfolioCurrentResponse getCurrent(UUID userId);
    List<PortfolioOrderResponse> getHistory(UUID userId, LocalDate from, LocalDate to, StrategyTicker ticker);
}
