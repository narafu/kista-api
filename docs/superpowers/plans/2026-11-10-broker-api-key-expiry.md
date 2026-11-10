# 증권사 API 키 만료 알림 확장 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** KIS·TOSS·MERITZ 계좌에도 API 키 만료 30·7·0일 전 알림을 보내고, 만료일을 추정(+1년) 또는 사용자 입력으로 채운다.

**Architecture:** `Broker`에 만료일 출처 enum(`ApiKeyExpirySource`)을 두고, `AccountService`가 등록·키 교체·수정 세 경로에서 출처별로 만료일을 정한다. 스케줄러 필터(`isApiKeyExpiring()`)는 그대로 두고 대상만 넓힌다. 기존 계좌는 Flyway V19로 `created_at + 1년` 채운다.

**Tech Stack:** Java 25, Spring Boot 4, JUnit 5 + Mockito + AssertJ, Flyway(PostgreSQL)

**Spec:** `docs/superpowers/specs/2026-11-10-broker-api-key-expiry-design.md`

## Global Constraints

- 출처 매핑: UPBIT=`BROKER_API`, KIS·TOSS·MERITZ=`ESTIMATED`, MOCK·BINANCE=`NONE`.
- 추정 유효기간 1년, KST `ZonedDateTime.plusYears(1)`로 계산. 현재 시각은 `Clock` 빈, 시간대는 `TimeZones.KST`(`ZoneId.of` 금지).
- 사용자 입력 `apiKeyExpiresOn`(`LocalDate`, KST 날짜) → 저장값은 `expiresOn.atStartOfDay(TimeZones.KST).toInstant()`.
- 거부 메시지(그대로): `"이 거래소는 API 키 만료일을 직접 바꿀 수 없습니다."`, `"API 키 만료일은 오늘 이후여야 합니다."` — 둘 다 `IllegalArgumentException`(400). 오늘(KST)은 허용, 어제부터 거부.
- 업비트는 사용자 입력을 받지 않고(위 첫 메시지로 거부) API 값이 null이면 null 유지.
- ESTIMATED 알림 추가 문구(그대로): `"실제 만료일과 다르면 계좌 설정에서 고칠 수 있습니다."`
- 주석 규칙: `//` 인라인만, 커밋 메시지 한글 + Conventional Commit. 서브에이전트는 git add/commit 금지 지시를 받는다(커밋은 컨트롤러가).
- 빌드·테스트 출력은 `2>&1 | grep -E "FAILED|BUILD|ERROR|tests completed"`로 거른다.

## Review Focus

1. KST 날짜 경계: UTC 15:00 이후(KST 다음 날)에 "어제(KST)" 입력은 거부, "오늘(KST)"은 허용 — Task 2 `manualExpiryUsesKstToday` 테스트.
2. 잘못된 만료일 입력은 증권사 호출(`verifyAccount`) 전에 거부돼 외부 호출이 없어야 함 — Task 2 `invalidExpiryRejectedBeforeBrokerCall`.
3. 같은 appKey 재입력은 만료일 유지, 같은 appKey + 날짜 입력은 그 날짜로 교체 — Task 2 두 테스트.
4. 필드 없는 기존 JSON 요청(PUT `/api/accounts/{id}`)은 만료일을 바꾸지 않음 — Task 3 `updateWithoutExpiryKeepsExisting`.
5. 2월 29일 등록 추정값은 다음 해 2월 28일 — Task 2 `estimateFromLeapDay`.

---

### Task 1: Broker 만료일 출처 enum + /api/meta 노출

**Files:**
- Create: `shared/src/main/java/com/kista/shared/kernel/ApiKeyExpirySource.java`
- Modify: `shared/src/main/java/com/kista/shared/kernel/Broker.java:11-22`
- Modify: `src/main/java/com/kista/api/web/dto/BrokerMeta.java` (+ `from` 팩토리)
- Test: `shared/src/test/java/com/kista/shared/kernel/MarketVocabularyTest.java:78`, root MetaController 테스트(grep `BrokerMeta`로 기존 테스트 찾기)

