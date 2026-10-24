package com.kista.tradingcore.broker.adapter.out.meritz;

import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderType;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.broker.adapter.out.internal.DbTokenCoordinator;
import com.kista.tradingcore.broker.application.port.output.BrokerTokenCachePort;
import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.tradingcore.broker.domain.model.CancelInstruction;
import com.kista.tradingcore.broker.domain.model.OrderInstruction;
import com.kista.tradingcore.broker.domain.model.OrderResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

// 메리츠 실 API 검증 — 메리츠는 모의 서버가 없어 운영 API를 실계좌로 호출한다. 환경변수 없으면 건너뜀(비밀값은 출력 금지)
// 조회: MERITZ_APP_KEY·MERITZ_APP_SECRET / 주문: 추가로 MERITZ_LIVE_ORDER=1 (LOC 취소 불가 구간인 마감 10분 전 이후 실행 금지)
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "MERITZ_APP_KEY", matches = ".+")
class MeritzLiveIT {

    private static final StrategyTicker TICKER = StrategyTicker.SOXL;
    private static final long POLL_TIMEOUT_MILLIS = 10_000;

    private BrokerAccountRef account;
    private MeritzAuthApi authApi;
    private MeritzHttpClient http;
    private MeritzBrokerAdapter adapter;

    @BeforeEach
    void setUp() {
        String appKey = System.getenv("MERITZ_APP_KEY");
        String secret = System.getenv("MERITZ_APP_SECRET");
        // 메모리 토큰 캐시 — DB 없이 DbTokenCoordinator 재사용
        BrokerTokenCachePort cache = new InMemoryTokenCache();
        RestClient rest = new MeritzConfig().meritzRestClient("https://openapi.imeritz.com:9443");
        JsonMapper mapper = JsonMapper.builder().build();
        authApi = new MeritzAuthApi(rest, mapper, new DbTokenCoordinator(cache));
        http = new MeritzHttpClient(rest, authApi, mapper);
        MeritzExchangeRegistry registry = new MeritzExchangeRegistry();
        adapter = new MeritzBrokerAdapter(new MeritzTradingApi(http), new MeritzOrderApi(http, registry),
                new MeritzPriceApi(http, registry));
        account = new BrokerAccountRef(UUID.randomUUID(), appKey, secret, null, null, Broker.MERITZ);
    }

    @Test
    void read_capabilities_work() {
        String appKey = account.appKey();
        String secret = account.secretKey();
        authApi.verifyCredentials(appKey, secret);
        authApi.verifyAccount(appKey, secret, null);

        BigDecimal price = adapter.getPrice(TICKER, account);
        BigDecimal prevClose = adapter.getPrevClose(TICKER, account);
        BigDecimal closing = adapter.getClosingPrice(TICKER, previousWeekday(), account);
        assertThat(price).isPositive();
        assertThat(prevClose).isPositive();
        assertThat(closing).isPositive();

        var sellable = adapter.getSellableQuantity(TICKER, account);
        var buyable = adapter.getUsdBuyableAmount(account);
        var present = adapter.getPresentBalance(account);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        var executions = adapter.getExecutions(today.minusDays(30), today, TICKER, account);
        assertThat(sellable).isNotNull();
        assertThat(buyable).isNotNull();
        assertThat(present).isNotNull();
        assertThat(executions).isNotEmpty(); // 실계좌에 체결 이력이 있다 — 비면 조회 결함

        System.out.printf("[MeritzLive] price=%s prevClose=%s closing=%s sellable=%d buyable=%s executions30d=%d%n",
                price, prevClose, closing, sellable.quantity(), buyable, executions.size());
    }

    @Test
    void place_and_cancel_one_share_far_loc() throws InterruptedException {
        placeAndCancel(OrderType.LOC);
    }

    @Test
    void place_and_cancel_one_share_far_limit() throws InterruptedException {
        placeAndCancel(OrderType.LIMIT);
    }

