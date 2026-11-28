## 토스증권 API

**공식 문서**: https://developers.tossinvest.com/docs

응답 구조·파라미터 명세·오류 코드는 공식 문서가 SSOT. 아래는 kista 코드베이스에 특화된 패턴과 주의사항만 기록.

### 어댑터 구조

클래스 구성(아래). `TossHttpClient`는 package-private 공통 헤더 처리이고 `TossConfig`의 `tossRestClient` 빈을 쓴다.

- 구성: adapter/out/toss/   ← TossHttpClient/TossConfig/TossAuthApi/TossHoldingsApi/TossOrderApi/TossPriceApi/TossResponseParser/TossResult/UsdKrwRateCache
- TossDistributedTokenCoordinator + TossRedisTokenStore(계좌·관리자 Redis canonical token; TTL owner lease+원자적 generation INCR; generation counter/canonical generation 비교 Lua fencing CAS; owner-safe unlock; SHA-256 최근 발급 fingerprint 2초 TTL) — 관리자 토큰은 Account가 없어 TokenCoordinator 범위 밖 별도 public 메서드(TossTokenStore)
- TossBrokerAdapter(공통 6개 + 환율 Port 구현 — Toss 응답 record를 벤더 중립 타입으로 변환해 노출)
- 관리자 키(`toss.admin-client-id/secret`)는 환율(`getExchangeRate`)에만 쓴다. T4(Frankfurter) 뒤 T5b에서 관리자 토큰·`getCommon`과 함께 제거한다. DCA 일봉은 공용 시세(`MarketDataClient.dailyCandles`)다.
- DCA 주문 가격: Toss: 매수는 캡가(지정가) 기준으로 보정하고, 매도는 0.01 규칙을 적용한다.

### 계좌번호 포맷

- `XXX-XX-XXXXXX` (하이픈 포함). Toss 호출은 `accounts.broker_account_code`(accountSeq)를 헤더에 쓴다(`TossHttpClient.buildHeaders`)

### 토큰·인증

