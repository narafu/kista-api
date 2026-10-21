# Event Publication Registry 전환 설계

## 배경

`docs/agents/architecture.md`의 `event/` 전체 — 사용자 승인/거부/재신청/신규가입, 사이클 종료/신규시작, 매매리포트, 주문취소실패, 사용자탈퇴 — 는 현재 `@TransactionalEventListener` 패턴으로 구현돼 있다. 리스너가 예외를 던지면 `log.warn`만 남기고 이벤트는 영구 유실된다(재시도 경로 없음). Spring Modulith Event Publication Registry(EPR)로 전환해, 실패한 이벤트를 재기동 시 자동 재시도할 수 있게 한다.

이 작업은 `[[project_modulith_migration]]`(finance→notify→broker→trading 4모듈 이전) 완료 후 남은 "보류 2번" 항목이며, 그 마이그레이션과 독립적으로 진행한다.

## 목표

- 실패한 이벤트 발행을 DB(`event_publication` 테이블)에 기록하고, 재기동 시 미완료 항목을 자동 재시도
- 기존 알림 텍스트·발송 채널(Telegram/FCM/SSE) 동작은 그대로 유지 — 전환은 배관(plumbing)만 바꾼다

## 목표 아님(스코프 아웃)

- `@ApplicationModuleListener` 전환 — 아래 "annotation 선택" 근거로 배제
- 멱등성/중복 발송 방지 장치 — 재시도로 인한 텔레그램/FCM 중복 발송은 수용(현재의 "영구 유실"보다 나은 상태로 취급)
- 관리자 수동 재시도 API/UI — 재기동 자동 재시도만 구현
- SSE 리스너를 EPR 추적에서 명시적으로 배제하는 별도 장치 — 아래 "SSE 처리" 근거로 불필요 판단

## 핵심 설계 결정

### 1. 보안 문제 — 이벤트 payload의 평문 비밀값

`CycleCompletedEvent`/`NewCycleStartedEvent`/`BatchInterruptedEvent`/`TradingReportReadyEvent` 등 다수의 trading/user 이벤트가 `User`/`Account` 도메인 record를 통째로 담고 있다. 이 record들은 **복호화된 평문**을 필드로 가진다(`Account.appKey`/`secretKey`/`accountNo`, `User.telegramBotToken`). EPR은 이벤트 객체를 JSON 직렬화해 `event_publication` 테이블에 저장하므로, 그대로 전환하면 KIS/Toss API 키·계좌번호·텔레그램 봇 토큰이 평문으로 DB에 남는다 — `constraints.md`의 "AES-256 암호화는 persistence adapter 경계에서만" 원칙 위반.

**결정**: 이벤트 payload에서 `User`/`Account`를 `UUID userId`/`UUID accountId`로 교체하고, 리스너가 실행 시점에 기존 `UserPort`/`AccountPort`(레거시 `com.kista.application.port.output`, `Type.OPEN`)로 재조회한다. `Strategy`/`TradingReport`/`Execution`/`AccountBalance`/`BigDecimal` 등 암호화 컬럼과 무관한 타입은 그대로 유지한다.

부수 효과: 재시도 시점에 최신 상태를 재조회하므로 stale 스냅샷 문제가 없다. finance 모듈이 이미 `UserPort`/`UserSettingsPort`를 참조하는 선례가 있어(`FinanceRegistrationReminderNotifier`), notify가 read-only 포트를 추가하는 것도 기존 패턴과 정합적이다.

### 2. TradingErrorEvent의 Exception 필드

`TradingErrorEvent(User user, Exception e)`의 `Exception`은 JSON 직렬화 대상으로 부적합(스택트레이스·cause 체인, 역직렬화 취약). 소비처(`FcmAdapter`/`TelegramAdapter`/`TelegramUserNotificationAdapter`의 `notifyError`) 전부 `e.getMessage()`만 사용함을 코드로 확인했다 — `String message`로 교체해도 정보 손실 없음.

