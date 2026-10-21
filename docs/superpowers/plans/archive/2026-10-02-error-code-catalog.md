# 기계 판독 에러 코드(ErrorCode) 카탈로그 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** ProblemDetail 응답에 기계 판독 `code` 확장 프로퍼티를 추가하고, detail을 사용자 노출 안전 문구로 정리한다.

**Architecture:** `:shared`의 `platform.web`에 `ErrorCode` enum을 두고 `ProblemDetailMappings.Mapping`을 `(status, title, code, fixedDetail)`로 넓힌다. root `GlobalExceptionHandler`·trading-core `TradingExceptionHandler`의 매핑 테이블이 코드를 지정하고, 공용 헬퍼가 `setProperty("code", ...)`로 싣는다. root `OpenApiCustomizer`가 `ErrorCode` enum을 openapi.json `components.schemas`에 등록한다.

**Tech Stack:** Java 21, Spring Boot 4(Jackson 3), springdoc-openapi, JUnit 5, AssertJ, Mockito, MockMvc.

**Spec:** `docs/superpowers/specs/2026-10-02-error-code-catalog-design.md`

## Global Constraints

- 코드 상수 이름은 `UPPER_SNAKE`, 한 번 공개하면 이름 변경·삭제 금지.
- 코드가 없는 응답은 `code` 키 자체가 없어야 한다(`null` 직렬화 금지 — `setProperty`를 호출하지 않는다).
- detail 카피: 완전한 문장은 "~습니다." + 마침표, 권유는 "~해주세요." + 마침표.
- 주석은 `//` 인라인만(Javadoc·블록 주석 금지), 신규 코드엔 한 줄 주석 동반.
- `platform`은 다른 `com.kista` 모듈을 참조하지 않는다(`HexagonalArchitectureTest.platform_must_not_depend_on_other_modules`).
- `--tests` 필터는 서브프로젝트 접두사 필수(`:shared:test`, `:trading-core:test`, `:test`).
- 빌드 로그는 `2>&1 | grep -E "FAILED|BUILD|ERROR|Test.*>"`로 필터링.
- 커밋 author `narafu <narafu@kakao.com>`, 메시지 한글 Conventional Commit, 푸시 금지.

## Review Focus

1. 코드 없는 예외(예: `IllegalArgumentException` 400) 응답 JSON에 `"code"` 키가 아예 없어야 한다 — Task 1 테스트 `catchAll_mappedWithoutCode_hasNoCodeProperty`.
2. `ManualTradingException` 서브클래스가 상위 `ManualTradingException` 매핑(409)보다 먼저 해석돼야 한다 — Task 2 핸들러 테스트 2건.
3. `code`가 실제 HTTP 응답 JSON 최상위 필드로 직렬화된다(Jackson 3 + ProblemDetail properties) — Task 2 `AccountControllerTest` `jsonPath("$.code")`.
4. `CooldownException` 응답이 `code`와 기존 `retryAfter`를 둘 다 유지한다 — Task 3 테스트.
5. 바로주문 비증권사 실패(500)가 catch-all 보고를 타지 않아 에러 로그가 이중 저장되지 않는다 — Task 2 핸들러 테스트 `verifyNoInteractions(eventPublisher)`.

---

### Task 1: `:shared` — ErrorCode enum + Mapping 확장 + 범용 매핑 코드/고정 detail

**Files:**
- Create: `shared/src/main/java/com/kista/platform/web/ErrorCode.java`
- Modify: `shared/src/main/java/com/kista/platform/web/ProblemDetailMappings.java`
- Test: `shared/src/test/java/com/kista/platform/web/ProblemDetailMappingsTest.java`

**Interfaces:**
- Produces:
  - `enum ErrorCode { BROKER_UNAVAILABLE, BROKER_CREDENTIAL_INVALID, BROKER_RATE_LIMITED, DUPLICATE_ACCOUNT, ALREADY_ORDERED_TODAY, ORDER_NOT_CANCELLABLE, MONTH_CLOSED, ACCESS_DENIED, COOLDOWN_ACTIVE, TRADING_CORE_UNAVAILABLE }`
  - `record Mapping(HttpStatus status, String title, ErrorCode code, String fixedDetail)` + 생성자 `Mapping(status, title)`, `Mapping(status, title, code)`
  - `static ProblemDetail toProblem(Mapping m, Exception ex)` — detail = fixedDetail ?: ex.getMessage(), code 있으면 프로퍼티 세팅
  - `static ProblemDetail problem(HttpStatus, String title, String detail, ErrorCode code)` — code null이면 프로퍼티 미설정
  - 기존 `problem(HttpStatus, String, String)` 유지
  - 상수 `CODE_PROPERTY = "code"`

- [ ] **Step 1: 실패 테스트 작성** — `ProblemDetailMappingsTest`에 추가 (기존 테스트 `catchAll_unmappedException_invokesConsumerAndReturns500`의 기대 detail도 `"예기치 않은 오류가 발생했습니다."`로 수정)