**Interfaces:**
- Produces: `enum ApiKeyExpirySource { NONE, BROKER_API, ESTIMATED }`(각 상수 `//` 주석), `Broker.getApiKeyExpirySource()`(lombok @Getter), `Broker.isApiKeyExpiring()` = `apiKeyExpirySource != NONE`(기존 boolean 필드 제거, 메서드는 유지). `BrokerMeta`에 `String apiKeyExpirySource`(enum name(), `@Schema` 포함).

- [ ] **Step 1: 실패 테스트** — `MarketVocabularyTest`의 `isApiKeyExpiring` 단언을 교체:
```java
assertThat(Arrays.stream(Broker.values()).filter(Broker::isApiKeyExpiring))
        .containsExactlyInAnyOrder(Broker.UPBIT, Broker.KIS, Broker.TOSS, Broker.MERITZ);
assertThat(Broker.UPBIT.getApiKeyExpirySource()).isEqualTo(ApiKeyExpirySource.BROKER_API);
assertThat(Stream.of(Broker.KIS, Broker.TOSS, Broker.MERITZ).map(Broker::getApiKeyExpirySource))
        .containsOnly(ApiKeyExpirySource.ESTIMATED);
assertThat(Stream.of(Broker.MOCK, Broker.BINANCE).map(Broker::getApiKeyExpirySource))
        .containsOnly(ApiKeyExpirySource.NONE);
```
MetaController 테스트에 UPBIT 메타의 `apiKeyExpirySource == "BROKER_API"` 단언 추가.
- [ ] **Step 2: 실패 확인** — `./gradlew :shared:test --tests '*MarketVocabularyTest' 2>&1 | grep -E "FAILED|BUILD|ERROR"` → 컴파일 실패.
- [ ] **Step 3: 구현** — enum 생성, `Broker` 생성자 5번째 인자를 `ApiKeyExpirySource`로 교체, 주석 "API 키 만료일 출처 — 업비트는 API 조회, KIS·토스·메리츠는 추정(+1년)·사용자 입력". `BrokerMeta`에 필드 추가.
- [ ] **Step 4: 통과 확인** — `./gradlew :shared:test --tests '*MarketVocabularyTest' :test --tests '*Meta*' 2>&1 | grep -E "FAILED|BUILD|ERROR"` → BUILD SUCCESSFUL.

### Task 2: AccountService 만료일 결정 + V19 backfill

**Files:**
- Modify: `trading-core/src/main/java/com/kista/tradingcore/account/domain/model/{RegisterAccountCommand,UpdateCredentialsCommand,UpdateAccountCommand,Account}.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/account/application/service/AccountService.java` (register `:45-93`, update `:103-111`, updateCredentials `:113-139`)
- Create: `trading-core/src/main/resources/db/migration-trading/V19__accounts_api_key_expiry_estimate.sql`
- Test: `trading-core/src/test/java/com/kista/tradingcore/account/application/service/AccountServiceTest.java`, `.../account/adapter/in/schedule/ApiKeyExpirySchedulerTest.java`

**Interfaces:**
- Consumes: Task 1 `Broker.getApiKeyExpirySource()`.
- Produces:
  - 세 Command 마지막 필드에 `@Nullable LocalDate apiKeyExpiresOn // 사용자 입력 만료일(KST) — null이면 추정·유지` 추가.
  - `Account.withApiKeyExpiresAt(Instant)` 위더.
  - `AccountService`에 `Clock clock` 생성자 주입(`@RequiredArgsConstructor` 필드 추가), private 헬퍼:
    - `@Nullable Instant manualExpiry(Broker broker, @Nullable LocalDate expiresOn)` — null이면 null, ESTIMATED 아니면 첫 거부 메시지, KST 오늘 이전이면 둘째 거부 메시지, 아니면 KST 자정 Instant.
    - `Instant estimatedExpiry()` — `ZonedDateTime.now(clock).withZoneSameInstant(TimeZones.KST).plusYears(1).toInstant()`.

