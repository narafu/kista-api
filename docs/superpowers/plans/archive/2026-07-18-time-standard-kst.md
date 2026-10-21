# 시간 기준 KST 통일 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 거래일 기준을 "DB=US 거래일, 도메인=KST(±1 변환)"에서 "전 구간 KST 단일 기준"으로 통일하고, 전수조사에서 확증된 버그 4건을 해소한다.

**Architecture:** `orders.trade_date`를 +1일 shift 마이그레이션(V27)으로 KST화하고 persistence 레이어의 `TradeDateConverter` 호출을 전면 제거한다. privacy `release_date`는 FIDA 원본(KST 발행일) 그대로 두고, 발행일→거래일 +1일을 `PrivacyDates` 도메인 규칙으로 명문화한다. KST↔US 변환은 US 기준 외부 데이터를 만나는 어댑터(KIS, 휴장일 캘린더) 내부에만 남기고 `UsTradeDates`로 리네임한다.

**Tech Stack:** Java 21, Spring Boot 3, Flyway, PostgreSQL, JUnit 5 + Mockito

**Spec:** `docs/superpowers/specs/2026-07-18-time-standard-kst-design.md`

## Global Constraints

- 커밋 메시지 한글 + Conventional Commit 접두사 (`fix:`, `feat:`, `refactor:`, `docs:`), author `narafu <narafu@kakao.com>` 확인
- 이미 운영 적용된 Flyway 파일(V1~V26) 수정 절대 금지 — 신규는 V27부터
- 주석은 `//` 인라인만 (Javadoc·블록 주석 금지), 신규 코드에 역할 주석 필수
- BOM 삽입 금지 — Java 파일 수정 후 `compileJava`로 즉시 검증
- 도메인 레이어에 Spring/JPA 어노테이션 금지 (jakarta.validation·Jackson은 기존 `FidaOrderCommand` 선례상 허용)
- 인라인 `.minusDays(1)`/`.plusDays(1)`로 시간대 변환 금지 — 명명된 헬퍼 경유 (`UsTradeDates`, `PrivacyDates`)
- 테스트 실패 진단: `grep -oP 'failures="\K[^"]+' build/test-results/test/TEST-*.xml | grep -v ':0'`
- 각 Task 완료 시 즉시 커밋

## 실행 전 확인

- [ ] `docker compose up -d postgres` — persistence 테스트(DataJpaTestBase)는 로컬 PostgreSQL 필수
- [ ] `git config user.name` = `narafu`, `git config user.email` = `narafu@kakao.com`

---

### Task 1: orders 경로 KST 통일 (V27 마이그레이션 + OrderPersistenceAdapter 변환 제거)

DB `orders.trade_date`를 +1일 shift해 KST 거래일로 바꾸고, adapter의 모든 `TradeDateConverter` 호출을 제거한다. 코드와 마이그레이션은 한 커밋(원자적 배포 단위)이어야 한다. 버그 ③(`findTradeDatesByStrategyId` 미변환)이 자연 해소된다.

**Files:**
- Create: `src/main/resources/db/migration/V27__shift_orders_trade_date_to_kst.sql`
- Modify: `src/main/java/com/kista/adapter/out/persistence/trade/OrderPersistenceAdapter.java`
- Modify: `src/main/java/com/kista/adapter/out/persistence/trade/OrderEntity.java:35` (주석)
- Test: `src/test/java/com/kista/adapter/out/persistence/trade/OrderPersistenceAdapterDbTest.java`, `OrderPersistenceAdapterTest.java`

**Interfaces:**
- Consumes: 없음 (첫 태스크)
- Produces: `OrderPort` 전 메서드가 KST 일자를 받고 KST 일자를 반환 (시그니처 불변, 의미만 통일). `orders.trade_date` = KST 거래일.

- [ ] **Step 1: 실패하는 테스트로 전환 — 기존 테스트의 ±1 기대 제거**

`OrderPersistenceAdapterDbTest.java`에서 (Flyway 마이그레이션 skill 참고: 이 태스크의 SQL 작성 시 `flyway-migration` 스킬 invoke):

```java
// 변경 전 (:129)
LocalDate domainTradeDate = TradeDateConverter.toKst(databaseTradeDate);
// 변경 후 — DB와 도메인이 같은 KST 일자
LocalDate domainTradeDate = databaseTradeDate;
```

`OrderPersistenceAdapterTest.java`에서 (:56, :150 및 파일 내 전체):

```java
// 변경 전
LocalDate utcDate = TradeDateConverter.toUtc(TODAY);
// 변경 후 — repository stub/verify가 TODAY를 그대로 기대
LocalDate utcDate = TODAY; // 변수 자체를 제거하고 사용처를 TODAY로 치환해도 됨
```

기계적 치환 규칙 (두 테스트 파일 전체에 적용): `TradeDateConverter.toUtc(X)` → `X`, `TradeDateConverter.toKst(X)` → `X`, 이후 `import com.kista.common.TradeDateConverter;` 제거.

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests 'com.kista.adapter.out.persistence.trade.*'`
Expected: FAIL — adapter가 아직 ±1 변환 중이므로 날짜 불일치 단언 실패

- [ ] **Step 3: V27 마이그레이션 작성**

`src/main/resources/db/migration/V27__shift_orders_trade_date_to_kst.sql`:

```sql
-- orders.trade_date 기준 변경: UTC(=US 거래일) → KST 거래일 (+1일 균일 shift)
-- 소프트 삭제 행 포함 전체 갱신 — 모든 행이 단일 기준을 유지해야 함
UPDATE orders SET trade_date = trade_date + 1;

