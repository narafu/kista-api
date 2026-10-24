## 토스증권 API

**공식 문서**: https://developers.tossinvest.com/docs

응답 구조·파라미터 명세·오류 코드는 공식 문서가 SSOT. 아래는 kista 코드베이스에 특화된 패턴과 주의사항만 기록.

### 어댑터 구조

- `TossHttpClient` — 공통 헤더 처리 (package-private), `TossConfig`의 `tossRestClient`(RestClient) 빈 주입
- API 클래스 6개: `TossAuthApi`, `TossCandleApi`, `TossHoldingsApi`, `TossOrderApi`, `TossPriceApi`, `TossMarketApi`
- `TossBrokerAdapter`: `BrokerCapabilitiesPort`(어댑터 식별 + 공통 6개 Port) + `BrokerStatisticsPort`(통계 5개 Port) 구현 (`BrokerConnectionTestPort`는 `TossAuthApi`가 구현)
  - 통계 capability(`BrokerStatisticsPort`로 묶임, 현재 Toss만 구현 — 벤더 중립 `BrokerCandle`/`BrokerStockInfo`/`ExchangeRateQuote`/`MarketCalendarDay`/`BrokerAccountInfo` 반환): `CandlePort`, `ExchangeRatePort`, `StockInfoPort`, `BrokerMarketCalendarPort`, `BrokerAccountPort`

### 계좌번호 포맷

- `XXX-XX-XXXXXX` (하이픈 포함) — KIS `XXXXXXXX-XX`와 다름. 하이픈 분리(`KisHttpClient.splitAccountNo`)는 KIS 전용이고 Toss 호출은 `accounts.broker_account_code`(accountSeq)를 헤더에 쓴다(`TossHttpClient.buildHeaders`)

### 토큰·인증

