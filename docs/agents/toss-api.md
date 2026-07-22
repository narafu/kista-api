## 토스증권 API

**공식 문서**: https://developers.tossinvest.com/docs

응답 구조·파라미터 명세·오류 코드는 공식 문서가 SSOT. 아래는 kista 코드베이스에 특화된 패턴과 주의사항만 기록.

### 어댑터 구조

- `TossHttpClient` — 공통 헤더 처리 (package-private), `TossConfig`에서 RestTemplate 빈 주입
- API 클래스 6개: `TossAuthApi`, `TossCandleApi`, `TossHoldingsApi`, `TossOrderApi`, `TossPriceApi`, `TossMarketApi`
- `TossBrokerAdapter`: `BrokerAdapterPort` + 공통 7개 Port + Toss 전용 5개 Port 구현 (`BrokerConnectionTestPort`는 `TossAuthApi`가 구현)
  - Toss 전용: `CandlePort`, `ExchangeRatePort`, `StockInfoPort`, `BrokerMarketCalendarPort`, `BrokerAccountPort`

### 계좌번호 포맷

- `XXX-XX-XXXXXX` (하이픈 포함) — KIS `XXXXXXXX-XX`와 다름, `AccountInfoStep` 분기 처리 필요

### 토큰·인증

- 계좌 토큰의 canonical 저장소는 PostgreSQL `broker_tokens`이다. `TossAuthApi` 계좌 조회·40 401 복구는 `TossDistributedTokenCoordinator`를 통하며, Redis 60초 owner lease 획득 후 DB를 double-check하고 필요할 때만 OAuth를 호출한다. DB 저장이 완료된 뒤 Lua compare-delete로 자신의 lease만 해제한다. 60초는 Toss HTTP 타임아웃(3초 연결+10초 응답)과 Hikari 연결 대기(20초)를 합친 정상 임계구역에 여유를 둔 값이다.
- 관리자(공통 API) access token은 모든 Fly 인스턴스가 Redis `toss:token:admin`을 canonical 캐시로 공유한다. TTL은 OAuth `expires_in`보다 5분 짧게 저장한다.
- 계좌·관리자 신규 토큰은 raw bearer가 아닌 SHA-256 fingerprint만 Redis에 2초 TTL로 기록한다. 같은 fingerprint의 401은 리소스 서버 전파 중으로 보고 무효화하지 않는다.
- lease 대기자는 50ms 간격으로 canonical 저장소를 최대 16초 polling한다. Redis 오류나 대기 시간 초과 시 JVM-local 발급으로 우회하지 않고 `TossApiException`(503)으로 fail-closed 한다.
- `TossHttpClient`는 최초 401에서 원자적 복구로 최신/신규 토큰을 **먼저 획득한 뒤 300ms 대기**하고, 후속 401에서는 같은 토큰으로 600ms를 추가 대기해 동일 요청을 최대 2회 재시도한다 — 갓 재발급된 토큰이 Toss 리소스 서버에 즉시 반영되지 않아 재시도 직후에도 401이 나는 사례(운영 `app_error_logs` 관측)에 대응. 헤더 빌더 내부에서 토큰을 재조회하지 말고, `executeWithRetry`가 고정한 시도별 토큰을 사용해야 한다.
- 관리자 경로(`getCommon`)도 동일한 분산 세대 보호와 백오프(300ms/600ms)·최대 2회 재시도 정책을 적용한다. lease는 캐시 복구와 OAuth 발급만 조정하며 정상 리소스 API 호출은 직렬화하지 않는다.

### 날짜 처리 (KIS와 다름)

- `TossOrderApi.fetchExecutions()`: Toss는 **주문 접수일(KST)** 기준 날짜 필터링 — 변환 없이 KST 날짜 그대로 전달
- **`queryFrom = from - 1일`**: 전날 저녁 선접수 주문이 당일 장마감에 체결될 수 있어 1일 앞당겨 조회 후, `filledAt(KST)` 기반 필터링
- KIS(US 거래일 기준, `UsTradeDates` 변환 필요)와 반대 방향 — 혼용 금지

### 주의사항

- KIS API와 필드명·인증 방식이 다르므로 혼용 주의 (`TossApiException` → 503은 constraints.md, 브로커 분기는 architecture.md "BrokerAdapter Registry 패턴" 참고)

### 가격 조회 API 3분리 — TossPriceApi

Toss는 현재가(`/api/v1/prices`)와 전일종가(캔들 API)가 **완전히 분리된 HTTP 호출**이므로, 필요한 값만 조회하도록 메서드를 3그룹으로 분리했다 (KIS는 한 응답에 둘 다 묶여 있어 이 구분이 무의미 — `kis-api.md` 참고):

- `getPrice`/`getPrices` — 현재가만, `/api/v1/prices` 1회 호출
- `getPrevClose`/`getPrevCloses` — 전일종가만, 캔들 API만 호출(**현재가 API 미호출**). 캔들 조회 실패 종목만 현재가로 fallback(별도 배치 호출)
- `getPriceSnapshot`/`getPriceSnapshots` — 현재가+전일종가 둘 다 필요할 때만 사용하는 조합(위 두 API를 순차 호출) — "기본으로 쓰는 API"가 아니라 소수 케이스 전용

**소비처 선택 기준**: 실제 주문가 예측/계산(전략 생성 기준가, 다음 주문 미리보기, 매매 실행 시작가)은 `getPrevClose(s)`만 필요 — `getPriceSnapshot`을 쓰면 쓰지도 않는 현재가 API 호출이 낭비된다.

### 전일종가(prevClose) 조회 상세 — TossPriceApi.fetchPrevCloseCached()

- `/api/v1/prices`에는 전일종가 필드가 없음(현재가만 제공) — 반드시 `TossCandleApi.getCandleBefore(symbol, "1d", before)`(캔들 `count=1`)로 별도 조회
- `before` 시각 계산이 핵심: `DstInfo.isRegularSessionActive()`가 true(정규장 진행 중)면 그 세션의 봉이 아직 미확정이므로 `DstInfo.lastSessionOpenInstant().minusMillis(1)`을 `before`로 사용해 배제, false면 이미 확정된 봉만 있으므로 `Instant.now()`
- **`DstInfo.marketOpen` 필드를 그대로 쓰면 안 됨**: 이 필드는 "오늘 날짜" 고정이라 자정~개장 전(00:00~22:30 KST) 사이 호출 시 실제 진행 중인 세션(전날 저녁 개장)이 아닌 미래 시각을 가리킴 — `lastSessionOpenInstant()`가 `nextTradeDate()`와 같은 패턴으로 날짜 롤백 처리한 버전이므로 이걸 사용
- `PrevCloseCache` 키에 세션 버킷(`"ACTIVE"`/`"CLOSED"`) 포함 — 정규장 종료로 확정 종가가 바뀌는 순간 같은 KST 날짜 내에서도 캐시를 재사용하지 않고 재조회
- 과거 이력: 최신 캔들 2개 중 배열 인덱스로 확정 종가를 추측하던 방식이 "장마감 후에도 최신 캔들을 미확정으로 오인" + "DIRECT가 프리마켓까지 포함해 그 구간도 stale" 버그 2건을 냈음 — `count=1`+`before` 방식으로 인덱스 추측 자체를 제거해 해결
