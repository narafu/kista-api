# 주문 접수 선점(SUBMITTING) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 증권사 접수 직전 건별 선점(PLANNED→SUBMITTING)과 확정 거절/결과 불명 분류로, 재실행 중복 접수와 접수 도중 취소의 불일치를 차단한다.

**Architecture:** broker가 "증권사가 접수하지 않았음이 확실" 플래그와 Toss `clientOrderId`를 제공하고, trading은 새 `OrderSubmitter`(선점→접수→기록 단일 경로)를 배치 접수와 재주문 IMMEDIATE가 공유한다. 잔류 SUBMITTING은 접수 경로 진입 시 `OrderSubmissionReconciler`가 Toss만 2~9분 창에서 같은 키로 재요청해 대사하고, 취소 경로는 `SubmittingOrderAwaiter`로 최대 60초 기다린 뒤 판정한다.

**Tech Stack:** Java 25, Spring Boot 4, JPA(PostgreSQL), JUnit5·Mockito·AssertJ, MockRestServiceServer(기존 어댑터 테스트 방식).

**Spec:** `docs/superpowers/specs/2026-10-16-order-submission-claim-design.md` — 각 태스크의 "왜"는 spec 절 번호로 참조한다.

## Global Constraints

- 매매 공식(`trading-formulas.md`)·Toss `sellableQuantity` 처리 변경 금지.
- 마이그레이션 없음 — `orders.status`는 VARCHAR(20), CHECK 없음. `SUBMITTING`(10자) 그대로 저장.
- 선점 시각 = `orders.updated_at`(`BaseAuditEntity.updatedAt`, `Instant`). 새 컬럼 금지.
- 상수: 취소 대기 `Duration.ofSeconds(60)`, 대기 폴링 간격 `Duration.ofSeconds(1)`, 잔류 판정 `Duration.ofMinutes(2)`, Toss 대사 상한 `Duration.ofMinutes(9)`. 설정화하지 않는다.
- `clientOrderId` = `order.id().toString()`(36자). Toss만 body에 싣고 KIS·Mock은 무시.
- 전이는 기존 `lockOrConflict` + `requireStatus` 패턴, 증권사 HTTP는 트랜잭션 밖. Virtual Thread — 대기는 `Thread.sleep`.
- 주석: `//` 인라인만, Javadoc 금지. 커밋 메시지 한글 Conventional Commit, author `narafu <narafu@kakao.com>`, 끝에 세션 attribution 2줄.
- 테스트는 `--tests`로 좁혀 실행(`./gradlew :trading-core:test --tests '...'`), 전체 스위트는 Task 6에서 1회. DB 테스트 전 `docker compose up -d postgres`. 출력은 `2>&1 | grep -E "FAILED|BUILD|Tests? "`로 거른다.
- push 금지(사용자 요청 시에만). 실계좌 주문 검증 금지 — 최종 보고에 "미검증(사유)".

## Review Focus

1. **키 만료 계좌의 매 배치** — `BrokerCredentialException`이 결과 불명으로 떨어지면 매일 SUBMITTING이 쌓인다. 기대: FAILED. → Task 1·3 테스트.
2. **그레이스풀 재기동 중 게이트 대기 인터럽트** — 송신 전 실패인데 SUBMITTING이 남으면 재개마다 수동 정정. 기대: FAILED. → Task 1(KisHttpClient 게이트 인터럽트 rejected) + Task 3(rejected → markFailed).
3. **취소가 먼저 지운 남은 주문** — 루프 도중 `deletePlanned`된 행에 HTTP를 보내면 안 된다. 기대: `markSubmitting` 경합 → `place` 미호출. → Task 3 테스트.
4. **예산 이중 배정** — SUBMITTING BUY/SELL이 합계에서 빠지면 같은 예수금·수량을 재배정. 기대: 합계 포함. → Task 2 DB 테스트.
5. **Toss 재요청 창 경계** — 선점 2분 미만(다른 스레드 접수 중)·9분 이상(키 만료로 새 주문)에서 재요청하면 중복. 기대: 둘 다 재요청 없음. → Task 3 대사 테스트.

---

### Task 1: broker — 확정 거절 플래그 + Toss clientOrderId

spec §3.3, §3.5(앞부분).