- [ ] **Step 1: 실패 테스트** (`AccountServiceTest`, 고정 Clock `2026-11-10T15:30:00Z` = KST 11-11 00:30 사용, 기존 테스트 생성자 호출은 `compileTestJava`로 찾아 Clock·null 필드 추가):
  - `registerKisEstimatesOneYear` — KIS 등록, 저장된 `apiKeyExpiresAt == 2027-11-10T15:30:00Z`.
  - `registerKisWithManualExpiry` — `apiKeyExpiresOn=2027-03-01` → `2027-02-28T15:00:00Z`.
  - `registerUpbitUsesBrokerValueAndRejectsManual` — verified 값 그대로, null이면 null; `apiKeyExpiresOn` 있으면 첫 거부 메시지.
  - `registerBinanceHasNoExpiry` — null.
  - `estimateFromLeapDay` — Clock `2028-02-29T03:00:00Z` → `2029-02-28T03:00:00Z`.
  - `updateCredentialsNewAppKeyReestimates` / `updateCredentialsSameAppKeyKeepsExpiry` / `updateCredentialsSameAppKeyWithManualExpiryReplaces` (KIS).
  - `manualExpiryUsesKstToday` — 입력 `2026-11-10`(KST 어제) 거부(둘째 메시지), `2026-11-11` 허용.
  - `invalidExpiryRejectedBeforeBrokerCall` — 등록·키 교체 둘 다 잘못된 날짜면 `verify(connectionTesters, never()).of(any())`.
  - `updateSetsManualExpiry` / `updateWithNullExpiryKeepsExisting` / `updateUpbitManualRejected`.
  - `ApiKeyExpirySchedulerTest`: `meritzAccountWithExpiryIsIgnored` → `meritzAccountWithExpiryIsPublished`로 뒤집고, KIS도 발행, BINANCE·MOCK은 무시로 `nonUpbitAccountIgnored` 정정.
- [ ] **Step 2: 실패 확인** — `./gradlew :trading-core:test --tests '*AccountServiceTest' --tests '*ApiKeyExpirySchedulerTest' 2>&1 | grep -E "FAILED|BUILD|ERROR"`.
- [ ] **Step 3: 구현** — 세 경로 모두 `manualExpiry`를 증권사 검증 호출 **전에** 계산. 만료일 결정:
  - 등록: `BROKER_API`→`verified.apiKeyExpiresAt()`, `ESTIMATED`→`manual ?? estimatedExpiry()`, `NONE`→null.
  - 키 교체: `BROKER_API`→verified 값, `ESTIMATED`→`manual ?? (appKey 변경 ? estimatedExpiry() : account.apiKeyExpiresAt())`, `NONE`→기존 값.
  - 수정: manual이 null이 아니면 `withApiKeyExpiresAt`.
  - `Account.java:21` 주석을 "업비트는 API 조회, KIS·토스·메리츠는 추정(+1년)·사용자 입력"으로.
- [ ] **Step 4: V19 마이그레이션**
```sql
-- KIS·토스·메리츠 기존 계좌 API 키 만료일 추정(등록일 + 1년) — 키 발급일이 아니라 추정치, 사용자가 계좌 설정에서 정정
UPDATE trading.accounts
SET api_key_expires_at = created_at + interval '1 year'
WHERE broker IN ('KIS', 'TOSS', 'MERITZ')
  AND api_key_expires_at IS NULL;

COMMENT ON COLUMN trading.accounts.api_key_expires_at IS 'API 키 만료 시각 — 업비트는 API 조회, KIS·토스·메리츠는 추정(+1년) 또는 사용자 입력, 그 외 NULL';
```
- [ ] **Step 5: 통과 확인** — Step 2 명령 + `./gradlew :trading-core:test --tests '*AccountPersistenceAdapterDbTest' 2>&1 | grep -E "FAILED|BUILD|ERROR"`(Flyway 적용 확인, postgres 기동 필요) → BUILD SUCCESSFUL.

### Task 3: Web DTO·알림 문구·문서

**Files:**
- Modify: `trading-core/src/main/java/com/kista/tradingcore/account/adapter/in/web/dto/{AccountRequest,CredentialsRequest}.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/account/adapter/in/web/AccountController.java:84`(키 교체 설명 문구)
- Modify: `trading-core/src/main/java/com/kista/tradingcore/notify/adapter/out/gateway/TradingUserNotificationAdapter.java:149-156`
- Modify: `docs/agents/modules/tradingcore/account.md:4,8,10,18-19`, `docs/agents/{kis-api,toss-api,meritz-api}.md`, `src/test/resources/architecture/flows.yml:58,740,750`
- Test: `.../account/adapter/in/web/AccountControllerTest.java`, `.../notify/adapter/out/gateway/TradingUserNotificationAdapterWordingTest.java`

