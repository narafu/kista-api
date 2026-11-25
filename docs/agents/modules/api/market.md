## com.kista.api.market (`:api`)

com.kista.api.market/    ← Spring Modulith 모듈(CLOSED, root 전용) — 공포탐욕지수(CNN/Crypto) 애그리게이트. 휴장일 캘린더는 `marketcalendar`로 분리. "domain"·"port"·"event" 3개 NamedInterface 공개, service·adapter는 internal
  domain/model/       ← FearGreedRating/FearGreedSnapshot/CryptoFearGreedHistory — "domain". 일봉은 `contract.broker.DailyCandleResponse`를 그대로 쓴다(own-type 복제 없음)
  application/port/output/ ← CnnFearGreedPort/FearGreedSnapshotPort + MarketCalendarQueryPort(marketcalendar 내부API, 반환 `contract.marketcalendar.MarketSessionResponse` — tradeDateKst·tradingDay)/CandleQueryPort(broker 내부API, 일봉만, 반환 `contract.broker.DailyCandleResponse`) — "port"
  application/event/  ← FearGreedFetchFailedEvent(CNN 수집 실패) — notify `AlertNotifier.onFearGreedFetchFailed`가 구독. "event"
  application/service/ ← internal — FearGreedQueryService/FearGreedService/MarketHolidayService(marketcalendar/broker 양쪽에 HTTP 위임하며 그쪽 타입을 직접 참조하지 않는다)
    - 공포탐욕 소스별 경로: CNN은 `FearGreedScheduler`(KST 07:00/12:00)→`FearGreedService`가 `fear_greed_snapshots`에 저장하고 조회는 DB(비공식 엔드포인트라 장애 버퍼). CRYPTO는 저장 없이 `FearGreedQueryService`가 포트 없이 구체 어댑터 `CryptoFearGreedAdapter`(public, 단일 구현이라 포트 금지 — constraints.md)로 alternative.me를 직접 조회해 `TtlResultCache`(정상은 응답 `time_until_update`까지 — 1분~24시간 clamp, 값이 없으면 1시간, 최신값이 24시간 넘게 오래되면 1분 / 실패 1분, days 무관 단일 키)로 캐시하고 days로 잘라 응답 — 실패는 빈 이력(crypto.current=null)으로 흡수해 CNN 응답을 막지 않는다. `days`는 1~365, 0=전체 기간(KST ISO 주 월~일별 마지막 값 1건으로 축약, 이번 주는 최신값 1건이라 `current`=실제 최신값), 그 외 400. 같은 시각 중복은 createdAt 순 정렬 뒤 마지막 값이 이긴다. CRYPTO 외부 호출은 `limit=0`(전체) 하나로 days별로 잘라 쓴다
    - CRYPTO `Point.date`는 alternative.me 기준일 UTC 00:00(=KST 같은 날 09:00) — kista-ui가 KST 일자로 표시하므로 변환 없음. 코인 매매·DCA 조건용 공탐은 없다(대시보드 조회 전용)
    - trading-core `adapter/out/feargreed`와의 중복은 의도된 것이다 — trading-core는 root를 호출할 수 없고(프로세스 경계, `tradingCoreMustNotCallRootInternalApi`) DCA FEAR_GREED 조건은 최신 점수를 fail-open으로 바로 받아야 한다. root는 히스토리·스냅샷을 소유하고, 중복은 업스트림 URL·응답 record뿐이며 RestClient 헤더는 `shared.platform.http.CnnRestClients`로 공유한다.
  adapter/in/web/     ← internal — FearGreedController/MarketHolidayController + dto/(FearGreedResponse/MarketSessionResponse(tradeDateKst·tradingDay)/TossCandleResponse)
  adapter/in/schedule/ ← FearGreedScheduler
  adapter/out/feargreed/ ← CnnFearGreedAdapter/CryptoFearGreedAdapter(포트 없음, 서비스가 직접 사용)/FearGreedConfig
  adapter/out/internal/ ← MarketCalendarQueryHttpAdapter(`/api/internal/marketcalendar/**`)/CandleQueryHttpAdapter(`/api/internal/broker/candles/latest`, 일봉만)
  adapter/out/persistence/feargreed/ ← FearGreedSnapshotEntity + JpaRepository + PersistenceAdapter

- 캔들 응답 `sma20/50/120/200·rsi14`(`TossCandleResponse`): RAW 종가 기준이며 DCA(수정주가)와 같은 `DcaIndicators` 코드·다른 입력이다. RSI 창 `DcaIndicators.CLOSE_WINDOW`(250)
