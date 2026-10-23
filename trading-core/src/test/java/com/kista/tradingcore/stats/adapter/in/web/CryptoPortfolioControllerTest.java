package com.kista.tradingcore.stats.adapter.in.web;

import com.kista.shared.kernel.Currency;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.stats.application.usecase.CryptoPortfolioUseCase;
import com.kista.tradingcore.stats.domain.model.CryptoPortfolio;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.kista.shared.support.WebMvcTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CryptoPortfolioController.class)
@Execution(ExecutionMode.SAME_THREAD)
class CryptoPortfolioControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean CryptoPortfolioUseCase useCase;

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    @Test
    void returnsKrwPortfolio() throws Exception {
        when(useCase.getCryptoPortfolio(ACCOUNT_ID, USER_ID)).thenReturn(new CryptoPortfolio(Currency.KRW, new BigDecimal("1000"),
                List.of(new CryptoPortfolio.Holding(StrategyTicker.BTC, new BigDecimal("0.0015"), new BigDecimal("140000000"),
                        new BigDecimal("150000000"), new BigDecimal("225000")))));

        mockMvc.perform(get("/api/accounts/{id}/crypto-portfolio", ACCOUNT_ID).with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.krwCash").value(1000))
                .andExpect(jsonPath("$.holdings[0].ticker").value("BTC"))
                .andExpect(jsonPath("$.holdings[0].quantity").value(0.0015))
                .andExpect(jsonPath("$.totalAssetKrw").value(226000));
    }

    @Test
    void jsonNumbersArePlainDecimalsWithoutExponent() throws Exception {
        // stripTrailingZeros 정규화 값(5E-7, 1.505E+8)이 지수 표기로 나가면 안 된다
        when(useCase.getCryptoPortfolio(ACCOUNT_ID, USER_ID)).thenReturn(new CryptoPortfolio(Currency.KRW, new BigDecimal("1E+3"),
                List.of(new CryptoPortfolio.Holding(StrategyTicker.BTC, new BigDecimal("5E-7"), new BigDecimal("1.505E+8"),
                        new BigDecimal("1.505E+8"), new BigDecimal("75")))));

        String body = mockMvc.perform(get("/api/accounts/{id}/crypto-portfolio", ACCOUNT_ID).with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("E").doesNotContain("e+")
                .contains("\"quantity\":0.0000005").contains("\"avgBuyPrice\":150500000").contains("\"krwCash\":1000");
    }

    @Test
    void stockAccountReturns400() throws Exception {
        when(useCase.getCryptoPortfolio(ACCOUNT_ID, USER_ID)).thenThrow(new IllegalArgumentException("코인 계좌가 아닙니다."));

        mockMvc.perform(get("/api/accounts/{id}/crypto-portfolio", ACCOUNT_ID).with(authentication(userToken(USER_ID))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnsKrwCryptoPricesWithoutExponent() throws Exception {
        when(useCase.getCurrentPrices(ACCOUNT_ID, USER_ID, List.of(StrategyTicker.ETH, StrategyTicker.BTC)))
                .thenReturn(Map.of(StrategyTicker.BTC, new BigDecimal("1.5E+8"), StrategyTicker.ETH, new BigDecimal("5000000")));

        String body = mockMvc.perform(get("/api/accounts/{id}/crypto-prices", ACCOUNT_ID).param("tickers", "ETH,BTC,ETH")
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prices[0].ticker").value("BTC"))
                .andExpect(jsonPath("$.prices[1].ticker").value("ETH"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"price\":150000000").doesNotContain("E+");
    }

    @Test
    void unknownTickerReturns400() throws Exception {
        mockMvc.perform(get("/api/accounts/{id}/crypto-prices", ACCOUNT_ID).param("tickers", "DOGE")
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isBadRequest());
    }
}