```java
    @Test
    void toProblem_withCode_setsCodeProperty() {
        var p = ProblemDetailMappings.toProblem(
                new Mapping(HttpStatus.CONFLICT, "Conflict", ErrorCode.MONTH_CLOSED), new IllegalStateException("마감된 달입니다."));
        assertThat(p.getStatus()).isEqualTo(409);
        assertThat(p.getDetail()).isEqualTo("마감된 달입니다.");
        assertThat(p.getProperties()).containsEntry("code", "MONTH_CLOSED");
    }

    @Test
    void toProblem_fixedDetail_overridesExceptionMessage() {
        var p = ProblemDetailMappings.toProblem(
                new Mapping(HttpStatus.BAD_REQUEST, "Bad Request", null, "요청 형식이 올바르지 않습니다."),
                new IllegalArgumentException("Failed to convert value of type 'java.lang.String'"));
        assertThat(p.getDetail()).isEqualTo("요청 형식이 올바르지 않습니다.");
    }

    @Test
    void catchAll_mappedWithoutCode_hasNoCodeProperty() {
        var p = ProblemDetailMappings.catchAll(new IllegalArgumentException("잘못된 값입니다."),
                ProblemDetailMappings.GENERIC, ex -> {});
        assertThat(p.getStatus()).isEqualTo(400);
        assertThat(p.getProperties() == null || !p.getProperties().containsKey("code")).isTrue();
    }

    @Test
    void catchAll_securityException_hasAccessDeniedCode() {
        var p = ProblemDetailMappings.catchAll(new SecurityException("접근 권한이 없습니다."),
                ProblemDetailMappings.GENERIC, ex -> {});
        assertThat(p.getStatus()).isEqualTo(403);
        assertThat(p.getDetail()).isEqualTo("접근 권한이 없습니다.");
        assertThat(p.getProperties()).containsEntry("code", "ACCESS_DENIED");
    }

    @Test
    void catchAll_frameworkException_usesFixedKoreanDetail() {
        var p = ProblemDetailMappings.catchAll(
                new java.time.format.DateTimeParseException("Text 'abc' could not be parsed", "abc", 0),
                ProblemDetailMappings.GENERIC, ex -> {});
        assertThat(p.getStatus()).isEqualTo(400);
        assertThat(p.getDetail()).isEqualTo("날짜 형식이 올바르지 않습니다.");
    }

    @Test
    void problem_withNullCode_hasNoCodeProperty() {
        var p = ProblemDetailMappings.problem(HttpStatus.CONFLICT, "Conflict", "충돌입니다.", null);
        assertThat(p.getProperties() == null || !p.getProperties().containsKey("code")).isTrue();
    }
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :shared:test --tests 'com.kista.platform.web.ProblemDetailMappingsTest' 2>&1 | grep -E "FAILED|BUILD|error:"`
Expected: 컴파일 실패(`ErrorCode`, `toProblem` 없음)

- [ ] **Step 3: `ErrorCode.java` 작성**

```java
package com.kista.platform.web;

// ProblemDetail "code" 확장 프로퍼티 값 — kista-ui가 분기·문구 매핑에 쓰는 공개 계약(openapi.json components.schemas.ErrorCode).
// 상수 이름 변경·삭제 금지: 의미가 바뀌면 새 상수를 추가한다
public enum ErrorCode {
    BROKER_UNAVAILABLE,          // 503 증권사 API 장애
    BROKER_CREDENTIAL_INVALID,   // 422 증권사 API 키 오류
    BROKER_RATE_LIMITED,         // 429 증권사 API 호출 한도 초과
    DUPLICATE_ACCOUNT,           // 409 이미 등록된 증권 계좌
    ALREADY_ORDERED_TODAY,       // 409 바로주문 — 오늘 이미 주문이 등록된 전략
    ORDER_NOT_CANCELLABLE,       // 409 취소 불가 상태의 주문
    MONTH_CLOSED,                // 409 기록 점검 완료(마감)된 달의 가계부 쓰기
    ACCESS_DENIED,               // 403 소유·권한 위반
    COOLDOWN_ACTIVE,             // 429 재신청 대기 시간 미경과
    TRADING_CORE_UNAVAILABLE     // 503 매매 서버(trading-core) 도달 실패
}
```

- [ ] **Step 4: `ProblemDetailMappings` 수정**

`Mapping` 레코드 교체:

```java
    // status·title·code·고정 detail 튜플 — code null이면 응답에 code 프로퍼티 없음, fixedDetail null이면 예외 메시지를 detail로 사용
    public record Mapping(HttpStatus status, String title, ErrorCode code, String fixedDetail) {
        public Mapping(HttpStatus status, String title) { this(status, title, null, null); }
        public Mapping(HttpStatus status, String title, ErrorCode code) { this(status, title, code, null); }
    }

    public static final String CODE_PROPERTY = "code"; // ProblemDetail 확장 프로퍼티 키 — kista-ui 계약
```

`GENERIC` 교체 (Map.of 10쌍 한도 내):