**Files:**
- Modify: `trading-core/src/main/java/com/kista/tradingcore/broker/domain/model/BrokerApiException.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/broker/domain/model/kis/KisApiException.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/broker/domain/model/toss/TossApiException.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/broker/domain/model/OrderInstruction.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/broker/adapter/out/kis/KisHttpClient.java:108-142,145-175`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/broker/adapter/out/kis/KisOrderApi.java:60-65`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/broker/adapter/out/toss/TossHttpClient.java:132-187`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/broker/adapter/out/toss/TossOrderApi.java:37-58`
- Test: `trading-core/src/test/java/com/kista/tradingcore/broker/adapter/out/kis/KisHttpClientTest.java`, `KisOrderApiTest.java`, `.../toss/TossHttpClientTest.java`, `TossOrderApiTest.java`, Create `trading-core/src/test/java/com/kista/tradingcore/broker/domain/model/BrokerApiExceptionTest.java`

**Interfaces:**
- Produces:
  - `BrokerApiException`: 새 protected 생성자 `(String vendorLabel, String message, Throwable cause, Conflict conflict, boolean rejected)`(기존 4-인자 생성자는 `rejected=false`로 위임), `public boolean isRejected()`, `public static boolean isRejection(Throwable e)` — `e instanceof BrokerApiException b && b.isRejected()` 또는 `BrokerCredentialException`·`BrokerRateLimitException`이면 true.
  - `KisApiException.rejected(String message, Throwable cause)`, `TossApiException.rejected(String message, Throwable cause)` — static 팩토리, `rejected=true`.
  - `OrderInstruction(ticker, direction, orderType, quantity, price, String clientOrderId)` — 6번째 컴포넌트 추가 + 기존 5-인자 보조 생성자(`clientOrderId=null`) 유지(기존 호출부 15곳 무수정).

- [ ] **Step 1: 실패 테스트 작성**

`BrokerApiExceptionTest`:
- `isRejection_trueFor_rejectedKisAndToss_credential_rateLimit` — `KisApiException.rejected("x", null)`, `TossApiException.rejected("x", null)`, `new BrokerCredentialException()`, `new BrokerRateLimitException()` 모두 true.
- `isRejection_falseFor_plainBrokerException_and_others` — `new KisApiException("x", null)`, `new IllegalStateException()` false.

`KisHttpClientTest`(기존 MockRestServiceServer 셋업 재사용, POST 주문 경로):
- `post_ioError_isNotRejected` — 응답 대신 `ResourceAccessException` → 던져진 `KisApiException.isRejected()==false`.
- `post_5xxWithoutKisBody_isNotRejected` — 502 + 빈 본문 → false.
- `post_errorWithKisMsgCd_isRejected` — 500 + `{"rt_cd":"1","msg_cd":"EGW00201","msg1":"..."}` → true.
- `post_4xx_isRejected` — 400 빈 본문 → true.
- `post_tokenAcquisitionFails_isRejected` — `kisAuthApi.getToken`이 `new KisApiException("tok", null)` 던짐 → 결과 `isRejected()==true`.
- `post_queueOverflow_isRejected` — 기존 대기열 초과 테스트가 있으면 그 단언에 `isRejected()==true` 추가.

`KisOrderApiTest`: `place_rtCdNonZero_throwsRejected` — `rt_cd="1"` 응답 → `isRejected()==true`.

`TossHttpClientTest`:
- `post_409RequestInProgress_isNotRejected` — 409 + `{"error":{"code":"request-in-progress"}}` → false.
- `post_4xx_isRejected` — 400 → true. `post_401Exhausted_isRejected` → true.
- `post_ioError_isNotRejected`, `post_5xx_isNotRejected` → false.

`TossOrderApiTest`: `place_sendsClientOrderId` — `new OrderInstruction(SOXL, BUY, LOC, 3, 20.00, "0199...36자")` → 요청 body JSON에 `"clientOrderId":"<그 값>"`. `place_nullClientOrderId_omitsField` → body에 키 없음.

- [ ] **Step 2: 실행 — 실패 확인**

Run: `./gradlew :trading-core:test --tests 'com.kista.tradingcore.broker.*' 2>&1 | grep -E "FAILED|BUILD"`
Expected: 컴파일 실패(`rejected`/`isRejected` 없음).

- [ ] **Step 3: 구현**

- `KisHttpClient.executeWithRetry`: `getToken`을 try로 감싸 `KisApiException`이면 `KisApiException.rejected(...)`로 재던짐(자격증명·한도 예외는 그대로 통과 — 타입 분류). `awaitAccountSlot`의 두 throw(`:160`,`:172`)와 EGW00201 백오프 인터럽트(`:133`)는 `rejected`. non-401 `HttpStatusCodeException`은 `status.is4xxClientError() || 본문에 msg_cd 존재`(기존 `ErrorBodyDecoder` + `KisErrorBody` 재사용)이면 `rejected`. 401 경로의 `recoverToken` 실패는 `rejected`, 재시도 호출에서 잡은 예외는 `HttpStatusCodeException`이면 같은 규칙, 그 외 `RestClientException`은 비거절. 판정은 private `KisApiException httpFailure(String prefix, HttpStatusCodeException e)` 헬퍼 하나로.
- `KisOrderApi.place`: `rt_cd != "0"` → `KisApiException.rejected`.
- `TossHttpClient.executeWithBackoffRetry`: `tokenFetcher.get()` 실패 중 `TossApiException`은 `rejected`로 재던짐. non-401 4xx는 `409 && error.code == "request-in-progress"`만 비거절, 나머지 `rejected`(기존 conflict 판정 유지 — 생성자에 conflict와 rejected 둘 다 전달하도록 `TossApiException`에 `(message, cause, conflict, rejected)` 생성자 추가). 401 소진(`:171`)·`recover` 실패 → `rejected`. `RestClientException`(5xx·I/O) → 비거절(현행).
- `TossOrderApi.place`: `clientOrderId != null`이면 `body.put("clientOrderId", ...)`. `orderId == null` 응답 → `TossApiException.rejected`.

- [ ] **Step 4: 실행 — 통과 확인**

Run: Step 2와 같은 명령. Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: 커밋** — `feat(broker): 주문 접수 확정 거절 판정과 Toss clientOrderId 멱등키를 추가한다`

---

### Task 2: OrderStatus.SUBMITTING + persistence 전이·집계

spec §3.1, §3.4, §3.5(선점 시각).

**Files:**
- Modify: `shared/src/main/java/com/kista/shared/kernel/OrderStatus.java` — `PLANNED`와 `PLACED` 사이에 `SUBMITTING, // 증권사 접수 진행 중(결과 미확정) — 재접수·재캡 대상 아님`
- Create: `trading-core/src/main/java/com/kista/tradingcore/trading/domain/model/SubmittingOrder.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/port/output/OrderPort.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/adapter/out/persistence/OrderPersistenceAdapter.java:35-38,57-67,146-160,229-235`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/adapter/out/persistence/OrderJpaRepository.java:59-83`
- Modify: `src/test/resources/architecture/flows.yml` `lifecycles.order`(1636-1657행)
- Test: `trading-core/src/test/java/com/kista/tradingcore/trading/adapter/out/persistence/OrderPersistenceAdapterDbTest.java`

**Interfaces:**
- Produces:
  - `record SubmittingOrder(Order order, Instant submittedAt)` — `// 접수 진행 중 주문 + 선점 시각(updated_at)`
  - `OrderPort.markSubmitting(UUID orderId)` — PLANNED에서만 SUBMITTING, 아니면 `OrderStateConflictException`.
  - `OrderPort.markPlaced(UUID, String)` — 전제 상태 **SUBMITTING**(같은 접수번호 PLACED면 멱등 성공 유지).
  - `OrderPort.markFailed(UUID)` — 전제 상태 **SUBMITTING**.
  - `OrderPort.findSubmittingByCycleAndDate(UUID strategyCycleId, LocalDate tradeDate): List<SubmittingOrder>`
  - `OrderPort.save(Order order): Order` — 단건 저장 후 id 채운 도메인 반환(재주문 IMMEDIATE용).
  - `findPlannedOrPlaced*` 결과에 SUBMITTING 포함(이름 유지, 주석 갱신). `sumPlannedBuyByAccountAndDate`는 PLANNED+SUBMITTING, `sumPlannedOrPlacedSell…`는 PLANNED+SUBMITTING+PLACED.