- 계좌 토큰 조정(`obtain`/`recover`)은 `com.kista.tradingcore.broker.adapter.out.internal.TokenCoordinator` 공통 계약을 `TossDistributedTokenCoordinator`가 구현한다 — KIS(`DbTokenCoordinator`, JVM 내 로컬 락)와 같은 형태의 인터페이스지만 메커니즘은 다르다(→ `modules/tradingcore/broker.md`의 `adapter/out/internal` 항목). 관리자(admin) 토큰은 Account가 없어 대응 개념이 없으므로 `getAdminToken`/`recoverAdminToken`으로 인터페이스 밖에 별도 노출한다.
- Toss 계좌·관리자 access token의 canonical 저장소는 모두 Redis다. 계좌 hash key는 `toss:token:canonical:account:<UUID>`, 관리자는 `toss:token:canonical:admin`이며 access token, expiry epoch, fencing generation을 저장한다. TTL은 OAuth `expires_in`보다 5분 짧다. Toss는 PostgreSQL `broker_tokens`를 읽거나 무효화하거나 저장하지 않으며, 해당 DB 경로는 KIS 전용이다.
- 계좌 키 교체·계좌 삭제·탈퇴는 `AccountTokenEvictionPort` → `TossRedisTokenStore.invalidate`로 계좌 scope를 무효화한다 — Lua 한 번에 canonical·최근 지문을 DEL하고 generation이 있으면 INCR한다. canonical만 지우면 옛 키로 발급 중이던 owner가 `storeIfCurrent`로 옛 토큰을 되살리므로, generation 증가로 그 write를 STALE로 fence한다(STALE을 받은 owner는 lease를 먼저 놓고 `awaitNewerCanonical`로 다음 발급분을 기다린다 — lease를 쥔 채 기다리면 같은 계좌 follower가 lease TTL 20초 동안 막힌다. 새 발급자가 없으면 그 요청은 25초 뒤 503). generation이 없는 scope(발급 이력 없음 — KIS·업비트 계좌 포함)는 key를 만들지 않는다. 호출자 트랜잭션(계좌 삭제·탈퇴)이 있으면 커밋 뒤에 실행하고(키 교체는 NOT_SUPPORTED라 즉시), Redis 실패는 warn 로그만 남기고 진행한다(canonical TTL로 자연 만료). 남는 한계: 무효화 전에 옛 키 Account를 읽은 요청이 무효화 뒤에 lease를 새로 잡으면 더 큰 generation으로 옛 키 토큰을 저장할 수 있다(Toss가 옛 키 토큰을 받아 줄 때만 문제). 401 자동 복구는 별개로 남아 있어, Toss가 옛 키 토큰을 거부하는 경우엔 무효화 없이도 재발급됐다 — 무효화가 막는 것은 Toss가 옛 키 토큰을 계속 받아 주는 경우다.
- scope별 발급 owner는 Lua로 20초 lease `SET NX PX`(OAuth HTTP 타임아웃(`TossConfig` 연결 3초 + 읽기 10초 = ~13초) 최악 케이스보다 여유 있게, owner crash 시 blast radius 최소화 목적)와 generation `INCR`를 원자 실행한다. follower polling ceiling은 25초(POLL_INTERVAL 50ms × 500회)로 lease TTL을 5초 여유로 초과해, 정상 owner 완료나 lease 자연 만료보다 먼저 포기하지 않는다. lease는 중복 OAuth를 줄일 뿐 correctness를 보장하지 않는다. canonical write Lua가 incoming generation을 현재 generation counter와 저장된 canonical generation 모두와 비교해 stale owner write를 거절하는 fencing CAS가 correctness 경계다.
- lease 만료 후 successor가 더 큰 generation을 받으면 지연된 owner는 자신의 token을 저장·반환하지 않는다. successor의 더 큰 generation canonical token을 bounded polling해 반환하며 저장이 완료되지 않으면 503으로 fail-closed 한다.
- canonical CAS와 최근 발급 SHA-256 fingerprint(2초 TTL)는 같은 Lua script에서 원자 저장한다. 같은 fingerprint의 401은 리소스 서버 전파 중으로 보고 해당 token을 보존한다. raw bearer token은 canonical hash에만 저장하고 로그/fingerprint key에는 저장하지 않는다. 이 경로는 정상 전파 지연에서도 흔히 타는 경로라 `TossDistributedTokenCoordinator`는 DEBUG로만 기록한다. prod의 `com.kista` 로깅 레벨은 INFO이고 클래스 단위 override는 없으므로(`application-prod.yml`) 이 DEBUG 로그는 prod에서 찍히지 않는다 — 신규 DEBUG 로그는 prod 로깅 레벨과 대조할 것.
- **`forceReissue` 에스컬레이션**: `reusableToken()`은 `forceReissue=true`로 호출되면 지문 보호 재사용 분기를 건너뛰고(`null` 반환) lease 획득 → 실제 OAuth 재발급 경로로 강제 진입한다. `TossHttpClient`는 **같은 rejectedToken이 연속으로 다시 거절되면**(직전 지문 보호 대기가 전파 지연을 해소하지 못했다는 뜻) 이 플래그를 세운다 — 진짜 불량 토큰이 지문 보호 구간(2초) 내내 재시도만 소진하고 확정 실패하던 문제를 막는다. `TokenCoordinator` 공유 인터페이스(KIS와 공유)는 건드리지 않고 `TossDistributedTokenCoordinator`/`TossAuthApi`의 `recover(...,forceReissue)` 전용 오버로드로만 존재한다.
- lease 해제는 owner 값이 일치할 때만 삭제하는 Lua compare-delete다. 만료된 owner가 successor lease를 삭제할 수 없다. Redis read/script 오류, malformed hash, polling 소진 시 JVM-local·PostgreSQL fallback 없이 `TossApiException`(503)으로 fail-closed 한다.
- `TossHttpClient`는 **매 401마다** 코디네이터에 복구를 재조회한다(백오프 300ms→600ms→900ms, 누적 1800ms, 최대 3회 재시도) — 갓 재발급된 토큰이 Toss 리소스 서버에 즉시 반영되지 않아 재시도 직후에도 401이 나는 경우에 대응. 직전에 복구를 시도했던 rejectedToken 값을 기억해뒀다가 같은 토큰이 다시 거절되면 위 `forceReissue`로 승격한다. 헤더 빌더 내부에서 토큰을 재조회하지 말고, `executeWithRetry`가 고정한 시도별 토큰을 사용해야 한다.
- `getCommon`(관리자 토큰 공통 시세 API, 멱등 GET)만 **429 `rate-limit-exceeded`를 최대 2회 재시도**한다(백오프 500ms→1000ms) — 기간 캔들 페이징처럼 연속 호출이 순간 한도를 넘는 경우 대응. 계좌 API(`get`/`post` — 주문 등)는 429를 재시도하지 않는다
- 복구 API는 `RecoveredToken(accessToken, freshlyIssued)`를 반환한다. 이미 다른 인스턴스가 저장해 둔 canonical 토큰을 Redis 읽기만으로 재사용한 경우(`freshlyIssued=false`)엔 전파 지연 위험이 없어 `TossHttpClient`가 300ms 백오프를 생략한다 — 실제 신규 발급이거나 최근 발급 지문 보호 구간 재사용인 경우, 또는 forceReissue로 강제 재발급된 경우(모두 `freshlyIssued=true`)에만 대기한다.
- 관리자 경로(`getCommon`)도 동일한 Redis fencing 세대 보호·`forceReissue` 에스컬레이션과 백오프(300ms/600ms/900ms)·최대 3회 재시도 정책을 적용한다. lease는 OAuth 발급만 조정하며 정상 리소스 API 호출은 직렬화하지 않는다. 401 재시도 경로 자체의 지연·부하는 이전보다 늘었다(코디네이터 호출이 매 401마다 발생, forceReissue는 실제 lease 획득+OAuth 재발급을 유발할 수 있음) — 정상 응답 경로에는 영향 없음.

