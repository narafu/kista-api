package com.kista.adapter.out.toss;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.toss.TossCommissionRate;
import com.kista.domain.port.out.TossCommissionsPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class TossCommissionsApi implements TossCommissionsPort {

    // GET /api/v1/commissions — 계좌별 시장(KR/US)별 수수료율 조회
    private static final String COMMISSIONS_PATH = "/api/v1/commissions";

    private final TossHttpClient tossHttpClient;

    @Override
    public List<TossCommissionRate> getCommissions(Account account) {
        TossResult<List<CommissionItem>> wrapper = tossHttpClient.get(
                COMMISSIONS_PATH, account, new LinkedMultiValueMap<>(),
                new ParameterizedTypeReference<TossResult<List<CommissionItem>>>() {});
        if (wrapper == null || wrapper.result() == null) {
            log.warn("Toss 수수료율 응답 없음: accountId={}", account.id());
            return List.of();
        }
        return wrapper.result().stream()
                .map(item -> new TossCommissionRate(
                        item.marketCountry(),
                        new BigDecimal(item.commissionRate()),
                        item.startDate() != null ? LocalDate.parse(item.startDate()) : null,
                        item.endDate()   != null ? LocalDate.parse(item.endDate())   : null
                ))
                .toList();
    }

    record CommissionItem(
            @JsonProperty("marketCountry")  String marketCountry,  // "KR" | "US"
            @JsonProperty("commissionRate") String commissionRate, // 수수료율 (%) — "0.015"
            @JsonProperty("startDate")      String startDate,      // YYYY-MM-DD, 해외는 null
            @JsonProperty("endDate")        String endDate         // YYYY-MM-DD, 무기한이면 null
    ) {}
}