**결정**: `TradingErrorEvent(UUID userId, String message)`. 리스너가 `notifyPort.notifyError(new RuntimeException(message))` 형태로 기존 Port 시그니처(`Exception` 파라미터)에 맞춰 재포장한다 — `NotifyPort`/`UserNotificationPort` 자체는 변경하지 않는다(다른 호출부 영향 최소화).

### 3. 영속화 백엔드 — JDBC vs JPA

`spring-modulith-events-jdbc`를 채택한다. 이 모듈은 jar 안에 `schema-postgresql.sql` 원본 DDL을 포함하고 있어, Flyway 마이그레이션에 그대로 복사하면 스키마 불일치 리스크가 없다. `spring-modulith-events-jpa`는 Hibernate 매핑을 손으로 추정해 Flyway SQL을 작성해야 해서 `ddl-auto: validate` 부팅 크래시 리스크가 크다(`constraints.md` "Entity ↔ Flyway 크로스체크 필수" 항목과 동일한 종류의 위험).

구현 1번 태스크는 실제 jar를 풀어 스키마 파일을 확인하는 것 — 컬럼명·타입을 추측하지 않는다.

### 4. Annotation 선택 — `@TransactionalEventListener` 유지, `@ApplicationModuleListener` 미채택

`@ApplicationModuleListener`는 `@Async` + `@TransactionalEventListener(phase=AFTER_COMMIT)`의 조합이며 `fallbackExecution`을 지원하지 않는다. 현재 다수의 trading 발행처(`TradingService`, `MarketEventNotifier` 등)는 `@Transactional` 메서드가 아니라서, 리스너가 `fallbackExecution=true`로 "트랜잭션 있으면 커밋 후, 없으면 즉시 동기 실행"을 보장하고 있다(`CycleLifecycleNotifier`/`TradingAlertNotifier`/`TradingReportNotifier`/`OrderCancelFailureNotifier` 주석에 명시). `@ApplicationModuleListener`로 바꾸면 비-트랜잭션 발행 시 이벤트가 조용히 드롭되는 회귀가 생긴다.

**결정**: 기존 `@TransactionalEventListener`(phase/`fallbackExecution` 설정 그대로)를 유지한다. EPR 추적은 `spring-modulith-events-jdbc` 의존성이 클래스패스에 있으면 리스너 annotation과 무관하게 전역 적용되는 것으로 가정 — 구현 1번 태스크(스파이크)에서 실제로 확인한다.

### 5. SSE 리스너 처리

`SseEmitterRegistry`(승인/거절 알림)와 `TradingReportNotifier`의 체결건별 SSE 루프는 인메모리 연결(`emitters` 맵)에 의존해 재기동 시 어차피 끊긴다. EPR이 이 리스너들도 전역으로 추적한다면(annotation 무관 가정), 재기동 후 재전송은 이미 끊긴 연결에 대한 no-op이라 무해하다. 별도 배제 장치(설정·필터링)를 만들지 않는다 — 필요성 자체가 없다고 판단.

`TradingReportNotifier.onTradingReportReady`는 리포트 알림(Telegram/FCM, 재시도 가치 있음)과 SSE 루프(무해한 재시도)가 한 메서드에 섞여 있다. 재시도 시 메서드 전체가 재실행되므로 리포트 알림도 함께 재발송된다 — 이는 "중복 발송 수용" 결정 범위 안에 있어 별도 분리(메서드 split)는 하지 않는다.

## 변경 대상 이벤트 record

| 이벤트 | 변경 |
|---|---|
| `NewUserRegisteredEvent`/`UserApprovedEvent`/`UserRejectedEvent`/`UserReappliedEvent` | `User user` → `UUID userId` |
| `CycleCompletedEvent`/`CycleEndedEvent`/`NewCycleStartedEvent`/`BatchInterruptedEvent`/`MarketOpenEvent`/`MarketCloseEvent` | `User`/`Account` → `userId`/`accountId`, `Strategy`는 그대로 |
| `InsufficientBalanceEvent` | `User`/`Account` → `userId`/`accountId`, `AccountBalance`/`Ticker`/`Type`은 그대로 |
| `TradingErrorEvent` | `User user` → `UUID userId`(nullable 유지), `Exception e` → `String message` |
| `TradingReportReadyEvent` | `User`/`Account` → `userId`/`accountId`, `TradingReport`/`List<Execution>`은 그대로 |
| `OrderCancelFailedEvent`/`MarketClosedEvent`/`UserDeletedEvent` | 변경 없음(이미 ID/스칼라만 담음) |