```java
    public static final Map<Class<? extends Exception>, Mapping> GENERIC = Map.of(
            SecurityException.class,                       new Mapping(HttpStatus.FORBIDDEN,   "Access Denied", ErrorCode.ACCESS_DENIED),
            IllegalStateException.class,                   new Mapping(HttpStatus.BAD_REQUEST, "Invalid State"),
            NoSuchElementException.class,                  new Mapping(HttpStatus.NOT_FOUND,   "Resource Not Found"),
            IllegalArgumentException.class,                new Mapping(HttpStatus.BAD_REQUEST, "Invalid Request"),
            // 아래 프레임워크 예외는 원본 메시지가 영어 내부 정보(타입명·파서 오류)라 고정 한국어 detail로 교체 — 원문은 debug 로그
            MissingServletRequestParameterException.class, new Mapping(HttpStatus.BAD_REQUEST, "Bad Request", null, "필수 요청 값이 누락되었습니다."),
            MethodArgumentTypeMismatchException.class,     new Mapping(HttpStatus.BAD_REQUEST, "Bad Request", null, "요청 값의 형식이 올바르지 않습니다."),
            DateTimeParseException.class,                  new Mapping(HttpStatus.BAD_REQUEST, "Invalid Date Format", null, "날짜 형식이 올바르지 않습니다."),
            // 요청 바디 파싱 실패(잘못된 JSON, enum에 없는 값 등) — 매핑 누락 시 catch-all이 500으로 처리해 클라이언트 오류가 서버 오류로 잘못 보고됨
            HttpMessageNotReadableException.class,         new Mapping(HttpStatus.BAD_REQUEST, "Malformed Request", null, "요청 형식이 올바르지 않습니다."),
            // 존재하지 않는 정적 리소스·경로(취약점 스캐너의 /actuator/** probe 등) — 매핑 없으면 catch-all이 500 + 오류 로그로 처리해 오염
            NoResourceFoundException.class,                new Mapping(HttpStatus.NOT_FOUND,   "Not Found", null, "요청한 경로를 찾을 수 없습니다.")
    );
```

`catchAll` 내부의 매핑 분기와 500 문구 교체:

```java
        Mapping m = resolve(ex, mappings);
        if (m != null) {
            return toProblem(m, ex);
        }
        // 매핑 없는 미처리 예외 — 호출자가 보고·로그를 수행한 뒤 500
        onUnmapped.accept(ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", "예기치 않은 오류가 발생했습니다.");
```

헬퍼 추가(`problem` 옆):

```java
    // 매핑 → ProblemDetail — fixedDetail 우선(원본 메시지는 debug 로그), code 있으면 확장 프로퍼티로 싣는다
    public static ProblemDetail toProblem(Mapping m, Exception ex) {
        if (m.fixedDetail() != null) log.debug("고정 detail로 대체된 예외 메시지: {}", ex.getMessage());
        String detail = m.fixedDetail() != null ? m.fixedDetail() : ex.getMessage();
        return problem(m.status(), m.title(), detail, m.code());
    }

    // code 포함 ProblemDetail 생성 — code null이면 프로퍼티를 세팅하지 않아 응답에 키 자체가 없다
    public static ProblemDetail problem(HttpStatus status, String title, String detail, ErrorCode code) {
        ProblemDetail problem = problem(status, title, detail);
        if (code != null) problem.setProperty(CODE_PROPERTY, code.name());
        return problem;
    }
```

- [ ] **Step 5: 통과 확인**

Run: `./gradlew :shared:test --tests 'com.kista.platform.web.ProblemDetailMappingsTest' 2>&1 | grep -E "FAILED|BUILD"`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: 커밋** (리뷰어 검수 후)

```bash
git add shared/src/main/java/com/kista/platform/web/ErrorCode.java shared/src/main/java/com/kista/platform/web/ProblemDetailMappings.java shared/src/test/java/com/kista/platform/web/ProblemDetailMappingsTest.java
git commit -m "feat(platform): ProblemDetail 기계 판독 에러 코드와 고정 detail 매핑 추가"
```

---

### Task 2: `:trading-core` — 핸들러 코드 부여, 예외 서브클래스 2종, 카피 정리, 바로주문 500 수정

**Files:**
- Create: `trading-core/src/main/java/com/kista/trading/domain/model/AlreadyOrderedTodayException.java`
- Create: `trading-core/src/main/java/com/kista/trading/domain/model/ManualTradingFailedException.java`
- Modify: `trading-core/src/main/java/com/kista/tradingweb/TradingExceptionHandler.java`
- Modify: `trading-core/src/main/java/com/kista/trading/application/service/ManualTradingService.java:62-63,148-153`
- Modify: `trading-core/src/main/java/com/kista/trading/application/service/OrderCancelService.java:110-113`
- Modify: `trading-core/src/main/java/com/kista/broker/domain/model/BrokerCredentialException.java`
- Modify: `trading-core/src/main/java/com/kista/broker/domain/model/BrokerRateLimitException.java`
- Modify: `trading-core/src/main/java/com/kista/account/domain/model/Account.java:40-45`
- Test: `trading-core/src/test/java/com/kista/tradingweb/TradingExceptionHandlerTest.java`
- Test: `trading-core/src/test/java/com/kista/trading/application/service/ManualTradingServiceTest.java`
- Test: `trading-core/src/test/java/com/kista/account/adapter/in/web/AccountControllerTest.java`
- Test: `trading-core/src/test/java/com/kista/trading/adapter/in/web/OrderCancelControllerTest.java:80` (메시지 리터럴만)

**Interfaces:**
- Consumes (Task 1): `ErrorCode`, `Mapping(status, title, code)`, `Mapping(status, title, code, fixedDetail)`, `ProblemDetailMappings.toProblem(Mapping, Exception)`, `ProblemDetailMappings.problem(HttpStatus, String, String, ErrorCode)`
- Produces: `AlreadyOrderedTodayException()`(no-arg), `ManualTradingFailedException(Throwable cause)` — 둘 다 `ManualTradingException` 상속, `com.kista.trading.domain.model`

