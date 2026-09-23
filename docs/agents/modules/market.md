## com.kista.market (`:api`)

com.kista.market/    ← Spring Modulith 모듈(CLOSED, root 전용) — 공포탐욕지수(CNN/Crypto) 애그리게이트. 휴장일 캘린더는 `marketcalendar`로 분리. "domain"·"port"·"event" 3개 NamedInterface 공개, usecase·service·adapter는 internal
  domain/model/       ← FearGreedRating/FearGreedSnapshot + MarketSession(DIRECT/BLOCKED — `marketcalendar.MarketSessionSnapshot.MarketSession` own-type)/TossDailyCandle(`broker.TossCandle` 일봉 own-type) — "domain". 순환 방지용 복제, `OwnTypeContractTest`가 검증
  application/port/output/ ← CnnFearGreedPort/CryptoFearGreedPort/FearGreedSnapshotPort + MarketCalendarQueryPort(marketcalendar 내부API, `SessionView(MarketSession, isDst)`)/CandleQueryPort(broker 내부API, 일봉만) — "port"
  application/usecase/ ← FetchFearGreedUseCase/GetFearGreedUseCase/MarketUseCase — internal(외부 소비자 없음). `MarketHolidayService`가 `MarketUseCase` 구현 — marketcalendar/broker 양쪽에 HTTP 위임하며 그쪽 타입을 직접 참조하지 않는다
  application/event/  ← FearGreedFetchFailedEvent — notify `MarketAlertNotifier`가 구독. "event"
  application/service/ ← internal — FearGreedQueryService/FearGreedService/MarketHolidayService
  adapter/in/web/     ← internal — FearGreedController/MarketHolidayController + dto/(FearGreedResponse/MarketSessionResponse/TossCandleResponse)
  adapter/in/schedule/ ← FearGreedScheduler
  adapter/out/feargreed/ ← CnnFearGreedAdapter/CryptoFearGreedAdapter/FearGreedConfig
  adapter/out/internal/ ← MarketCalendarQueryHttpAdapter(`/api/internal/marketcalendar/**`)/CandleQueryHttpAdapter(`/api/internal/broker/candles/latest`, interval="1d" 고정)
  adapter/out/persistence/feargreed/ ← FearGreedSnapshotEntity + JpaRepository + PersistenceAdapter