- 계좌 토큰 조정(`obtain`/`recover`)은 `com.kista.tradingcore.broker.adapter.out.internal.TokenCoordinator` 공통 계약을 `TossDistributedTokenCoordinator`가 구현한다 — Meritz(`DbTokenCoordinator`, JVM 내 로컬 락)와 같은 형태의 인터페이스지만 메커니즘은 다르다(→ `modules/tradingcore/broker.md`의 `adapter/out/internal` 항목). 관리자(admin) 토큰은 Account가 없어 대응 개념이 없으므로 `getAdminToken`/`recoverAdminToken`으로 인터페이스 밖에 별도 노출한다.
- Toss 계좌·관리자 access token의 canonical 저장소는 모두 Redis다. 계좌 hash key는 `toss:token:canonical:account:<UUID>`, 관리자는 `toss:token:canonical:admin`이며 access token, expiry epoch, fencing generation을 저장한다. TTL은 OAuth `expires_in`보다 5분 짧다. Toss는 PostgreSQL `broker_tokens`를 읽거나 무효화하거나 저장하지 않으며, 해당 DB 경로는 Meritz 전용이다.
- 계좌 키 교체·계좌 삭제·탈퇴는 `AccountTokenEvictionPort` → `TossRedisTokenStore.invalidate`로 계좌 scope를 무효화한다 — Lua 한 번에 canonical·최근 지문을 DEL하고 generation이 있으면 INCR한다. canonical만 지우면 옛 키로 발급 중이던 owner가 `storeIfCurrent`로 옛 토큰을 되살리므로, generation 증가로 그 write를 STALE로 fence한다(STALE을 받은 owner는 lease를 먼저 놓고 `awaitNewerCanonical`로 다음 발급분을 기다린다 — lease를 쥔 채 기다리면 같은 계좌 follower가 lease TTL 20초 동안 막힌다. 새 발급자가 없으면 그 요청은 25초 뒤 503). generation이 없는 scope(발급 이력 없음 — Meritz 계좌 포함)는 key를 만들지 않는다. 호출자 트랜잭션(계좌 삭제·탈퇴)이 있으면 커밋 뒤에 실행하고(키 교체는 NOT_SUPPORTED라 즉시), Redis 실패는 warn 로그만 남기고 진행한다(canonical TTL로 자연 만료). 남는 한계: 무효화 전에 옛 키 Account를 읽은 요청이 무효화 뒤에 lease를 새로 잡으면 더 큰 generation으로 옛 키 토큰을 저장할 수 있다(Toss가 옛 키 토큰을 받아 줄 때만 문제). 401 자동 복구는 별개로 남아 있어, Toss가 옛 키 토큰을 거부하는 경우엔 무효화 없이도 재발급됐다 — 무효화가 막는 것은 Toss가 옛 키 토큰을 계속 받아 주는 경우다.
- scope별 발급 owner는 Lua로 20초 lease `SET NX PX`(OAuth HTTP 타임아웃(`TossConfig` 연결 3초 + 읽기 10초 = ~13초) 최악 케이스보다 여유 있게, owner crash 시 blast radius 최소화 목적)와 generation `INCR`를 원자 실행한다. follower polling ceiling은 25초(POLL_INTERVAL 50ms × 500회)로 lease TTL을 5초 여유로 초과해, 정상 owner 완료나 lease 자연 만료보다 먼저 포기하지 않는다. lease는 중복 OAuth를 줄일 뿐 correctness를 보장하지 않는다. canonical write Lua가 incoming generation을 현재 generation counter와 저장된 canonical generation 모두와 비교해 stale owner write를 거절하는 fencing CAS가 correctness 경계다.
- lease 만료 후 successor가 더 큰 generation을 받으면 지연된 owner는 자신의 token을 저장·반환하지 않는다. successor의 더 큰 generation canonical token을 bounded polling해 반환하며 저장이 완료되지 않으면 503으로 fail-closed 한다.
- canonical CAS와 최근 발급 SHA-256 fingerprint(2초 TTL)는 같은 Lua script에서 원자 저장한다. 같은 fingerprint의 401은 리소스 서버 전파 중으로 보고 해당 token을 보존한다. raw bearer token은 canonical hash에만 저장하고 로그/fingerprint key에는 저장하지 않는다. 이 경로는 정상 전파 지연에서도 흔히 타는 경로라 `TossDistributedTokenCoordinator`는 DEBUG로만 기록한다. prod의 `com.kista` 로깅 레벨은 INFO이고 클래스 단위 override는 없으므로(`application-prod.yml`) 이 DEBUG 로그는 prod에서 찍히지 않는다 — 신규 DEBUG 로그는 prod 로깅 레벨과 대조할 것.
- **`forceReissue` 에스컬레이션**: `reusableToken()`은 `forceReissue=true`로 호출되면 지문 보호 재사용 분기를 건너뛰고(`null` 반환) lease 획득 → 실제 OAuth 재발급 경로로 강제 진입한다. `TossHttpClient`는 **같은 rejectedToken이 연속으로 다시 거절되면**(직전 지문 보호 대기가 전파 지연을 해소하지 못했다는 뜻) 이 플래그를 세운다 — 진짜 불량 토큰이 지문 보호 구간(2초) 내내 재시도만 소진하고 확정 실패하던 문제를 막는다. `TokenCoordinator` 공유 인터페이스(Meritz와 공유)는 건드리지 않고 `TossDistributedTokenCoordinator`/`TossAuthApi`의 `recover(...,forceReissue)` 전용 오버로드로만 존재한다.
- lease 해제는 owner 값이 일치할 때만 삭제하는 Lua compare-delete다. 만료된 owner가 successor lease를 삭제할 수 없다. Redis read/script 오류, malformed hash, polling 소진 시 JVM-local·PostgreSQL fallback 없이 `TossApiException`(503)으로 fail-closed 한다.
- `TossHttpClient`는 **매 401마다** 코디네이터에 복구를 재조회한다(백오프 300ms→600ms→900ms, 누적 1800ms, 최대 3회 재시도) — 갓 재발급된 토큰이 Toss 리소스 서버에 즉시 반영되지 않아 재시도 직후에도 401이 나는 경우에 대응. 직전에 복구를 시도했던 rejectedToken 값을 기억해뒀다가 같은 토큰이 다시 거절되면 위 `forceReissue`로 승격한다. 헤더 빌더 내부에서 토큰을 재조회하지 말고, `executeWithRetry`가 고정한 시도별 토큰을 사용해야 한다.
- `getCommon`(관리자 토큰 공통 시세 API, 멱등 GET)만 **429 `rate-limit-exceeded`를 최대 2회 재시도**한다(백오프 500ms→1000ms) — 연속 호출이 순간 한도를 넘는 경우 대응. 계좌 API(`get`/`post` — 주문 등)는 429를 재시도하지 않는다. 재시도 후에도 남은 429는 `executeWithBackoffRetry`가 `BrokerRateLimitException`(429, 확정 거절)으로 변환한다(백오프 대기 중 인터럽트는 확정 거절 `TossApiException`)
- 복구 API는 `RecoveredToken(accessToken, freshlyIssued)`를 반환한다. 이미 다른 인스턴스가 저장해 둔 canonical 토큰을 Redis 읽기만으로 재사용한 경우(`freshlyIssued=false`)엔 전파 지연 위험이 없어 `TossHttpClient`가 300ms 백오프를 생략한다 — 실제 신규 발급이거나 최근 발급 지문 보호 구간 재사용인 경우, 또는 forceReissue로 강제 재발급된 경우(모두 `freshlyIssued=true`)에만 대기한다.
- 관리자 경로(`getCommon`)도 동일한 Redis fencing 세대 보호·`forceReissue` 에스컬레이션과 백오프(300ms/600ms/900ms)·최대 3회 재시도 정책을 적용한다. lease는 OAuth 발급만 조정하며 정상 리소스 API 호출은 직렬화하지 않는다. 401 재시도 경로 자체의 지연·부하는 이전보다 늘었다(코디네이터 호출이 매 401마다 발생, forceReissue는 실제 lease 획득+OAuth 재발급을 유발할 수 있음) — 정상 응답 경로에는 영향 없음.

