package com.kista.api.market.adapter.in.web;

import com.kista.api.market.domain.model.CandleUnavailableException;
import com.kista.shared.platform.web.ErrorCode;
import com.kista.shared.platform.web.ProblemDetailMappingContributor;
import com.kista.shared.platform.web.ProblemDetailMappings.Mapping;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Map;

// market 모듈 예외의 HTTP 응답 — 공용 advice(ProblemDetailExceptionHandler)에 기여한다
@Component
class MarketProblemDetails implements ProblemDetailMappingContributor {

    @Override
    public Map<Class<? extends Exception>, Mapping> mappings() {
        // trading-core 캔들 내부 API 503 복원 — 원 장애는 trading-core가 이미 보고했으므로 매핑만(재보고 없음)
        return Map.of(CandleUnavailableException.class, new Mapping(HttpStatus.SERVICE_UNAVAILABLE, "Broker API Error", ErrorCode.BROKER_UNAVAILABLE));
    }
}