### 날짜 처리 (KIS와 다름)

- `TossOrderApi.fetchExecutions()`: Toss는 **주문 접수일(KST)** 기준 날짜 필터링 — 변환 없이 KST 날짜 그대로 전달
- **`queryFrom = from - 1일`**: 전날 저녁 선접수 주문이 당일 장마감에 체결될 수 있어 1일 앞당겨 조회 후, `filledAt(KST)` 기반 필터링
- KIS(US 거래일 기준, `UsTradeDates` 변환 필요)와 반대 방향 — 혼용 금지
- **예외 — 일봉 캔들**: `TossCandleApi`의 `TossCandle.date()`는 timestamp(ISO8601 UTC)에서 파생한 **US 세션일**이다. `TossPriceApi.getClosingPrice(ticker, kstTradeDate)`는 KST 거래일 D의 확정 종가로 US 세션 D-1 봉을 조회해야 하므로 `UsTradeDates.toUsTradeDate` 변환 후 `date().equals(usSessionDate)` 필터 — 봉 없으면 현재가 폴백 (KIS `KisPriceApi.fetchConfirmedClose`와 동일 규칙).

### 판매 가능 수량 — 보유 수량 사용
- `SellableQuantityPort` 계약은 "미체결 매도를 빼지 않은 보유 수량"(KIS `ovrs_cblc_qty`·Mock 최신 포지션과 동일)이고, 소비처(`DcaPlanInputCollector`·수동 SELL 검증)가 DB PLANNED/SUBMITTING/PLACED SELL 예약을 직접 뺀다
- Toss `/api/v1/sellable-quantity`는 미체결 매도를 이미 뺀 순수량이다 — 2026-10-17 실계좌 실측: SOXL 보유 30·미체결 매도 1주 → 29(취소 후 30). 그대로 쓰면 PLACED SELL을 이중 차감해 매도 배정이 줄어든다. 그래서 `TossHoldingsApi.getSellableQuantity`는 `/api/v1/holdings`의 `quantity`를 쓴다. 같은 날 KIS 실측: 보유 12·PLACED SELL 12 → `ovrs_cblc_qty`=12, `ord_psbl_qty`=0(코드는 `ovrs_cblc_qty` 사용 — 정합)
- 한계: kista 밖에서 낸 미체결 매도는 총량 기준에서 빠지지 않아 배정이 과다할 수 있고, 그 경우 증권사가 접수를 거절한다(KIS와 동일)

### 주문 멱등키 (clientOrderId)
- `TossOrderApi.place`는 `OrderInstruction.clientOrderId`(= `orders.id` UUID 문자열, 36자 — 허용 패턴 `^[a-zA-Z0-9\-_]+$`·최대 36자 안)를 body에 싣는다. 공식 스펙(`OrderCreateRequest.clientOrderId`·FAQ): 10분 내 같은 키·같은 내용 재요청은 원 주문 결과를 재반환, 원 요청 처리 중이면 409 `request-in-progress`(결과 불명으로 분류), 내용이 다르면 422 `idempotency-key-conflict`. 10분이 지나면 같은 키도 새 주문이다
- 주문 목록·상세 응답(`Order` 스키마)에는 `clientOrderId`가 없어 사후 조회가 안 된다 — 잔류 SUBMITTING 대사(`OrderSubmissionReconciler`)는 선점 후 2~9분 창에서만 같은 키 재요청으로 원 주문번호를 회수한다(workflow.md "PLANNED 상태 전이 경합")
- 2026-10-17 실계좌 실측: 같은 키·같은 내용 재요청 → 같은 `orderId` 재반환(200), 같은 키·다른 내용 → 422 `idempotency-key-conflict`(본문 gzip). 409 `request-in-progress`는 공식 openapi.json 응답 예시로 확인

### 주의사항

