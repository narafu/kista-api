package com.kista.api.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static com.kista.shared.support.WebMvcTestSupport.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.kista.api.admin.application.port.output.AppErrorLogPort;
import com.kista.shared.kernel.StrategyType;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyCycleSeedType;

@WebMvcTest(MetaController.class)
@Execution(ExecutionMode.SAME_THREAD)
class MetaControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean AppErrorLogPort appErrorLogPort;

    @Test
    void getBundle_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/api/meta"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getBundle_authenticated_returns200WithAllSections() throws Exception {
        int strategyTypeCount = StrategyType.values().length;
        int tickerCount = StrategyTicker.values().length;

        mockMvc.perform(get("/api/meta")
                        .with(authentication(userToken(DEV_USER_UUID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strategyTypes").isArray())
                .andExpect(jsonPath("$.strategyTypes.length()").value(strategyTypeCount))
                .andExpect(jsonPath("$.tickers").isArray())
                .andExpect(jsonPath("$.tickers.length()").value(tickerCount))
                .andExpect(jsonPath("$.brokers").isArray())
                .andExpect(jsonPath("$.strategyStatuses").isArray());
    }

    // capability는 shared.kernel 상수에서 직접 조립한다 — 내부 API(RestClient) 없이 JSON shape 유지
    // StrategyType 선언 순서(INFINITE, PRIVACY, VR)대로 직렬화된다
    @Test
    void getBundle_authenticated_strategyTypesCarryCapabilityFields() throws Exception {
        mockMvc.perform(get("/api/meta")
                        .with(authentication(userToken(DEV_USER_UUID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strategyTypes[0].code").value("INFINITE"))
                .andExpect(jsonPath("$.strategyTypes[0].label").value("무한매수법"))
                .andExpect(jsonPath("$.strategyTypes[0].requiresPrivacyBase").value(false))
                .andExpect(jsonPath("$.strategyTypes[0].supportsReverseMode").value(true))
                .andExpect(jsonPath("$.strategyTypes[0].tickerFixed").value(false))
                .andExpect(jsonPath("$.strategyTypes[0].divisionCounts.length()").value(3))
                .andExpect(jsonPath("$.strategyTypes[0].divisionCounts[0]").value(20))
                .andExpect(jsonPath("$.strategyTypes[1].code").value("PRIVACY"))
                .andExpect(jsonPath("$.strategyTypes[1].requiresPrivacyBase").value(true))
                .andExpect(jsonPath("$.strategyTypes[1].tickerFixed").value(true))
                .andExpect(jsonPath("$.strategyTypes[2].code").value("VR"))
                .andExpect(jsonPath("$.strategyTypes[2].availableTickers[0]").value("TQQQ"));
    }

    @Test
    void getBundle_authenticated_includesCycleSeedTypes() throws Exception {
        mockMvc.perform(get("/api/meta")
                        .with(authentication(userToken(DEV_USER_UUID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cycleSeedTypes").isArray())
                .andExpect(jsonPath("$.cycleSeedTypes.length()").value(StrategyCycleSeedType.values().length));
    }

    @Test
    void getBundle_authenticated_includesDcaMeta() throws Exception {
        mockMvc.perform(get("/api/meta")
                        .with(authentication(userToken(DEV_USER_UUID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strategyTypes[?(@.code=='DCA')].orderTypes[0]").value(org.hamcrest.Matchers.contains("MOO")))
                .andExpect(jsonPath("$.strategyTypes[?(@.code=='DCA')].divisionCounts").value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.empty())))
                .andExpect(jsonPath("$.strategyTypes[?(@.code=='INFINITE')].orderTypes").value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.empty())))
                .andExpect(jsonPath("$.strategyTypes[?(@.code=='DCA')].orderTypes").value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.contains("MOO", "MOC", "MARKET"))))
                .andExpect(jsonPath("$.strategyTypes[?(@.code=='DCA')].directions").value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.contains("BUY", "SELL"))))
                .andExpect(jsonPath("$.strategyTypes[?(@.code=='DCA')].frequencies").value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.contains("DAILY", "WEEKLY", "MONTHLY"))))
                .andExpect(jsonPath("$.strategyTypes[?(@.code=='DCA')].conditionIndicators").value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.contains("AVG_PRICE", "SMA", "RSI", "FEAR_GREED"))))
                .andExpect(jsonPath("$.orderTypes.length()").value(com.kista.shared.kernel.OrderType.values().length))
                .andExpect(jsonPath("$.orderTypes[?(@.code=='MOO')].label").value(org.hamcrest.Matchers.contains("MOO")))
                .andExpect(jsonPath("$.orderDirections[0].label").value("매수"))
                .andExpect(jsonPath("$.dcaFrequencies[2].code").value("MONTHLY"))
                .andExpect(jsonPath("$.dcaOperators[0].label").value("이하"))
                .andExpect(jsonPath("$.dcaSkipReasons[0].code").value("DATA_UNAVAILABLE"))
                .andExpect(jsonPath("$.dcaSizingTypes[*].code").value(org.hamcrest.Matchers.contains("AMOUNT", "HOLDING_PERCENT")))
                .andExpect(jsonPath("$.dcaSizingTypes[1].label").value("보유 비율"))
                .andExpect(jsonPath("$.dcaShortfallPolicies[*].code").value(org.hamcrest.Matchers.contains("PARTIAL", "REJECT")))
                .andExpect(jsonPath("$.dcaShortfallPolicies[1].label").value("주문하지 않음"))
                .andExpect(jsonPath("$.dcaIndicators.length()").value(4))
                .andExpect(jsonPath("$.dcaIndicators[?(@.code=='FEAR_GREED')]").isNotEmpty())
                .andExpect(jsonPath("$.dcaIndicators[?(@.code=='SMA')].smaPeriods[0]").value(org.hamcrest.Matchers.contains(20)))
                .andExpect(jsonPath("$.dcaIndicators[?(@.code=='RSI')].smaPeriods").value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.empty())));
    }

    @Test
    void getBundle_exposesMarketAndCurrency() throws Exception {
        mockMvc.perform(get("/api/meta").with(authentication(userToken(DEV_USER_UUID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brokers[?(@.code=='UPBIT')].market").value(org.hamcrest.Matchers.contains("CRYPTO_KRW")))
                .andExpect(jsonPath("$.brokers[?(@.code=='UPBIT')].currency").value(org.hamcrest.Matchers.contains("KRW")))
                .andExpect(jsonPath("$.brokers[?(@.code=='BINANCE')].market").value(org.hamcrest.Matchers.contains("CRYPTO_USDT")))
                .andExpect(jsonPath("$.brokers[?(@.code=='BINANCE')].currency").value(org.hamcrest.Matchers.contains("USDT")))
                .andExpect(jsonPath("$.brokers[?(@.code=='BINANCE')].label").value(org.hamcrest.Matchers.contains("바이낸스")))
                .andExpect(jsonPath("$.brokers[?(@.code=='BINANCE')].minOrderAmount").value(org.hamcrest.Matchers.contains("5")))
                .andExpect(jsonPath("$.brokers[?(@.code=='BINANCE')].amountScale").value(org.hamcrest.Matchers.contains(2)))
                .andExpect(jsonPath("$.brokers[?(@.code=='UPBIT')].minOrderAmount").value(org.hamcrest.Matchers.contains("5000")))
                .andExpect(jsonPath("$.brokers[?(@.code=='UPBIT')].amountScale").value(org.hamcrest.Matchers.contains(0)))
                .andExpect(jsonPath("$.brokers[?(@.code=='KIS')].minOrderAmount").value(org.hamcrest.Matchers.contains("0")))
                .andExpect(jsonPath("$.brokers[?(@.code=='KIS')].market").value(org.hamcrest.Matchers.contains("US_STOCK")))
                .andExpect(jsonPath("$.brokers[?(@.code=='KIS')].description").value(org.hamcrest.Matchers.contains("한투")))
                .andExpect(jsonPath("$.tickers[0].market").doesNotExist())
                .andExpect(jsonPath("$.tickers[?(@.code=='BTC')].markets[*]").value(org.hamcrest.Matchers.containsInAnyOrder("CRYPTO_KRW", "CRYPTO_USDT")))
                .andExpect(jsonPath("$.tickers[?(@.code=='SOXL')].markets[*]").value(org.hamcrest.Matchers.contains("US_STOCK")))
                .andExpect(jsonPath("$.dcaSkipReasons[?(@.code=='API_KEY_EXPIRED')].label").value(org.hamcrest.Matchers.contains("API 키 인증 실패")));
    }
}
