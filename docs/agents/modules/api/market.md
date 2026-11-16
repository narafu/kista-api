## com.kista.api.market (`:api`)

com.kista.api.market/    ← Spring Modulith 모듈(CLOSED, root 전용) — 공포탐욕지수(CNN) 애그리게이트. 휴장일 캘린더는 `marketcalendar`로 분리. "domain"·"port"·"event" 3개 NamedInterface 공개, service·adapter는 internal
  domain/model/       ← FearGreedRating/FearGreedSnapshot — "domain". 일봉은 `contract.broker.DailyCandleResponse`를 그대로 쓴다(own-type 복제 없음)
  application/port/output/ ← CnnFearGreedPort/FearGreedSnapshotPort + MarketCalendarQueryPort(marketcalendar 내부API, 반환 `contract.marketcalendar.MarketSessionResponse` — tradeDateKst·tradingDay)/CandleQueryPort(broker 내부API, 일봉만, 반환 `contract.broker.DailyCandleResponse`) — "port"
  application/event/  ← FearGreedFetchFailedEvent(CNN 수집 실패) — notify `AlertNotifier.onFearGreedFetchFailed`가 구독. "event"
  application/service/ ← internal — FearGreedQueryService/FearGreedService/MarketHolidayService(marketcalendar/broker 양쪽에 HTTP 위임하며 그쪽 타입을 직접 참조하지 않는다)
    - CNN 경로: `FearGreedScheduler`(KST 07:00/12:00)→`FearGreedService`가 `fear_greed_snapshots`에 저장하고 조회는 DB(비공식 엔드포인트라 장애 버퍼). `days`는 1~365(위반 400)
    - trading-core `adapter/out/feargreed`와의 중복은 의도된 것이다 — trading-core는 root를 호출할 수 없고(프로세스 경계, `tradingCoreMustNotCallRootInternalApi`) DCA FEAR_GREED 조건은 최신 점수를 fail-open으로 바로 받아야 한다. root는 히스토리·스냅샷을 소유하고, 중복은 업스트림 URL·응답 record뿐이며 RestClient 헤더는 `shared.platform.http.CnnRestClients`로 공유한다.
  adapter/in/web/     ← internal — FearGreedController/MarketHolidayController + dto/(FearGreedResponse/MarketSessionResponse(tradeDateKst·tradingDay)/TossCandleResponse)
  adapter/in/schedule/ ← FearGreedScheduler
  adapter/out/feargreed/ ← CnnFearGreedAdapter/FearGreedConfig
  adapter/out/internal/ ← MarketCalendarQueryHttpAdapter(`/api/internal/marketcalendar/**`)/CandleQueryHttpAdapter(`/api/internal/broker/candles/latest`, interval="1d" 고정)
  adapter/out/persistence/feargreed/ ← FearGreedSnapshotEntity + JpaRepository + PersistenceAdapter