- [ ] **Step 1: 실패 테스트 작성**

`TradingExceptionHandlerTest`에 추가 (기존 `brokerApiException` 테스트의 detail 기대값 `"증권사 API 조회에 실패했습니다. 잠시 후 다시 시도해주세요."`로 수정, import `com.kista.platform.web.ErrorCode`, `com.kista.trading.domain.model.AlreadyOrderedTodayException`, `com.kista.trading.domain.model.ManualTradingFailedException`):

```java
    @Test
    void brokerCredentialException_hasCode() {
        var detail = handler.handleTradingCoreExceptions(new BrokerCredentialException());
        assertThat(detail.getProperties()).containsEntry("code", ErrorCode.BROKER_CREDENTIAL_INVALID.name());
        assertThat(detail.getDetail()).isEqualTo("증권사 API 키가 유효하지 않습니다.");
    }

    @Test
    void brokerRateLimitException_hasCode() {
        var detail = handler.handleTradingCoreExceptions(new BrokerRateLimitException());
        assertThat(detail.getProperties()).containsEntry("code", ErrorCode.BROKER_RATE_LIMITED.name());
        assertThat(detail.getDetail()).isEqualTo("증권사 API 호출 한도를 초과했습니다. 잠시 후 다시 시도해주세요.");
    }

    @Test
    void duplicateAccountException_hasCodeAndHidesAccountNo() {
        var detail = handler.handleTradingCoreExceptions(new Account.DuplicateAccountException("74420614-01"));
        assertThat(detail.getProperties()).containsEntry("code", ErrorCode.DUPLICATE_ACCOUNT.name());
        assertThat(detail.getDetail()).isEqualTo("이미 등록된 계좌번호입니다.");
    }

    @Test
    void orderCancelException_hasCode() {
        var detail = handler.handleTradingCoreExceptions(new OrderCancelException("취소 가능한 상태가 아닙니다."));
        assertThat(detail.getProperties()).containsEntry("code", ErrorCode.ORDER_NOT_CANCELLABLE.name());
    }

    @Test
    void alreadyOrderedToday_resolvesSubclassMappingBeforeParent() {
        var detail = handler.handleTradingCoreExceptions(new AlreadyOrderedTodayException());
        assertThat(detail.getStatus()).isEqualTo(409);
        assertThat(detail.getProperties()).containsEntry("code", ErrorCode.ALREADY_ORDERED_TODAY.name());
        assertThat(detail.getDetail()).isEqualTo("오늘 이미 주문이 등록된 전략입니다.");
    }

    @Test
    void plainManualTradingException_hasNoCode() {
        var detail = handler.handleTradingCoreExceptions(new ManualTradingException("예수금이 부족합니다"));
        assertThat(detail.getStatus()).isEqualTo(409);
        assertThat(detail.getProperties() == null || !detail.getProperties().containsKey("code")).isTrue();
    }

    @Test
    void manualTradingFailed_mapsTo500WithFixedDetailAndNoErrorReport() {
        var detail = handler.handleTradingCoreExceptions(new ManualTradingFailedException(new RuntimeException("DB down")));
        assertThat(detail.getStatus()).isEqualTo(500);
        assertThat(detail.getDetail()).isEqualTo("주문 계산 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요.");
        assertThat(detail.getProperties() == null || !detail.getProperties().containsKey("code")).isTrue();
        // TradingErrorEvent 경로(ManualTradingService)가 이미 보고하므로 핸들러는 재보고하지 않는다
        verifyNoInteractions(eventPublisher);
    }
```

기존 BrokerApiException(503) 테스트에 code 단언 추가:

```java
        assertThat(detail.getProperties()).containsEntry("code", ErrorCode.BROKER_UNAVAILABLE.name());
```

`ManualTradingServiceTest`:
- `execute_liveBalanceFetchFails_notifiesAdminAndThrowsManualTradingException`를 `execute_liveBalanceFetchFails_notifiesAdminAndThrowsManualTradingFailed`로 개명, 주석을 "증권사 타입이 아닌 예상 밖 예외는 500(ManualTradingFailedException)으로 승격 — 핸들러는 보고하지 않으므로 서비스가 TradingErrorEvent를 발행"으로 수정, 단언을 `.isInstanceOf(ManualTradingFailedException.class).hasCauseInstanceOf(RuntimeException.class)`로 교체.
- 이중 실행 테스트 추가:

```java
    @Test
    void execute_existingOrderToday_throwsAlreadyOrderedToday() {
        Order existing = new Order(null, null, null, LocalDate.now(), StrategyTicker.SOXL,
                OrderType.LOC, OrderTiming.AT_OPEN,
                OrderDirection.BUY, 1, new BigDecimal("22.00"),
                OrderStatus.PLANNED, null, null, null);
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(CYCLE.id()), any())).thenReturn(List.of(existing));

        assertThatThrownBy(() -> service.execute(STRATEGY.id(), REQUESTER_ID))
                .isInstanceOf(AlreadyOrderedTodayException.class);
    }
```

(이미 같은 시나리오의 테스트가 있으면 — `grep -n "이미 주문" ManualTradingServiceTest.java` — 새로 만들지 말고 그 테스트의 단언을 `AlreadyOrderedTodayException`으로 바꾼다.)

