package com.kista.tradingcore.broker.adapter.out.kis;

import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.tradingcore.broker.domain.model.Execution;
import com.kista.tradingcore.broker.domain.model.PresentBalanceResult;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.StrategyTicker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.math.BigDecimal;
import java.util.function.Consumer;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("KisTradingApi 단위 테스트")
class KisTradingApiTest {

    @Mock KisHttpClient kisHttpClient;
    @Spy KisExchangeRegistry exchangeRegistry = new KisExchangeRegistry();
    @InjectMocks KisTradingApi api;

    private static final BrokerAccountRef ACCOUNT = new BrokerAccountRef(
            UUID.randomUUID(), "appKey", "appSecret",
            "74420614", null,
            Broker.KIS
    );

    @Nested
    @DisplayName("PortfolioPort — 체결기준현재잔고")
    class PortfolioTests {

        @Test
        @DisplayName("API 응답 정상 시 종목 목록과 요약 반환")
        void getPresentBalance_returnsResult_whenApiSucceeds() {
            KisTradingApi.PortfolioResponse response = new KisTradingApi.PortfolioResponse(
                    List.of(new KisTradingApi.PortfolioResponse.Output1(
                            "SOXL", "10", "20.00", "22.00", "220.00", "20.00", "10.0", "NASD"
                    )),
                    new KisTradingApi.PortfolioResponse.Output3("1000.00", "20.00", "2.0")
            );
            when(kisHttpClient.tradingGet(anyString(), anyString(), any(), eq(KisTradingApi.PortfolioResponse.class), any()))
                    .thenReturn(response);

            PresentBalanceResult result = api.getPresentBalance(ACCOUNT);

            assertThat(result.items()).hasSize(1);
            assertThat(result.items().getFirst().ticker()).isEqualTo(StrategyTicker.SOXL);
            assertThat(result.items().getFirst().holdings()).isEqualTo(10);
            assertThat(result.totalAssetUsd()).isEqualByComparingTo("1000.00");
            assertThat(result.totalReturnRate()).isEqualByComparingTo("2.0");
        }

