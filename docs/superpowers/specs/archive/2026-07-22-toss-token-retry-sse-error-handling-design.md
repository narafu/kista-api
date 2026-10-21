# Toss 토큰 재시도 및 SSE 오류 처리 설계

## 배경

로그인 직후 동일 Toss 계좌를 사용하는 여러 요청이 동시에 실행될 때 계좌 토큰 재발급 후에도 `401 invalid-token`이 반복되어 라이브 예수금 조회가 실패한다. 별도로 매매 SSE 연결의 30분 타임아웃 또는 클라이언트 연결 종료 시 `GlobalExceptionHandler`가 `ProblemDetail`을 `text/event-stream` 응답에 쓰려 해 직렬화 경고가 발생한다.

두 문제는 독립적이므로 각각 최소 변경으로 수정하되, 기존 REST 오류 응답과 KIS 토큰 정책에는 영향을 주지 않는다.

## 조사 결과

### Toss 토큰

- `DoubleCheckedTokenCache`는 계좌별 락 안에서 캐시를 다시 확인하므로 동일 계좌의 동시 캐시 미스가 OAuth 발급을 여러 번 실행하지 않는다.
- DB 무효화 쿼리는 `accountId`와 실제 거절된 access token이 모두 일치할 때만 갱신하므로 stale 401이 이미 저장된 신규 계좌 토큰을 무효화하지 않는다.
- 현재 `TossHttpClient`는 모든 401에서 현재 시도 토큰을 무효화하고 다시 조회한다. 따라서 신규 토큰이 리소스 서버에 아직 전파되지 않아 첫 재시도가 401이면 그 신규 토큰을 바로 폐기하고 또 다른 토큰을 발급한다.
- 이 동작은 백오프의 목적인 "같은 신규 토큰의 전파 대기"와 모순된다. 기존 단위 테스트도 매 시도마다 서로 다른 토큰을 기대해 잘못된 의미론을 고정한다.
- 관리자 토큰 무효화는 거절된 토큰을 인자로 받지 않아, 오래된 요청의 401이 이미 발급된 신규 관리자 토큰을 무효화할 수 있다.
- Toss 공식 문서는 OAuth client credentials, 401 인증 실패, 429 요청 제한을 구분하지만 신규 발급이 기존 토큰을 폐기하는지와 전파 시간은 보장하지 않는다. 따라서 401을 발급 rate-limit으로 단정하지 않고 불필요한 반복 발급을 제거한다.

### SSE

- `TradeSseEmitterRegistry`는 연결 즉시 ping을 전송하고 30분 타임아웃을 설정한다. 타임아웃 시 `AsyncRequestTimeoutException`, 연결 종료나 쓰기 실패 시 `AsyncRequestNotUsableException`이 발생할 수 있다.
- 두 예외는 현재 503 매핑에 포함되어 오류 로그 저장만 생략할 뿐, `handleAll`은 여전히 `ProblemDetail` 본문을 반환한다.
- 이미 SSE ping으로 `text/event-stream` 응답이 커밋된 뒤에는 JSON용 `ProblemDetail`을 쓸 수 없어 `HttpMessageNotWritableException`과 response-already-committed 오류가 연쇄 발생한다.
- 연결은 이미 만료되거나 끊긴 상태이고 레지스트리 cleanup callback도 등록되어 있으므로 주 영향은 불필요한 서버 경고와 재연결 사이 이벤트 공백이다.

## 선택한 설계

### Toss: 동일 발급 락 안의 세대 보호와 요청 내 재사용

`TossHttpClient`의 한 요청은 다음 상태 전이를 따른다.

1. 캐시에서 최초 토큰을 가져와 요청한다.
2. 최초 요청이 401이면 계좌 발급 락 안에서 거절 토큰과 현재 캐시 토큰을 비교한다. 현재 토큰이 더 새로우면 그대로 반환하고, 거절 토큰이 최근 2초 안에 발급한 동일 세대이면 리소스 서버 전파 대상으로 보고 보존한다.
3. 보호 대상이 아닌 현재 거절 토큰일 때만 조건부 무효화하고 같은 락 안에서 OAuth 발급·저장·발급 시각 기록까지 완료한다.
4. 복구된 최신/신규 토큰을 얻은 뒤 300ms 대기하고 첫 재시도를 수행한다. 후속 401은 같은 토큰을 유지한 채 600ms를 추가 대기한다.
5. 재시도 한도를 넘으면 기존과 같이 `TossApiException`을 던진다.

관리자 토큰도 `recoverAdminToken(rejectedAccessToken)`과 `adminLock` 안에서 같은 현재 토큰 비교·최근 발급 세대 보호·단일 발급 정책을 적용한다.

계좌별 전체 API 요청을 직렬화하거나 별도 in-flight Future를 도입하지 않는다. 락은 캐시 복구와 OAuth 발급만 조정하며 정상 리소스 API 호출은 계속 병렬로 실행한다.

### 운영 다중 인스턴스: Redis canonical token과 fencing generation

Toss 계좌·관리자 토큰은 모두 Redis를 canonical 저장소로 사용한다. Toss 경로는 PostgreSQL `broker_tokens`를 읽거나 무효화하거나 저장하지 않는다. KIS만 기존 `BrokerTokenCachePort`와 `DoubleCheckedTokenCache`를 유지한다.

