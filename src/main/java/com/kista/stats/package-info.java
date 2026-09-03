// stats 애그리게이트(사용자 통계·주택/ETF 벤치마크 비교·과거 일봉 백테스트·텔레그램 포트폴리오 조회) 모듈 —
// domain.model·application.{usecase,port.output,event}·adapter.in.schedule만 공개 계약, application.service·domain.backtest·나머지 adapter는 internal.
@org.springframework.modulith.ApplicationModule
package com.kista.stats;
