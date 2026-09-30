## com.kista.market (`:api`)

com.kista.market/    ← Spring Modulith 모듈(CLOSED, root 전용) — 공포탐욕지수(CNN/Crypto) 애그리게이트. 휴장일 캘린더는 `marketcalendar`로 분리. "domain"·"port"·"event" 3개 NamedInterface 공개, usecase·service·adapter는 internal
  domain/model/       ← FearGreedRating/FearGreedSnapshot — "domain". `MarketSession`은 `sharedkernel.MarketSession`, 일봉은 `contract.broker.DailyCandleResponse`를 그대로 쓴다(own-type 복제본 `TossDailyCandle`/market `MarketSession` 삭제)
  application/port/output/ ← CnnFearGreedPort/CryptoFearGreedPort/FearGreedSnapshotPort + MarketCalendarQueryPort(marketcalendar 내부API, 반환 `contract.marketcalendar.MarketSessionResponse`)/CandleQueryPort(broker 내부API, 일봉만, 반환 `contract.broker.DailyCandleResponse`) — "port"
  application/usecase/ ← FetchFearGreedUseCase/GetFearGreedUseCase/MarketUseCase — internal(외부 소비자 없음). `MarketHolidayService`가 `MarketUseCase` 구현 — marketcalendar/broker 양쪽에 HTTP 위임하며 그쪽 타입을 직접 참조하지 않는다
  application/event/  ← FearGreedFetchFailedEvent — notify `AlertNotifier`(구 MarketAlertNotifier, StatsAlertNotifier와 병합)가 구독. "event"
  application/service/ ← internal — FearGreedQueryService/FearGreedService/MarketHolidayService
  adapter/in/web/     ← internal — FearGreedController/MarketHolidayController + dto/(FearGreedResponse/MarketSessionResponse/TossCandleResponse)
  adapter/in/schedule/ ← FearGreedScheduler
  adapter/out/feargreed/ ← CnnFearGreedAdapter/CryptoFearGreedAdapter/FearGreedConfig
  adapter/out/internal/ ← MarketCalendarQueryHttpAdapter(`/api/internal/marketcalendar/**`)/CandleQueryHttpAdapter(`/api/internal/broker/candles/latest`, interval="1d" 고정)
  adapter/out/persistence/feargreed/ ← FearGreedSnapshotEntity + JpaRepository + PersistenceAdapter
