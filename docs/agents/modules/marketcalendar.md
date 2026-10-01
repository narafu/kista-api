## com.kista.marketcalendar (`:trading-core`)

com.kista.marketcalendar/ ← Spring Modulith 모듈(CLOSED, `:trading-core`) — 미국 시장 휴장일 캘린더. "domain"·"port" 2개 NamedInterface, service·adapter internal
  domain/model/       ← MarketSessionSnapshot
  application/port/output/ ← MarketCalendarPort/MarketCalendarRefreshPort/MarketHolidayStorePort
  adapter/in/web/     ← internal — MarketCalendarInternalController(`/api/internal/marketcalendar/{holidays,is-open,session}`) — `session` 라우트는 `contract.marketcalendar.MarketSessionResponse(MarketSession session, boolean isDst)`(`MarketSession`은 `sharedkernel` 공용 enum — `MarketSessionSnapshot`·`DstInfo`도 같은 enum 사용)
  adapter/in/schedule/ ← MarketCalendarRefreshScheduler
    - 초기 적재(`ApplicationReadyEvent`, 향후 3년치가 없을 때만)와 월간(매월 1일 01:00 KST)·연간(1월 1일 00:00 KST) 갱신 크론은 `@ConditionalOnProperty(scheduler.enabled, matchIfMissing=true)`로 게이팅된다. trading-core는 `scheduler.enabled`를 설정하지 않아(trading-core `application*.yml`에 없음) 항상 켜져 `kista-trading` 기동마다 초기 적재 판정이 실행된다 — 캘린더 이상 시 `kista-trading` 재기동으로 즉시 재적재
  adapter/out/alpaca/  ← AlpacaCalendarAdapter/AlpacaConfig/AlpacaProperties — benchmark판과 빈 이름 충돌 방지를 위해 marketcalendar판만 `marketAlpacaConfig`/`marketAlpacaRestClient`로 개명
  adapter/out/persistence/ ← UsMarketHolidayEntity + JpaRepository + MarketCalendarPersistenceAdapter