COMMENT ON COLUMN orders.trade_date IS 'KST 거래일 — 매매가 실행·정산되는 KST 아침이 속한 날';
```

- [ ] **Step 4: OrderPersistenceAdapter 변환 제거**

`OrderPersistenceAdapter.java` 전체에서 기계적 치환: `TradeDateConverter.toUtc(x)` → `x`, `TradeDateConverter.toKst(x)` → `x`. `import com.kista.common.TradeDateConverter;` 제거. 관련 주석 갱신:

```java
// :35 등 각 조회 메서드 — "KST → UTC 변환" 류 주석 삭제 (변환 없음)
// :97 주석 교체
// 같은 계좌·거래일(KST)·ticker의 미체결 SELL 예약 수량을 합산한다
// :185 toEntity
e.setTradeDate(o.tradeDate()); // KST 거래일 그대로 저장 (변환 없음)
// :202 toDomain
e.getId(), e.getAccountId(), e.getStrategyCycleId(), e.getTradeDate(), e.getTicker(), // KST 거래일 그대로 복원
```

`OrderEntity.java:35` 주석 교체:

```java
private LocalDate tradeDate; // KST 거래일 — DB·도메인 동일 기준 (V27에서 US→KST shift 완료)
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew test --tests 'com.kista.adapter.out.persistence.trade.*'`
Expected: PASS (DbTest는 로컬 postgres에 V27 자동 적용 후 실행됨)

- [ ] **Step 6: 전체 컴파일 + trade 관련 서비스 테스트**

Run: `./gradlew compileJava compileTestJava && ./gradlew test --tests 'com.kista.application.service.trading.*' --tests 'com.kista.application.service.admin.*'`
Expected: 전체 PASS (privacy 경로의 변환은 Task 1에서 건드리지 않으므로 `AdminQueryServiceTest`도 이 시점엔 기존 기대값으로 통과)

- [ ] **Step 7: 커밋**

```bash
git add src/main/resources/db/migration/V27__shift_orders_trade_date_to_kst.sql \
  src/main/java/com/kista/adapter/out/persistence/trade/ \
  src/test/java/com/kista/adapter/out/persistence/trade/