- [ ] **Step 1: 실패 테스트 작성** (`OrderPersistenceAdapterDbTest`, 기존 FK 선행 행 헬퍼 재사용)
- `markSubmitting_plannedToSubmitting` — 저장한 PLANNED → `markSubmitting` → `findById` status SUBMITTING.
- `markSubmitting_nonPlanned_conflicts` — PLACED 행 → `OrderStateConflictException`; 삭제된 id → 동일.
- `markPlaced_requiresSubmitting` — PLANNED 행에 `markPlaced` → 경합; SUBMITTING 행 → PLACED + externalOrderId; 같은 번호로 재호출 → 예외 없음.
- `markFailed_requiresSubmitting` — PLANNED → 경합; SUBMITTING → FAILED.
- `findSubmitting_returnsSubmittedAt` — SUBMITTING 1건·PLANNED 1건 → 결과 1건, `submittedAt` not null.
- `findPlannedOrPlaced_includesSubmitting` — PLANNED·SUBMITTING·PLACED·FAILED 각 1 → 3건.
- `sumPlannedBuy_includesSubmitting` — BUY PLANNED 10×2, SUBMITTING 5×2, PLACED 7×2 → `30.00`.
- `sumSell_includesSubmitting` — SELL 수량 PLANNED 1·SUBMITTING 2·PLACED 4·FAILED 8 → `7`.
- `deletePlanned_keepsSubmitting` — PLANNED·SUBMITTING → 삭제 건수 1, SUBMITTING 잔존.
- `save_returnsId` — `save(order)` 반환 `id() != null`, status 보존.
- 기존 `markPlaced`/`markFailed` 테스트 중 PLANNED에서 바로 전이하던 것은 선행 `markSubmitting`을 넣어 갱신.

