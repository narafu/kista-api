package com.kista.tradingcore.stats.adapter.in.web;

import com.kista.tradingcore.broker.domain.model.DailyTransaction;
import com.kista.tradingcore.broker.domain.model.DailyTransactionResult;
import com.kista.shared.kernel.Currency;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.trading.domain.model.CycleHistoryPage;
import com.kista.tradingcore.stats.application.service.AccountStatisticsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.kista.shared.support.WebMvcTestSupport.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DashboardController.class)
@Execution(ExecutionMode.SAME_THREAD)
class DashboardControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean AccountStatisticsService accountStatistics;

    private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000099");

    @Test
    void cycleHistory_returns_page_with_date_params() throws Exception {
        var page = new CycleHistoryPage(List.of(), null, false);
        when(accountStatistics.getByAccount(eq(ACCOUNT_ID), any(), any(), any(), isNull(), eq(50)))
                .thenReturn(page);

        mockMvc.perform(get("/api/accounts/" + ACCOUNT_ID + "/cycle-history")
                        .param("from", "2024-01-01").param("to", "2024-12-31")
                        .with(authentication(userToken(DEV_USER_UUID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void cycleHistory_returns_page_without_date_params() throws Exception {
        // '전체' 선택 시 from/to 없어도 200 반환
        var page = new CycleHistoryPage(List.of(), null, false);
        when(accountStatistics.getByAccount(eq(ACCOUNT_ID), any(), isNull(), isNull(), isNull(), eq(50)))
                .thenReturn(page);

        mockMvc.perform(get("/api/accounts/" + ACCOUNT_ID + "/cycle-history")
                        .with(authentication(userToken(DEV_USER_UUID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void cycleHistory_returns_nextCursor_when_hasMore() throws Exception {
        Instant cursor = Instant.parse("2024-06-01T00:00:00Z");
        var page = new CycleHistoryPage(List.of(), cursor, true);
        when(accountStatistics.getByAccount(eq(ACCOUNT_ID), any(), any(), any(), any(), eq(50)))
                .thenReturn(page);

        mockMvc.perform(get("/api/accounts/" + ACCOUNT_ID + "/cycle-history")
                        .param("from", "2024-01-01").param("to", "2024-12-31")
                        .with(authentication(userToken(DEV_USER_UUID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.nextCursor").value("2024-06-01T00:00:00Z"));
    }

    @Test
    void dailyTrades_returns_user_scoped_result_without_account_path() throws Exception {
        var result = new DailyTransactionResult(List.of(new DailyTransaction("2024-01-05", OrderDirection.BUY,
                StrategyTicker.BITO, "BITO", new BigDecimal("3"), new BigDecimal("30000"),
                new BigDecimal("90000"), Currency.KRW)));
        when(accountStatistics.getDailyTransactionsForUser(any(), any(), any())).thenReturn(result);

        mockMvc.perform(get("/api/daily-trades")
                        .param("from", "2024-01-01").param("to", "2024-01-31")
                        .with(authentication(userToken(DEV_USER_UUID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].quantity").value(3))
                .andExpect(jsonPath("$.items[0].tradeAmount").value(90000))
                .andExpect(jsonPath("$.items[0].currency").value("KRW"))
                .andExpect(jsonPath("$.items[0].tradeAmountUsd").doesNotExist())
                .andExpect(jsonPath("$.summary").doesNotExist())
                .andExpect(jsonPath("$.byDate[0].date").value("2024-01-05"))
                .andExpect(jsonPath("$.byDate[0].tradeCount").value(1))
                .andExpect(jsonPath("$.byDate[0].byCurrency[0].currency").value("KRW"))
                .andExpect(jsonPath("$.byDate[0].byCurrency[0].buyAmount").value(90000))
                .andExpect(jsonPath("$.byDate[0].byCurrency[0].buyCount").value(1))
                .andExpect(jsonPath("$.byDate[0].byCurrency[0].sellCount").value(0));
    }
}