    // 현재가 절반 1주 매수 → 당일 목록 반영 대기 → 취소 → 취소 가능 수량 0 확인 (체결 불가 가격)
    private void placeAndCancel(OrderType type) throws InterruptedException {
        assumeTrue("1".equals(System.getenv("MERITZ_LIVE_ORDER")));
        BigDecimal farPrice = adapter.getPrice(TICKER, account).multiply(new BigDecimal("0.5")).setScale(2, RoundingMode.HALF_UP);
        long start = System.currentTimeMillis();
        OrderResult placed = adapter.place(new OrderInstruction(TICKER, OrderDirection.BUY, type, 1, farPrice), account);
        String orderNo = placed.externalOrderId();
        long placedMs = System.currentTimeMillis() - start;
        try {
            MeritzOrderApi.OrderRow row = pollRow(orderNo, false);
            assertThat(row).as("당일 목록 반영").isNotNull();
            long listedMs = System.currentTimeMillis() - start;
            System.out.printf("[MeritzLive] %s place=%dms listed=%dms ordered=%s cancelable=%s aspr=%s%n",
                    type, placedMs, listedMs, row.todayOrdered(), row.todayCancelable(), row.asprType());
        } finally {
            // 접수 성공 후엔 어떤 경우에도 취소 시도
            adapter.cancel(new CancelInstruction(TICKER, orderNo), account);
        }
        long cancelStart = System.currentTimeMillis();
        MeritzOrderApi.OrderRow after = pollRow(orderNo, true);
        assertThat(after).as("취소 후 당일 목록").isNotNull();
        // 필드 누락을 0으로 취급하지 않는다 — 값이 있어야 하고 그 값이 0이어야 한다
        assertThat(after.todayCancelable()).as("취소 가능 수량 필드").isNotBlank();
        assertThat(MeritzResponse.wholeQuantity(after.todayCancelable())).isZero();
        System.out.printf("[MeritzLive] %s cancelReflected=%dms%n", type, System.currentTimeMillis() - cancelStart);
    }

    // 당일 목록에서 주문번호 행을 찾는다 — cancelledOnly면 취소 가능 수량 필드가 존재하고 0이 될 때까지 대기(누락은 미반영으로 계속 대기). 없으면 마지막 관측 행(또는 null)
    private MeritzOrderApi.OrderRow pollRow(String orderNo, boolean cancelledOnly) throws InterruptedException {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("cntg_cls_code", "0");
        params.add("seln_byov_clcd", "0");
        long deadline = System.currentTimeMillis() + POLL_TIMEOUT_MILLIS;
        MeritzOrderApi.OrderRow last = null;
        do {
            for (MeritzOrderApi.OrderRow r : http.getAllPages("/trading/v1/overseas/orders/today", account, params, MeritzOrderApi.OrderRow.class)) {
                if (r.orderNo() != null && new BigDecimal(r.orderNo().trim()).compareTo(new BigDecimal(orderNo)) == 0) last = r;
            }
            if (last != null && (!cancelledOnly || cancelReflected(last))) return last;
            Thread.sleep(500);
        } while (System.currentTimeMillis() < deadline);
        return last;
    }

    // 취소 반영 — 취소 가능 수량 필드가 있고 값이 0 (누락은 반영 아님)
    private static boolean cancelReflected(MeritzOrderApi.OrderRow r) {
        String q = r.todayCancelable();
        return q != null && !q.isBlank() && MeritzResponse.wholeQuantity(q) == 0;
    }

    // KST 오늘 기준 직전 평일 — 휴장일이면 값이 없을 수 있어 단순 평일 계산만 한다
    private static LocalDate previousWeekday() {
        LocalDate d = LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1);
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) d = d.minusDays(1);
        return d;
    }

    // BrokerTokenCachePort 메모리 구현
    private static final class InMemoryTokenCache implements BrokerTokenCachePort {
        private record Entry(String token, OffsetDateTime expiresAt) {}
        private final ConcurrentMap<UUID, Entry> store = new ConcurrentHashMap<>();

        @Override
        public Optional<String> findValidToken(UUID accountId, OffsetDateTime threshold) {
            return Optional.ofNullable(store.get(accountId)).filter(e -> e.expiresAt().isAfter(threshold)).map(Entry::token);
        }

        @Override
        public void saveToken(UUID accountId, String accessToken, OffsetDateTime expiresAt) {
            store.put(accountId, new Entry(accessToken, expiresAt));
        }

        @Override
        public void invalidateToken(UUID accountId, String rejectedAccessToken, OffsetDateTime invalidatedAt) {
            store.computeIfPresent(accountId, (k, e) -> e.token().equals(rejectedAccessToken) ? null : e);
        }

        @Override
        public void deleteByAccountIds(Collection<UUID> accountIds) {
            accountIds.forEach(store::remove);
        }
    }
}