- [ ] **Step 2: 실행 — 실패 확인**

Run: `./gradlew :trading-core:test --tests 'com.kista.tradingcore.trading.adapter.out.persistence.OrderPersistenceAdapter*' 2>&1 | grep -E "FAILED|BUILD"`
Expected: 컴파일 실패.

- [ ] **Step 3: 구현** — 위 Interfaces대로. 집계 쿼리는 문자열 리터럴(`'SUBMITTING'`) 추가. `findSubmittingByCycleAndDate`는 기존 `findByStrategyCycleIdAndTradeDateAndStatus(…, SUBMITTING)` 재사용 후 `new SubmittingOrder(toDomain(e), e.getUpdatedAt())`. flows.yml `lifecycles.order`: states에 `{id: SUBMITTING, label: 접수 중, desc: 증권사 응답 대기·결과 미확정}`(PLANNED 다음), transitions — `PLANNED→SUBMITTING`(접수 직전 선점, code `OrderSubmitter#submit`), 기존 `PLANNED→PLACED`·`PLANNED→FAILED`를 `SUBMITTING→PLACED`·`SUBMITTING→FAILED`(확정 거절만)로 교체, `SUBMITTING→PLACED` Toss 대사(code `OrderSubmissionReconciler#reconcile`) 추가. code 참조 클래스는 Task 3에서 생기므로 ArchitectureMapTest는 Task 3 Step 4에서 함께 확인.

- [ ] **Step 4: 실행 — 통과 확인** — Step 2 명령. Expected: `BUILD SUCCESSFUL`. 이어서 `./gradlew :trading-core:compileTestJava compileTestJava 2>&1 | grep -E "error|BUILD"` — root가 `OrderStatus` exhaustive switch로 깨지지 않는지(현재 없음, 확인용).

- [ ] **Step 5: 커밋** — `feat(trading): 주문 접수 중(SUBMITTING) 상태와 선점·집계 쿼리를 추가한다` (본문에 "kista-api를 kista-trading보다 먼저 또는 함께 배포" 명시)

---

### Task 3: OrderSubmitter + 배치 접수 경로 + Toss 대사 + 리포트 알림

spec §3.2, §3.3(소비 측), §3.5, §3.8.