### 날짜 처리

- `TossOrderApi.fetchExecutions()`: Toss는 **주문 접수일(KST)** 기준 날짜 필터링 — 변환 없이 KST 날짜 그대로 전달
- **`queryFrom = from - 1일`**: 거래일 D 주문은 D-1 저녁에 접수되므로 1일 앞당겨 조회한 뒤, 체결의 거래일로 재필터링
- **체결 거래일 = `filledAt`의 뉴욕 일자 + 1일**(`UsTradeDates.toKstTradeDate`, 메리츠와 같은 규칙): KST 경계는 서머타임 13:00 / 표준시 14:00이라 프리마켓~애프터마켓 체결이 모두 같은 거래일에 묶인다. KST 달력일을 쓰면 D-1 22:00 이후 장중 체결이 D-1로 잡혀 `getExecutions(D, D)`에서 빠졌다. 한계: 토스 주간거래(KST 낮) 체결도 같은 규칙으로 분류되지만 kista 주문은 DAY/CLS만 써서 해당 없음. 실계좌 `filledAt` 오프셋 표기는 미실측
- **Toss API**: 주문 접수일(KST) 기준, 변환 없음. `TossOrderApi.fetchExecutions()`는 전날 저녁 선접수 대응으로 `queryFrom = from - 1일` 조회 후, `filledAt`의 뉴욕 일자(US 세션일)를 `UsTradeDates.toKstTradeDate`로 바꾼 KST 거래일로 재필터(KST 달력일을 쓰면 D-1 22:00 이후 장중 체결이 D에서 빠진다).