- scope는 `account:<UUID>`와 `admin`이다. scope별 canonical Redis hash는 access token, shortened expiry epoch, fencing generation을 저장하고 실제 OAuth 만료보다 5분 짧은 TTL을 갖는다.
- 발급 시작은 하나의 Lua script가 TTL owner lease의 `SET NX PX`와 generation `INCR`를 원자적으로 수행한다. lease는 중복 OAuth를 줄이는 최적화이고 correctness 경계가 아니다.
- canonical write Lua는 incoming generation을 현재 generation counter 및 저장된 canonical generation과 비교한다. incoming이 둘 중 하나보다 작으면 쓰기를 거절한다. 따라서 lease가 만료된 뒤 generation 2 owner가 시작하면 지연된 generation 1 owner는 token을 덮어쓸 수 없다.
- stale write owner는 자신의 token을 반환하지 않고 generation이 더 큰 canonical token이 보일 때까지 bounded polling한 뒤 그 token을 반환한다. 새 owner 저장이 끝나지 않으면 503으로 fail-closed 한다.
- canonical CAS 성공과 2초 SHA-256 recent fingerprint 저장은 같은 Lua script에서 수행한다. raw bearer token은 canonical hash에만 존재하고 로그/fingerprint key에는 남기지 않는다.
- lease 해제는 owner 값이 일치할 때만 삭제하는 Lua compare-delete다. 만료된 owner는 successor lease를 삭제할 수 없다.
- Redis read/script 오류, malformed canonical state, polling 소진은 모두 `TossApiException`으로 fail-closed 하며 JVM-local 또는 PostgreSQL token fallback은 없다.
- 정상 Toss resource API 호출은 lease 밖에서 병렬 실행한다.
- 현재 production은 이 branch 이전 protocol이므로 최초 전환은 Fly `immediate`로 old/new overlap 없이 수행한다. production 검증 뒤 별도 follow-up 배포에서 `rolling`으로 복원한다.

### SSE: 본문 없는 전용 비정상 종료 처리

`AsyncRequestTimeoutException`과 `AsyncRequestNotUsableException`을 일반 `ProblemDetail` 매핑에서 제거한다. 두 예외를 받는 전용 `@ExceptionHandler`는 반환 본문 없이 처리하고 앱 오류 로그도 저장하지 않는다.

이 처리로 이미 커밋된 SSE 응답에 다른 미디어 타입의 본문을 쓰지 않는다. 일반 REST 요청의 `ProblemDetail` 정책과 다른 예외 매핑은 유지한다. Trade SSE의 30분 타임아웃이나 status SSE의 수명 정책은 이번 변경에서 바꾸지 않는다.

## 오류 처리와 관측성

- 최초/전파 대기 재시도를 로그에서 구분해 실제 토큰 갱신 횟수와 같은 토큰 재시도를 식별할 수 있게 한다.
- OAuth 발급 자체가 실패하면 기존 자격증명 오류 흐름을 유지한다.
- 최종 401은 기존처럼 `TossApiException`으로 변환해 호출자가 라이브 잔고 조회 불가를 처리하게 한다.
- 정상적인 SSE 타임아웃과 연결 종료는 app error log와 ProblemDetail 생성 대상에서 제외한다.
- 429가 관측되면 별도의 발급 제한 정책으로 다룬다. 이번 401 수정에 추정성 debounce를 추가하지 않는다.

## 테스트 설계

구현은 실패 테스트부터 진행한다.

### Toss 회귀 테스트

- 최초 토큰 401, 신규 토큰 첫 요청 401, 다음 요청 성공 시 OAuth 토큰 조회는 최초와 갱신의 두 번뿐이고 두 번째와 세 번째 HTTP 요청은 같은 신규 토큰을 사용한다.
- 최종 실패 시에도 최초 토큰만 무효화하며 신규 토큰을 반복 무효화하지 않는다.
- stale 관리자 토큰의 401 복구는 현재 신규 관리자 토큰을 반환한다.
- 늦게 시작한 계좌·관리자 요청이 최근 발급된 토큰으로 401을 받아도 같은 세대를 재사용하고 OAuth 발급은 한 번만 실행된다.
- 비 401 오류와 최대 재시도 실패의 기존 예외 계약을 유지한다.
- fake Redis clock으로 generation 1 owner lease를 만료시킨 뒤 generation 2 successor를 시작한다. successor token 저장 후 지연된 generation 1 CAS는 거절되고 두 owner 모두 generation 2 token을 반환해야 한다.
- owner-safe unlock이 successor lease를 보존하고 Redis 장애가 OAuth/JPA fallback 없이 실패하는지 검증한다.
- outer Spring transaction 안에서 Toss account token을 발급해도 `broker_tokens` 행과 JPA pool connection 사용량이 늘지 않는지 검증한다.
- 로컬 Redis가 있으면 실제 Lua acquire/CAS/unlock과 TTL을 통합 검증한다. unavailable이면 faithful fake 결과를 보고서에 명시한다.

### SSE 회귀 테스트

- async timeout 및 async request unusable 예외의 전용 핸들러가 본문을 반환하지 않고 app error log를 저장하지 않는다.
- MockMvc 비동기 SSE 요청을 오류로 완료·dispatch했을 때 `ProblemDetail` message converter 오류가 발생하지 않는다.
- 정상 REST 예외가 기존 `ProblemDetail` 상태와 본문을 계속 반환한다.
- 기존 emitter 연결·전송·cleanup 동작을 유지한다.

## 범위 제외

- 프론트엔드 예수금 부족 표시 변경
- KIS 토큰 재시도 정책 변경
- Toss API 발급 rate-limit 추정에 기반한 임의 debounce
- SSE heartbeat, 무제한 연결, 자동 재연결 프로토콜 변경
- 운영 배포 및 실계좌를 사용한 파괴적 재현
