package com.kista.tradingcore.trading.adapter.in.web;

import com.kista.shared.platform.web.ErrorCode;
import com.kista.shared.platform.web.ProblemDetailMappingContributor;
import com.kista.shared.platform.web.ProblemDetailMappings;
import com.kista.shared.platform.web.ProblemDetailMappings.FieldRejection;
import com.kista.shared.platform.web.ProblemDetailMappings.Mapping;
import com.kista.shared.platform.web.ProblemResponder;
import com.kista.tradingcore.trading.domain.model.DuplicateStrategyTickerException;
import com.kista.tradingcore.trading.domain.model.FieldRejectedException;
import com.kista.tradingcore.trading.domain.model.OrderCancelException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.List;
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

    @Override
    public Map<Class<? extends Exception>, ProblemResponder> responders() {
        // 필드를 지목한 도메인 거절 — Bean Validation 400과 같은 모양(detail 요약 + fieldErrors), 검증 POST의 fieldErrors와도 같은 {field, detail}
        return Map.of(FieldRejectedException.class, (ex, reporter) -> {
            FieldRejectedException rejected = (FieldRejectedException) ex;
            return ResponseEntity.badRequest().body(ProblemDetailMappings.fieldProblem("Invalid Request",
                    List.of(new FieldRejection(rejected.field(), rejected.getMessage()))));
        });
    }
}