### 판매 가능 수량 — 보유 수량 사용
- `SellableQuantityPort` 계약은 "미체결 매도를 빼지 않은 보유 수량"(Mock 최신 포지션과 동일)이고, 소비처(수동 SELL 검증 등 — 주식 DCA는 원장 기준이라 더는 쓰지 않음)가 DB PLANNED/SUBMITTING/PLACED SELL 예약을 직접 뺀다
- Toss `/api/v1/sellable-quantity`는 미체결 매도를 이미 뺀 순수량이다 — 2026-10-17 실계좌 실측: SOXL 보유 30·미체결 매도 1주 → 29(취소 후 30). 그대로 쓰면 PLACED SELL을 이중 차감해 매도 배정이 줄어든다. 그래서 `TossHoldingsApi.getSellableQuantity`는 `/api/v1/holdings`의 `quantity`를 쓴다.
- 한계: kista 밖에서 낸 미체결 매도는 총량 기준에서 빠지지 않아 배정이 과다할 수 있고, 그 경우 증권사가 접수를 거절한다

### 주문 멱등키 (clientOrderId)
- `TossOrderApi.place`는 `OrderInstruction.clientOrderId`(= `orders.id` UUID 문자열, 36자 — 허용 패턴 `^[a-zA-Z0-9\-_]+$`·최대 36자 안)를 body에 싣는다. 공식 스펙(`OrderCreateRequest.clientOrderId`·FAQ): 10분 내 같은 키·같은 내용 재요청은 원 주문 결과를 재반환, 원 요청 처리 중이면 409 `request-in-progress`(결과 불명으로 분류), 내용이 다르면 422 `idempotency-key-conflict`. 10분이 지나면 같은 키도 새 주문이다
- 주문 목록·상세 응답(`Order` 스키마)에는 `clientOrderId`가 없어 사후 조회가 안 된다 — 잔류 SUBMITTING 대사(`OrderSubmissionReconciler`)는 선점 후 2~9분 창에서만 같은 키 재요청으로 원 주문번호를 회수한다(workflow-orders.md "PLANNED 상태 전이 경합")
- 2026-10-17 실계좌 실측: 같은 키·같은 내용 재요청 → 같은 `orderId` 재반환(200), 같은 키·다른 내용 → 422 `idempotency-key-conflict`(본문 gzip). 409 `request-in-progress`는 공식 openapi.json 응답 예시로 확인

### 현재가 — TossPriceApi

- **토스 계좌의 현재가는 계좌 토큰 `/api/v1/prices`다**(`TossPriceApi.currentPrices`, 계좌 헤더 `X-Tossinvest-Account` 전송, 캐시 없음). Q-13: 2026-11-21 운영에서 계좌 토큰으로 읽기 전용 확인 — 토큰 200, `GET /api/v1/prices?symbols=SOXL,TQQQ` 200(계좌 헤더 유무 모두). 응답 `{"result":[{"symbol","timestamp","lastPrice","currency"}]}`. 응답에 없는 종목은 결과 Map에서 빠진다(키 부재 = 그 종목 실패).

### 잔고 부족 거절 분류
- 주문 POST가 HTTP 422 + `error.code = insufficient-buying-power`이면 `TossApiException.isInsufficientFunds()`=true(확정 거절의 하위 분류, `TossHttpClient`가 판정). 출처: 공식 문서 `openapi.tossinvest.com/openapi-docs/overview.md`.
- 422 `insufficient-sellable-quantity`(SELL, 판매 가능 수량 부족)도 같은 `isInsufficientFunds()`로 분류한다. `idempotency-key-conflict` 등 다른 422 코드는 분류하지 않는다. 방향별 문구는 알림 어댑터가 정한다.

- 키 만료일 조회 API 없음 — 등록·교체 시점 + 1년(유효기간 1년), 사용자 입력 우선(`ApiKeyExpirySource.ESTIMATED`)