## 영향받는 컴포넌트

**이벤트 발행처** (record 필드 변경에 맞춰 호출부 수정): `UserService`, `UserCascadeDeleter`, `AdminTradeCorrectionService`, `CyclePositionPersistor`, `CycleRotationService`, `ManualTradingService`, `MarketEventNotifier`, `OrderCancelService`, `TradingErrorReporter`, `TradingOrderExecutor`, `TradingPriceFetcher`, `TradingReporter`, `TradingService`, `VrCycleRolloverService`, `VrReconfigureService`

**이벤트 리스너** (재조회 로직 추가, `UserPort`/`AccountPort` 생성자 주입): `TelegramUserNotificationAdapter`, `CycleEndedNotifier`, `CycleLifecycleNotifier`, `TradingAlertNotifier`, `TradingReportNotifier`, `SseEmitterRegistry`, `OrderCancelFailureNotifier`(userId 관련 변경 없음, 확인만), `UserDeletedNotifier`(변경 없음, 확인만)

## 오류 처리

- 재조회 대상(`userId`/`accountId`)이 soft-delete로 사라진 경우(`UserPort.findByIdOrThrow`/`AccountPort.findByIdOrThrow`가 `NoSuchElementException`) → 리스너 예외로 publication이 계속 incomplete 상태 유지, 재기동마다 재시도되며 실패 로그만 남음. 별도 가드 추가하지 않는다(수용) — 실제로 이런 케이스는 "탈퇴 직후 마지막 이벤트가 실패해있던" 드문 경합에서만 발생하고, 무한 재시도가 데이터 정합성을 해치지 않는다.
- 재시도로 인한 중복 알림(텔레그램/FCM)은 수용 — 멱등성 장치 없음.

## DB 스키마

- `spring-modulith-events-jdbc` jar 내 `org/springframework/modulith/events/jdbc/schema-postgresql.sql` 원본을 신규 Flyway 마이그레이션으로 그대로 복사(수정 없음)
- `public` 스키마(플랫폼 공통 인프라 테이블 — `audit_logs`/`scheduler_locks`와 동급 취급, `architecture.md`의 3-스키마 분류 기준)

## 설정

- `application.yml`: `spring.modulith.events.republish-outstanding-events-on-restart: true`
- `libs.versions.toml`: `spring-modulith-events-api`, `spring-modulith-events-jdbc` (bom 버전 2.1.1 그대로, 별도 버전 지정 불필요)

## 테스트 전략

- 실제 jar 스키마 확인 스파이크 결과로 Flyway SQL 작성 → `ddl-auto: validate` 부팅 성공 확인
- 발행→리스너 예외→재기동 시뮬레이션 재시도 흐름 통합 테스트 (최소 1개, EPR이 실제로 미완료 publication을 재실행하는지 검증)
- soft-delete된 대상 재조회 실패 케이스 최소 1개
- record 필드 변경으로 컴파일 깨지는 기존 테스트 전부 수정 (`compileTestJava`로 누락 확인 — `testing.md` "record 필드 수정 시 주의" 참고)
- 전체 테스트 스위트 최종 1회

## 참고

- `[[project_modulith_migration]]` — 이 작업의 상위 계획, 보류 2번 항목
- `docs/agents/architecture.md`의 `event/` 절 — 전환 후 이 문서도 갱신 필요(레지스트리 언급 추가)
- `docs/agents/constraints.md` "AES-256 암호화 위치" — 이번 설계의 근거 원칙
