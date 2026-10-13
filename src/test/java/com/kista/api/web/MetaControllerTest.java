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
                .andExpect(jsonPath("$.strategyTypes[?(@.code=='DCA')].orderTypes").value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.contains("MOO", "MOC"))))
                .andExpect(jsonPath("$.strategyTypes[?(@.code=='DCA')].directions").value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.contains("BUY", "SELL"))))
                .andExpect(jsonPath("$.strategyTypes[?(@.code=='DCA')].frequencies").value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.contains("DAILY", "WEEKLY", "MONTHLY"))))
                .andExpect(jsonPath("$.strategyTypes[?(@.code=='DCA')].conditionIndicators").value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.contains("AVG_PRICE", "SMA", "RSI", "FEAR_GREED"))))
                .andExpect(jsonPath("$.orderTypes.length()").value(com.kista.shared.kernel.OrderType.values().length))
                .andExpect(jsonPath("$.orderTypes[?(@.code=='MOO')].label").value(org.hamcrest.Matchers.contains("MOO")))
                .andExpect(jsonPath("$.orderDirections[0].label").value("매수"))
                .andExpect(jsonPath("$.dcaFrequencies[2].code").value("MONTHLY"))
                .andExpect(jsonPath("$.dcaOperators[0].label").value("이하"))
                .andExpect(jsonPath("$.dcaSkipReasons[0].code").value("DATA_UNAVAILABLE"))
                .andExpect(jsonPath("$.dcaIndicators.length()").value(4))
                .andExpect(jsonPath("$.dcaIndicators[?(@.code=='FEAR_GREED')]").isNotEmpty())
                .andExpect(jsonPath("$.dcaIndicators[?(@.code=='SMA')].smaPeriods[0]").value(org.hamcrest.Matchers.contains(20)))
                .andExpect(jsonPath("$.dcaIndicators[?(@.code=='RSI')].smaPeriods").value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.empty())));
    }
}