- KIS API와 필드명·인증 방식이 다르므로 혼용 주의 (`TossApiException` → 503 매핑은 `GlobalExceptionHandler`가 SSOT, 브로커 분기는 `docs/agents/modules/tradingcore/broker.md` "BrokerCapabilitiesPort + BrokerRouter 패턴" 참고)

### 가격 조회 API 3분리 — TossPriceApi

Toss는 현재가(`/api/v1/prices`)와 전일종가(캔들 API)가 **완전히 분리된 HTTP 호출**이므로, 필요한 값만 조회하도록 메서드를 3그룹으로 분리했다 (KIS는 한 응답에 둘 다 묶여 있어 이 구분이 무의미 — `kis-api.md` 참고):

- `getPrice`/`getPrices` — 현재가만, `/api/v1/prices` 1회 호출
- `getPrevClose`/`getPrevCloses` — 전일종가만, 캔들 API만 호출(**현재가 API 미호출**). 캔들 조회 실패 종목만 현재가로 fallback(별도 배치 호출)
- `getPriceSnapshot`/`getPriceSnapshots` — 현재가+전일종가 둘 다 필요할 때만 사용하는 조합(위 두 API를 순차 호출) — "기본으로 쓰는 API"가 아니라 소수 케이스 전용

**소비처 선택 기준**: 실제 주문가 예측/계산(전략 생성 기준가, 다음 주문 미리보기, 매매 실행 시작가)은 `getPrevClose(s)`만 필요 — `getPriceSnapshot`을 쓰면 쓰지도 않는 현재가 API 호출이 낭비된다.

**관리자 시세 1단계(`TossAdminPriceSource`)**: 계좌 브로커와 무관하게 모든 현재가·전일종가·확정 종가 조회의 첫 단계다(`BrokerRouter` 경유)(`workflow.md` "시세 출처"). 위 public 메서드 대신 대체값 없는 package-private 변형을 쓴다 — `fetchPricesPartial`(응답에 있는 종목만, 누락 시 예외 없음), `fetchPrevCloseCached` 성공분만(현재가 폴백 없음), `fetchConfirmedClose` 성공분만(라이브가 폴백 없음). 빠진 종목은 KIS 관리자 → 계좌 순으로 넘어간다. 기존 public 메서드(MOCK·Toss 어댑터 경로)의 예외·폴백 동작은 그대로다. 캔들 실패(empty)도 `prevCloseCache` 버킷에 캐시되므로 그 버킷 동안은 1단계 전일종가가 계속 누락으로 처리된다

### 전일종가(prevClose) 조회 상세 — TossPriceApi.fetchPrevCloseCached()

- `/api/v1/prices`에는 전일종가 필드가 없음(현재가만 제공) — 반드시 `TossCandleApi.getCandleBefore(symbol, "1d", before)`(캔들 `count=1`)로 별도 조회
- `before` 시각 계산이 핵심: `DstInfo.isRegularSessionActive()`가 true(정규장 진행 중)면 그 세션의 봉이 아직 미확정이므로 `DstInfo.lastSessionOpenInstant().minusMillis(1)`을 `before`로 사용해 배제, false면 이미 확정된 봉만 있으므로 `Instant.now()`
- **`DstInfo.marketOpen` 필드를 그대로 쓰면 안 됨**: 이 필드는 "오늘 날짜" 고정이라 자정~개장 전(00:00~22:30 KST) 사이 호출 시 실제 진행 중인 세션(전날 저녁 개장)이 아닌 미래 시각을 가리킴 — `lastSessionOpenInstant()`가 `nextTradeDate()`와 같은 패턴으로 날짜 롤백 처리한 버전이므로 이걸 사용
- `TossPriceApi.prevCloseCache` 키에 세션 버킷(`"ACTIVE"`/`"CLOSED"`) 포함 — 정규장 종료로 확정 종가가 바뀌는 순간 같은 KST 날짜 내에서도 캐시를 재사용하지 않고 재조회
- 캔들 배열 인덱스로 확정 종가를 추측하지 말 것 — `count=1`+`before` 방식만 사용 (인덱스 추측은 장마감 후 최신 캔들 오인·프리마켓 구간 stale 버그를 냈음)

### 잔고 부족 거절 분류
- 주문 POST가 HTTP 422 + `error.code = insufficient-buying-power`이면 `TossApiException.isInsufficientFunds()`=true(확정 거절의 하위 분류, `TossHttpClient`가 판정). 출처: 공식 문서 `openapi.tossinvest.com/openapi-docs/overview.md`.
- `insufficient-sellable-quantity`(SELL)·`idempotency-key-conflict` 등 다른 422 코드는 분류하지 않는다. BUY 여부 판단은 호출자(`OrderSubmitter`) 몫.
