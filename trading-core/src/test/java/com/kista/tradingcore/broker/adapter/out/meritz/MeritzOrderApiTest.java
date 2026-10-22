package com.kista.tradingcore.broker.adapter.out.meritz;

import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderType;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.broker.adapter.out.internal.CallIntervalGate;
import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.tradingcore.broker.domain.model.CancelInstruction;
import com.kista.tradingcore.broker.domain.model.OrderInstruction;
import com.kista.tradingcore.broker.domain.model.meritz.MeritzApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MeritzOrderApiTest {

    private static final String BASE = "https://meritz.test";
    private static final BrokerAccountRef ACCOUNT =
            new BrokerAccountRef(UUID.randomUUID(), "app-key", "secret", null, null, Broker.MERITZ);
    private static final String ORDERS = BASE + "/trading/v1/overseas/orders/";

    @Mock MeritzAuthApi authApi;

    private MockRestServiceServer server;
    private MeritzOrderApi api;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        when(authApi.getToken(ACCOUNT)).thenReturn("tok");
        api = new MeritzOrderApi(new MeritzHttpClient(builder.build(), authApi, JsonMapper.builder().build(),
                new CallIntervalGate(0, 20_000), 1), new MeritzExchangeRegistry());
    }

    private static String orderResponse(String warn, long orderNo) {
        return "{\"rsp_cd\":\"0001\",\"data\":{\"oder_no\":" + orderNo + ",\"warn_cls_code\":\"" + warn + "\",\"warn_msg\":\"가격 괴리\"}}";
    }

    private static org.springframework.test.web.client.response.DefaultResponseCreator ok(String body) {
        return withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private static OrderInstruction soxl(OrderType type, OrderDirection dir) {
        return new OrderInstruction(StrategyTicker.SOXL, dir, type, 30, new BigDecimal("82.111"));
    }

    @ParameterizedTest
    @CsvSource({
            "LOC,   BUY,  buy,  32, 7, 82.11",
            "LOC,   SELL, sell, 32, 7, 82.11",
            "MOC,   BUY,  buy,  32, 7, 82.11",
            "MOC,   SELL, sell, 34, 7, 0",
            "LIMIT, BUY,  buy,  01, 0, 82.11",
            "LIMIT, SELL, sell, 01, 0, 82.11",
            "MOO,   BUY,  buy,  01, 0, 82.11",
            "MOO,   SELL, sell, 05, 0, 0",
    })
    void place_maps_order_type(OrderType type, OrderDirection dir, String path, String cls, String cond, String price) {
        server.expect(requestTo(ORDERS + path)).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.ovrs_stck_symb_iscd").value("SOXL"))
                .andExpect(jsonPath("$.ovrs_exch_vndr_idno").value("0066"))
                .andExpect(jsonPath("$.oder_cls_code").value(cls))
                .andExpect(jsonPath("$.oder_cond_cls_code").value(cond))
                .andExpect(jsonPath("$.odqt").value("30"))
                .andExpect(jsonPath("$.ovrs_stck_oder_prc").value(price))
                .andExpect(jsonPath("$.warn_cnfr_yn").doesNotExist())
                .andRespond(ok(orderResponse("0", 2700586)));

        assertThat(api.place(soxl(type, dir), ACCOUNT).externalOrderId()).isEqualTo("2700586");
        server.verify();
    }

    @Test
    void place_market_throws_illegal_argument() {
        assertThatThrownBy(() -> api.place(soxl(OrderType.MARKET, OrderDirection.BUY), ACCOUNT))
                .isInstanceOf(IllegalArgumentException.class);
        server.verify();
    }

    @Test
    void place_priced_type_with_null_price_throws_without_sending() {
        OrderInstruction noPrice = new OrderInstruction(StrategyTicker.SOXL, OrderDirection.SELL, OrderType.LOC, 30, null);
        assertThatThrownBy(() -> api.place(noPrice, ACCOUNT)).isInstanceOf(IllegalArgumentException.class);
        server.verify(); // 요청 기대 없음 → 송신됐으면 실패
    }

    @Test
    void place_market_sell_with_null_price_sends_zero() {
        server.expect(requestTo(ORDERS + "sell")).andExpect(jsonPath("$.oder_cls_code").value("34"))
                .andExpect(jsonPath("$.ovrs_stck_oder_prc").value("0"))
                .andRespond(ok(orderResponse("0", 2700586)));
        OrderInstruction moc = new OrderInstruction(StrategyTicker.SOXL, OrderDirection.SELL, OrderType.MOC, 30, null);
        assertThat(api.place(moc, ACCOUNT).externalOrderId()).isEqualTo("2700586");
        server.verify();
    }

    @Test
    void place_null_quantity_throws_without_sending() {
        OrderInstruction noQty = new OrderInstruction(StrategyTicker.SOXL, OrderDirection.BUY, OrderType.LOC, null, new BigDecimal("82.11"));
        assertThatThrownBy(() -> api.place(noQty, ACCOUNT)).isInstanceOf(IllegalArgumentException.class);
        server.verify();
    }

    @Test
    void place_warning_1_resends_once_with_confirm_yes() {
        server.expect(requestTo(ORDERS + "buy")).andExpect(jsonPath("$.warn_cnfr_yn").doesNotExist())
                .andRespond(ok(orderResponse("1", 0)));
        server.expect(requestTo(ORDERS + "buy")).andExpect(jsonPath("$.warn_cnfr_yn").value("Y"))
                .andExpect(jsonPath("$.oder_cls_code").value("32"))
                .andExpect(jsonPath("$.odqt").value("30"))
                .andRespond(ok(orderResponse("0", 2700586)));

        assertThat(api.place(soxl(OrderType.LOC, OrderDirection.BUY), ACCOUNT).externalOrderId()).isEqualTo("2700586");
        server.verify(); // 정확히 2건
    }

    @Test
    void place_hold_2_is_rejected_without_resend() {
        server.expect(ExpectedCount.once(), requestTo(ORDERS + "buy")).andRespond(ok(orderResponse("2", 0)));

        assertThatThrownBy(() -> api.place(soxl(OrderType.LOC, OrderDirection.BUY), ACCOUNT))
                .isInstanceOfSatisfying(MeritzApiException.class, e -> {
                    assertThat(e.isRejected()).isTrue();
                    assertThat(e.rspCd()).isEqualTo("WARN_2");
                });
        server.verify();
    }

    @Test
    void place_warning_persisting_after_resend_is_rejected() {
        server.expect(requestTo(ORDERS + "sell")).andRespond(ok(orderResponse("3", 0)));
        server.expect(requestTo(ORDERS + "sell")).andExpect(jsonPath("$.warn_cnfr_yn").value("Y"))
                .andRespond(ok(orderResponse("3", 0)));

        assertThatThrownBy(() -> api.place(soxl(OrderType.LOC, OrderDirection.SELL), ACCOUNT))
                .isInstanceOfSatisfying(MeritzApiException.class, e -> {
                    assertThat(e.isRejected()).isTrue();
                    assertThat(e.rspCd()).isEqualTo("WARN_3");
                });
        server.verify(); // 재전송은 1회뿐
    }

    @Test
    void place_accepted_without_order_no_is_unknown() {
        server.expect(requestTo(ORDERS + "buy")).andRespond(ok(orderResponse("0", 0)));

        assertThatThrownBy(() -> api.place(soxl(OrderType.LOC, OrderDirection.BUY), ACCOUNT))
                .isInstanceOfSatisfying(MeritzApiException.class, e -> assertThat(e.isRejected()).isFalse());
    }

    private static String todayRow(long orderNo, String aspr, int cancelable, int ordered, int filled) {
        return "{\"oder_date\":\"20261022\",\"oder_no\":" + orderNo + ",\"aspr_type_code\":\"" + aspr + "\",\"odqt\":" + ordered
                + ",\"cntg_qty\":" + filled + ",\"oder_able_qty\":" + cancelable + ",\"ovrs_stck_symb_iscd\":\"SOXL\"}";
    }

    private void expectToday(String rows) {
        server.expect(requestTo(startsWith(ORDERS + "today"))).andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("cntg_cls_code", "0"))
                .andExpect(queryParam("seln_byov_clcd", "0"))
                .andRespond(ok("{\"rsp_cd\":\"5766\",\"data\":[" + rows + "]}"));
    }

    @Test
    void cancel_uses_today_row_remaining_qty_and_mapped_code() {
        expectToday(todayRow(2700414, "8", 0, 40, 0) + "," + todayRow(2700586, "5", 30, 36, 6));
        server.expect(requestTo(ORDERS + "cancel")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.oder_date").value("20261022"))
                .andExpect(jsonPath("$.orgl_oder_no").value("2700586"))
                .andExpect(jsonPath("$.oder_cls_code").value("32"))
                .andExpect(jsonPath("$.odqt").value("30"))
                .andRespond(ok(orderResponse("0", 2700999)));

        api.cancel(new CancelInstruction(StrategyTicker.SOXL, "2700586"), ACCOUNT);
        server.verify();
    }

    @ParameterizedTest
    @CsvSource({"1,05", "2,01", "4,31", "5,32", "6,33", "7,34", "8,35", "9,36", "A,37", "B,38"})
    void cancel_maps_aspr_type_to_order_class(String aspr, String cls) {
        expectToday(todayRow(2700586, aspr, 5, 5, 0));
        server.expect(requestTo(ORDERS + "cancel")).andExpect(jsonPath("$.oder_cls_code").value(cls))
                .andRespond(ok(orderResponse("0", 2700999)));

        api.cancel(new CancelInstruction(StrategyTicker.SOXL, "2700586"), ACCOUNT);
        server.verify();
    }

    @Test
    void cancel_unknown_aspr_type_is_rejected_without_sending() {
        expectToday(todayRow(2700586, "Z", 5, 5, 0));
        assertThatThrownBy(() -> api.cancel(new CancelInstruction(StrategyTicker.SOXL, "2700586"), ACCOUNT))
                .isInstanceOfSatisfying(MeritzApiException.class, e -> assertThat(e.isRejected()).isTrue());
        server.verify(); // cancel POST 없음
    }

    @Test
    void cancel_warning_resends_once_with_confirm_yes() {
        expectToday(todayRow(2700586, "2", 10, 10, 0));
        server.expect(requestTo(ORDERS + "cancel")).andRespond(ok(orderResponse("1", 0)));
        server.expect(requestTo(ORDERS + "cancel")).andExpect(jsonPath("$.warn_cnfr_yn").value("Y"))
                .andExpect(jsonPath("$.oder_cls_code").value("01"))
                .andRespond(ok(orderResponse("0", 2700999)));

        api.cancel(new CancelInstruction(StrategyTicker.SOXL, "2700586"), ACCOUNT);
        server.verify();
    }

    @Test
    void cancel_terminal_order_throws_conflict_without_calling_cancel_api() {
        expectToday(todayRow(2700586, "2", 0, 36, 0)); // 만료·취소
        assertThatThrownBy(() -> api.cancel(new CancelInstruction(StrategyTicker.SOXL, "2700586"), ACCOUNT))
                .isInstanceOfSatisfying(MeritzApiException.class, e -> {
                    assertThat(e.isAlreadyCanceledConflict()).isTrue();
                    assertThat(e.isAlreadyFilledConflict()).isFalse();
                });
        server.verify(); // cancel POST 기대 없음 → 호출됐으면 실패

        server.reset();
        expectToday(todayRow(2700586, "5", 0, 36, 36)); // 전량 체결
        assertThatThrownBy(() -> api.cancel(new CancelInstruction(StrategyTicker.SOXL, "2700586"), ACCOUNT))
                .isInstanceOfSatisfying(MeritzApiException.class, e -> assertThat(e.isAlreadyFilledConflict()).isTrue());
        server.verify();
    }

    @Test
    void cancel_row_without_order_quantity_is_unknown_not_filled() {
        expectToday("{\"oder_date\":\"20261022\",\"oder_no\":2700586,\"aspr_type_code\":\"2\",\"oder_able_qty\":0}");
        assertThatThrownBy(() -> api.cancel(new CancelInstruction(StrategyTicker.SOXL, "2700586"), ACCOUNT))
                .isInstanceOfSatisfying(MeritzApiException.class, e -> {
                    assertThat(e.isRejected()).isFalse();
                    assertThat(e.isAlreadyFilledConflict()).isFalse();
                    assertThat(e.isAlreadyCanceledConflict()).isFalse();
                });
        server.verify(); // cancel POST 없음
    }

    @Test
    void cancel_falls_back_to_history_then_unknown() {
        String historyRow = "{\"oder_date\":\"20261020\",\"oder_no\":1295619,\"aspr_type_code\":\"7\","
                + "\"oder_dqty\":\"36.0000000000\",\"cntg_dqty\":\"6.0000000000\",\"ncnt_odqt\":30}";
        expectToday(todayRow(2700414, "8", 0, 40, 0));
        server.expect(requestTo(startsWith(ORDERS + "history"))).andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("cntg_cls_code", "0"))
                .andExpect(queryParam("oder_sta_date", org.hamcrest.Matchers.matchesPattern("\\d{8}")))
                .andExpect(queryParam("oder_fin_date", org.hamcrest.Matchers.matchesPattern("\\d{8}")))
                .andRespond(ok("{\"rsp_cd\":\"5766\",\"data\":[" + historyRow + "]}"));
        server.expect(requestTo(ORDERS + "cancel"))
                .andExpect(jsonPath("$.oder_date").value("20261020"))
                .andExpect(jsonPath("$.orgl_oder_no").value("1295619"))
                .andExpect(jsonPath("$.oder_cls_code").value("34"))
                .andExpect(jsonPath("$.odqt").value("30"))
                .andRespond(ok(orderResponse("0", 1)));

        api.cancel(new CancelInstruction(StrategyTicker.SOXL, "1295619"), ACCOUNT);
        server.verify();

        server.reset();
        expectToday("");
        server.expect(requestTo(startsWith(ORDERS + "history"))).andRespond(ok("{\"rsp_cd\":\"5820\",\"data\":[]}"));
        assertThatThrownBy(() -> api.cancel(new CancelInstruction(StrategyTicker.SOXL, "9999999"), ACCOUNT))
                .isInstanceOfSatisfying(MeritzApiException.class, e -> {
                    assertThat(e.isRejected()).isFalse();
                    assertThat(e.isAlreadyCanceledConflict()).isFalse();
                    assertThat(e.isAlreadyFilledConflict()).isFalse();
                });
        server.verify();
    }
}
