package com.kista.adapter.out.toss;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.broker.Execution;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.toss.TossApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
class TossOrderApi {

    // Toss 주문 API 경로
    private static final String ORDER_PATH = "/api/v1/orders";

    private final TossHttpClient tossHttpClient;

    public Order place(Order order, Account account) {
        // Toss는 MARKET 주문 미지원 — MOC도 LIMIT+CLS로 대체
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("symbol", order.ticker().name());              // 종목 코드 (예: SOXL)
        body.put("side", order.direction().name());             // BUY / SELL
        body.put("orderType", "LIMIT");                         // Toss 지원 타입: LIMIT만
        body.put("timeInForce", resolveTimeInForce(order.orderType())); // CLS(장마감) or DAY(정규장)
        body.put("quantity", order.quantity());
        body.put("price", resolvePrice(order.orderType(), order.price()));

        // Toss API 응답: {"result": {"orderId": "...", "clientOrderId": "..."}} — TossResult 언랩
        OrderResponse resp = TossResponseParser.unwrap(
                tossHttpClient.post(ORDER_PATH, account, body,
                        new ParameterizedTypeReference<TossResult<OrderResponse>>() {}));

        // orderId 없으면 비즈니스 실패 처리
        if (resp.orderId() == null) {
            throw new TossApiException("Toss 주문 실패: 응답에 orderId 없음", null);
        }

        // id=null — KIS와 동일하게 호출자가 DB 저장(plan/markPlaced) 처리
        return order.withPlaced(resp.orderId());
    }

    public void cancel(Order order, Account account) {
        // DELETE /api/v1/orders/{externalOrderId}
        try {
            tossHttpClient.delete(ORDER_PATH + "/" + order.externalOrderId(), account);
        } catch (TossApiException e) {
            // 이미 체결/만료된 주문은 Toss가 404(not-found)로 응답 — KIS는 이 경우 예외 없이 조용히 무시하므로 동일하게 흡수
            if (e.getCause() instanceof HttpClientErrorException.NotFound) {
                log.info("취소 대상 주문 없음(이미 체결/만료로 추정) — externalOrderId={}", order.externalOrderId());
                return;
            }
            throw e;
        }
    }

    public List<Execution> getExecutions(LocalDate from, LocalDate to, Ticker ticker, Account account) {
        // CLOSED + OPEN 두 상태 모두 조회 — PARTIAL_FILLED는 OPEN에 속함
        List<Execution> result = new ArrayList<>();
        result.addAll(fetchExecutions("CLOSED", from, to, ticker, account));
        result.addAll(fetchExecutions("OPEN",   from, to, ticker, account));
        return result;
    }

    // status별 GET /api/v1/orders — 페이지네이션 루프 처리 (CLOSED), OPEN은 단일 응답
    private List<Execution> fetchExecutions(String status, LocalDate from, LocalDate to,
                                             Ticker ticker, Account account) {
        List<Execution> result = new ArrayList<>();
        String cursor = null;
        boolean hasNext = true;

        // Toss API는 주문 접수일 기준으로 from/to 필터링 — 전날 저녁 접수된 주문이 당일 장마감에 체결되는 경우 누락 방지
        // 1일 앞당겨 조회 후 filledAt(체결일, KST +09:00) 기준으로 요청 날짜 범위 재필터링
        LocalDate queryFrom = from.minusDays(1);

        while (hasNext) {
            // Toss 응답은 {"result": {...}} TossResult 제네릭 래퍼 구조
            MultiValueMap<String, String> params = buildOrderParams(status, queryFrom, to, ticker, cursor);
            TossResult<OrdersResponse> wrapper = tossHttpClient.get(ORDER_PATH, account, params,
                    new ParameterizedTypeReference<TossResult<OrdersResponse>>() {});
            OrdersResponse response = wrapper != null ? wrapper.result() : null;
            if (response == null || response.orders() == null) break;

            // 체결된 주문만 Execution으로 변환 후 날짜 범위 필터링
            for (OrderItem order : response.orders()) {
                toExecution(order, from, to, ticker).ifPresent(result::add);
            }

            // cursor 없이 hasNext=true면 다음 페이지 조회 불가 — 무한루프 방지
            hasNext = Boolean.TRUE.equals(response.hasNext())
                    && "CLOSED".equals(status)
                    && response.nextCursor() != null;
            cursor  = response.nextCursor();
        }
        return result;
    }