**Files:**
- Create: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/OrderSubmitter.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/OrderSubmissionReconciler.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/TradingOrderExecutor.java:48-70,117-204`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/TradingReporter.java:56-61`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/adapter/in/schedule/TradingBatchResumer.java:72,95` (경고 문구만)
- Test: Create `OrderSubmitterTest.java`, `OrderSubmissionReconcilerTest.java`; Modify `TradingOrderExecutorTest.java`, `TradingReporterTest.java` (모두 `trading-core/src/test/java/com/kista/tradingcore/trading/application/service/`)

**Interfaces:**
- Consumes: Task 1 `BrokerApiException.isRejection`, `OrderInstruction` 6-인자; Task 2 `markSubmitting`/`markPlaced`/`markFailed`/`findSubmittingByCycleAndDate`/`SubmittingOrder`.
- Produces:
  - `OrderSubmitter`(package-private `@Component`): `Submission submit(Order order, BrokerAccountRef account, String accountLabel)`; `enum Outcome { PLACED, REJECTED, UNKNOWN, SKIPPED }`; `record Submission(Outcome outcome, String externalOrderId)` — 둘 다 `OrderSubmitter` 중첩 타입.
    - 순서: `markSubmitting`(경합 → `SKIPPED`, HTTP 없음) → `place(instruction with clientOrderId=order.id().toString())` → 예외: `isRejection` → 기존 `[접수 실패]` 알림 + `markFailed`(경합 무시) → `REJECTED`; 아니면 `[접수 결과 불명] … orderId=… 자동 재접수되지 않음 — 증권사 확인 후 런북 정정` 알림 → `UNKNOWN`. 성공: 기존 `markPlacedWithRetry`(이 클래스로 이동) → `PLACED`; 경합 → 기존 `cancelRacedOrder`(이동) → `SKIPPED`; 그 외 예외 → `[DB 불일치]` 알림(문구 끝을 "DB는 SUBMITTING이라 자동 재접수되지 않음 — 런북으로 정정") → `UNKNOWN`.
  - `OrderSubmissionReconciler`(package-private `@Component`): `void reconcile(UUID strategyCycleId, LocalDate tradeDate, TradingAccount account)`. `Clock` 빈은 없다 — `TossRedisTokenStore` 패턴대로 public 생성자는 `Clock.systemUTC()`, package-private 생성자가 `Clock`을 받는다(`@RequiredArgsConstructor` 대신 명시 생성자).
    - 각 `SubmittingOrder`: age = now − submittedAt. `< 2분` → 무시. `account.broker() == TOSS && age < 9분` → `orderPort.findById` 재확인(SUBMITTING·tradeDate 일치 아니면 무시) → 같은 instruction/`clientOrderId`로 `place` → 성공: `markPlaced` / `isRejection`: `markFailed` / 그 외: 유지. 나머지(KIS·Toss ≥9분·재요청 후 유지) → 모아서 `[접수 결과 불명]` 알림 1건(orderId 나열).
  - `TradingOrderExecutor.placeEach`: 건별 `orderSubmitter.submit` 호출, `PLACED`만 `placed`에 `p.withPlaced(id)` 추가. 인터럽트 체크(루프 상단·`REJECTED` 직후) 유지. `placeOrders`·`placeAtOpenOrders`는 `applyCap` **전에** `reconciler.reconcile(strategyCycleId, date, account)` 호출.
  - `TradingReporter.recordAndNotify` 시작: `findSubmittingByCycleAndDate(ctx.currentCycle().id(), today)` 비어있지 않으면 `TradingErrorEvent` `[접수 결과 불명 — 리포트 제외] … orderId=…`.

- [ ] **Step 1: 실패 테스트 작성**

`OrderSubmitterTest`:
- `submit_claimsBeforeSending` — InOrder: `markSubmitting` → `place` → `markPlaced(id, "EXT")`; 결과 `PLACED`, `"EXT"`.
- `submit_claimConflict_skipsWithoutHttp` — `markSubmitting` 경합 → `SKIPPED`, `verify(brokerPort, never()).place(...)`.
- `submit_passesOrderIdAsClientOrderId` — captor로 `instruction.clientOrderId()` == `order.id().toString()`.
- `submit_rejected_marksFailed` — `place`가 `KisApiException.rejected(...)` → `REJECTED`, `markFailed` 호출.
- `submit_credentialFailure_marksFailed` — `new BrokerCredentialException()` → `REJECTED`.
- `submit_ambiguous_keepsSubmitting` — `new KisApiException("io", null)` → `UNKNOWN`, `markFailed` 미호출, 이벤트 메시지에 `"[접수 결과 불명]"`.
- `submit_markPlacedFails_keepsSubmitting` — `markPlaced` 두 번 `RuntimeException` → `UNKNOWN`, 이벤트에 `"자동 재접수되지 않음"`.
- `submit_markPlacedConflict_cancelsAtBroker` — 기존 executor의 경합 테스트를 이리로 이동.

`OrderSubmissionReconcilerTest` (`Clock.fixed`):
- `tossWithinWindow_resendsWithSameKey_andMarksPlaced` — TOSS, submittedAt=now−3분 → `place` captor `clientOrderId == id`, `markPlaced(id, "EXT")`.
- `tossYoungerThan2Min_untouched` — now−1분 → `place` 미호출, 알림 없음.
- `toss9MinOrOlder_alertsWithoutResend` — now−9분 → `place` 미호출, 알림 1건.
- `kis_alertsWithoutResend` — KIS, now−5분 → `place` 미호출, 알림 1건.
- `toss_rowNoLongerSubmitting_skips` — `findById`가 PLACED → `place` 미호출.
- `toss_resendRejected_marksFailed`, `toss_resendAmbiguous_keepsAndAlerts`.

`TradingOrderExecutorTest`: 기존 테스트의 `markPlaced` 기대를 `OrderSubmitter` 경유로 갱신(executor 테스트는 실제 `OrderSubmitter`를 생성해 주입하거나 mock — 실제 인스턴스 권장해 기존 시나리오가 끝까지 검증되게). 추가:
- `placeOrders_reconcilesBeforePlacing` — InOrder: `reconciler.reconcile(cycle, date, account)` → `findPlannedByCycleAndDate`.
- `placeEach_orderDeletedMidLoop_notSent` — 2건 중 2번째 `markSubmitting` 경합 → `place` 1회만.

`TradingReporterTest`: `recordAndNotify_alertsOnLeftoverSubmitting` — `findSubmittingByCycleAndDate` 1건 → 이벤트 메시지에 `"리포트 제외"`.

- [ ] **Step 2: 실행 — 실패 확인**

Run: `./gradlew :trading-core:test --tests 'com.kista.tradingcore.trading.application.service.OrderSubmit*' --tests 'com.kista.tradingcore.trading.application.service.TradingOrderExecutorTest' --tests 'com.kista.tradingcore.trading.application.service.TradingReporterTest' 2>&1 | grep -E "FAILED|BUILD"`
Expected: 컴파일 실패.

- [ ] **Step 3: 구현** — Interfaces대로. `TradingBatchResumer` PLACING 경고 2곳 끝에 " — SUBMITTING 잔류 주문은 재접수되지 않음" 추가. `TradingServiceTest` 등 executor를 생성자로 만드는 테스트가 깨지면 새 의존을 주입해 맞춘다(`compileTestJava`로 전수 확인).

- [ ] **Step 4: 실행 — 통과 확인**

Run: Step 2 명령 + `./gradlew :trading-core:test --tests 'com.kista.tradingcore.trading.application.service.TradingServiceTest' --tests 'com.kista.tradingcore.trading.application.service.ManualTradingServiceTest' 2>&1 | grep -E "FAILED|BUILD"` + `./gradlew :test --tests '*ArchitectureMapTest' 2>&1 | grep -E "FAILED|BUILD"`
Expected: 모두 `BUILD SUCCESSFUL`.

- [ ] **Step 5: 변이 확인** — `OrderSubmitter`에서 `markSubmitting` 호출을 잠시 제거 → `submit_claimConflict_skipsWithoutHttp` 실패 확인 → 복구. `isRejection` 분기를 항상 true로 → `submit_ambiguous_keepsSubmitting` 실패 확인 → 복구.

- [ ] **Step 6: 커밋** — `feat(trading): 증권사 접수 직전 주문을 선점하고 결과 불명 주문은 재접수하지 않는다`

---

### Task 4: 취소 경로 — SUBMITTING 유한 대기 후 판정

spec §3.6.

**Files:**
- Create: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/SubmittingOrderAwaiter.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/OrderCancelService.java:62-114,116-153`
- Test: Create `SubmittingOrderAwaiterTest.java`; Modify `OrderCancelServiceTest.java`

