## com.kista.marketcalendar (`:trading-core`)

com.kista.marketcalendar/ ← Spring Modulith 모듈(CLOSED, `:trading-core`) — 미국 시장 휴장일 캘린더. "domain"·"port" 2개 NamedInterface, service·adapter internal
  domain/model/       ← MarketSessionSnapshot
  application/port/output/ ← MarketCalendarPort/MarketCalendarRefreshPort/MarketHolidayStorePort
  adapter/in/web/     ← internal — MarketCalendarInternalController(`/api/internal/marketcalendar/{holidays,is-open,session}`) — `session` 라우트는 own-type `SessionResponse(String session, boolean isDst)`
  adapter/in/schedule/ ← MarketCalendarRefreshScheduler
    - **2-role 이후 캘린더 부트스트랩 staleness**: 초기 적재(`ApplicationReadyEvent`)는 `@ConditionalOnProperty(scheduler.enabled)`로 게이팅돼 `kista-scheduler` 재기동 시에만 실행된다(`kista-api` 재기동마다가 아님). self-heal 창이 수시간→수주. 월간(1일)·연간(1월 1일) 갱신 크론이 있어 무해, 캘린더 이상 시 `kista-scheduler` 재기동으로 즉시 재적재
  adapter/out/alpaca/  ← AlpacaCalendarAdapter/AlpacaConfig/AlpacaProperties — stats판과 빈 이름 충돌 방지를 위해 marketcalendar판만 `marketAlpacaConfig`/`marketAlpacaRestClient`로 개명
  adapter/out/persistence/ ← UsMarketHolidayEntity + JpaRepository + MarketCalendarPersistenceAdapter
