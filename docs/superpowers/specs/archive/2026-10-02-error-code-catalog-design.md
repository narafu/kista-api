# 기계 판독 에러 코드(ErrorCode) 카탈로그 설계

- 작성일: 2026-10-02
- 범위: kista-api(`:shared`·`:api`·`:trading-core`). kista-ui 반영은 별도 세션(본 문서 "kista-ui 인계" 절)

## 1. 목표

- ProblemDetail 응답에 안정적인 기계 판독 코드 `code`를 추가한다. UI는 코드로 분기하고 코드별 문구를 UI가 소유한다.
- UI 판별 우선순위: `code` 매핑 → 서버 `detail`(사용자용 폴백) → UI 기본 문구.
- `detail`은 사용자에게 그대로 보여줄 수 있는 한국어 문구라는 계약을 명문화하고, 계약을 깨던 프레임워크 예외 detail을 고정 문구로 교체한다. 내부 예외 메시지는 로그에만 남긴다.
- `title` 문자열이 계약 역할을 하던 구조(kista-ui `relayUpstreamError`의 503 판별)를 `code` 기반으로 옮길 수 있게 한다.

## 2. 결정 사항 (2026-10-02 승인)

| # | 결정 | 근거 |
|---|---|---|
| 1 | ProblemDetail 확장 프로퍼티 `code`(문자열). `type`은 기본값 `about:blank` 유지 | `CooldownException`의 `retryAfter`가 이미 `setProperty` 선례. UI `apiMsg`가 본문 JSON을 이미 읽으므로 필드 하나 추가로 끝남. `type` URI는 UI가 문자열 파싱해야 해 이득 없음 |
| 2 | `UPPER_SNAKE` 명명, UI가 실제로 분기하거나 문구를 달리할 대상만 초기 카탈로그로 | 점진 확장 |
| 3 | enum `com.kista.platform.web.ErrorCode`(`:shared`), 코드는 핸들러 매핑 테이블이 지정. openapi.json에는 root `OpenApiCustomizer`가 `components.schemas.ErrorCode` enum으로 등록 | `Mapping`이 `platform.web`에 있고 platform은 outbound-zero leaf — 같은 패키지에 둔다. 도메인 예외는 HTTP 코드를 모른다 |
| 4 | 1차는 카탈로그 대상만 코드 부여, 나머지는 `code` 필드 자체를 생략. 내부 API 어댑터의 code 전달은 하지 않음 | 아래 "4. 범위" |
| 5 | detail 카피는 kista-ui 카피 규칙(완전한 문장 → "~습니다." + 마침표)에 맞춘다 | 아래 "6. detail 카피" |
| A | `Mapping`에 선택적 고정 detail을 두어 Spring 프레임워크 예외 4종의 영어 내부 메시지를 고정 한국어 문구로 교체 | "detail은 사용자 안전" 계약이 첫날부터 거짓이 되지 않게 |
| + | `ManualTradingService.queryFailure`의 비증권사 실패를 409 → 500으로 바로잡음 | 아래 "7. 동반 결함 수정" |

## 3. 초기 카탈로그

| code | status | 대상 예외 (매핑 위치) |
|---|---|---|
| `BROKER_UNAVAILABLE` | 503 | `BrokerApiException` (TradingExceptionHandler 전용 핸들러) |
| `BROKER_CREDENTIAL_INVALID` | 422 | `BrokerCredentialException` (Trading), `AdminBrokerCredentialException` (Global) |
| `BROKER_RATE_LIMITED` | 429 | `BrokerRateLimitException` (Trading), `AdminBrokerRateLimitException` (Global) |
| `DUPLICATE_ACCOUNT` | 409 | `Account.DuplicateAccountException` (Trading) |
| `ALREADY_ORDERED_TODAY` | 409 | 신설 `ManualTradingException` 서브클래스 — "오늘 이미 주문이 등록된 전략" 분기 (Trading) |
| `ORDER_NOT_CANCELLABLE` | 409 | `OrderCancelException` (Trading) |
| `MONTH_CLOSED` | 409 | `MonthlyClosing.MonthClosedException` (Global) |
| `ACCESS_DENIED` | 403 | `SecurityException` (`ProblemDetailMappings.GENERIC` — 양 프로세스 공통) |
| `COOLDOWN_ACTIVE` | 429 | `User.CooldownException` (Global 전용 핸들러) |
| `TRADING_CORE_UNAVAILABLE` | 503 | `TradingPolicyUnavailableException` (Global) |