**Interfaces:**
- Consumes: Task 2 `findSubmittingByCycleAndDate`, `findById`.
- Produces: `SubmittingOrderAwaiter`(package-private `@Component`) — public 생성자 `(OrderPort orderPort)`은 60초/1초, package-private `(OrderPort, Duration timeout, Duration pollInterval)` 테스트용.
  - `List<Order> awaitCycle(UUID strategyCycleId, LocalDate tradeDate)` — 타임아웃까지 폴링, 남은 SUBMITTING 주문 반환(없으면 즉시 빈 리스트).
  - `Order awaitOrder(UUID orderId)` — 최신 `Order` 반환(타임아웃이면 SUBMITTING 그대로). 삭제됐으면 `NoSuchElementException`.
  - 인터럽트 시 플래그 복원 후 현재 상태로 즉시 반환.

- [ ] **Step 1: 실패 테스트 작성**

`SubmittingOrderAwaiterTest`(timeout 200ms, poll 10ms):
- `awaitCycle_noSubmitting_returnsImmediately` — 첫 조회 빈 → 빈 리스트, 조회 1회.
- `awaitCycle_settlesDuringWait` — 1차 1건, 2차 빈 → 빈 리스트.
- `awaitCycle_timeout_returnsRemaining` — 항상 1건 → 1건.
- `awaitOrder_becomesPlaced` — SUBMITTING → PLACED 순 → PLACED 반환.

