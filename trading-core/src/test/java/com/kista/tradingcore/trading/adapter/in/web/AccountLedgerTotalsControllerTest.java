package com.kista.tradingcore.trading.adapter.in.web;

import com.kista.shared.kernel.Currency;
import com.kista.tradingcore.trading.application.usecase.AccountLedgerTotalsUseCase;
import com.kista.tradingcore.trading.domain.model.AccountLedgerTotals;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static com.kista.shared.support.WebMvcTestSupport.userToken;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AccountLedgerTotalsController.class)
@Execution(ExecutionMode.SAME_THREAD)
class AccountLedgerTotalsControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean AccountLedgerTotalsUseCase accountLedgerTotals;

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000020");

    @Test
    void get_returns200WithTotals() throws Exception {
        when(accountLedgerTotals.get(ACCOUNT_ID, USER_ID)).thenReturn(
                new AccountLedgerTotals(Currency.USD, new BigDecimal("160"), new BigDecimal("50"), new BigDecimal("500")));

        mockMvc.perform(get("/api/accounts/{id}/ledger-totals", ACCOUNT_ID).with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.deposit").value(160))
                .andExpect(jsonPath("$.evaluation").value(50))
                .andExpect(jsonPath("$.unassignedCash").value(500));
    }

    @Test
    void get_nullFieldsSerializedAsNull() throws Exception {
        when(accountLedgerTotals.get(any(), any())).thenReturn(
                new AccountLedgerTotals(Currency.USD, new BigDecimal("160"), null, null));

        mockMvc.perform(get("/api/accounts/{id}/ledger-totals", ACCOUNT_ID).with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evaluation").value(nullValue()))
                .andExpect(jsonPath("$.unassignedCash").value(nullValue()));
    }

    @Test
    void get_notOwner_returns403() throws Exception {
        when(accountLedgerTotals.get(any(), any())).thenThrow(new SecurityException("소유권 불일치"));

        mockMvc.perform(get("/api/accounts/{id}/ledger-totals", ACCOUNT_ID).with(authentication(userToken(USER_ID))))
                .andExpect(status().isForbidden());
    }
}