**불변 규칙**: 한 번 공개한 코드 상수는 이름 변경·삭제 금지(UI 계약). 의미가 바뀌면 새 코드를 추가하고 옛 코드는 deprecated 주석만 단다.

## 4. 범위 — 코드 없이 두는 것

- 400 계열(`IllegalArgumentException`/`IllegalStateException`/검증 실패 등), 404, finance 409 5종(MonthClosed 제외), privacy 409(`PrivacyTradeConflictException`/`AdminPrivacyTradeConflictException`), `ManualTradingException`의 나머지 의미(실행 이력 없음·예수금 부족·보유수량 부족), 500 catch-all.
- 이들은 응답에 `code` 키가 없다(`null` 직렬화가 아니라 프로퍼티 미설정). 기존 동작과 동일.

**프로세스 경계 한계**: UI → root → (내부 HTTP) → trading-core 경로에서 root 어댑터(`TradingCommandHttpAdapter` 등)는 trading-core 응답의 status만 보고 `Admin*` 예외를 되살린다. 1차는 root 매핑 테이블이 `Admin*` 예외에 같은 코드를 붙여 결과를 맞춘다 — status:code가 1:1인 422/429는 정확하다. 같은 status에 서로 다른 코드가 실려 오는 내부 API 경로가 생기면 그때 `InternalApiErrorDetails`가 `code`도 읽어 전달하도록 확장한다(현재 root가 409로 되살리는 건 privacy 충돌 1종뿐이라 해당 없음).

## 5. 구조

### 5.1 `com.kista.platform.web.ErrorCode` (`:shared`)

```java
// ProblemDetail "code" 확장 프로퍼티 값 — kista-ui가 분기·문구 매핑에 쓰는 공개 계약. 상수 이름 변경·삭제 금지
public enum ErrorCode {
    BROKER_UNAVAILABLE,
    BROKER_CREDENTIAL_INVALID,
    ...
}
```

### 5.2 `ProblemDetailMappings.Mapping` 확장

```java
public record Mapping(HttpStatus status, String title, ErrorCode code, String fixedDetail) {
    public Mapping(HttpStatus status, String title) { this(status, title, null, null); }
    public Mapping(HttpStatus status, String title, ErrorCode code) { this(status, title, code, null); }
}
```

- 응답 생성을 `Mapping`에서 하는 헬퍼 1개로 모은다: `toProblem(Mapping m, Exception ex)` — detail = `fixedDetail != null ? fixedDetail : ex.getMessage()`, `code != null`이면 `setProperty("code", code.name())`.
- `catchAll`·`TradingExceptionHandler.handleTradingCoreExceptions`·`GlobalExceptionHandler`가 모두 이 헬퍼를 쓴다. 전용 핸들러(`BrokerApiException` 503, `CooldownException`)는 `problem(...)` 후 동일하게 code를 세팅 — `problem(status, title, detail, code)` 오버로드를 둔다.
- `fixedDetail`이 쓰이는 경우 원본 메시지는 `log.debug`로만 남긴다.

### 5.3 고정 detail 대상 (결정 A)

`GENERIC`의 Spring 프레임워크 예외 4종 — 원본 메시지가 영어 내부 정보(타입명·파서 오류)라 사용자 노출 불가:

| 예외 | fixedDetail |
|---|---|
| `MissingServletRequestParameterException` | "필수 요청 값이 누락되었습니다." |
| `MethodArgumentTypeMismatchException` | "요청 값의 형식이 올바르지 않습니다." |
| `HttpMessageNotReadableException` | "요청 형식이 올바르지 않습니다." |
| `NoResourceFoundException` | "요청한 경로를 찾을 수 없습니다." |

