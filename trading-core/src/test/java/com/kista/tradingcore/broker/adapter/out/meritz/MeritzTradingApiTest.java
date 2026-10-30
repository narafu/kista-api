package com.kista.tradingcore.broker.adapter.out.meritz;

import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.broker.adapter.out.internal.CallIntervalGate;
import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.tradingcore.broker.domain.model.Execution;
import com.kista.tradingcore.broker.domain.model.MarginItem;
import com.kista.tradingcore.broker.domain.model.PresentBalanceResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

@ExtendWith(MockitoExtension.class)
class MeritzTradingApiTest {

    private static final String BASE = "https://meritz.test";
    private static final BrokerAccountRef ACCOUNT =
            new BrokerAccountRef(UUID.randomUUID(), "app-key", "secret", null, null, Broker.MERITZ);
    private static final String HOLDINGS = "{\"rsp_cd\":\"5766\",\"data\":[{\"ovrs_stck_symb_iscd\":\"SOXL\",\"rmnd_dqty\":\"417.0000000000\","
            + "\"seln_able_dqty\":\"400.0000000000\",\"avrg_unpr\":\"124.812200\",\"psnt_prc\":\"81.700000\"},"
            + "{\"ovrs_stck_symb_iscd\":\"ZZZZ\",\"rmnd_dqty\":\"3.0000000000\",\"seln_able_dqty\":\"3\",\"avrg_unpr\":\"1\",\"psnt_prc\":\"1\"}]}";
    private static final String ESTIMATE = "{\"rsp_cd\":\"5766\",\"data\":["
            + "{\"crcd\":\"USD\",\"exrt\":\"1325.5000\",\"stnd_deps_camt\":\"6813.2200\",\"oder_crnc_oder_able_camt\":\"3445.0100\"},"
            + "{\"crcd\":\"KRW\",\"exrt\":\"1.0000\",\"stnd_deps_camt\":\"19609.0000\",\"oder_crnc_oder_able_camt\":\"14.0500\"}]}";
    private static final String CURRENCIES = "{\"rsp_cd\":\"5766\",\"data\":[{\"crcd\":\"USD\",\"frcr_amt\":\"6813.220\",\"vltn_exrt\":\"1325.5000\"}]}";

    @Mock MeritzAuthApi authApi;

    private MockRestServiceServer server;
    private MeritzTradingApi api;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        when(authApi.getToken(ACCOUNT)).thenReturn("tok");
        api = new MeritzTradingApi(new MeritzHttpClient(builder.build(), authApi, JsonMapper.builder().build(),
                new CallIntervalGate(0, 20_000), 1));
    }

    private void expect(String path, String body) {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + path)))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body(body));
    }

    @Test
    void sellable_quantity_reads_holdings_not_net_sellable_and_zero_when_unheld() {
        expect("/accounts/v1/me/overseas/holdings", HOLDINGS);
        expect("/accounts/v1/me/overseas/holdings", HOLDINGS);

        assertThat(api.getSellableQuantity(StrategyTicker.SOXL, ACCOUNT).quantity()).isEqualTo(417); // seln_able_dqty(400) 아님
        assertThat(api.getSellableQuantity(StrategyTicker.TQQQ, ACCOUNT).quantity()).isZero();
    }

    @Test
    void buyable_amount_sums_usd_and_krw_rows() {
        expect("/trading/v1/overseas/orders/estimate", ESTIMATE);

        // KRW 행의 USD 환산 주문가능금액도 자동 환전으로 매수에 쓰인다
        assertThat(api.getUsdBuyableAmount(ACCOUNT)).isEqualByComparingTo("3459.0600");
    }

    @Test
    void buyable_amount_uses_krw_row_without_usd_row() {
        expect("/trading/v1/overseas/orders/estimate", "{\"rsp_cd\":\"5766\",\"data\":[{\"crcd\":\"KRW\",\"oder_crnc_oder_able_camt\":\"1\"}]}");

        assertThat(api.getUsdBuyableAmount(ACCOUNT)).isEqualByComparingTo("1");
    }

    @Test
    void margin_has_single_usd_item() {
        expect("/trading/v1/overseas/orders/estimate", ESTIMATE);

        List<MarginItem> m = api.getMargin(ACCOUNT);

        assertThat(m).singleElement().satisfies(i -> {
            assertThat(i.purchasableAmount()).isEqualByComparingTo("3459.0600");
            assertThat(i.integratedOrderableAmount()).isEqualByComparingTo("3459.0600");
            assertThat(i.foreignOrderableAmount()).isEqualByComparingTo("3445.0100");
            assertThat(i.usdToKrwRate()).isEqualByComparingTo("1325.5");
        });
    }

    @Test
    void executions_convert_us_dates_and_filter_filled() {
        String row = "{\"oder_date\":\"20260901\",\"oder_no\":1295619,\"seln_byov_clcd\":\"2\",\"iscd\":\"SOXL.AX\","
                + "\"oder_rctf_cncl_cls_code\":\"0\",\"cntg_dqty\":\"36.0000000000\",\"cntg_crnc_prc\":\"106.04440000\",\"cntg_camt\":\"3817.6000\"}";
        String unfilled = row.replace("36.0000000000", "0").replace("1295619", "1");
        String other = row.replace("\"SOXL.AX\"", "\"TQQQ.OQ\"").replace("1295619", "2");
        String cancel = row.replace("\"oder_rctf_cncl_cls_code\":\"0\"", "\"oder_rctf_cncl_cls_code\":\"2\"").replace("1295619", "3");
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/trading/v1/overseas/orders/history")))
                .andExpect(queryParam("oder_sta_date", "20260901"))
                .andExpect(queryParam("oder_fin_date", "20260901"))
                .andExpect(queryParam("cntg_cls_code", "0"))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"rsp_cd\":\"5766\",\"data\":[" + String.join(",", row, unfilled, other, cancel) + "]}"));

        List<Execution> result = api.getExecutions(LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 2), StrategyTicker.SOXL, ACCOUNT);

        assertThat(result).singleElement().satisfies(e -> {
            assertThat(e.tradeDate()).isEqualTo(LocalDate.of(2026, 9, 2));
            assertThat(e.ticker()).isEqualTo(StrategyTicker.SOXL);
            assertThat(e.direction()).isEqualTo(OrderDirection.BUY);
            assertThat(e.quantity()).isEqualTo(36);
            assertThat(e.price()).isEqualByComparingTo("106.04440000");
            assertThat(e.amountUsd()).isEqualByComparingTo("3817.6000");
            assertThat(e.externalOrderId()).isEqualTo("1295619");
        });
    }

    @Test
    void present_balance_aggregates_holdings_with_usd_and_krw_deposit() {
        expect("/accounts/v1/me/overseas/holdings", HOLDINGS);
        expect("/accounts/v1/me/overseas/currencies", CURRENCIES);
        expect("/trading/v1/overseas/orders/estimate", ESTIMATE);

        PresentBalanceResult r = api.getPresentBalance(ACCOUNT);

        assertThat(r.items()).singleElement().satisfies(i -> {
            assertThat(i.ticker()).isEqualTo(StrategyTicker.SOXL);
            assertThat(i.holdings()).isEqualTo(417);
            assertThat(i.evalAmountUsd()).isEqualByComparingTo("34068.90");
        });
        assertThat(r.exchangeRateKrwPerUsd()).isEqualByComparingTo("1325.5");
        // USD 예수금 + KRW 예수금(19609/1325.5=14.79) 환산
        assertThat(r.usdDepositActual()).isEqualByComparingTo("6828.01");
    }
}