git commit -m "refactor: orders.trade_date KST 통일 — V27 shift + persistence 변환 제거"
```

---

### Task 2: privacy 경로 — PrivacyDates 도메인 규칙 신설 + 왕복 변환 제거 + 시드 미리보기 버그 수정

`release_date`는 FIDA 원본(KST 발행일)이며 거래일이 아님을 코드로 명문화한다. 발행일→거래일 +1일은 `PrivacyDates` 업무 규칙 헬퍼로 대체한다. 버그 ①(`findSeedPreviewBase` 하루 누락)을 수정한다. DB 마이그레이션 없음 (저장값 변화 없음 — `toKst`→`toUtc` 왕복이 원래 상쇄였음).

**Files:**
- Create: `src/main/java/com/kista/domain/model/privacy/PrivacyDates.java`
- Modify: `src/main/java/com/kista/application/service/privacy/PrivacyService.java`
- Modify: `src/main/java/com/kista/adapter/out/persistence/privacy/PrivacyTradePersistenceAdapter.java`
- Modify: `src/main/java/com/kista/adapter/out/persistence/privacy/PrivacyTradeBaseEntity.java` (필드명 `tradeDate`→`releaseDate` + 주석)
- Modify: `src/main/java/com/kista/adapter/out/persistence/privacy/PrivacyTradeBaseJpaRepository.java` (메서드명 정합)
- Modify: `src/main/java/com/kista/application/service/admin/AdminQueryService.java:116-122`
- Test: `src/test/java/com/kista/application/service/privacy/PrivacyServiceTest.java`, `src/test/java/com/kista/application/service/admin/AdminQueryServiceTest.java`

**Interfaces:**
- Consumes: 없음 (Task 1과 독립)
- Produces: `PrivacyDates.releaseDateFor(LocalDate kstTradeDate): LocalDate`, `PrivacyDates.tradeDateOf(LocalDate releaseDate): LocalDate`. `PrivacyTradePort` 시그니처 불변 — `findTodayTrade(today)`/`findSeedPreviewBase()`가 반환하는 도메인 `tradeDate`는 여전히 KST 거래일(=발행일+1).

- [ ] **Step 1: PrivacyDates 단위 테스트 작성**

Create `src/test/java/com/kista/domain/model/privacy/PrivacyDatesTest.java`:

```java
package com.kista.domain.model.privacy;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class PrivacyDatesTest {

    @Test
    void 거래일의_적용_기준표_발행일은_전날() {
        assertThat(PrivacyDates.releaseDateFor(LocalDate.of(2026, 7, 18)))
                .isEqualTo(LocalDate.of(2026, 7, 17));
    }

    @Test
    void 발행일의_적용_거래일은_다음날() {
        assertThat(PrivacyDates.tradeDateOf(LocalDate.of(2026, 7, 17)))
                .isEqualTo(LocalDate.of(2026, 7, 18));
    }

    @Test
    void 두_변환은_역함수() {
        LocalDate date = LocalDate.of(2026, 7, 18);
        assertThat(PrivacyDates.tradeDateOf(PrivacyDates.releaseDateFor(date))).isEqualTo(date);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests 'com.kista.domain.model.privacy.PrivacyDatesTest'`
Expected: 컴파일 실패 — `PrivacyDates` 미존재

- [ ] **Step 3: PrivacyDates 구현**

Create `src/main/java/com/kista/domain/model/privacy/PrivacyDates.java`:

```java
package com.kista.domain.model.privacy;

import java.time.LocalDate;

// 기준 매매표 발행일(release_date, KST 원본) ↔ 적용 거래일(KST) 업무 규칙.
// 기준표는 발행 다음날 KST 거래일 세션에 적용된다 — 시간대 변환이 아니라 도메인 규칙이므로
// UsTradeDates(구 TradeDateConverter)와 혼용 금지.
public final class PrivacyDates {

    // 거래일 → 그 세션에 적용되는 기준표 발행일 (전날)
    public static LocalDate releaseDateFor(LocalDate kstTradeDate) {
        return kstTradeDate.minusDays(1);
    }

    // 발행일 → 적용 거래일 (다음날)
    public static LocalDate tradeDateOf(LocalDate releaseDate) {
        return releaseDate.plusDays(1);
    }

    private PrivacyDates() {}
}
```

Run: `./gradlew test --tests 'com.kista.domain.model.privacy.PrivacyDatesTest'` → PASS

- [ ] **Step 4: PrivacyService 왕복 제거 테스트 수정**

`PrivacyServiceTest.java:63` — 현재 `argThat(r -> r.tradeDate().equals(TradeDateConverter.toKst(utcDate)))` 형태를 원본 그대로 전달 기대로 교체:

```java
// FIDA 수신 날짜(KST 발행일)가 변환 없이 그대로 port에 전달된다
argThat(r -> r.tradeDate().equals(receivedDate)));
```

(`utcDate` 변수명은 `receivedDate`로 리네임, `TradeDateConverter` import 제거. 필드명 `tradeDate()`는 Task 3에서 `releaseDate()`로 바뀌므로 이 시점엔 유지.)

Run: `./gradlew test --tests 'com.kista.application.service.privacy.PrivacyServiceTest'` → FAIL (아직 서비스가 +1 변환 중)

- [ ] **Step 5: PrivacyService 왕복 제거**

`PrivacyService.executeFidaOrder()` 전체 교체:

```java
@Override
public PrivacyTradeSaveResult executeFidaOrder(FidaOrderCommand command) {
    // FIDA 수신값은 KST 발행일 원본 — 변환 없이 그대로 검증·저장 (release_date는 거래일이 아님)
    PrivacyTradeValidationReport report = validationService.inspect(command);
    if (report.hasBlockingIssues()) {
        log.error("[FIDA] 기준 매매표 저장 차단: {}", report.summary());
        IllegalArgumentException exception = new IllegalArgumentException("[FIDA] " + report.summary());
        notifyPort.notifyError(exception);
        throw exception;
    }
    if (report.hasIssues()) {
        notifyPort.notifyInfo("[PRIVACY] 기준 매매표 경고: " + report.summary());
    }
    return privacyTradePort.saveBaseWithOrders(command);
}
```

`import com.kista.common.TradeDateConverter;` 제거.

- [ ] **Step 6: Entity·Repository 명명 정합**

`PrivacyTradeBaseEntity.java` 필드 리네임:

```java
@Column(name = "release_date", nullable = false)
private LocalDate releaseDate;            // FIDA 발행일 원본 (KST) — 거래일 아님, 변환 금지
```

`PrivacyTradeBaseJpaRepository.java` 전체 교체:

```java
interface PrivacyTradeBaseJpaRepository extends JpaRepository<PrivacyTradeBaseEntity, UUID> {
    // 중복 체크용 — 정확한 발행일 일치 (>= 쓰면 미래 레코드를 잡아 false 409 발생)
    Optional<PrivacyTradeBaseEntity> findByReleaseDateAndTicker(LocalDate releaseDate, Ticker ticker);

    Optional<PrivacyTradeBaseEntity> findFirstByReleaseDateGreaterThanEqualAndTickerOrderByReleaseDateAsc(LocalDate releaseDate, Ticker ticker);

    @EntityGraph(attributePaths = "orders")
    Optional<PrivacyTradeBaseEntity> findFirstWithOrdersByReleaseDateGreaterThanEqualAndTickerOrderByReleaseDateAsc(LocalDate releaseDate, Ticker ticker);

    // N+1 방지: 주문(orders)을 join fetch, DISTINCT로 기준 매매표 중복 제거, 발행일 내림차순
    @Query("SELECT DISTINCT b FROM PrivacyTradeBaseEntity b LEFT JOIN FETCH b.orders "
            + "WHERE b.releaseDate >= :fromReleaseDate ORDER BY b.releaseDate DESC")
    List<PrivacyTradeBaseEntity> findBasesFromReleaseDate(LocalDate fromReleaseDate);
}
```

- [ ] **Step 7: PrivacyTradePersistenceAdapter 변환 제거 + 버그 ① 수정**

변경 지점 (전부 이 파일 안):

```java
// import: TradeDateConverter 제거, PrivacyDates 추가
import com.kista.domain.model.privacy.PrivacyDates;

// saveBaseWithOrders(:61)
base.setReleaseDate(command.tradeDate()); // FIDA 발행일 원본 그대로 (Task 3에서 command.releaseDate()로 리네임)

// getByTradeDateAndTicker(:83-85) → 리네임 + 직접 비교
private Optional<PrivacyTradeBaseEntity> getByReleaseDateAndTicker(LocalDate releaseDate, Ticker ticker) {
    return baseRepository.findByReleaseDateAndTicker(releaseDate, ticker); // 발행일 정확 일치
}

// findSeedPreviewBase(:117-125) — 버그 수정: 오늘 거래일에 적용되는 발행일(전날)부터 조회
@Override
public Optional<PrivacyCurrentBase> findSeedPreviewBase() {
    // 미리보기는 KST 오늘 거래일 이후에 적용되는 기준표만 사용 — 발행일은 거래일 전날
    LocalDate todayKst = LocalDate.now(TimeZones.KST);
    return baseRepository
            .findFirstByReleaseDateGreaterThanEqualAndTickerOrderByReleaseDateAsc(
                    PrivacyDates.releaseDateFor(todayKst), Ticker.SOXL)
            .map(e -> new PrivacyCurrentBase(e.getTicker(), e.getCurrentCycleStart(),
                    PrivacyDates.tradeDateOf(e.getReleaseDate()))); // 발행일 → 적용 거래일
}

// findTodayTrade(:129-143) — 동일 규칙 (동작 결과는 기존과 동일, 의미만 교정)
return baseRepository.findFirstWithOrdersByReleaseDateGreaterThanEqualAndTickerOrderByReleaseDateAsc(
                PrivacyDates.releaseDateFor(today), Ticker.SOXL)
        .map(entity -> {
            LocalDate kstTradeDate = PrivacyDates.tradeDateOf(entity.getReleaseDate()); // 발행일 → 적용 거래일
            ...(이하 기존 매핑 유지)...

// findBasesFromTradeDate(:153) → 파라미터 의미 변경 (발행일 기준)
@Override
@Transactional(readOnly = true)
public List<PrivacyTradeBaseView> findBasesFromTradeDate(LocalDate fromReleaseDate) {
    return baseRepository.findBasesFromReleaseDate(fromReleaseDate).stream()
            .map(this::toView)
            .toList();
}

// toView(:160-175) — e.getTradeDate() → e.getReleaseDate(), 주석 갱신
// 엔티티 → 조회 뷰 (관리자 표시용 — 발행일 원본 그대로, 이제 예외가 아니라 규칙)
```

- [ ] **Step 8: AdminQueryService.listPrivacyBases 수정**

```java
@Override
public List<PrivacyTradeBaseView> listPrivacyBases(Integer days) {
    // days==null → 전체(EPOCH부터). 그 외 KST 기준 최근 N일 발행분 (release_date는 KST 발행일 원본)
    LocalDate fromReleaseDate = days == null
            ? LocalDate.EPOCH
            : LocalDate.now(TimeZones.KST).minusDays(days);
    return privacyTradePort.findBasesFromTradeDate(fromReleaseDate);
}
```

`AdminQueryServiceTest.java:54`의 기대값 교체: `LocalDate expected = LocalDate.now().minusDays(30);` (import 정리). 주의: 기존 대비 필터 경계가 1일 이동(원래 `toUtc`로 -31일이던 것이 -30일) — 의도된 의미 교정.

- [ ] **Step 9: 테스트 실행**

Run: `./gradlew test --tests 'com.kista.application.service.privacy.*' --tests 'com.kista.application.service.admin.AdminQueryServiceTest' --tests 'com.kista.domain.model.privacy.*'`
Expected: PASS

- [ ] **Step 10: 커밋**

```bash
git add src/main/java/com/kista/domain/model/privacy/PrivacyDates.java \
  src/main/java/com/kista/application/service/privacy/ \
  src/main/java/com/kista/adapter/out/persistence/privacy/ \
  src/main/java/com/kista/application/service/admin/AdminQueryService.java \
  src/test/java/com/kista/domain/model/privacy/ \
  src/test/java/com/kista/application/service/privacy/ \
  src/test/java/com/kista/application/service/admin/AdminQueryServiceTest.java
git commit -m "fix: privacy 발행일 도메인 규칙(PrivacyDates) 명문화 + 시드 미리보기 하루 누락 수정"
```

---

### Task 3: FidaOrderCommand.tradeDate → releaseDate 리네임

거래일이 아님을 타입 수준에서 명시한다. 기존 FIDA 송신측 하위호환을 위해 `@JsonAlias("tradeDate")` 유지.

**Files:**
- Modify: `src/main/java/com/kista/domain/model/privacy/FidaOrderCommand.java:16`
- Modify: `src/main/java/com/kista/adapter/in/web/dto/FidaOrderResponse.java:17,54`
- Modify: `src/main/java/com/kista/adapter/out/persistence/privacy/PrivacyTradePersistenceAdapter.java` (command.tradeDate() 호출부)
- Test: `src/test/java/com/kista/adapter/in/web/FidaOrderControllerTest.java`, `src/test/java/com/kista/application/service/privacy/PrivacyServiceTest.java`

**Interfaces:**
- Consumes: Task 2의 adapter 구조
- Produces: `FidaOrderCommand.releaseDate(): LocalDate` (JSON 수신 키 `releaseDate`, 별칭 `tradeDate` 허용). `FidaOrderResponse.releaseDate` 필드.

- [ ] **Step 1: 컨트롤러 테스트에 별칭 수신 케이스 추가**

`FidaOrderControllerTest.java`에 추가 (기존 테스트 패턴의 `@Import`/`X-Internal-Token` 헤더 셋업 재사용):

```java
@Test
void 구버전_tradeDate_키로도_수신된다() throws Exception {
    // @JsonAlias("tradeDate") 하위호환 — FIDA 송신측 키 전환 전까지 유지
    mockMvc.perform(post("/api/internal/fida-orders")
                    .header("X-Internal-Token", "test-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(기존_정상_요청_JSON.replace("\"releaseDate\"", "\"tradeDate\"")))
            .andExpect(status().isCreated());
}
```

(기존 정상 요청 JSON 문자열 상수는 파일 내 기존 케이스의 것을 재사용하고, 그 상수의 키를 `releaseDate`로 먼저 바꾼다.)

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests 'com.kista.adapter.in.web.FidaOrderControllerTest'`
Expected: FAIL — `releaseDate` 키 미인식 (필드가 아직 `tradeDate`)

- [ ] **Step 3: 리네임 적용**

`FidaOrderCommand.java`:

```java
import com.fasterxml.jackson.annotation.JsonAlias;

public record FidaOrderCommand(
        @NotNull @JsonAlias("tradeDate") LocalDate releaseDate, // FIDA 발행일 원본 (KST) — 거래일 아님
        ...(나머지 필드 동일)...
```

컴파일 오류를 따라 사용처 일괄 수정 (전부 `.tradeDate()` → `.releaseDate()`):
- `PrivacyTradePersistenceAdapter`: `saveBaseWithOrders` 내 `command.releaseDate()` (저장·중복체크·예외 메시지 — 예외 메시지도 `releaseDate=`로 변경)
- `FidaOrderResponse.java:17` `LocalDate releaseDate,` / `:54` `command.releaseDate(),`
- `PrivacyServiceTest` argThat: `r.releaseDate().equals(receivedDate)`
- `PrivacyTradeValidationService`는 tradeDate 미사용 — 영향 없음 (확인 완료)

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew compileTestJava && ./gradlew test --tests 'com.kista.adapter.in.web.FidaOrderControllerTest' --tests 'com.kista.application.service.privacy.*'`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add -A src/main src/test
git commit -m "refactor: FIDA 발행일 필드 releaseDate 리네임 (@JsonAlias 하위호환)"
```

---

### Task 4: 통계 경계 KST 자정 통일 (버그 ② 수정)

`createdAt`(Instant) 범위 필터의 from/to 의미를 "KST 달력일"로 확정 — KST day D의 04:30 배치 스냅샷은 D 범위에 속한다.

**Files:**
- Modify: `src/main/java/com/kista/application/service/account/AccountStatisticsService.java:194-201`
- Modify: `src/main/java/com/kista/application/service/stats/StatsService.java:70`
- Test: `src/test/java/com/kista/application/service/stats/StatsServiceTest.java`

**Interfaces:**
- Consumes: 없음 (독립)
- Produces: 시그니처 불변 — `resolveFrom/resolveTo` 및 equity curve 경계가 KST 자정 기준 Instant 반환

- [ ] **Step 1: 경계 검증 테스트 추가**

`StatsServiceTest.java`에 추가 (기존 `@Mock cyclePositionPort` 등 필드·셋업 재사용):

```java
@Test
void equityCurve_조회_경계는_KST_자정() {
    // to=2026-07-18 → toInstant = 2026-07-19T00:00 KST = 2026-07-18T15:00:00Z
    ArgumentCaptor<Instant> toCaptor = ArgumentCaptor.forClass(Instant.class);
    when(cyclePositionPort.findByUserAndRange(any(), any(), any())).thenReturn(List.of());
    // loadCycles 의존 stub은 파일 내 기존 equity curve 테스트 셋업을 재사용

    service.getEquityCurve(USER_ID, null, LocalDate.of(2026, 7, 18));

    verify(cyclePositionPort).findByUserAndRange(eq(USER_ID), any(), toCaptor.capture());
    assertThat(toCaptor.getValue())
            .isEqualTo(LocalDate.of(2026, 7, 19).atStartOfDay(TimeZones.KST).toInstant());
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests 'com.kista.application.service.stats.StatsServiceTest'`
Expected: FAIL — 현재 UTC 자정(2026-07-19T00:00:00Z) 반환

- [ ] **Step 3: KST 자정으로 교체**

`StatsService.java:70`:

```java
Instant toInstant = effectiveTo.plusDays(1).atStartOfDay(TimeZones.KST).toInstant(); // KST 자정 경계 — 04:30 배치 스냅샷이 해당 KST 일자에 속함
```

`AccountStatisticsService.java:194-201`:

```java
private Instant resolveFrom(LocalDate from) {
    return from != null ? from.atStartOfDay(TimeZones.KST).toInstant() : Instant.EPOCH; // KST 자정 경계
}

private Instant resolveTo(LocalDate to) {
    var resolved = to != null ? to : LocalDate.now(TimeZones.KST);
    return resolved.plusDays(1).atStartOfDay(TimeZones.KST).toInstant(); // KST 자정 경계 (to 당일 포함)
}
```

`atStartOfDay(ZoneId)`는 `ZonedDateTime`을 반환하므로 `.toInstant()` 그대로 성립. 두 파일에서 미사용이 된 `java.time.ZoneOffset` import 제거.

- [ ] **Step 4: 통과 확인 + 커밋**

Run: `./gradlew test --tests 'com.kista.application.service.stats.*' --tests 'com.kista.application.service.account.*'`
Expected: PASS

```bash
git add src/main/java/com/kista/application/service/account/AccountStatisticsService.java \
  src/main/java/com/kista/application/service/stats/StatsService.java \
  src/test/java/com/kista/application/service/stats/StatsServiceTest.java
git commit -m "fix: 통계 from/to 경계를 KST 자정으로 통일 — 04:30 배치 스냅샷 오분류 해소"
```

---

### Task 5: 거래일 경계 04:30 정렬 (버그 ④ 수정)

`DstInfo.nextTradeDate()`의 날짜 경계(04:00)를 마감 배치 cron 발화 시각(04:30)에 정렬 — 04:00~04:30 창에서 미리보기/수동실행이 임박한 배치와 다른 거래일을 가리키는 불일치 제거. 마감 배치(`executeBatch`)의 `LocalDate.now(KST)` 산출은 변경하지 않는다.

**Files:**
- Modify: `src/main/java/com/kista/domain/model/strategy/DstInfo.java:118-119`
- Test: `src/test/java/com/kista/domain/model/strategy/DstInfoTest.java` (없으면 생성)

**Interfaces:**
- Consumes: 없음 (독립)
- Produces: `DstInfo.nextTradeDate()` 경계만 04:30으로 변경 — 소비처(`TradingPreviewService:48`, `ManualTradingService:55`, `OrderCancelService:46`, `TradingService.placeOpenOrders:328`) 시그니처·로직 불변

- [ ] **Step 1: 경계 테스트 작성**

`nextTradeDate()`는 `LocalTime.now(KST)` 직접 호출이라 시각 주입이 불가 — 기존 파일 패턴(시각 주입식 package-private 오버로드)에 맞춰 오버로드를 추가하고 테스트한다. `DstInfoTest.java`에 추가:

```java
@Test
void 거래일_경계는_0430_직전까지_당일() {
    assertThat(DstInfo.nextTradeDateAt(LocalDate.of(2026, 7, 18), LocalTime.of(4, 29)))
            .isEqualTo(LocalDate.of(2026, 7, 18));
}

@Test
void 거래일_경계_0430부터_익일() {
    // 마감 배치 cron 발화(04:30 KST)와 동일 임계값 — 04:00~04:30 불일치 창 제거
    assertThat(DstInfo.nextTradeDateAt(LocalDate.of(2026, 7, 18), LocalTime.of(4, 30)))
            .isEqualTo(LocalDate.of(2026, 7, 19));
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests 'com.kista.domain.model.strategy.DstInfoTest'`
Expected: 컴파일 실패 — `nextTradeDateAt` 미존재

- [ ] **Step 3: 구현**

`DstInfo.java:118-126` 교체:

```java
// 매매일 경계 기준 시각 — 마감 배치 cron 발화(TradingCloseScheduler 04:30 KST)와 동일 임계값.
// 04:30 이후면 당일 배치는 이미 계획 완료 → 다음 세션(익일)이 preview/수동 실행 대상
private static final LocalTime SCHEDULER_RUN_TIME = LocalTime.of(4, 30);

// preview/수동 실행에서 "오늘 매매 기준 날짜" 산출 — 스케쥴러와 동일 로직 SSOT
public static LocalDate nextTradeDate() {
    return nextTradeDateAt(LocalDate.now(KST), LocalTime.now(KST));
}

// 시각 주입식 판단 — 테스트 및 nextTradeDate 공용
static LocalDate nextTradeDateAt(LocalDate today, LocalTime now) {
    return now.isBefore(SCHEDULER_RUN_TIME) ? today : today.plusDays(1);
}
```

- [ ] **Step 4: 통과 확인 + 커밋**

Run: `./gradlew test --tests 'com.kista.domain.model.strategy.*' --tests 'com.kista.application.service.trading.*'`
Expected: PASS

```bash
git add src/main/java/com/kista/domain/model/strategy/DstInfo.java src/test/java/com/kista/domain/model/strategy/
git commit -m "fix: 거래일 경계를 마감 배치 발화 시각 04:30에 정렬 — 04:00~04:30 불일치 창 제거"
```

---

### Task 6: tradeDateKst → tradeDate 리네임 (KST가 기본 규칙이 된 후 잉여 접미사 제거)

**Files:**
- Modify: `src/main/java/com/kista/domain/model/admin/AdminReorderCommand.java:15`
- Modify: `src/main/java/com/kista/domain/model/admin/AdminManualTradeCorrectionCommand.java:18`
- Modify: `src/main/java/com/kista/adapter/in/web/dto/AdminReorderRequest.java:25,42`
- Modify: `src/main/java/com/kista/adapter/in/web/dto/AdminManualTradeCorrectionRequest.java:37,51`
- Modify: `src/main/java/com/kista/application/service/admin/AdminReorderService.java:79`, `AdminTradeCorrectionService.java:81,108,116,136`
- Test: `src/test/java/com/kista/adapter/in/web/AdminTradeControllerTest.java`

**Interfaces:**
- Consumes: 없음 (독립)
- Produces: 도메인 command 필드 `tradeDate` (KST). Request JSON 키 `tradeDate` + `@JsonAlias("tradeDateKst")` 하위호환 (kista-ui 전환 완료 후 별도 정리 대상)

- [ ] **Step 1: 리네임 적용**

- Command 2개: 필드 `tradeDateKst` → `tradeDate` (record 컴포넌트 리네임, 주석 `// KST 거래일` 유지/추가)
- Request 2개: `@NotNull @JsonAlias("tradeDateKst") LocalDate tradeDate` (import `com.fasterxml.jackson.annotation.JsonAlias`), toCommand 매핑 갱신
- 서비스 호출부 `.tradeDateKst()` → `.tradeDate()` (컴파일 오류 따라 전부)
- `AdminTradeControllerTest`의 요청 JSON 키·record 생성자 갱신 + 별칭 케이스 1개 추가:

```java
@Test
void 구버전_tradeDateKst_키로도_수신된다() throws Exception {
    // 기존 정상 요청 JSON의 "tradeDate" 키를 "tradeDateKst"로 바꿔 전송 — @JsonAlias 하위호환 검증
    // (파일 내 기존 정상 케이스의 mockMvc 셋업·인증 패턴 재사용, 200/201 기대 동일)
}
```

- [ ] **Step 2: 검증 + 커밋**

Run: `./gradlew compileJava compileTestJava && ./gradlew test --tests 'com.kista.adapter.in.web.AdminTradeControllerTest' --tests 'com.kista.application.service.admin.*'`
Expected: PASS

```bash
git add -A src/main src/test
git commit -m "refactor: tradeDateKst → tradeDate 리네임 — KST 단일 기준 확립 후 잉여 접미사 제거"
```

---

### Task 7: TradeDateConverter → UsTradeDates 리네임 (US 기준 어댑터 전용 헬퍼로 축소)

Task 1~3 완료 후 남은 사용처는 `KisTradingApi`(KIS API가 US 거래일 기준)와 `MarketCalendarPersistenceAdapter`(휴장일 테이블이 US 달력)뿐이다.

**Files:**
- Create: `src/main/java/com/kista/common/UsTradeDates.java`
- Delete: `src/main/java/com/kista/common/TradeDateConverter.java`
- Modify: `src/main/java/com/kista/adapter/out/kis/KisTradingApi.java:178,184,197`
- Modify: `src/main/java/com/kista/adapter/out/persistence/calendar/MarketCalendarPersistenceAdapter.java:24-25`
- Test: 기존 테스트 컴파일로 검증 (남은 참조 0 확인)

**Interfaces:**
- Consumes: Task 1~3에서 다른 사용처 제거 완료
- Produces: `UsTradeDates.toUsTradeDate(LocalDate kst): LocalDate`, `UsTradeDates.toKstTradeDate(LocalDate us): LocalDate`

- [ ] **Step 1: UsTradeDates 생성**

```java
package com.kista.common;

import java.time.LocalDate;

// KST 거래일 ↔ US 거래일 변환 — US 기준 외부 데이터(KIS API, 휴장일 캘린더)를 만나는 어댑터 내부 전용.
// KST 거래일(매매 정산 아침)은 항상 US 거래일 다음날이므로 단순 ±1일이 성립한다.
// 도메인·서비스·persistence(orders)에서는 사용 금지 — 전 구간 KST 단일 기준.
public final class UsTradeDates {

    // KST 거래일 → US 거래일. 예: KST 5/27 → US 5/26
    public static LocalDate toUsTradeDate(LocalDate kstTradeDate) {
        return kstTradeDate.minusDays(1);
    }

    // US 거래일 → KST 거래일. 예: US 5/26 → KST 5/27
    public static LocalDate toKstTradeDate(LocalDate usTradeDate) {
        return usTradeDate.plusDays(1);
    }

    private UsTradeDates() {}
}
```

- [ ] **Step 2: 사용처 교체 + 구 클래스 삭제**

- `KisTradingApi`: `TradeDateConverter.toUtc` → `UsTradeDates.toUsTradeDate`, `TradeDateConverter.toKst` → `UsTradeDates.toKstTradeDate` (:178 `utcFrom` 변수명은 `usFrom`으로), `:195-198 formatTradeDate` 주석 "KST 날짜 → KIS API 날짜 파라미터 (US 거래일 YYYYMMDD)"
- `MarketCalendarPersistenceAdapter:24-25`: `LocalDate usDate = UsTradeDates.toUsTradeDate(date);` + 주석 "date는 KST 거래일 — 휴장일 테이블은 US 달력 기준이므로 변환 필수" (이하 `utcDate` 변수명 `usDate`로)
- `TradeDateConverter.java` 삭제
- 잔여 참조 확인: `grep -rn "TradeDateConverter" src/ --include="*.java"` → 0건이어야 함 (테스트 포함)

- [ ] **Step 3: 검증 + 커밋**

Run: `./gradlew compileJava compileTestJava && ./gradlew test --tests 'com.kista.adapter.out.kis.*'`
Expected: PASS

```bash
git add -A src/main src/test
git commit -m "refactor: TradeDateConverter → UsTradeDates — US 기준 어댑터 전용 헬퍼로 축소"
```

---

### Task 8: 전체 테스트 + 문서 갱신

**Files:**
- Modify: `docs/agents/constraints.md` ("tradeDate 변환 정책" 섹션), `CLAUDE.md` (날짜 변환 정책 요약), `docs/agents/architecture.md` (TradeDateConverter·privacy 서술), `docs/agents/workflow.md` (필요 지점), `docs/agents/testing.md` (변환 관련 테스트 패턴 서술 확인)

- [ ] **Step 1: 전체 테스트**

Run: `./gradlew test`
Expected: 전체 PASS. 실패 시 `grep -oP 'failures="\K[^"]+' build/test-results/test/TEST-*.xml | grep -v ':0'`로 진단 후 수정.

- [ ] **Step 2: constraints.md 섹션 교체**

"### tradeDate 변환 정책 (KST 코드 ↔ UTC=US 거래일 DB)" 섹션 전체를 다음으로 교체:

```markdown
### 시간 기준 정책 (KST 단일 기준)
- **거래일(tradeDate) = KST 일자** — 매매가 실행·정산되는 KST 아침이 속한 날. DB(`orders.trade_date`)·도메인·API 모두 동일 값, 변환 없음 (V27에서 US→KST shift 완료)
- **`privacy_trade_bases.release_date` = FIDA 발행일 원본(KST)** — 거래일 아님. 발행일↔거래일(+1일)은 `PrivacyDates.releaseDateFor()/tradeDateOf()` 업무 규칙 헬퍼만 사용
- **외부 원본 참조 데이터는 원본 기준 유지**: `us_market_holidays`(US 달력일), `market_index_prices`(US 거래일) — KST↔US 변환은 해당 어댑터 내부에서만 (`UsTradeDates.toUsTradeDate()/toKstTradeDate()`)
- `UsTradeDates` 사용 허용 위치: `KisTradingApi`(KIS API는 US 거래일 기준), `MarketCalendarPersistenceAdapter` — 도메인·서비스·orders persistence에서 사용 금지
- **Toss API**: 주문 접수일(KST) 기준 — 변환 없음. `TossOrderApi.fetchExecutions()`는 전날 저녁 선접수 대응으로 `queryFrom = from - 1일` 조회 후 `filledAt`(KST) 재필터
- Instant ↔ KST 일자 경계는 `atStartOfDay(TimeZones.KST)` 단일 관용구 — `ZoneOffset.UTC` 자정 경계 금지
- 거래일 경계 시각: `DstInfo.SCHEDULER_RUN_TIME = 04:30 KST` (마감 배치 cron 발화와 동일) — preview·수동실행·주문취소가 `DstInfo.nextTradeDate()` SSOT 사용
- 인라인 `.minusDays(1)`/`.plusDays(1)`로 날짜 기준 변환 금지 — 반드시 `UsTradeDates`/`PrivacyDates` 경유
```

- [ ] **Step 3: CLAUDE.md·architecture.md·workflow.md 정합**

- `CLAUDE.md` "작업 방식"의 날짜 변환 정책 불릿을 새 정책 한 줄 요약으로 교체: "**시간 기준 정책**: 거래일은 전 구간 KST 단일 기준(변환 없음), `release_date`는 FIDA 발행일 원본, US 기준 외부 데이터만 어댑터 내부 `UsTradeDates` 변환 (`docs/agents/constraints.md` 참고)"
- `architecture.md`: `TradeDateConverter` 항목을 `UsTradeDates`·`PrivacyDates`로 교체, `PrivacyTradePersistenceAdapter`·`OrderPersistenceAdapter` 서술에서 "toUtc/toKst" 언급 제거
- `workflow.md`: 날짜 기준 언급 지점 grep(`toUtc\|toKst\|UTC`) 후 정합 (대부분 KST 서술이라 변경 소요 적음)
- `testing.md`: `OrderPersistenceAdapterTest` 관련 서술에 변환 제거 반영 여부 확인

- [ ] **Step 4: 커밋**

```bash
git add docs/ CLAUDE.md
git commit -m "docs: 시간 기준 KST 단일 정책 반영 — 변환 정책 섹션 전면 갱신"
```

---

### Task 9: 운영 배포 절차 + kista-ui 연계 확인

- [ ] **Step 1: kista-ui 영향 조사**

```bash
grep -rn "tradeDateKst\|releaseDate\|tradeDate" /Users/phs/workspace/kista/kista-ui/src --include="*.ts" --include="*.tsx" -l
```

- 어드민 재주문/수동정정 화면이 `tradeDateKst` 키를 보내면 `tradeDate`로 변경 (`@JsonAlias` 덕에 전환 기간 무중단)
- privacy 기준표 어드민 화면의 `releaseDate` 라벨이 "KST 발행일"로 읽히는지 확인 (값 변화 없음)
- FIDA 응답 소비처가 있으면 `tradeDate` → `releaseDate` 필드 변경
- kista-ui 레포에서 별도 커밋 (`git -C /Users/phs/workspace/kista/kista-ui ...`)

- [ ] **Step 2: 배포 체크리스트 확인 (사용자 승인 후 실행)**

- 배포 창: 스케쥴러 발화(22:30, 04:30 KST) 회피
- V27은 단방향 — 롤백 시 `UPDATE orders SET trade_date = trade_date - 1` 역shift 필요함을 인지
- 배포 직후 검증: `supabase db query --linked`로 최신 거래일 행이 KST 기준인지 확인
  (`SELECT trade_date, created_at FROM orders ORDER BY created_at DESC LIMIT 5;` — trade_date가 created_at의 KST 일자와 일치해야 함)
- FIDA 송신측은 `@JsonAlias` 하위호환으로 무중단 — 추후 `releaseDate` 키 전환 후 별칭 제거 이슈 남김

---

## 자가 리뷰 결과

- 스펙 §1(orders KST)→Task 1, §3(privacy 규칙·버그①)→Task 2·3, §4(통계 경계·버그②)→Task 4, §5(경계 04:30·버그④)→Task 5, §6(명명 정리·버그③)→Task 1·6, 어댑터 변환 축소→Task 7, §8(문서)→Task 8, §7(UI)·배포→Task 9 — 전 요구사항 커버
- 버그 ③은 Task 1의 변환 제거로 해소 (별도 태스크 불필요 — `findTradeDatesByStrategyId`가 KST 반환)
- `market_index_prices`는 현재 application 소비처가 없어(수집 전용) 변경 대상 아님 — 조사로 확인 완료
- `PrivacyTradeValidationService`는 날짜 필드 미사용 — Task 2·3 영향 없음 확인 완료
