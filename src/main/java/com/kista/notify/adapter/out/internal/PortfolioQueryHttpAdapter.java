package com.kista.notify.adapter.out.internal;

import com.kista.platform.internalapi.InternalApiStatusHandlers;
import com.kista.notify.application.port.output.PortfolioQueryPort;
import com.kista.sharedkernel.StrategyTicker;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
class PortfolioQueryHttpAdapter implements PortfolioQueryPort {

    private final RestClient internalApiRestClient;

    @Override
    public PortfolioCurrentView getCurrent(UUID userId) {
        RestClient.ResponseSpec spec = internalApiRestClient.get()
                .uri(b -> b.path("/api/internal/trading/stats/portfolio/current").queryParam("userId", userId).build())
                .retrieve();
        // trading 쪽 GlobalExceptionHandler가 매핑한 상태코드를 원래 예외 타입으로 되돌린다 — 없으면
        // NoSuchElementException(포트폴리오 데이터 없음)이 HttpClientErrorException으로 흘러 TelegramBotService의
        // catch(NoSuchElementException)가 동작하지 않는다
        return InternalApiStatusHandlers.notFoundAsNoSuchElement(spec, "포트폴리오 데이터가 없습니다.")
                .body(PortfolioCurrentView.class);
    }

    @Override
    public List<PortfolioOrderView> getHistory(UUID userId, LocalDate from, LocalDate to, StrategyTicker ticker) {
        return internalApiRestClient.get()
                .uri(b -> b.path("/api/internal/trading/stats/portfolio/history")
                        .queryParam("userId", userId).queryParam("from", from).queryParam("to", to).queryParam("ticker", ticker)
                        .build())
                .retrieve()
                .body(new ParameterizedTypeReference<List<PortfolioOrderView>>() {});
    }
}