`AccountControllerTest` — 직렬화 검증(Review Focus 3). `register_*` 테스트 패턴을 따라 추가(import `jsonPath`, `Account`):

```java
    @Test
    void register_duplicate_returns409WithCodeInBody() throws Exception {
        when(accountUseCase.register(any(UUID.class), any(RegisterAccountCommand.class)))
                .thenThrow(new Account.DuplicateAccountException("74420614-01"));

        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"계좌\",\"accountNo\":\"74420614-01\"," +
                                "\"appKey\":\"k\",\"secretKey\":\"s\",\"broker\":\"KIS\"}")
                        .with(csrf()).with(authentication(userToken(UUID.fromString(USER_ID)))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_ACCOUNT"))
                .andExpect(jsonPath("$.detail").value("이미 등록된 계좌번호입니다."));
    }
```

(요청 본문이 `@Pattern` 검증에 걸리면 같은 파일의 성공 케이스 본문을 그대로 복사해 쓴다.)

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :trading-core:test --tests 'com.kista.tradingweb.*' --tests '*ManualTradingServiceTest' --tests '*AccountControllerTest' 2>&1 | grep -E "FAILED|BUILD|error:"`
Expected: 컴파일 실패(신설 예외 없음)

- [ ] **Step 3: 신설 예외 2종**

```java
package com.kista.trading.domain.model;

// 바로주문 이중 실행 거부 — 오늘 이미 PLANNED/PLACED 주문이 있는 전략. 409 + ALREADY_ORDERED_TODAY
public class AlreadyOrderedTodayException extends ManualTradingException {
    public AlreadyOrderedTodayException() {
        super("오늘 이미 주문이 등록된 전략입니다.");
    }
}
```

```java
package com.kista.trading.domain.model;