`OrderCancelServiceTest`:
- `cancelOpenOrders_submittingSettlesToPlaced_cancelsAtBroker` — `awaiter.awaitCycle` 빈 리스트, 이후 `findPlacedByCycleAndDate`가 그 주문(PLACED) → 증권사 `cancel` 호출, `cancelledCount` 1. **순서**: `deletePlanned` → `awaitCycle` → `findPlacedByCycleAndDate`(InOrder).
- `cancelOpenOrders_stillSubmitting_countsRetryableFailure` — `awaitCycle` 1건 → `failedCount 1`, `credentialFailedCount 0`, `OrderCancelFailedEvent` 메시지에 그 orderId와 `"접수 결과 미확정"`.
- `cancelOrder_submitting_waitsThenCancels` — `findById` SUBMITTING, `awaitOrder` PLACED → 증권사 `cancel` + `markCancelled`.
- `cancelOrder_stillSubmitting_throws` — `awaitOrder` SUBMITTING → `OrderCancelException` 메시지 `"주문 접수를 처리하는 중입니다. 잠시 후 다시 시도해 주세요."`.

- [ ] **Step 2: 실행 — 실패 확인**

Run: `./gradlew :trading-core:test --tests 'com.kista.tradingcore.trading.application.service.SubmittingOrderAwaiterTest' --tests 'com.kista.tradingcore.trading.application.service.OrderCancelServiceTest' 2>&1 | grep -E "FAILED|BUILD"`
Expected: 컴파일 실패.

- [ ] **Step 3: 구현** — `cancelOpenOrders`: 거래일 루프에서 `deletePlanned` 다음 `awaiter.awaitCycle(...)` 결과를 `stuck` 목록에 모으고 그 **뒤에** `findPlacedByCycleAndDate`. `stuck` 각 건은 `failures`에 `"orderId=…: 접수 결과 미확정(SUBMITTING)"` 추가, `failedCount++`. `cancelOrder`: PLANNED 경합 재판정 후 `status == SUBMITTING`이면 `order = awaiter.awaitOrder(orderId)`, 여전히 SUBMITTING이면 위 문구로 `OrderCancelException`.

- [ ] **Step 4: 실행 — 통과 확인** — Step 2 명령 + `--tests '*AccountOpenOrderCancellerTest' --tests '*StrategyServiceTest' --tests '*UserTradingDataEraser*'`. Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: 커밋** — `feat(trading): 취소가 접수 중 주문을 만나면 결과를 기다린 뒤 판정한다`

---

### Task 5: 재주문 IMMEDIATE — 선점 경로로 통합

spec §3.7.