    // API 요청 파라미터 빌드 — KST 날짜 그대로 전달 (KIS와 달리 toUtc 변환 없음)
    private MultiValueMap<String, String> buildOrderParams(String status, LocalDate queryFrom, LocalDate to,
                                                            Ticker ticker, String cursor) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("status", status);
        params.add("symbol", ticker.name());
        params.add("from",   queryFrom.toString()); // 1일 앞당겨 조회
        params.add("to",     to.toString());
        params.add("limit",  "100");
        if (cursor != null) params.add("cursor", cursor);
        return params;
    }

    // 주문 1건 → Execution 변환 — filledQuantity=0이거나 날짜 범위 밖이면 empty
    private Optional<Execution> toExecution(OrderItem order, LocalDate from, LocalDate to, Ticker ticker) {
        if (order.execution() == null) return Optional.empty();

        // filledQuantity > 0인 주문만 처리
        int filledQuantity = TossResponseParser.parseIntOrZero(order.execution().filledQuantity());
        if (filledQuantity <= 0) return Optional.empty();

        BigDecimal price     = TossResponseParser.parseBdOrZero(order.execution().averageFilledPrice());
        BigDecimal amountUsd = parseAmountUsd(order.execution().filledAmount(), price, filledQuantity);

        // filledAt(KST +09:00) 기반 체결일 — 없으면 요청 from 날짜 fallback
        LocalDate tradeDate = parseFilledDate(order.execution().filledAt(), from);

        // queryFrom~to로 넓게 조회했으므로 체결일이 요청 범위(from~to) 밖이면 제외
        if (tradeDate.isBefore(from) || tradeDate.isAfter(to)) return Optional.empty();

        Order.OrderDirection direction = "BUY".equals(order.side())
                ? Order.OrderDirection.BUY
                : Order.OrderDirection.SELL;

        return Optional.of(new Execution(tradeDate, ticker, direction, filledQuantity, price, amountUsd, order.orderId()));
    }

    // filledAt 문자열 → KST 날짜 파싱 — null·공백이면 요청 from 날짜 fallback (queryFrom 아님)
    private LocalDate parseFilledDate(String filledAtStr, LocalDate from) {
        // filledAt은 KST(+09:00) — toLocalDate()로 KST 날짜 추출
        return (filledAtStr != null && !filledAtStr.isBlank())
                ? OffsetDateTime.parse(filledAtStr).toLocalDate()
                : from;
    }

    // filledAmount 파싱 — 명시값 우선, null·공백이면 price × quantity로 산출 (nullable 가드)
    private BigDecimal parseAmountUsd(String amtStr, BigDecimal price, int filledQuantity) {
        return (amtStr != null && !amtStr.isBlank())
                ? new BigDecimal(amtStr)
                : price.multiply(BigDecimal.valueOf(filledQuantity));
    }

    // LOC/MOC → 장마감 지정가(CLS), LIMIT → 정규장 지정가(DAY)
    private String resolveTimeInForce(Order.OrderType type) {
        return switch (type) {
            case LOC, MOC -> "CLS"; // MOC 미지원 → LIMIT+CLS로 장마감 경매 참여
            case LIMIT -> "DAY";
        };
    }

    // MOC 대체 주문: 최저가(0.01)로 장마감 경매에서 시장가처럼 체결되도록 유도
    private BigDecimal resolvePrice(Order.OrderType type, BigDecimal price) {
        return type == Order.OrderType.MOC ? new BigDecimal("0.01") : price;
    }

    // package-private — TossOrderApiTest에서 직접 생성하여 stub에 사용
    record OrderResponse(
        @JsonProperty("orderId") String orderId,
        @JsonProperty("clientOrderId") String clientOrderId
    ) {}

    // GET /api/v1/orders 응답 — package-private으로 테스트에서 직접 생성 가능
    record OrdersResponse(
        @JsonProperty("orders")    List<OrderItem> orders,
        @JsonProperty("nextCursor") String nextCursor,
        @JsonProperty("hasNext")    Boolean hasNext
    ) {}

    record OrderItem(
        @JsonProperty("orderId")   String orderId,
        @JsonProperty("symbol")    String symbol,
        @JsonProperty("side")      String side,       // BUY / SELL
        @JsonProperty("status")    String status,
        @JsonProperty("execution") OrderExecutionItem execution
    ) {}

    record OrderExecutionItem(
        @JsonProperty("filledQuantity")      String filledQuantity,      // nullable
        @JsonProperty("averageFilledPrice")  String averageFilledPrice,  // nullable
        @JsonProperty("filledAmount")        String filledAmount,         // nullable
        @JsonProperty("filledAt")            String filledAt              // nullable
    ) {}
}
