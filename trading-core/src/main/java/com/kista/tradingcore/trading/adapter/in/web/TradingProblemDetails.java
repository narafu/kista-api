package com.kista.tradingcore.trading.adapter.in.web;

import com.kista.shared.platform.web.ErrorCode;
import com.kista.shared.platform.web.ProblemDetailMappingContributor;
import com.kista.shared.platform.web.ProblemDetailMappings.Mapping;
import com.kista.tradingcore.trading.domain.model.AlreadyOrderedTodayException;
import com.kista.tradingcore.trading.domain.model.DcaManualExecutionUnsupportedException;
import com.kista.tradingcore.trading.domain.model.DuplicateStrategyTickerException;
import com.kista.tradingcore.trading.domain.model.ManualTradingException;
import com.kista.tradingcore.trading.domain.model.ManualTradingFailedException;
import com.kista.tradingcore.trading.domain.model.OrderCancelException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Map;

// trading 모듈 예외의 HTTP 응답 — 공용 advice(ProblemDetailExceptionHandler)에 기여한다.
// 서브클래스(AlreadyOrderedToday/ManualTradingFailed)는 advice의 계층 탐색이 상위 ManualTradingException보다 먼저 찾는다
@Component
class TradingProblemDetails implements ProblemDetailMappingContributor {

    @Override
    public Map<Class<? extends Exception>, Mapping> mappings() {
        return Map.of(
                AlreadyOrderedTodayException.class,           new Mapping(HttpStatus.CONFLICT, "Conflict", ErrorCode.ALREADY_ORDERED_TODAY),
                DcaManualExecutionUnsupportedException.class, new Mapping(HttpStatus.CONFLICT, "Conflict", ErrorCode.DCA_MANUAL_EXECUTION_UNSUPPORTED),
                // 바로주문 비증권사 실패(500) — 오류 로그는 ManualTradingService가 TradingErrorEvent로 따로 남기므로 표 매핑(보고 없음)
                ManualTradingFailedException.class,           new Mapping(HttpStatus.INTERNAL_SERVER_ERROR, "Manual Trading Failed", null,
                                                                      "주문 계산 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요."),
                ManualTradingException.class,                 new Mapping(HttpStatus.CONFLICT, "Conflict"),
                OrderCancelException.class,                   new Mapping(HttpStatus.CONFLICT, "Conflict", ErrorCode.ORDER_NOT_CANCELLABLE),
                // 등록 409는 이 의미 하나뿐이라 UI 분기용 code 없이 status로 충분 — detail이 사용자 문구
                DuplicateStrategyTickerException.class,       new Mapping(HttpStatus.CONFLICT, "Conflict"));
    }
}
