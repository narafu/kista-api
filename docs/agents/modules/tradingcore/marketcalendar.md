## com.kista.tradingcore.marketcalendar (`:trading-core`)

com.kista.tradingcore.marketcalendar/ ← Spring Modulith 모듈(CLOSED, `:trading-core`) — 미국 시장 휴장일 캘린더. "domain"·"port" 2개 NamedInterface, service·adapter internal
  domain/model/       ← MarketSessionTimes(실제 개장·마감 Instant)/MarketCalendarTradingDay(Alpaca 원본 뉴욕 현지 일자·시각)
  application/port/output/ ← MarketCalendarPort/MarketCalendarRefreshPort/MarketHolidayStorePort
  adapter/in/web/     ← internal — MarketCalendarInternalController(`/api/internal/marketcalendar/{holidays,is-open,session}`) — `session` 라우트는 `contract.marketcalendar.MarketSessionResponse(LocalDate tradeDateKst, boolean tradingDay)` — `tradeDateKst`는 `KstTradeDates.nextTradeDate`(04:30 KST 경계), `tradingDay`는 `isMarketOpen(tradeDateKst)` — 경로명은 kista-ui 호환으로 유지. DIRECT/BLOCKED·isDst 필드는 소비자가 없어 2026-11-11 제거
  adapter/in/schedule/ ← MarketCalendarRefreshScheduler
    - 초기 적재(`ApplicationReadyEvent`, 향후 3년 + 휴장일 보유 기존 연도 중 휴장일 또는 연 전체 세션 행이 빠진 연도 — 연도별 실패는 모아서 보고)와 월간(매월 1일 01:00 KST)·연간(1월 1일 00:00 KST) 갱신 크론은 `@ConditionalOnProperty(scheduler.enabled, matchIfMissing=true)`로 게이팅된다. trading-core는 `scheduler.enabled`를 설정하지 않아(trading-core `application*.yml`에 없음) 항상 켜져 `kista-trading` 기동마다 초기 적재 판정이 실행된다 — 캘린더 이상 시 `kista-trading` 재기동으로 즉시 재적재
  adapter/out/alpaca/  ← AlpacaCalendarAdapter/AlpacaConfig/AlpacaProperties — benchmark판과 빈 이름 충돌 방지를 위해 marketcalendar판만 `marketAlpacaConfig`/`marketAlpacaRestClient`로 개명
  adapter/out/persistence/ ← UsMarketHolidayEntity·UsMarketSessionEntity + JpaRepository + MarketCalendarPersistenceAdapter
    - `trading_ref.us_market_sessions`(V20): 수집 범위의 **모든 날짜** 행(거래일은 `open_at`·`close_at` timestamptz, 휴장·주말은 둘 다 null) — 행 없음=미적재와 휴장을 구분한다. Alpaca `open`/`close`(뉴욕 현지)를 어댑터가 `TimeZones.NY`로 Instant 변환하고, 원본 전체를 검증한 뒤에만 휴장일·세션을 같은 트랜잭션에서 교체한다(`replaceSessionsByYear/Month`)
    - `MarketCalendarPort.findSession(KST 거래일)`: 거래일이면 실제 세션, 휴장이면 empty, **미적재·검증 실패는 `IllegalStateException`**(마감 시각을 추정하지 않는다 — 마감 예약·접수는 fail-closed). `isMarketOpen`은 기존대로 휴장일 테이블 기준