// 바로주문 중 증권사 타입이 아닌 예상 밖 실패(계획 계산·예산 배정 중 DB/데이터 결함 등) — 500, 사용자에겐 고정 문구.
// 보고는 ManualTradingService가 TradingErrorEvent로 이미 수행하므로 핸들러 전용 매핑으로 처리해 catch-all 재보고를 피한다
public class ManualTradingFailedException extends ManualTradingException {
    public ManualTradingFailedException(Throwable cause) {
        super("바로주문 처리 중 예상 밖 오류: " + cause.getMessage(), cause);
    }
}
```

- [ ] **Step 4: `TradingExceptionHandler` 수정**

import 추가: `com.kista.platform.web.ErrorCode`, `com.kista.trading.domain.model.AlreadyOrderedTodayException`, `com.kista.trading.domain.model.ManualTradingFailedException`.

```java
    // trading-core 고유 예외 매핑 — 서브클래스(AlreadyOrderedToday/ManualTradingFailed)는 resolve()의 계층 탐색이 상위 ManualTradingException보다 먼저 찾는다
    private static final String BROKER_UNAVAILABLE_DETAIL = "증권사 API 조회에 실패했습니다. 잠시 후 다시 시도해주세요."; // 503 응답 detail

    private static final Map<Class<? extends Exception>, Mapping> MAPPINGS = Map.of(
            BrokerCredentialException.class,        new Mapping(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid Broker Credentials", ErrorCode.BROKER_CREDENTIAL_INVALID),
            BrokerRateLimitException.class,          new Mapping(HttpStatus.TOO_MANY_REQUESTS,     "KIS Rate Limit", ErrorCode.BROKER_RATE_LIMITED),
            AlreadyOrderedTodayException.class,      new Mapping(HttpStatus.CONFLICT,              "Conflict", ErrorCode.ALREADY_ORDERED_TODAY),
            ManualTradingFailedException.class,      new Mapping(HttpStatus.INTERNAL_SERVER_ERROR, "Manual Trading Failed", null,
                                                             "주문 계산 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요."),
            ManualTradingException.class,            new Mapping(HttpStatus.CONFLICT,              "Conflict"),
            OrderCancelException.class,              new Mapping(HttpStatus.CONFLICT,              "Conflict", ErrorCode.ORDER_NOT_CANCELLABLE),
            PrivacyTradeConflictException.class,     new Mapping(HttpStatus.CONFLICT,              "Conflict"),
            Account.DuplicateAccountException.class, new Mapping(HttpStatus.CONFLICT,              "Conflict", ErrorCode.DUPLICATE_ACCOUNT)
    );
```

`handleTradingCoreExceptions`의 반환을 `return ProblemDetailMappings.toProblem(m, ex);`로 교체.

`handleBrokerApiException` 반환과 그 위 주석 교체:

```java
        // code=BROKER_UNAVAILABLE이 relay 판별 계약 — title("<vendorLabel> API Error")은 kista-ui가 code 판별로 이행할 때까지 함께 유지
        // detail은 사용자 노출용 고정 문구 — 원본 메시지(응답 바디·accountId 등 내부 정보)는 로그·에러 로그에만 남긴다
        return problem(HttpStatus.SERVICE_UNAVAILABLE, ex.vendorLabel() + " API Error", BROKER_UNAVAILABLE_DETAIL, ErrorCode.BROKER_UNAVAILABLE);
```

클래스 상단 주석의 "trading-core 고유 6종"은 "trading-core 고유 예외"로, `@ExceptionHandler` 목록은 그대로(서브클래스는 `ManualTradingException.class`로 이미 라우팅).

- [ ] **Step 5: 서비스·예외 메시지 수정**

`ManualTradingService` 이중 실행 분기:

```java
        if (!orderPort.findPlannedOrPlacedByCycleAndDate(currentCycle.id(), today).isEmpty())
            throw new AlreadyOrderedTodayException();
```

`queryFailure` 마지막 줄과 주석:

```java
    // 바로주문 조회 실패 응답 변환 — 증권사 타입 예외는 다른 화면과 동일하게 503/422/429로 그대로 전파
    // (503은 TradingExceptionHandler가 app_error_logs에 기록), 그 외 예외는 500(ManualTradingFailedException) + 관리자 알림
    private RuntimeException queryFailure(Exception e) {
        if (BrokerCallGuard.isBrokerTyped(e)) return (RuntimeException) e;
        // ManualTradingFailedException은 핸들러 전용 매핑이라 catch-all 보고를 타지 않으므로 여기서 직접 기록
        eventPublisher.publishEvent(new TradingErrorEvent(null, e.getMessage()));
        return new ManualTradingFailedException(e);
    }
```

`OrderCancelService` (현재 상태값은 로그로만):

```java
        if (order.status() != OrderStatus.PLACED) {
            log.info("취소 불가 상태 주문 취소 요청: orderId={}, status={}", orderId, order.status());
            throw new OrderCancelException("취소 가능한 상태가 아닙니다.");
        }
```

`BrokerCredentialException` super 메시지 → `"증권사 API 키가 유효하지 않습니다."`
`BrokerRateLimitException` super 메시지 → `"증권사 API 호출 한도를 초과했습니다. 잠시 후 다시 시도해주세요."` (Toss도 같은 예외라 "KIS" 제거)
`Account.DuplicateAccountException` → `super("이미 등록된 계좌번호입니다.");` — 생성자 파라미터 `accountNo`는 호출부(`AccountService:62,68`) 호환을 위해 유지하되 메시지에 넣지 않는다. 주석: `// accountNo는 메시지에 싣지 않는다 — detail이 사용자 응답으로 나가므로 계좌번호 원문 노출 방지`

`OrderCancelControllerTest:80`의 리터럴 `"취소 가능한 상태가 아닙니다. 현재 상태: FILLED"` → `"취소 가능한 상태가 아닙니다."`.

옛 문구를 단언하는 다른 테스트 탐색 후 갱신:

```bash
grep -rn "API 키가 유효하지 않습니다\"\|KIS API 호출 한도\|이미 등록된 계좌번호입니다:\|오늘 이미 주문이 등록된 전략입니다\"\|현재 상태: " trading-core/src/test src/test
```

- [ ] **Step 6: 통과 확인**

Run: `./gradlew :trading-core:test --tests 'com.kista.tradingweb.*' --tests '*ManualTradingServiceTest' --tests '*AccountControllerTest' --tests '*OrderCancel*' --tests 'com.kista.broker.*' 2>&1 | grep -E "FAILED|BUILD"`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: 커밋** (리뷰어 검수 후)

```bash
git add trading-core/src
git commit -m "feat(tradingweb): trading-core 에러 응답에 기계 판독 코드 부여하고 바로주문 비증권사 실패를 500으로 수정"
```

---

### Task 3: root `:api` — GlobalExceptionHandler 코드, 카피 정리, OpenAPI ErrorCode 스키마

**Files:**
- Modify: `src/main/java/com/kista/web/GlobalExceptionHandler.java`
- Create: `src/main/java/com/kista/web/ErrorCodeOpenApiCustomizer.java`
- Modify: `src/main/java/com/kista/admin/domain/model/AdminBrokerCredentialException.java`
- Modify: `src/main/java/com/kista/admin/domain/model/AdminBrokerRateLimitException.java`
- Modify: `src/main/java/com/kista/admin/domain/model/TradingPolicyUnavailableException.java`
- Modify: `src/main/java/com/kista/finance/domain/model/MonthlyClosing.java:28-32`
- Modify: `src/main/java/com/kista/user/domain/model/User.java:28-37`
- Test: `src/test/java/com/kista/web/GlobalExceptionHandlerTest.java`
- Test: `src/test/java/com/kista/web/ErrorCodeOpenApiCustomizerTest.java`

**Interfaces:**
- Consumes (Task 1): `ErrorCode`, `Mapping(status, title, code)`, `ProblemDetailMappings.problem(HttpStatus, String, String, ErrorCode)`
- Produces: openapi `components.schemas.ErrorCode` = `{type: string, enum: [ErrorCode.values() 이름]}`

- [ ] **Step 1: 실패 테스트 작성**

`GlobalExceptionHandlerTest`에 추가 (기존 테스트처럼 `new GlobalExceptionHandler(mock(ApplicationEventPublisher.class))` 직접 생성; import `ErrorCode`, `MonthlyClosing`, `User`, `AdminBrokerCredentialException`, `AdminBrokerRateLimitException`, `TradingPolicyUnavailableException`, `Instant`, `HttpStatus`):

```java
    @Test
    void monthClosed_hasCode() {
        var handler = new GlobalExceptionHandler(mock(ApplicationEventPublisher.class));
        var p = handler.handleAll(new MonthlyClosing.MonthClosedException("2026-08"));
        assertThat(p.getStatus()).isEqualTo(409);
        assertThat(p.getProperties()).containsEntry("code", ErrorCode.MONTH_CLOSED.name());
        assertThat(p.getDetail()).endsWith("다시 시도해주세요.");
    }

    @Test
    void adminBrokerExceptions_shareTradingCoreCodes() {
        var handler = new GlobalExceptionHandler(mock(ApplicationEventPublisher.class));
        assertThat(handler.handleAll(new AdminBrokerCredentialException()).getProperties())
                .containsEntry("code", ErrorCode.BROKER_CREDENTIAL_INVALID.name());
        assertThat(handler.handleAll(new AdminBrokerRateLimitException()).getProperties())
                .containsEntry("code", ErrorCode.BROKER_RATE_LIMITED.name());
    }

    @Test
    void tradingPolicyUnavailable_hasCode() {
        var handler = new GlobalExceptionHandler(mock(ApplicationEventPublisher.class));
        var p = handler.handleAll(new TradingPolicyUnavailableException(new RuntimeException("I/O error on GET http://internal:8081")));
        assertThat(p.getStatus()).isEqualTo(503);
        assertThat(p.getProperties()).containsEntry("code", ErrorCode.TRADING_CORE_UNAVAILABLE.name());
        assertThat(p.getDetail()).doesNotContain("http");
    }

    @Test
    void cooldown_keepsRetryAfterAndAddsCode() {
        var handler = new GlobalExceptionHandler(mock(ApplicationEventPublisher.class));
        Instant retryAfter = Instant.parse("2026-10-03T00:00:00Z");
        var res = handler.handleCooldown(new User.CooldownException(retryAfter));
        assertThat(res.getStatusCode().value()).isEqualTo(429);
        assertThat(res.getBody().getProperties())
                .containsEntry("code", ErrorCode.COOLDOWN_ACTIVE.name())
                .containsEntry("retryAfter", retryAfter.toString());
        assertThat(res.getBody().getDetail()).isEqualTo("재신청 대기 중입니다. 잠시 후 다시 시도해주세요.");
    }

    @Test
    void securityException_hasAccessDeniedCode() {
        var handler = new GlobalExceptionHandler(mock(ApplicationEventPublisher.class));
        assertThat(handler.handleAll(new SecurityException("접근 권한이 없습니다")).getProperties())
                .containsEntry("code", ErrorCode.ACCESS_DENIED.name());
    }
```

`ErrorCodeOpenApiCustomizerTest` (단위 — 컨텍스트 없이):

```java
package com.kista.web;

import com.kista.platform.web.ErrorCode;
import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorCodeOpenApiCustomizerTest {

    @Test
    void registersErrorCodeEnumSchema() {
        OpenAPI openApi = new OpenAPI();
        new ErrorCodeOpenApiCustomizer().customise(openApi);

        var schema = openApi.getComponents().getSchemas().get("ErrorCode");
        assertThat(schema.getType()).isEqualTo("string");
        assertThat(schema.getEnum()).containsExactlyElementsOf(
                Arrays.stream(ErrorCode.values()).map(Enum::name).toList());
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :test --tests 'com.kista.web.GlobalExceptionHandlerTest' --tests 'com.kista.web.ErrorCodeOpenApiCustomizerTest' 2>&1 | grep -E "FAILED|BUILD|error:"`
Expected: 컴파일 실패(`ErrorCodeOpenApiCustomizer` 없음)

- [ ] **Step 3: `GlobalExceptionHandler` 수정**

import `com.kista.platform.web.ErrorCode`. `MAPPINGS`의 해당 엔트리 교체:

```java
        Map.entry(AdminBrokerCredentialException.class,             new Mapping(HttpStatus.UNPROCESSABLE_ENTITY,   "Invalid Broker Credentials", ErrorCode.BROKER_CREDENTIAL_INVALID)),
        Map.entry(AdminBrokerRateLimitException.class,              new Mapping(HttpStatus.TOO_MANY_REQUESTS,      "KIS Rate Limit", ErrorCode.BROKER_RATE_LIMITED)),
        Map.entry(TradingPolicyUnavailableException.class,          new Mapping(HttpStatus.SERVICE_UNAVAILABLE,    "Trading Core Unavailable", ErrorCode.TRADING_CORE_UNAVAILABLE)),
        ...
        Map.entry(MonthlyClosing.MonthClosedException.class,           new Mapping(HttpStatus.CONFLICT,           "Conflict", ErrorCode.MONTH_CLOSED))
```

`Admin*` 두 줄 위 주석 끝에 한 줄 추가: `// 코드는 trading-core 원본(BrokerCredential/RateLimit)과 동일 — 내부 API가 status만 전달하므로 root가 같은 코드를 다시 붙인다`

`handleCooldown`의 생성 줄 교체:

```java
        ProblemDetail detail = problem(HttpStatus.TOO_MANY_REQUESTS, "Cooldown Active", ex.getMessage(), ErrorCode.COOLDOWN_ACTIVE);
```

- [ ] **Step 4: 예외 메시지 카피 정리**

- `AdminBrokerCredentialException`: `"증권사 API 키가 유효하지 않습니다."`
- `AdminBrokerRateLimitException`: `"증권사 API 호출 한도를 초과했습니다. 잠시 후 다시 시도해주세요."`
- `TradingPolicyUnavailableException`: `"매매 런타임 정책을 처리할 수 없습니다. 잠시 후 다시 시도해주세요."`
- `MonthlyClosing.MonthClosedException`: `"기록 점검이 완료된 달(" + month + ")입니다. 자산탭 기록 점검에서 완료를 해제한 뒤 다시 시도해주세요."`
- `User.CooldownException`: `super("재신청 대기 중입니다. 잠시 후 다시 시도해주세요.");` — 가능 시각은 `retryAfter` 프로퍼티·`Retry-After` 헤더가 이미 전달(ISO Instant 원문을 문장에 싣지 않는다)

옛 문구를 단언하는 테스트 탐색 후 갱신:

```bash
grep -rn "API 키가 유효하지 않습니다\"\|KIS API 호출 한도\|정책을 처리할 수 없습니다. 잠시 후 다시 시도해주세요\"\|다시 시도하세요\|가능 시각" src/test
```

- [ ] **Step 5: `ErrorCodeOpenApiCustomizer` 작성**

```java
package com.kista.web;

import com.kista.platform.web.ErrorCode;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.StringSchema;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.stereotype.Component;

import java.util.Arrays;

// ProblemDetail "code" 값 집합을 openapi.json components.schemas.ErrorCode로 노출 — kista-ui gen:types가 타입 안전 매핑에 사용.
// 엔드포인트가 참조하지 않는 독립 스키마라 springdoc이 자동 생성하지 않으므로 직접 등록한다
@Component
public class ErrorCodeOpenApiCustomizer implements OpenApiCustomizer {

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi.getComponents() == null) openApi.setComponents(new Components());
        StringSchema schema = new StringSchema();
        Arrays.stream(ErrorCode.values()).map(Enum::name).forEach(schema::addEnumItem);
        openApi.getComponents().addSchemas("ErrorCode", schema);
    }
}
```

- [ ] **Step 6: 통과 확인 + 모듈 경계**

Run: `./gradlew :test --tests 'com.kista.web.*' --tests 'com.kista.architecture.*' --tests '*MonthlyClosing*' --tests '*UserService*' 2>&1 | grep -E "FAILED|BUILD"`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: 커밋** (리뷰어 검수 후)

```bash
git add src/main src/test
git commit -m "feat(web): root 에러 응답에 기계 판독 코드 부여하고 openapi에 ErrorCode 스키마 노출"
```

---

### Task 4: 문서

**Files:**
- Modify: `docs/agents/constraints.md` ("GlobalExceptionHandler 자동 예외 처리" 절)
- Modify: `docs/agents/modules/platform.md`

- [ ] **Step 1: `constraints.md` 절에 추가**

```markdown
- **ProblemDetail 응답 계약**: `detail`은 사용자에게 그대로 보여줄 수 있는 한국어 문구다(완전한 문장은 "~습니다."+마침표). 내부 정보(URL·응답 바디·계좌번호·enum 원문·영어 프레임워크 메시지)는 detail에 싣지 말고 로그로 — 원문이 영어 내부 정보인 프레임워크 예외는 `Mapping.fixedDetail`로 고정 문구를 쓴다. 예외: 검증 실패(`MethodArgumentNotValidException`)는 "[field: message]" 형식 유지
- **기계 판독 코드 `code`**: UI가 분기하거나 문구를 달리 보여줘야 하는 예외만 `ErrorCode`(`com.kista.platform.web`)를 `Mapping`에 지정한다 — 응답 확장 프로퍼티 `code`, 없으면 키 자체가 없다. 상수 이름 변경·삭제 금지(kista-ui 계약). 추가 절차: ① `ErrorCode` 상수 ② 핸들러 매핑 테이블 `Mapping(status, title, code)` ③ 같은 예외 클래스가 여러 의미면 서브클래스 분리(`resolve()`가 계층 하위부터 탐색 — 예: `AlreadyOrderedTodayException`) ④ openapi는 `ErrorCodeOpenApiCustomizer`가 자동 반영 → kista-ui `gen:types` + 문구 매핑
- **내부 API 경유 한계**: root 내부 API 어댑터는 trading-core 응답의 status만 보고 `Admin*` 예외를 되살린다 — root 테이블이 같은 코드를 다시 붙인다. 같은 status에 서로 다른 코드가 실려 오는 경로가 생기면 `InternalApiErrorDetails`가 `code`도 읽어 전달하도록 확장한다
```

- [ ] **Step 2: `platform.md`에 `ErrorCode` 한 줄 추가** — `platform.web` 설명 근처에: `` `ErrorCode` — ProblemDetail `code` 확장 프로퍼티 값 enum(kista-ui 계약, 이름 변경·삭제 금지). `Mapping(status, title, code, fixedDetail)`이 지정 ``

- [ ] **Step 3: 커밋**

```bash
git add docs/agents/constraints.md docs/agents/modules/platform.md
git commit -m "docs: ProblemDetail detail·code 응답 계약과 코드 추가 절차 명문화"
```

---

## 최종 검증 (전체 1회)

```bash
./gradlew test 2>&1 | grep -E "FAILED|BUILD|tests completed"
```

완료 후 kista-ui 인계 요약(스펙 10절)을 사용자에게 전달.