**Interfaces:**
- Consumes: Task 2 Command 필드 `apiKeyExpiresOn`.
- Produces: 요청 JSON 필드 `apiKeyExpiresOn`(`"yyyy-MM-dd"`, 선택) — 등록·수정(`AccountRequest`)과 키 교체(`CredentialsRequest`).

- [ ] **Step 1: 실패 테스트**
  - `AccountControllerTest`: `registerPassesApiKeyExpiresOn`(JSON `"apiKeyExpiresOn":"2027-03-01"` → Command에 `LocalDate.of(2027,3,1)`), `updateCredentialsPassesApiKeyExpiresOn`, `updateWithoutExpiryKeepsExisting`(필드 없는 JSON → Command `apiKeyExpiresOn == null`).
  - `TradingUserNotificationAdapterWordingTest`: KIS 이벤트(7일·0일) 텍스트가 `"실제 만료일과 다르면 계좌 설정에서 고칠 수 있습니다."`를 포함, UPBIT 이벤트는 미포함.
- [ ] **Step 2: 실패 확인** — `./gradlew :trading-core:test --tests '*AccountControllerTest' --tests '*TradingUserNotificationAdapterWordingTest' 2>&1 | grep -E "FAILED|BUILD|ERROR"`.
- [ ] **Step 3: 구현** — 두 DTO에 `@Schema(description = "API 키 만료일(KST, 선택) — KIS·토스·메리츠만, 비우면 등록·교체 시점 + 1년 추정", example = "2027-11-10") LocalDate apiKeyExpiresOn` 추가, `toRegisterCommand`·`toUpdateCommand`·`toCommand`로 전달. 알림은 `e.broker().getApiKeyExpirySource() == ESTIMATED`면 두 문구 뒤에 공백 + 추가 문구. 컨트롤러 :84 설명에서 "업비트" 한정 표현을 "만료일(업비트 조회·그 외 추정 또는 입력값)"로.
- [ ] **Step 4: 문서** — account.md 18행 제목 "API 키 만료 점검"으로, 본문에 출처 enum·세 경로 규칙·V19 한 줄. kis/toss/meritz-api.md에 "키 만료일 조회 API 없음 — 등록·교체 시점 + 1년 추정, 사용자 입력 우선(`ApiKeyExpirySource.ESTIMATED`)" 한 줄. flows.yml: 58행 title "API 키 만료 알림", 740·750행에 KIS·토스·메리츠 추정 반영.
- [ ] **Step 5: 통과 확인** — Step 2 명령 + `./gradlew :test --tests '*ArchitectureMapTest' 2>&1 | grep -E "FAILED|BUILD|ERROR"`.

### 최종 (컨트롤러)

- [ ] 전체 리뷰 1회 → 수정 → `./gradlew test 2>&1 | grep -E "FAILED|BUILD|ERROR"` 1회.
- [ ] 커밋: `feat(account): KIS·토스·메리츠 API 키 만료일을 추정·입력받아 만료 알림을 보낸다` (author `narafu <narafu@kakao.com>`, `git diff --cached --stat` 확인).
- [ ] 배포 직후(V19 적용 확인 후) 운영 DB 일회성 UPDATE — 사용자가 증권사 홈페이지에서 확인한 실제 만료일(KST), 사용자 승인 완료(2026-11-10). KIS는 사용자가 UI 계좌 수정으로 직접 입력.
```sql
UPDATE trading.accounts AS a SET api_key_expires_at = v.expires_at
FROM (VALUES
    ('TOSS',   '토쓰', timestamptz '2027-06-13 00:20:00+09'),
    ('MERITZ', 'P',    timestamptz '2027-10-23 07:29:18+09'),
    ('MERITZ', 'VR',   timestamptz '2027-10-24 13:47:25+09'),
    ('MERITZ', 'TEMP', timestamptz '2027-10-24 13:48:32+09')
) AS v(broker, nickname, expires_at)
WHERE a.broker = v.broker AND a.nickname = v.nickname AND a.deleted_at IS NULL;
-- 기대: UPDATE 4 (다르면 ROLLBACK 후 확인)
```
- [ ] kista-ui 후속 요청 메모 전달(설계 문서 "별도 작업: kista-ui"). 배포 후 plan·spec archive 이동은 UI 작업까지 끝난 뒤.