- `DateTimeParseException`은 도메인 코드가 직접 파싱하다 던지는 경우가 많고 메시지가 "Text '...' could not be parsed"(영어)라 동일하게 "날짜 형식이 올바르지 않습니다."로 고정한다.
- `MethodArgumentNotValidException`(검증 실패 "[field: msg]")은 1차 현행 유지 — 필드명이 섞이지만 메시지 자체는 각 DTO의 한국어 제약 메시지다. 문서에 예외로 명시.
- `SecurityException`·`IllegalArgumentException`·`IllegalStateException`·`NoSuchElementException`은 애플리케이션 코드가 한국어 메시지로 던지는 관례라 원문 유지.

### 5.4 `ALREADY_ORDERED_TODAY` 서브클래스

- `trading.domain.model`에 `ManualTradingException`을 상속한 전용 예외(예: `AlreadyOrderedTodayException`)를 두고 `ManualTradingService`의 이중 실행 거부 분기만 이것을 던진다.
- `TradingExceptionHandler.MAPPINGS`에 서브클래스 행을 추가 — `resolve()`가 클래스 계층을 하위부터 탐색하므로 핸들러 로직 변경 없음. `@ExceptionHandler` 목록은 상위 `ManualTradingException`이 이미 포함.
- 주의: `Map.of` 조회는 하위 클래스부터 올라가므로 서브클래스 행이 상위 행보다 우선한다(순서 무관).

### 5.5 OpenAPI 노출

- root(`com.kista.web` — 앱셸, 특정 애그리게이트 소유가 아님)에 `ErrorCodeOpenApiCustomizer implements OpenApiCustomizer`를 추가해 `components.schemas.ErrorCode`를 `type: string, enum: ErrorCode.values()`로 등록한다. kista-ui는 8080 `/api-docs`만 가져오므로 root 등록으로 충분하다.
- openapi-typescript는 참조되지 않은 component도 `components["schemas"]["ErrorCode"]`로 생성한다.
- 엔드포인트별 에러 응답 스키마(`ProblemDetail` + `code`) 주석은 1차 범위 밖.

## 6. detail 카피 정리

카탈로그 대상 예외 메시지·고정 문구를 kista-ui 카피 규칙에 맞춘다(완전한 문장은 "~습니다." + 마침표, 권유는 "~해주세요."):

| 위치 | 현재 | 변경 |
|---|---|---|
| `TradingExceptionHandler.BROKER_UNAVAILABLE_DETAIL` | 증권사 API 조회에 실패했습니다. 잠시 후 다시 시도해주세요 | …다시 시도해주세요. |
| `BrokerCredentialException`/`AdminBrokerCredentialException` | 증권사 API 키가 유효하지 않습니다 | …유효하지 않습니다. |
| `BrokerRateLimitException`/`AdminBrokerRateLimitException` | KIS API 호출 한도를 초과했습니다. 잠시 후 다시 시도하세요 | 증권사 API 호출 한도를 초과했습니다. 잠시 후 다시 시도해주세요. (Toss도 같은 예외라 "KIS" 제거) |
| `TradingPolicyUnavailableException` | …잠시 후 다시 시도해주세요 | …다시 시도해주세요. |
| `ManualTradingService` "오늘 이미 주문이 등록된 전략입니다" | — | 마침표 추가 |
| `OrderCancelService` "취소 가능한 상태가 아닙니다. 현재 상태: PLACED" | enum 원문 노출 | "취소 가능한 상태가 아닙니다." (상태값은 로그로) |
| `MonthClosedException`·`DuplicateAccountException`·`CooldownException` 메시지 | 구현 시 확인 | 규칙에 맞게 마침표 정리, 내부 값(계좌번호 원문 등) 노출 시 제거 |
| catch-all 500 "예기치 않은 오류가 발생했습니다" | — | 마침표 추가 |

- 범위 밖: 카탈로그 밖 예외의 개별 메시지(`SecurityException` 17곳 등) — 점진 확장 때 함께 정리.
- 바뀌는 문자열은 kista-ui 테스트 픽스처(`routeHelpers.test.ts`, `orderBannerCopy.test.ts` 등)에 영향 — 인계 절에 목록 기재.

## 7. 동반 결함 수정 — 바로주문 비증권사 실패 409 → 500