        @Test
        @DisplayName("null 응답 시 빈 목록과 0 반환")
        void getPresentBalance_returnsEmpty_whenApiReturnsNull() {
            when(kisHttpClient.tradingGet(anyString(), anyString(), any(), eq(KisTradingApi.PortfolioResponse.class), any()))
                    .thenReturn(null);

            PresentBalanceResult result = api.getPresentBalance(ACCOUNT);

            assertThat(result.items()).isEmpty();
            assertThat(result.totalAssetUsd()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("TR_ID CTRP6504R로 조회")
        void getPresentBalance_usesTrId() {
            when(kisHttpClient.tradingGet(anyString(), anyString(), any(), any(), any())).thenReturn(null);

            api.getPresentBalance(ACCOUNT);

            verify(kisHttpClient).tradingGet(eq("CTRP6504R"), anyString(), eq(ACCOUNT), any(), any());
        }
    }

    @Nested
    @DisplayName("KisExecutionPort — 체결 내역")
    class ExecutionTests {

        private static final LocalDate DATE = LocalDate.of(2024, 6, 15);

        @Test
        @DisplayName("null 응답: 빈 리스트 반환")
        void getExecutions_nullResponse_returnsEmptyList() {
            when(kisHttpClient.tradingGet(anyString(), anyString(), any(), any(), any())).thenReturn(null);

            List<Execution> result = api.getExecutions(DATE, DATE, StrategyTicker.SOXL, ACCOUNT);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("output=null 응답: 빈 리스트 반환")
        void getExecutions_nullOutput_returnsEmptyList() {
            KisTradingApi.ExecutionListResponse response = new KisTradingApi.ExecutionListResponse(null);
            when(kisHttpClient.tradingGet(anyString(), anyString(), any(), any(), any())).thenReturn(response);

            List<Execution> result = api.getExecutions(DATE, DATE, StrategyTicker.SOXL, ACCOUNT);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("sll_buy_dvsn_cd=01: SELL 방향 반환")
        void getExecutions_selnByovCls01_returnsSell() {
            KisTradingApi.ExecutionListResponse response = new KisTradingApi.ExecutionListResponse(
                    List.of(new KisTradingApi.ExecutionListResponse.OutputItem(
                            "SOXL", "20240614", "01", "5", "5", "0", "25.00", "125.00", "ORD001"
                    ))
            );
            when(kisHttpClient.tradingGet(anyString(), anyString(), any(), any(), any())).thenReturn(response);

            List<Execution> result = api.getExecutions(DATE, DATE, StrategyTicker.SOXL, ACCOUNT);

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().direction()).isEqualTo(OrderDirection.SELL);
        }

        @Test
        @DisplayName("sll_buy_dvsn_cd=02: BUY 방향, 필드 정상 파싱")
        void getExecutions_otherSelnByovCls_returnsBuyWithFields() {
            KisTradingApi.ExecutionListResponse response = new KisTradingApi.ExecutionListResponse(
                    List.of(new KisTradingApi.ExecutionListResponse.OutputItem(
                            "SOXL", "20240614", "02", "10", "10", "0", "30.50", "305.00", "ORD002"
                    ))
            );
            when(kisHttpClient.tradingGet(anyString(), anyString(), any(), any(), any())).thenReturn(response);

            List<Execution> result = api.getExecutions(DATE, DATE, StrategyTicker.SOXL, ACCOUNT);

            assertThat(result).hasSize(1);
            Execution e = result.getFirst();
            assertThat(e.direction()).isEqualTo(OrderDirection.BUY);
            assertThat(e.quantity()).isEqualTo(10);
            assertThat(e.price()).isEqualByComparingTo("30.50");
            assertThat(e.amountUsd()).isEqualByComparingTo("305.00");
            assertThat(e.externalOrderId()).isEqualTo("ORD002");
            assertThat(e.ticker()).isEqualTo(StrategyTicker.SOXL);
            assertThat(e.tradeDate()).isEqualTo(DATE);
        }

        @Test
        @DisplayName("빈 문자열 수량(ft_ccld_qty=''): 미체결로 간주되어 결과에서 제외됨")
        void getExecutions_blankFields_parsedSafely() {
            KisTradingApi.ExecutionListResponse response = new KisTradingApi.ExecutionListResponse(
                    List.of(new KisTradingApi.ExecutionListResponse.OutputItem(
                            "SOXL", "20240614", "02", "", "", "", "", "", "ORD003"
                    ))
            );
            when(kisHttpClient.tradingGet(anyString(), anyString(), any(), any(), any())).thenReturn(response);

            List<Execution> result = api.getExecutions(DATE, DATE, StrategyTicker.SOXL, ACCOUNT);

            // ft_ccld_qty="" → parseIntSafe → 0 → 미체결 필터에 의해 제외
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("findOrder: odno가 일치하는 행의 주문·체결·미체결 수량 반환")
        void findOrder_matchesOdno() {
            KisTradingApi.ExecutionListResponse response = new KisTradingApi.ExecutionListResponse(List.of(
                    new KisTradingApi.ExecutionListResponse.OutputItem(
                            "SOXL", "20240614", "02", "10", "4", "6", "30.50", "122.00", "ORD_A"),
                    new KisTradingApi.ExecutionListResponse.OutputItem(
                            "SOXL", "20240614", "02", "7", "7", "0", "30.50", "213.50", "ORD_B")));
            when(kisHttpClient.tradingGet(anyString(), anyString(), any(), any(), any())).thenReturn(response);

            assertThat(api.findOrder(StrategyTicker.SOXL, "ORD_A", ACCOUNT))
                    .contains(new KisTradingApi.OrderStatusView(10, 4, 6));
        }

        @Test
        @DisplayName("findOrder: 일치하는 odno·다른 종목 행만 있으면 empty")
        void findOrder_notFound_returnsEmpty() {
            KisTradingApi.ExecutionListResponse response = new KisTradingApi.ExecutionListResponse(List.of(
                    new KisTradingApi.ExecutionListResponse.OutputItem(
                            "SOXL", "20240614", "02", "10", "4", "6", "30.50", "122.00", "ORD_A"),
                    new KisTradingApi.ExecutionListResponse.OutputItem(
                            "TQQQ", "20240614", "02", "10", "4", "6", "30.50", "122.00", "ORD_T")));
            when(kisHttpClient.tradingGet(anyString(), anyString(), any(), any(), any())).thenReturn(response);

            assertThat(api.findOrder(StrategyTicker.SOXL, "ORD_T", ACCOUNT)).isEmpty();
            assertThat(api.findOrder(StrategyTicker.SOXL, "NOPE", ACCOUNT)).isEmpty();
        }

        // tradingGet에 넘긴 파라미터 Consumer를 실행해 SORT_SQN 값을 꺼낸다
        @SuppressWarnings("unchecked")
        private String capturedSortSqn() {
            ArgumentCaptor<Consumer<MultiValueMap<String, String>>> captor = ArgumentCaptor.forClass(Consumer.class);
            verify(kisHttpClient).tradingGet(anyString(), anyString(), any(), any(), captor.capture());
            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            captor.getValue().accept(params);
            return params.getFirst("SORT_SQN");
        }

        @Test
        @DisplayName("findOrder: SORT_SQN=AS(역순)로 조회")
        void findOrder_usesAscendingSortSqnAS() {
            when(kisHttpClient.tradingGet(anyString(), anyString(), any(), any(), any())).thenReturn(null);

            api.findOrder(StrategyTicker.SOXL, "ORD_A", ACCOUNT);

            assertThat(capturedSortSqn()).isEqualTo("AS");
        }

        @Test
        @DisplayName("getExecutions: SORT_SQN=DS(정순) 유지")
        void getExecutions_keepsSortSqnDS() {
            when(kisHttpClient.tradingGet(anyString(), anyString(), any(), any(), any())).thenReturn(null);

            api.getExecutions(DATE, DATE, StrategyTicker.SOXL, ACCOUNT);

            assertThat(capturedSortSqn()).isEqualTo("DS");
        }
    }

}
