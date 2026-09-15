package com.kista.trading.application.port.output;

// 관리자 매매 오류 알림 — adapter.in(스케쥴러)이 application.event/service에 직접 의존하지 않도록
// 출력 포트로 우회한다. 구현체는 application/service에서 ApplicationEventPublisher로 TradingErrorEvent를 발행한다
public interface TradingErrorReportPort {

    void reportError(Exception e);
}