현재 `ManualTradingService.queryFailure`는 증권사 타입이 아닌 예외(계획 계산·예산 배정 중 DB/데이터 결함 등)를 `ManualTradingException`(409)으로 감싸고 "증권사 API 조회에 실패했습니다"를 내보낸다. 원인과 무관한 문구 + 클라이언트 충돌(409)로 잘못 분류된 상태.

- 신설 `ManualTradingFailedException extends ManualTradingException`(이름은 구현 시 확정)을 던지고 `TradingExceptionHandler.MAPPINGS`에 `500 "Manual Trading Failed"`, fixedDetail "주문 계산 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요."로 매핑한다. code 없음.
- 기존 `TradingErrorEvent` 발행(관리자 텔레그램 + `AppErrorRaisedEvent`)은 유지한다. 이 매핑은 `handleTradingCoreExceptions` 경로라 catch-all 보고를 타지 않으므로 에러 로그가 중복 저장되지 않는다.
- 증권사 타입 예외는 기존대로 그대로 전파(503/422/429 + 코드).

## 8. 테스트

- `ProblemDetailMappingsTest`: code 세팅/미세팅, fixedDetail 우선, 서브클래스 계층 해석.
- `TradingExceptionHandler` 테스트(기존 MockMvc 테스트 확장): 503 `BROKER_UNAVAILABLE`, 422/429 코드, `ALREADY_ORDERED_TODAY`, `ManualTradingFailedException` 500 + 고정 detail.
- `GlobalExceptionHandler` 테스트: `MONTH_CLOSED`, `COOLDOWN_ACTIVE`(+`retryAfter` 유지), `ACCESS_DENIED`, 고정 detail 4종, 코드 없는 예외는 `code` 키 부재.
- `ManualTradingServiceTest`: 이중 실행 → 서브클래스, 비증권사 실패 → `ManualTradingFailedException`.
- OpenAPI: `/api-docs`에 `components.schemas.ErrorCode.enum`이 `ErrorCode.values()`와 일치(기존 openapi 테스트 패턴이 있으면 그것에 추가, 없으면 customizer 단위 테스트).
- 응답 JSON에 `code`가 최상위 필드로 직렬화되는지 MockMvc `jsonPath("$.code")`로 확인(Jackson 3 + ProblemDetail properties).

## 9. 문서

- `docs/agents/constraints.md` "GlobalExceptionHandler 자동 예외 처리" 절에 추가: detail 사용자 안전 계약, `code` 계약·불변 규칙, 신규 코드 추가 절차(enum 상수 → Mapping code → 필요 시 서브클래스 → openapi 자동 반영 → UI `gen:types`+문구 매핑).
- `docs/agents/modules/platform.md`: `ErrorCode` 소개.
- `TradingExceptionHandler`의 "title은 relay 판별 계약" 주석을 "kista-ui가 code로 이행하기 전까지 title도 유지" 로 갱신(이행 완료 후 별도 커밋에서 제거).

## 10. kista-ui 인계 (구현 완료 후 요약 전달)

- 코드 목록·status 표(3절), 응답 예시:
  ```json
  {"type":"about:blank","title":"KIS API Error","status":503,"detail":"증권사 API 조회에 실패했습니다. 잠시 후 다시 시도해주세요.","instance":"/api/...","code":"BROKER_UNAVAILABLE"}
  ```
- `relayUpstreamError`: 503 relay 판별을 `title ∈ BROKER_UNAVAILABLE_TITLES`에서 `code === 'BROKER_UNAVAILABLE'`로 교체하고 relay 필드에 `code` 포함. 4xx는 본문 그대로 relay라 변경 불필요.
- `apiMsg`/`ApiError`: `code` 매핑 → `detail` → fallback 순.
- 분기 교체 후보: `ConfirmStep` 409/422/429, `useAccountMarginQuery` 422, `useStrategyQueries` 409(현재 409 = "이미 실행"으로 가정 — 다른 409 의미도 같은 토스트로 덮이던 결함).
- 바뀐 detail 문자열 목록(6절)과 영향 테스트 픽스처.
- 배포 순서: API가 `code`와 title을 함께 보내므로 API 선배포 → UI 후배포 무해. UI가 title 판별을 제거한 뒤에만 API에서 title 계약 주석 정리.
