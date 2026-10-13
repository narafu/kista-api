package com.kista.tradingcore.broker.adapter.out.kis;

import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.broker.adapter.out.marketdata.AdminPriceSource;
import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 관리자 시세 2순위 — KIS 관리자 앱키(계좌 없음). 해외시세 TR은 CANO 없이 appkey/secret/token만 필요하다.
// 키가 비어 있으면 비활성(기동 실패 아님)
@Slf4j
@Component
@Order(2)
class KisAdminPriceSource implements AdminPriceSource {

    private final KisPriceApi kisPriceApi;
    private final BrokerAccountRef adminRef; // 토큰 id=ADMIN_TOKEN_ID → KisAuthApi가 Redis 관리자 토큰 코디네이터로 라우팅
    private final boolean enabled;

    KisAdminPriceSource(KisPriceApi kisPriceApi,
                        @Value("${kis.admin-app-key:}") String appKey,
                        @Value("${kis.admin-app-secret:}") String appSecret) {
        this.kisPriceApi = kisPriceApi;
        this.enabled = !appKey.isBlank() && !appSecret.isBlank();
        this.adminRef = new BrokerAccountRef(KisAdminTokenCoordinator.ADMIN_TOKEN_ID, appKey, appSecret, null, null, Broker.KIS);
        if (!enabled) log.info("KIS 관리자 시세 비활성 — kis.admin-app-key/secret 미설정");
    }

    @Override
    public String name() {
        return "KIS 관리자";
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public Map<StrategyTicker, BigDecimal> getPrices(List<StrategyTicker> tickers) {
        // last만 사용 — 개장 전 base는 D-2 종가일 수 있어 현재가로 대체 금지
        return quotes(tickers, true);
    }

    @Override
    public Map<StrategyTicker, BigDecimal> getPrevCloses(List<StrategyTicker> tickers) {
        return quotes(tickers, false);
    }

    @Override
    public Map<StrategyTicker, BigDecimal> getClosingPrices(List<StrategyTicker> tickers, LocalDate tradeDate) {
        // dailyprice 종목당 단건 — 성공분만(라이브가 폴백 금지)
        Map<StrategyTicker, BigDecimal> result = new LinkedHashMap<>();
        for (StrategyTicker t : tickers) {
            kisPriceApi.fetchConfirmedClose(t, tradeDate, adminRef)
                    .filter(v -> v.signum() > 0) // parseBd가 비숫자를 ZERO로 바꾸므로 0 이하는 누락 처리
                    .ifPresent(v -> result.put(t, v));
        }
        return result;
    }

    // multprice 1회 호출 — useLast=true면 last, false면 base(전일종가). 빈값 제외
    private Map<StrategyTicker, BigDecimal> quotes(List<StrategyTicker> tickers, boolean useLast) {
        Map<StrategyTicker, BigDecimal> result = new LinkedHashMap<>();
        kisPriceApi.fetchQuotes(tickers, adminRef).forEach((t, q) -> {
            String raw = useLast ? q.last() : q.base();
            // parseBd는 비숫자를 ZERO로 바꾸므로 0 이하는 누락 처리(대체값 금지)
            BigDecimal v = raw == null ? BigDecimal.ZERO : KisResponseParser.parseBd(raw);
            if (v.signum() > 0) result.put(t, v);
        });
        return result;
    }
}
