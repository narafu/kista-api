package com.kista.tradingcore.trading.adapter.in.web;

import com.kista.shared.platform.web.ErrorCode;
import com.kista.shared.platform.web.ProblemDetailMappingContributor;
import com.kista.shared.platform.web.ProblemDetailMappings.Mapping;
import com.kista.tradingcore.trading.domain.model.DuplicateStrategyTickerException;
import com.kista.tradingcore.trading.domain.model.OrderCancelException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Map;

// trading 모듈 예외의 HTTP 응답 — 공용 advice(ProblemDetailExceptionHandler)에 기여한다.
@Component
class TradingProblemDetails implements ProblemDetailMappingContributor {

    @Override
    public Map<Class<? extends Exception>, Mapping> mappings() {
        return Map.of(
                OrderCancelException.class,                   new Mapping(HttpStatus.CONFLICT, "Conflict", ErrorCode.ORDER_NOT_CANCELLABLE),
                // 등록 409는 이 의미 하나뿐이라 UI 분기용 code 없이 status로 충분 — detail이 사용자 문구
                DuplicateStrategyTickerException.class,       new Mapping(HttpStatus.CONFLICT, "Conflict"));
    }
}
