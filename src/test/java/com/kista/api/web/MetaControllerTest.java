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
import com.kista.api.admin.application.service.RuntimeConfigService;
import com.kista.api.admin.domain.model.RuntimeSettingsBundle;
import org.junit.jupiter.api.BeforeEach;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import com.kista.shared.kernel.StrategyType;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyCycleSeedType;

@WebMvcTest(MetaController.class)
@Execution(ExecutionMode.SAME_THREAD)
class MetaControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean AppErrorLogPort appErrorLogPort;
    @MockitoBean RuntimeConfigService runtimeConfigService; // 런타임 값 출처 — 기본 설정으로 대역

    @BeforeEach
    void stubRuntimeConfig() {
        when(runtimeConfigService.getSettings()).thenReturn(RuntimeSettingsBundle.defaults());
    }

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
                .andExpect(jsonPath("$.cycleSeedTypes.length()").value(StrategyCycleSeedType.values().length))
                .andExpect(jsonPath("$.cycleSeedTypes[?(@.code=='MAINTAIN')].label").value(org.hamcrest.Matchers.contains("투자금 유지")));
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
                .andExpect(jsonPath("$.orderTypes[?(@.code=='MOO')].label").value(org.hamcrest.Matchers.contains("MOO(시가시장가)")))
                .andExpect(jsonPath("$.orderTypes[0].code").value("LOC"))
                .andExpect(jsonPath("$.orderDirections[0].label").value("매수"))
                .andExpect(jsonPath("$.orderStatuses[*].code").value(org.hamcrest.Matchers.contains(
                        "PLANNED", "SUBMITTING", "PLACED", "FILLED", "PARTIALLY_FILLED", "FAILED", "CANCELLED")))
                .andExpect(jsonPath("$.orderStatuses[?(@.code=='PARTIALLY_FILLED')].label").value(org.hamcrest.Matchers.contains("부분체결")))
                .andExpect(jsonPath("$.orderTimings[0].code").value("AT_CLOSE"))
                .andExpect(jsonPath("$.orderTimings[?(@.code=='AT_OPEN')].label").value(org.hamcrest.Matchers.contains("개장 선접수")))
                .andExpect(jsonPath("$.dcaExecutionStatuses[*].code").value(org.hamcrest.Matchers.contains("ORDERED", "SKIPPED")))
                .andExpect(jsonPath("$.dcaExecutionStatuses[1].label").value("건너뜀"))
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
    void getBundle_exposesBrokerDescription() throws Exception {
        mockMvc.perform(get("/api/meta").with(authentication(userToken(DEV_USER_UUID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brokers[?(@.code=='TOSS')].description").value(org.hamcrest.Matchers.contains("토스")))
                .andExpect(jsonPath("$.brokers[?(@.code=='TOSS')].currency").value(org.hamcrest.Matchers.contains("USD")));
    }

    @Test
    void getBundle_exposesAddedEnumsAndNoStoreCache() throws Exception {
        mockMvc.perform(get("/api/meta").with(authentication(userToken(DEV_USER_UUID))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.userStatuses[?(@.code=='REJECTED')].label").value(org.hamcrest.Matchers.contains("반려")))
                .andExpect(jsonPath("$.userRoles.length()").value(2))
                .andExpect(jsonPath("$.recurringModes[?(@.code=='WITHDRAW')].label").value(org.hamcrest.Matchers.contains("정기 인출")))
                .andExpect(jsonPath("$.currencies[?(@.code=='USD')].label").value(org.hamcrest.Matchers.contains("미국 달러")))
                .andExpect(jsonPath("$.nextOrdersSkipReasons.length()").value(6))
                .andExpect(jsonPath("$.etfBenchmarkSymbols[?(@.code=='SPY')].description").value(org.hamcrest.Matchers.contains("SPDR S&P 500 ETF Trust")));
    }

    // 런타임 값은 RuntimeConfigService 결과를 그대로 싣는다 — RuntimeConfigService와 같은 출처
    @Test
    void getBundle_carriesRuntimeValuesAndVrRampDefaults() throws Exception {
        mockMvc.perform(get("/api/meta").with(authentication(userToken(DEV_USER_UUID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalRequired").value(true))
                .andExpect(jsonPath("$.benchmarkEtf.defaultValue").value("SPY"))
                .andExpect(jsonPath("$.brokers[?(@.code=='MERITZ')].enabled").value(org.hamcrest.Matchers.contains(false)))
                .andExpect(jsonPath("$.brokers[?(@.code=='TOSS')].enabled").value(org.hamcrest.Matchers.contains(true)))
                .andExpect(jsonPath("$.strategyTypes[?(@.code=='DCA')].enabled").value(org.hamcrest.Matchers.contains(false)))
                .andExpect(jsonPath("$.strategyTypes[?(@.code=='VR')].fields.recurringMode.defaultValue").value(org.hamcrest.Matchers.contains("HOLD")))
                .andExpect(jsonPath("$.strategyTypes[?(@.code=='VR')].vrRampDefaults.WITHDRAW.initialGradient").value(org.hamcrest.Matchers.contains(40)))
                .andExpect(jsonPath("$.strategyTypes[?(@.code=='VR')].vrRampDefaults.DEPOSIT.initialPoolLimitRate").value(org.hamcrest.Matchers.contains(1)))
                .andExpect(jsonPath("$.strategyTypes[0].vrRampDefaults").doesNotExist());
    }

    // 상태 플래그(T6) 코드값 — 전략 행위·막힘 사유·API 키 만료 상태·미리보기 경고 코드
    @Test
    void getBundle_carriesStateFlagEnums() throws Exception {
        mockMvc.perform(get("/api/meta").with(authentication(userToken(DEV_USER_UUID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strategyActions[?(@.code=='RESET_VR')].label").value(org.hamcrest.Matchers.contains("VR 재설정")))
                .andExpect(jsonPath("$.strategyActionBlockReasons[?(@.code=='HAS_HOLDINGS')].label").value(org.hamcrest.Matchers.contains("보유 수량 있음")))
                .andExpect(jsonPath("$.apiKeyExpiryStatuses.length()").value(4))
                .andExpect(jsonPath("$.orderPreviewWarningCodes[?(@.code=='ACCOUNT_CASH_PARTIAL')].label").value(org.hamcrest.Matchers.contains("보정 주문 거절 가능")));
    }
}