**Files:**
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/ReorderService.java:40-50,135-163`
- Test: Modify `trading-core/src/test/java/com/kista/tradingcore/trading/application/service/ReorderServiceTest.java`

**Interfaces:**
- Consumes: Task 2 `OrderPort.save`, Task 3 `OrderSubmitter.submit`/`Outcome`.
- Produces: `placeOrSave` IMMEDIATE — `Order saved = orderPort.save(newOrder)`(PLANNED) → `orderSubmitter.submit(saved, account.toBrokerRef(), account.nickname())` → `PLACED`→`PlacementResult(PLACED, ext)`, `REJECTED`→`(FAILED, null)`, `UNKNOWN`→`(SUBMITTING, null)`, `SKIPPED`→`findById(saved.id())`의 현재 상태·externalOrderId. `brokerOrderCorrectionPort` 필드는 `cancelSource`가 계속 쓰므로 유지. 기존 `[DB 불일치] 재주문 …` 분기는 삭제(Submitter가 담당).

- [ ] **Step 1: 실패 테스트 작성** (`ReorderServiceTest`, `OrderSubmitter` mock)
- `immediate_savesBeforeSubmitting` — InOrder `orderPort.save` → `orderSubmitter.submit(saved, …)`; `place` 직접 호출 없음.
- `immediate_unknown_returnsSubmitting` — `UNKNOWN` → 결과 `resultingStatus == SUBMITTING`.
- `immediate_rejected_returnsFailed`, `immediate_placed_returnsPlacedWithExternalId`.
- `immediate_saveFails_noBrokerCall` — `save` 예외 → 예외 전파, `submit` 미호출.
- 기존 IMMEDIATE 테스트(접수 후 `saveAll`)는 위로 교체.

- [ ] **Step 2: 실행 — 실패 확인** — `./gradlew :trading-core:test --tests 'com.kista.tradingcore.trading.application.service.ReorderServiceTest' 2>&1 | grep -E "FAILED|BUILD"` → 컴파일 실패.
- [ ] **Step 3: 구현** — Interfaces대로.
- [ ] **Step 4: 실행 — 통과 확인** — 같은 명령 + `--tests '*Reorder*'`(internal 컨트롤러 테스트 포함). Expected: `BUILD SUCCESSFUL`.
- [ ] **Step 5: 커밋** — `refactor(trading): 재주문 즉시 접수도 선점 후 접수 경로를 쓴다`

---

### Task 6: 문서 동기화 + 최종 검증

spec §3.9, §4.

**Files:**
- Modify: `docs/agents/workflow.md:29,73-75` — "병렬 접수 인터럽트 리스크"(이제 송신 후 예외는 SUBMITTING, FAILED 아님), "미체결 주문 정리 · 재주문", "PLANNED 상태 전이 경합"(선점·대기 60초·남은 창은 진행 중 1건)
- Modify: `docs/agents/toss-api.md` — 주문 섹션에 `clientOrderId` 멱등(10분, 409/422, 목록 미노출, 대사 2~9분)
- Modify: `docs/agents/docker-infra.md` — "SUBMITTING 잔류 정정 런북" (spec §3.9 SQL 2줄 + 확인 절차, 정정 후 PLACED 체결분은 관리자 수동 체결 보정)
- Modify: `src/test/resources/architecture/flows.yml` — 흐름 `desc`/`state`: close-batch/place(선점), manual-run/cancel-all·cancel-one(대기 판정), admin-orders/reorder-place(state `PLACED·FAILED·SUBMITTING(IMMEDIATE)`)
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/TradingOrderExecutor.java:30` 클래스 주석(선점 반영)

- [ ] **Step 1: 문서 수정** (위 목록)
- [ ] **Step 2: 메모리 부족 대비 `./gradlew --stop` 후 전체 스위트 1회** — `./gradlew test integration 2>&1 | grep -E "FAILED|BUILD|tests completed"`. Expected: `BUILD SUCCESSFUL`. 실패 시 `grep -l 'failures="[1-9]' build/test-results/test/*.xml trading-core/build/test-results/test/*.xml`로 진짜 실패/환경 실패 구분.
- [ ] **Step 3: 커밋** — `docs: 주문 접수 선점·잔류 정정 런북을 문서에 반영한다`
- [ ] **Step 4: 최종 보고에 포함** — 미검증(실계좌 필요): Toss 같은 키 재요청의 실제 원결과 재반환, KIS 오류 응답 본문 `msg_cd` 형태(4xx/5xx)별 실제 분류, Toss 409 `request-in-progress` 실제 코드값. 배포 순서(kista-api ≥ kista-trading), kista-ui 후속(`entities/order/model/status-badge.ts` SUBMITTING "접수 중").
