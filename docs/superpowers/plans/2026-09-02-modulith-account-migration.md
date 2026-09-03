# Spring Modulith account 모듈 이전 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 레거시 최상위(`com.kista.domain`/`application`/`adapter`, `Type.OPEN`)에 있는 account 애그리게이트(계좌 자격증명·브로커 연결)를 신규 `com.kista.account` Spring Modulith `CLOSED` 모듈로 이전한다.

**Architecture:** finance/notify/broker/trading/market/privacy/stats/admin/user 9모듈 이전과 동일 패턴 — 모듈 내부는 기존 Hexagonal 레이어(`domain/application/adapter`)를 그대로 유지하고 최상위 패키지만 `com.kista.account`로 옮긴다. 순수 구조 이전이라 TDD red-green이 아닌 "이동 → import 정합화 → 컴파일/테스트 그린 → 커밋" 사이클. account는 CLOSED 전환 시 순환 2건(account→strategy-config cascade 역방향, admin↔account RuntimeSettingsPort/Account.Broker 상호참조)이 사전 실측으로 확인됐으므로, 물리 이전 **전에** 순환을 끊는 코드 변경 태스크를 먼저 둔다. strategy-config는 이번 스코프가 아니다 — trading과 원자적 트랜잭션으로 결합(`CycleSnapshotCreator.reconfigureVrCycle`)돼 있어 별도 스펙이 필요하다는 게 실측으로 드러났다.

**Tech Stack:** Java 21, Spring Boot 4, Spring Modulith, Gradle, JUnit 5

**Spec:** `docs/superpowers/specs/2026-09-02-modulith-account-migration-design.md` — 이 계획의 "Global Constraints"가 스펙 실측을 대체하는 최신 SSOT다(스펙 작성 이후 계획 작성 중 추가로 확인된 세부사항 포함).

## Global Constraints

### 실측 순환 2건과 그 판정

1. **account↔strategy-config**: `AccountService.delete()`(`src/main/java/com/kista/application/service/account/AccountService.java:90-96`)가 `strategyPort.deleteByAccountId(accountId)`를 직접 호출한다 — account가 strategy-config 소유 포트를 직접 호출하는 역방향. strategy-config→account는 정당한 forward(`StrategyService`가 `AccountPort` 조회)이므로 이 방향만 끊으면 된다. `UserCascadeDeleter.deleteCascade()`(user, 이미 CLOSED)의 `accountPort.deleteByUserId(userId)` 호출은 **순환이 아니다** — account 소스 전체를 grep한 결과 `com.kista.user`를 참조하는 곳이 0건이라, user→account는 단방향 leaf 참조다. 이벤트 전환 불필요.
2. **admin↔account**: `AccountService.requireBrokerEnabled()`가 `RuntimeSettingsPort.load().brokers()`를 직접 소비(forward) ↔ `AdminAccountResponse`/`AdminAccountItem`/`AdminSelectionChain`/`AdminAnomalies`/`AdminTradeResponse`/`RuntimeSettings`/`AdminSettingsRequest`/`RuntimeSettingsResponse`가 `Account`/`Account.Broker`를 직접 참조(backward). admin↔user에서 이미 쓴 `ApprovalPolicyPort` 포트 역전과 동일 패턴으로 해소한다.

### 순환 해소 방향

- **admin↔account는 포트 역전으로 해소**: account가 신규 아웃바운드 포트 `BrokerEnabledPort`(1메서드: `boolean enabled(Account.Broker broker)`)를 자체 정의하고, admin의 `RuntimeSettingsService`가 이를 구현한다(`ApprovalPolicyPort`와 완전히 동일한 패턴 — "포트를 필요로 하는 쪽이 정의, 데이터를 가진 쪽이 구현"). admin이 `Account`/`Account.Broker`를 참조하는 나머지 지점(`AdminAccountResponse`/`AdminAccountItem`/`AdminSelectionChain`/`AdminAnomalies`/`AdminTradeResponse`)은 account가 "domain" NamedInterface로 `Account`를 공개하면 admin→account 정상 forward이므로 그대로 둔다. `AdminSettingsRequest`/`RuntimeSettingsResponse`의 `Account.Broker` 참조도 마찬가지로 forward 유지.
- **cascade는 이벤트 팬아웃으로 전환**: `AccountService.delete()`의 `strategyPort.deleteByAccountId(accountId)` 직접 호출을 제거하고, `AccountDeletedEvent(UUID accountId)`를 발행한다. strategy-config(레거시 `com.kista.application.service.strategy` 패키지, 아직 모듈 아님)가 신규 리스너로 구독해 자기 소유 `strategy` 테이블 행을 소프트삭제한다. `UserDeletedEvent`+`UserCascadeListener` 선례와 동일 메커니즘(EPR 추적, `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)`) — 단 리스너가 CLOSED 모듈이 아닌 레거시 패키지에 위치한다는 점만 다르다(strategy-config 모듈 자체가 아직 없으므로).

### own-type 전환 대상 (broker/notify/trading) — **정정(Task 4 1차 실행 후 실측으로 뒤집힘)**

**원래 판정(아래 취소선)은 틀렸다.** Task 4(물리 이전)를 실제로 실행해 `ApplicationModules.verify()`를 돌린 결과, `broker↔account` 순환이 실재했다 — `account→broker`(`AccountService.register()`/`test()`가 `BrokerConnectionTesters` 직접 호출, 등록 시 자격증명 검증이라 정당한 forward)와 `broker→account`(11개 포트 전부 + KIS/Toss/Mock 어댑터 구현체 176개 이상 지점이 `Account`/`Account.Broker`를 시그니처에 직접 사용)가 만나 진짜 2-cycle이었다. 원래 판정이 "broker 소스 전체에서 account를 참조하는 곳 0건"이라고 적은 건 **계획 작성 시점에 `account→broker` 쪽 엣지(`AccountService`의 `BrokerConnectionTesters` 의존)를 놓쳤기 때문**이다 — 이 의존은 레거시 코드에 이미 존재했지만, account가 레거시 OPEN 패키지에 있는 동안은 `verify()`가 검사하지 않아 보이지 않았다. market/privacy/stats/admin/user 5개 모듈 전부에서 반복된 "pairwise 분석이 놓친 순환은 물리 이전 후 verify()로만 잡힌다"는 교훈이 이번에도 그대로 재현됐다.

~~- broker 11개 포트는 KIS/Toss/Mock 어댑터 구현체가 실제로 `account.id()/appKey()/secretKey()/accountNo()/brokerAccountCode()`만 쓴다. 이 계획에서는 own-type 전환을 하지 않는다.~~
~~- notify 2개 포트도 동일 논리로 own-type 전환하지 않는다.~~
~~- trading `TradingExecutionUseCase.execute`도 동일 논리로 그대로 둔다.~~

**수정된 판정**:
- **broker 11개 포트는 own-type 전환이 필요하다** — Task 3.5(아래)에서 처리. KIS/Toss/Mock 어댑터 구현체 실측 결과 실제로 쓰는 필드는 `account.id()`(13회, 토큰 캐싱 키)/`appKey()`(9회)/`secretKey()`(8회)/`accountNo()`(2회)/`brokerAccountCode()`(1회) 5개뿐 — `broker()` 필드는 어댑터 내부가 아닌 `BrokerAdapterRegistry`/`BrokerConnectionTesters`가 라우팅 키로만 사용(이쪽은 `Account.Broker`를 그대로 파라미터로 받아도 됨 — 라우팅 키 자체가 `Broker` enum이지 `Account`가 아니므로 own-type이 굳이 필요 없다, 아래 Task 3.5 설계 참고).
- **notify 2개 포트는 원래 판정이 맞다** — notify→account 참조는 forward이고 account→notify 참조는 0건(grep 재확인 완료)이라 순환이 아니다. own-type 전환 불필요, 유지.
- **trading `TradingExecutionUseCase.execute(Strategy, Account, User)`도 원래 판정이 맞다** — trading→account 참조는 forward, account→trading 참조는 0건. own-type 전환 불필요, 유지.

notify·trading 두 항목은 여전히 스펙 문서가 "own-type 전환"으로 적었으나 실측 결과 불필요 — 그대로 태스크에서 제외 유지. broker만 정정 대상이다.

### 통계 서비스 재배치는 스코프 아웃

`AccountStatisticsService`/`TossStatisticsService`/`BrokerStatisticsRouter`(현재 `com.kista.application.service.account` 패키지)는 실질 관심사가 broker/trading/privacy/strategy 통계 집계라 stats 재배치 대상이지만, **이 셋을 옮기지 않아도 account 물리 이전에는 지장이 없다** — account 모듈 자체(`domain/model`+`application/{usecase,port/output,event}`+`adapter/{in,out}`)는 이 3개 서비스를 포함하지 않는다(별도 클래스, 다른 UseCase 인터페이스). 이 3개는 `com.kista.application.service.account` 레거시 패키지에 남아 `AccountPort`(account "port")를 소비하는 정상 forward가 되므로, 재배치는 이번 계획 스코프에서 제외하고 향후 stats 정리 작업(또는 strategy-config 이전 때)으로 미룬다.

### 이동/유지 경계

**MOVE 대상**: 아래 "File Structure" 절이 SSOT.

**레거시 잔류(이번 스코프 아님)**:
- `AccountStatisticsService`/`TossStatisticsService`/`BrokerStatisticsRouter` + 대응 UseCase 인터페이스 2개 — 위 이유로 재배치 안 함, import 경로만 갱신
- `StrategyService`/`AccountService`가 아닌 다른 모든 소비자(broker/notify/trading/admin/stats) — `AccountPort`/`Account` import 경로만 `com.kista.account.*`로 갱신
- `GlobalExceptionHandler` — `Account.InvalidBrokerKeyException`/`DuplicateAccountException`/`KisRateLimitException` 매핑, import 경로만 갱신
- `MetaController` — `Account.Broker.values()` 직렬화, import 경로만 갱신
- strategy 관련 레거시(`StrategyService`/`StrategyPort`/`Strategy` 등) — 이번 스코프 아님, `AccountPort` 소비 지점만 import 경로 갱신

### 사전 실측: 문자열 리터럴 FQN / YAML — 0건

기존 5개 모듈 계획과 동일하게 `src/main/resources/**`(`*.yml`) 및 Java 문자열 리터럴에서 `com.kista.domain.model.account`/`com.kista.application.service.account`/`com.kista.application.port.output.AccountPort`/`com.kista.application.usecase.AccountUseCase`/`com.kista.adapter.out.persistence.account`/`com.kista.adapter.in.web.AccountController` 참조를 확인 — Logback 로거는 `com.kista` 상위 prefix만 있어 하위 패키지 이동에 영향 없고, `@ComponentScan` 등 패키지 하드코딩도 없다. 각 물리 이전 태스크에서 Step 0으로 재확인만 하고 매치 시 갱신.

### BSD sed 함정 (필수 준수)

이 환경은 macOS(Darwin) — BSD sed는 `\|` alternation을 리터럴로 취급해 조용히 no-op한다. 모든 alternation 치환은 `perl -pi -e 's/.../.../g'`를 사용한다(아래 각 Step의 명령은 이미 이렇게 작성됨). 치환 직후 반드시 `git grep`으로 잔존 옛 경로 0건을 확인한다.

### 공통 규칙

- 커밋 전 검토자(리뷰어 서브에이전트) 검수 필수 — 전역 CLAUDE.md 규칙, 문서 전용 태스크 없음(이 계획 전체가 코드 변경).
- Git author `narafu <narafu@kakao.com>`, 커밋 메시지 한글 Conventional Commit.
- `ApplicationModules.verify()` 게이트는 모듈 선언(Task 4) 시점에만 유효 — account가 `@ApplicationModule` 미선언인 동안은 레거시 OPEN의 일부로 취급돼 순환이 안 잡힌다. Task 1~2에서 순환 2건을 사전 해소했지만, pairwise 한계로 놓친 전이 순환이 있을 수 있다(market/privacy/stats/user 교훈 — 매번 최소 1건씩 실측에서 드러났다). **Task 4에서 `verify()`가 예측 못한 순환을 보고하면 즉시 멈추고 보고**(추측 수정 금지).

---

## File Structure (최종 `com.kista.account` 트리)

```
com.kista.account/
  package-info.java                       ← @ApplicationModule (Task 4)
  domain/
    model/                                 ← "domain" NamedInterface (Task 4)
      Account.java                         ← Broker nested enum 포함(그대로 유지, sharedkernel 이관 안 함)
      RegisterAccountCommand.java
      UpdateAccountCommand.java
      SellableQuantity.java
      AccountNumberMasker.java
  application/
    usecase/                               ← "usecase" NamedInterface (Task 4)
      AccountUseCase.java
    port/output/                           ← "port" NamedInterface (Task 4)
      AccountPort.java
      BrokerEnabledPort.java               ← 신규(Task 2)
    event/                                 ← "event" NamedInterface (Task 4)
      AccountDeletedEvent.java             ← 신규(Task 1)
    service/                               ← internal
      AccountService.java
  adapter/
    in/web/                                ← internal
      AccountController.java
    in/web/dto/                            ← internal
      AccountRequest.java
      AccountResponse.java
      TestConnectionRequest.java
    out/persistence/                       ← internal
      AccountEntity.java
      AccountJpaRepository.java
      AccountPersistenceAdapter.java
```

### 레거시 잔류 (경로만 갱신 — account로 안 옮김)
- `com.kista.application.service.account.{AccountStatisticsService,TossStatisticsService,BrokerStatisticsRouter}` — `AccountPort`(account "port") 소비, import 경로만 갱신
- `com.kista.application.service.strategy.StrategyService` — `AccountPort` 소비, import 경로만 갱신 + 신규 `AccountDeletedEvent` 리스너 추가(Task 1)
- `com.kista.adapter.in.web.GlobalExceptionHandler` — `Account.*Exception` 매핑, import 경로만 갱신
- `com.kista.adapter.in.web.MetaController` — `Account.Broker.values()`, import 경로만 갱신
- `com.kista.admin.*` 전체 — `Account`/`Account.Broker` 소비, import 경로만 갱신 + `BrokerEnabledPort` 구현 추가(Task 2)
- broker/notify/trading 전체 — `Account` 소비, import 경로만 갱신

---

## Task 1: account→strategy-config 순환 해소 — cascade 이벤트 팬아웃 전환

> **배경:** `AccountService.delete()`가 strategy-config 소유 `StrategyPort.deleteByAccountId()`를 직접 호출하는 역방향 참조를 제거한다. account가 CLOSED 전환되면 이 직접 호출이 strategy-config→account(정당한 forward)와 만나 순환이 된다.

**Files:**
- Create: `src/main/java/com/kista/application/event/AccountDeletedEvent.java`(레거시 위치 — Task 4에서 `com.kista.account.application.event`로 물리 이동 예정, 지금은 임시)
- Modify: `src/main/java/com/kista/application/service/account/AccountService.java`
- Create: `src/main/java/com/kista/application/service/strategy/AccountCascadeListener.java`
- Test: `src/test/java/com/kista/application/service/account/AccountServiceTest.java`
- Test: `src/test/java/com/kista/application/service/strategy/AccountCascadeListenerTest.java` (신규)

**Interfaces:**
- Produces: `com.kista.application.event.AccountDeletedEvent(UUID accountId)` — Task 4에서 `com.kista.account.application.event`로 이동, 이후 모든 이후 태스크가 이 경로를 참조.
- Consumes: 없음.

- [ ] **Step 1: `AccountDeletedEvent` 신설**

`src/main/java/com/kista/application/event/AccountDeletedEvent.java`:
```java
package com.kista.application.event;

import java.util.UUID;

// 계좌 cascade 삭제 완료 — 트랜잭션 커밋 후에만 발행됨
public record AccountDeletedEvent(UUID accountId) {}
```

- [ ] **Step 2: `AccountService`가 직접 호출 대신 이벤트 발행**

`src/main/java/com/kista/application/service/account/AccountService.java` 수정 — import 추가:
```java
import com.kista.application.event.AccountDeletedEvent;
import org.springframework.context.ApplicationEventPublisher;
```
`import com.kista.application.port.output.StrategyPort;` 삭제(더 이상 직접 참조 안 함).

필드에 `ApplicationEventPublisher eventPublisher` 추가(생성자 주입, `@RequiredArgsConstructor`가 자동 처리):
```java
    private final AccountPort accountPort;
    private final BrokerConnectionTesters connectionTesters;
    private final RuntimeSettingsPort runtimeSettingsPort;
    private final ApplicationEventPublisher eventPublisher; // 계좌 삭제 cascade 이벤트 발행
```
(`strategyPort` 필드 제거)

`delete()` 메서드 수정:
```java
    @Override
    public void delete(UUID accountId, UUID requesterId) {
        accountPort.requireOwnedAccount(accountId, requesterId);
        accountPort.delete(accountId);
        // 커밋 후 발행 — strategy-config 리스너가 소유 데이터를 독립적으로 정리(EPR 재시도 보장)
        eventPublisher.publishEvent(new AccountDeletedEvent(accountId));
        log.info("계좌 삭제: accountId={}, requesterId={}", accountId, requesterId);
    }
```

- [ ] **Step 3: strategy-config 리스너 신설**

`src/main/java/com/kista/application/service/strategy/AccountCascadeListener.java`:
```java
package com.kista.application.service.strategy;

import com.kista.application.event.AccountDeletedEvent;
import com.kista.application.port.output.StrategyPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// 계좌 삭제 cascade — strategy-config 소유 데이터(strategy)를 독립적으로 soft-delete.
// AccountService가 직접 포트를 호출하던 것을 이벤트 구독으로 전환(account↔strategy-config 순환 해소).
// AFTER_COMMIT 시점엔 원본 트랜잭션이 종료돼 있으므로, @Modifying soft-delete 쿼리를 위해
// REQUIRES_NEW로 새 트랜잭션을 연다.
@Component
@RequiredArgsConstructor
public class AccountCascadeListener {

    private final StrategyPort strategyPort;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onAccountDeleted(AccountDeletedEvent event) {
        strategyPort.deleteByAccountId(event.accountId());
    }
}
```

- [ ] **Step 4: 기존 테스트 갱신**

`src/test/java/com/kista/application/service/account/AccountServiceTest.java`:
- `@Mock StrategyPort cyclePort;` 제거
- `@Mock ApplicationEventPublisher eventPublisher;` 추가
- `delete_by_owner_success` 테스트에 `verify(eventPublisher).publishEvent(new AccountDeletedEvent(accountId));` 추가, 기존 `strategyPort.deleteByAccountId` 관련 검증(있다면) 제거

```java
import com.kista.application.event.AccountDeletedEvent;
import org.springframework.context.ApplicationEventPublisher;
```

```java
    @Mock AccountPort accountPort;
    @Mock BrokerConnectionTesters connectionTesters;
    @Mock BrokerConnectionTestPort connectionTester;
    @Mock RuntimeSettingsPort runtimeSettingsPort;
    @Mock ApplicationEventPublisher eventPublisher;
    @InjectMocks AccountService accountService;
```

`delete_by_owner_success` 테스트 본문 끝에 추가:
```java
        verify(eventPublisher).publishEvent(new AccountDeletedEvent(accountId));
```

- [ ] **Step 5: 신규 리스너 테스트 작성**

`src/test/java/com/kista/application/service/strategy/AccountCascadeListenerTest.java`:
```java
package com.kista.application.service.strategy;

import com.kista.application.event.AccountDeletedEvent;
import com.kista.application.port.output.StrategyPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("AccountCascadeListener 단위 테스트")
class AccountCascadeListenerTest {

    @Mock StrategyPort strategyPort;
    @InjectMocks AccountCascadeListener listener;

    @Test
    @DisplayName("계좌 삭제 이벤트 수신 시 소속 전략을 소프트 삭제한다")
    void onAccountDeleted_deletesStrategiesByAccountId() {
        UUID accountId = UUID.randomUUID();

        listener.onAccountDeleted(new AccountDeletedEvent(accountId));

        verify(strategyPort).deleteByAccountId(accountId);
    }
}
```

- [ ] **Step 6: 컴파일 + 대상 테스트**

```bash
./gradlew compileJava compileTestJava 2>&1 | grep -E "error:|FAILED"
./gradlew test --tests 'com.kista.application.service.account.AccountServiceTest' --tests 'com.kista.application.service.strategy.AccountCascadeListenerTest' 2>&1 | grep -E "FAILED|BUILD"
```
Expected: 에러 없음, `BUILD SUCCESSFUL`.

- [ ] **Step 7: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(modulith): account↔strategy-config 순환 사전 해소 — cascade 이벤트 팬아웃 전환

AccountService.delete()가 strategy-config 소유 StrategyPort를 직접
호출하던 것을 AccountDeletedEvent 발행으로 전환하고, strategy-config
쪽 신규 AccountCascadeListener가 AFTER_COMMIT 구독으로 자기 소유
strategy 데이터를 독립적으로 soft-delete(UserDeletedEvent 선례와
동일 패턴). account CLOSED 전환 전 사전 해소.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: admin↔account 순환 해소 — BrokerEnabledPort 포트 역전

> **배경:** `AccountService.requireBrokerEnabled()`가 admin 소유 `RuntimeSettingsPort`를 직접 참조하는 걸 끊는다. admin이 `Account`/`Account.Broker`를 참조하는 나머지 지점(응답 DTO 등)은 정상 forward라 손대지 않는다.

**Files:**
- Create: `src/main/java/com/kista/application/port/output/BrokerEnabledPort.java`(레거시 위치 — Task 4에서 `com.kista.account.application.port.output`으로 물리 이동)
- Modify: `src/main/java/com/kista/admin/application/service/RuntimeSettingsService.java`
- Modify: `src/main/java/com/kista/application/service/account/AccountService.java`
- Test: `src/test/java/com/kista/admin/application/service/RuntimeSettingsServiceTest.java`
- Test: `src/test/java/com/kista/application/service/account/AccountServiceTest.java`

**Interfaces:**
- Produces: `com.kista.application.port.output.BrokerEnabledPort`(`boolean enabled(Account.Broker broker)`) — account가 소유, Task 4에서 `com.kista.account.application.port.output`으로 물리 이동.
- Consumes: 없음(admin의 `RuntimeSettingsService`가 구현).

- [ ] **Step 1: `BrokerEnabledPort` 신규 정의**

`src/main/java/com/kista/application/port/output/BrokerEnabledPort.java`:
```java
package com.kista.application.port.output;

import com.kista.domain.model.account.Account;

// 증권사 신규 계좌 등록 활성화 여부를 조회하는 포트 — AccountService.register()/test()가
// admin의 RuntimeSettingsPort를 직접 참조하지 않도록, account가 필요한 만큼만 담은 전용 포트를
// 자체 정의하고 admin이 구현한다(user의 ApprovalPolicyPort와 동일한 포트 역전 패턴).
public interface BrokerEnabledPort {
    boolean enabled(Account.Broker broker);
}
```

- [ ] **Step 2: `RuntimeSettingsService`가 `BrokerEnabledPort` 구현**

`src/main/java/com/kista/admin/application/service/RuntimeSettingsService.java` 수정 — import 추가:
```java
import com.kista.application.port.output.BrokerEnabledPort;
```
클래스 선언에 `implements` 추가:
```java
class RuntimeSettingsService implements RuntimeSettingsUseCase, AdminSettingsUseCase, ApprovalPolicyPort, BrokerEnabledPort {
```
메서드 추가 — 파일이 이미 `import com.kista.domain.model.account.Account.Broker;`(bare `Broker` 단독 import)를 쓰므로 `Account` 클래스 자체는 import돼 있지 않다. `BrokerEnabledPort.enabled(Account.Broker broker)`를 구현할 때 `Account.Broker`라고 쓰면 `Account`를 못 찾아 컴파일 에러가 난다 — 기존 import 스타일 그대로 bare `Broker`를 파라미터 타입으로 쓴다(같은 타입이라 오버라이드 시그니처로 유효):
```java
    @Override
    @Transactional(readOnly = true)
    public boolean enabled(Broker broker) {
        return settingsPort.load().brokers().get(broker).enabled();
    }
```

- [ ] **Step 3: `AccountService`가 `RuntimeSettingsPort` 대신 `BrokerEnabledPort` 사용**

`src/main/java/com/kista/application/service/account/AccountService.java` 수정 — import:
```java
import com.kista.admin.application.port.output.RuntimeSettingsPort;
```
삭제하고:
```java
import com.kista.application.port.output.BrokerEnabledPort;
```
추가. 필드:
```java
private final BrokerEnabledPort brokerEnabledPort; // 증권사 신규 등록 허용 여부 (admin RuntimeSettingsService가 구현)
```
(`runtimeSettingsPort` 필드 대체)

`requireBrokerEnabled()` 메서드 수정:
```java
    private void requireBrokerEnabled(Account.Broker broker) {
        if (!brokerEnabledPort.enabled(broker)) {
            throw new IllegalArgumentException(broker + " 증권사 신규 계좌 등록이 비활성화되어 있습니다");
        }
    }
```

- [ ] **Step 4: 컴파일 확인**

```bash
git grep -n "com\.kista\.admin\.application\.port\.output\.RuntimeSettingsPort" -- src/main/java/com/kista/application/service/account
```
Expected: 출력 없음.

```bash
./gradlew compileJava 2>&1 | grep -E "error:"
```
Expected: 테스트 파일 mock 불일치로 인한 컴파일 에러는 아직 정상 — Step 5에서 처리.

- [ ] **Step 5: 테스트 갱신**

`src/test/java/com/kista/admin/application/service/RuntimeSettingsServiceTest.java` — `enabled()` 신규 메서드 테스트 추가:
```java
    @Test
    @DisplayName("enabled는 저장된 브로커 설정을 그대로 반환한다")
    void enabled_delegatesToLoadedSettings() {
        when(settingsPort.load()).thenReturn(settingsWith(Account.Broker.KIS, false));

        boolean result = service.enabled(Account.Broker.KIS);

        assertThat(result).isFalse();
    }
```
(`settingsWith` 헬퍼가 없으면 `AccountServiceTest`의 동일 헬퍼 패턴을 참고해 추가하거나, 기존 `RuntimeSettings.defaults()` + `EnumMap` 조합으로 인라인 작성. `import com.kista.domain.model.account.Account;` 및 `@DisplayName` import 확인)

`src/test/java/com/kista/application/service/account/AccountServiceTest.java` — 전체 `RuntimeSettingsPort` mock을 `BrokerEnabledPort`로 교체:
```java
import com.kista.application.port.output.BrokerEnabledPort;
```
(`import com.kista.admin.application.port.output.RuntimeSettingsPort;`, `import com.kista.admin.domain.model.RuntimeSettings;` 삭제 — 단 `settingsWith` 헬퍼가 `RuntimeSettings.defaults()`를 쓰던 걸 제거하고 boolean 직접 stub으로 단순화)

```java
    @Mock AccountPort accountPort;
    @Mock BrokerConnectionTesters connectionTesters;
    @Mock BrokerConnectionTestPort connectionTester;
    @Mock BrokerEnabledPort brokerEnabledPort;
    @Mock ApplicationEventPublisher eventPublisher;
    @InjectMocks AccountService accountService;
```

기존 `when(runtimeSettingsPort.load()).thenReturn(settingsWith(Account.Broker.KIS, true));` 패턴 전부를:
```java
when(brokerEnabledPort.enabled(Account.Broker.KIS)).thenReturn(true);
```
형태로 치환(각 테스트가 쓰는 broker/enabled 조합 그대로 유지). `existingAccountOperations_doNotLoadRuntimeSettings` 테스트의 `verifyNoInteractions(runtimeSettingsPort);`는 `verifyNoInteractions(brokerEnabledPort);`로 교체. 파일 하단의 `settingsWith(...)` private 헬퍼 메서드와 `import com.kista.admin.domain.model.RuntimeSettings;`, `import java.util.EnumMap;`은 더 이상 필요 없으면 삭제(다른 테스트가 여전히 쓰는지 컴파일로 확인).

- [ ] **Step 6: 컴파일 + 대상 테스트**

```bash
./gradlew compileJava compileTestJava 2>&1 | grep -E "error:|FAILED"
./gradlew test --tests 'com.kista.admin.application.service.RuntimeSettingsServiceTest' --tests 'com.kista.application.service.account.AccountServiceTest' 2>&1 | grep -E "FAILED|BUILD"
```
Expected: 에러 없음, `BUILD SUCCESSFUL`.

- [ ] **Step 7: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(modulith): admin↔account 순환 사전 해소 — BrokerEnabledPort 포트 역전

AccountService가 admin의 RuntimeSettingsPort를 직접 참조하던 것을
자체 BrokerEnabledPort(1메서드)로 교체하고 admin의
RuntimeSettingsService가 구현(user의 ApprovalPolicyPort와 동일
포트 역전 패턴). admin이 Account/Account.Broker를 참조하는 나머지
지점(응답 DTO 등)은 정상 forward라 그대로 유지. account CLOSED
전환 전 사전 해소.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: 소비 파일 전수 확인 (물리 이전 준비)

> **배경:** 물리 이전(Task 4) 전에 account 관련 타입을 참조하는 전체 파일 목록을 최종 확정하고, 문자열 리터럴/YAML 참조가 없는지 재확인한다. 순수 조사 태스크 — 코드 변경 없음.

**Files:** 없음(조사만)

**Interfaces:** 없음

- [ ] **Step 1: 전체 소비 파일 색출**

```bash
git grep -ln "com\.kista\.domain\.model\.account\." -- src/main src/test | sort > /tmp/account-consumers.txt
git grep -ln "com\.kista\.application\.port\.output\.AccountPort" -- src/main src/test | sort >> /tmp/account-consumers.txt
git grep -ln "com\.kista\.application\.usecase\.AccountUseCase" -- src/main src/test | sort >> /tmp/account-consumers.txt
sort -u /tmp/account-consumers.txt
```

이 목록이 Task 4의 Step 3(전 소비 파일 import 경로 치환)이 처리해야 할 파일 전체다. 실행 시점에 이 계획 작성 당시(2026-09-02)와 파일 목록이 달라졌으면(다른 세션이 그 사이 관련 코드를 수정했으면) 아래 Task 4의 File Structure를 이 결과 기준으로 갱신한다.

- [ ] **Step 2: 문자열 리터럴 FQN 재확인**

```bash
git grep -n "com\.kista\.domain\.model\.account\|com\.kista\.application\.service\.account\|com\.kista\.application\.port\.output\.AccountPort\|com\.kista\.application\.usecase\.AccountUseCase\|com\.kista\.adapter\.out\.persistence\.account\|com\.kista\.adapter\.in\.web\.AccountController" -- 'src/main/resources/**/*.yml' src/main/java src/test/java | grep -v "^src/main/java.*:.*import \|^src/test/java.*:.*import "
```
Expected: 출력 없음(YAML/문자열 리터럴 참조 0건, import문만 있음 — 이건 Task 4가 처리).

- [ ] **Step 3: 와일드카드 import 확인**

```bash
git grep -n "^import com\.kista\.domain\.model\.account\.\*;\|^import com\.kista\.application\.port\.output\.\*;\|^import com\.kista\.application\.usecase\.\*;" -- src/main/java src/test/java
```
결과에 잡힌 파일은 Task 4 Step 3에서 명시 import로 분리 처리한다(다른 레거시 타입도 같은 와일드카드로 들어올 수 있으므로 컴파일 에러 발생 시 개별 확인).

- [ ] **Step 4: 커밋 없음**

이 태스크는 순수 조사라 커밋하지 않는다. Step 1 결과를 다음 태스크 실행자에게 그대로 전달한다.

---

## Task 3.5: broker↔account 순환 해소 — BrokerAccountRef own-type 전환

> **배경(2026-09-02 삽입 — Task 4 1차 실행이 `ApplicationModules.verify()`에서 발견)**: Task 4를 먼저 실행했을 때 `com.kista.account` 모듈 선언 직후 `verify()`가 `Slice account -> Slice broker -> Slice account` 순환을 보고했다. `account→broker`(`AccountService`가 등록/연결테스트 시 `BrokerConnectionTesters`를 직접 호출 — 정당한 forward)와 `broker→account`(11개 포트 전부 + KIS/Toss/Mock 어댑터 구현체가 `Account`/`Account.Broker`를 시그니처에 직접 사용 — 부당한 backward)가 만나 순환이 됐다. 해소 방향은 기존 `broker↔trading` 디커플링(2026-08-29, `Direction`/`OrderType`/`PriceSnapshot`/`BrokerBalance`/`OrderInstruction`/`OrderResult`/`CancelInstruction` 7개 own-type 신설 선례)과 완전히 동일한 패턴 — broker가 `Account` 대신 자기 소유 최소 값 객체를 포트 시그니처에 쓰고, account를 호출하는 쪽(trading/admin/stats/account 자신)이 매핑한다. 라우팅 키(`Account.Broker`)는 own-type 대상이 아니다 — `Broker` enum 자체는 account가 "domain" NamedInterface로 공개하는 값 타입이고 `BrokerAdapterRegistry`/`BrokerConnectionTesters`가 이걸로 라우팅하는 건 정상 forward이므로 그대로 둔다(순환의 원인은 `Account` 클래스 자체가 포트 시그니처에 박혀있는 것이지 `Broker` enum이 아니다).

**Files:**
- Create: `src/main/java/com/kista/broker/domain/model/BrokerAccountRef.java`
- Modify: broker 11개 포트 전체(`BrokerAdapterPort`/`StockInfoPort`/`BrokerOrderCorrectionPort`/`BrokerConnectionTestPort`/`SellableQuantityPort`/`BrokerAccountPort`/`ExecutionPort`/`LiveBalancePort`/`PortfolioPort`/`BrokerPricePort`/`MarginPort`, `src/main/java/com/kista/broker/application/port/output/*.java`)
- Modify: `BrokerAdapterRegistry.java`/`BrokerConnectionTesters.java`(`src/main/java/com/kista/broker/application/service/`) — `require`/`find`/`of` 전부 `Account` 대신 `BrokerAccountRef`(또는 `of`의 경우 `BrokerAccountRef.Broker`)를 파라미터로 받도록 시그니처 변경. 라우팅 키(`registry.get(...)`)는 `BrokerAccountRef.broker()`에서 꺼낸다.
- Modify: KIS 어댑터 5개(`KisAuthApi`/`KisBrokerAdapter`/`KisHttpClient`/`KisOrderApi`/`KisPriceApi`/`KisTradingApi`, `src/main/java/com/kista/broker/adapter/out/kis/`)
- Modify: Toss 어댑터 7개(`TossAuthApi`/`TossBrokerAdapter`/`TossHoldingsApi`/`TossHttpClient`/`TossMarketApi`/`TossOrderApi`, `src/main/java/com/kista/broker/adapter/out/toss/`)
- Modify: Mock 어댑터 2개(`MockAuthApi`/`MockBrokerAdapter`, `src/main/java/com/kista/broker/adapter/out/mock/`)
- Modify: 호출부 20개 파일(아래 정확한 목록) — `registry.require(account, X.class)`/`registry.find(account, X.class)`/`connectionTesters.of(broker)` 호출을 각 파일에 추가하는 인라인 헬퍼 `toBrokerRef(account)`를 거쳐 `registry.require(toBrokerRef(account), X.class)` 형태로 교체(Step 5).
- Test: broker 어댑터 테스트 전체(`src/test/java/com/kista/broker/adapter/out/{kis,toss,mock}/*.java`) — mock 대상 시그니처가 바뀌므로 stub도 함께 갱신
- Test: 호출부 20개 파일의 대응 테스트

**Interfaces:**
- Produces: `com.kista.broker.domain.model.BrokerAccountRef(UUID id, String appKey, String secretKey, String accountNo, String brokerAccountCode, BrokerAccountRef.Broker broker)` — broker "domain" NamedInterface 소속. `Broker`는 broker 자체 소유 복제 enum(값 집합만 `Account.Broker`와 동일). 이 레코드는 broker 쪽 어디에도 `Account`를 import하지 않는다.
- Consumes: 없음(broker 쪽 코드 기준). `Account → BrokerAccountRef` 변환은 전적으로 호출부(trading/admin/stats/account, 이미 `Account`를 아는 쪽) 20개 파일 각각의 인라인 private 정적 메서드 `toBrokerRef(Account)`가 담당한다(Step 5) — 모듈 간 공유 유틸 신설은 스코프 확대라 생략, 각자 1줄짜리 변환이라 중복 비용이 낮다.

- [ ] **Step 1: `BrokerAccountRef` own-type 신설**

**설계 원칙**: `BrokerAccountRef`(broker 소유)는 `Account`를 import하지 않는 순수 값 레코드다. `Account → BrokerAccountRef` 변환 로직을 broker 쪽 파일(레코드 자체든 별도 팩토리든)에 두면, 그 파일이 `Account`를 import하는 순간 broker→account 참조가 형태만 바뀐 채 남아 순환이 재발한다. 따라서 변환은 **반드시 호출부**(이미 `Account` 타입을 알고 있는 trading/admin/stats/account 등 20개 파일, Step 5)에 인라인으로 둔다. 같은 이유로 라우팅 키 `Broker` enum도 `Account.Broker`를 재사용하지 않고 broker가 자체 복제한다(재사용하면 `BrokerAccountRef` 필드 타입 자체가 account를 참조하게 됨) — broker의 기존 `Direction`이 trading의 `OrderDirection`을 복제한 것과 동일한 "값 집합만 동일한 별도 소유" 패턴.

`src/main/java/com/kista/broker/domain/model/BrokerAccountRef.java`:
```java
package com.kista.broker.domain.model;

import java.util.UUID;

// 계좌 자격증명 + 라우팅 키 — account.Account 전체를 broker 포트 시그니처에 노출하지 않기 위한 broker 소유 복제(Direction/OrderType 패턴과 동일)
// broker 모듈은 이 파일에서조차 account.Account를 참조하지 않는다 — Account → BrokerAccountRef 변환은 각 호출부(trading/admin/stats/account)가 담당한다
public record BrokerAccountRef(
        UUID id,                  // 토큰 캐싱 키
        String appKey,
        String secretKey,
        String accountNo,
        String brokerAccountCode, // KIS: null, TOSS: accountSeq
        Broker broker              // 라우팅 키
) {
    // account.domain.model.Account.Broker와 상수명 byte-identical 유지 — 매핑 시 valueOf(name())
    public enum Broker { TOSS, KIS, MOCK }
}
```

- [ ] **Step 2: broker 11개 포트 시그니처 교체**

각 포트 파일에서 `import com.kista.account.domain.model.Account;`(또는 Task 4 이전 상태라면 아직 `com.kista.domain.model.account.Account;` — **이 태스크는 Task 4보다 먼저 실행되므로 아직 레거시 경로다**, 아래 전부 레거시 경로 기준으로 작성) 삭제하고 `import com.kista.broker.domain.model.BrokerAccountRef;` 추가, 파라미터 타입 `Account` → `BrokerAccountRef`로 교체. `SellableQuantityPort`는 반환 타입 `SellableQuantity`도 함께 own-type 검토 필요하나(`com.kista.domain.model.account.SellableQuantity`), 실측 결과 이 타입은 `String symbol, int quantity` 2필드뿐이고 `Account`를 참조하지 않으므로 그대로 둬도 순환 무관 — 다만 소속 패키지가 account 도메인이라 참조 경로 자체는 발생한다. **`SellableQuantity`도 `com.kista.broker.domain.model`로 복제**(1개 필드 레코드, 비용 낮음):

```java
package com.kista.broker.domain.model;

// 종목별 판매 가능 수량 — KIS/Toss 공통 응답 타입, account.SellableQuantity 복제(순환 방지)
public record SellableQuantity(String symbol, int quantity) {}
```

11개 포트 파일 각각 수정 예시(`LiveBalancePort.java`):
```java
package com.kista.broker.application.port.output;

import com.kista.broker.domain.model.BrokerAccountRef;
import com.kista.broker.domain.model.BrokerBalance;
import com.kista.domain.model.strategy.Strategy.Ticker;

// live 잔고 조회 — KIS/Toss 브로커 어댑터에서 구현
public interface LiveBalancePort {
    BrokerBalance getLiveBalance(BrokerAccountRef account, Ticker ticker);
}
```

나머지 10개 포트(`BrokerAdapterPort`/`StockInfoPort`/`BrokerOrderCorrectionPort`/`BrokerConnectionTestPort`/`SellableQuantityPort`/`BrokerAccountPort`/`ExecutionPort`/`PortfolioPort`/`BrokerPricePort`/`MarginPort`)도 동일 패턴 — `Account` 파라미터를 `BrokerAccountRef`로, `SellableQuantity` 참조는 `com.kista.broker.domain.model.SellableQuantity`로 교체. `BrokerAdapterPort.supports()`의 반환 타입 `Account.Broker`는 `BrokerAccountRef.Broker`로 교체. `BrokerConnectionTestPort`는 원래부터 `Account`를 저장 전(등록 전) 원시 파라미터로 받으므로 `BrokerAccountRef`로 바꾸지 않고 대신 필요한 원시 필드(`appKey`, `secretKey`, `accountNo`, `Account.Broker`→`BrokerAccountRef.Broker`)만 그대로 유지(이미 `Account` 자체를 안 받고 있음 — `verifyCredentials(String, String, UUID)`/`verifyAccount(String, String, String)`, `supports(): BrokerAccountRef.Broker`만 교체).

- [ ] **Step 3: `BrokerAdapterRegistry`/`BrokerConnectionTesters` 라우팅 재설계**

`src/main/java/com/kista/broker/application/service/BrokerAdapterRegistry.java` — `require`/`find`가 여전히 `Account` 전체를 받으면 이 파일(application.service, "application" NamedInterface)에 `Account` 참조가 남아 순환이 재발한다. `Account`→`BrokerAccountRef` 변환은 **호출부**가 미리 해서 넘기도록, 라우팅 메서드 시그니처를 `BrokerAccountRef` 기반으로 바꾼다:

```java
package com.kista.broker.application.service;

import com.kista.broker.application.port.output.BrokerAdapterPort;
import com.kista.broker.domain.model.BrokerAccountRef;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

// 증권사 어댑터 레지스트리 — BrokerAccountRef.broker()로 BrokerAdapterPort 조회 후 Capability 캐스팅
@Slf4j
@Component
public class BrokerAdapterRegistry {

    private final Map<BrokerAccountRef.Broker, BrokerAdapterPort> registry;

    BrokerAdapterRegistry(List<BrokerAdapterPort> adapters) {
        registry = adapters.stream()
                .collect(Collectors.toMap(BrokerAdapterPort::supports, Function.identity()));
        log.info("BrokerAdapterRegistry 초기화: {}", registry.keySet());
    }

    public <T> T require(BrokerAccountRef account, Class<T> capability) {
        BrokerAdapterPort adapter = getAdapter(account);
        if (!capability.isInstance(adapter)) {
            throw new IllegalArgumentException(
                    account.broker() + " 브로커는 " + capability.getSimpleName() + "를 지원하지 않습니다");
        }
        return capability.cast(adapter);
    }

    public <T> Optional<T> find(BrokerAccountRef account, Class<T> capability) {
        BrokerAdapterPort adapter = registry.get(account.broker());
        if (adapter == null || !capability.isInstance(adapter)) return Optional.empty();
        return Optional.of(capability.cast(adapter));
    }

    private BrokerAdapterPort getAdapter(BrokerAccountRef account) {
        BrokerAdapterPort adapter = registry.get(account.broker());
        if (adapter == null) {
            throw new IllegalArgumentException("지원하지 않는 증권사: " + account.broker());
        }
        return adapter;
    }
}
```

`BrokerConnectionTesters.java` — `of(Account.Broker broker)`를 `of(BrokerAccountRef.Broker broker)`로 교체(파라미터 타입만 변경, 로직 동일).

- [ ] **Step 4: KIS/Toss/Mock 어댑터 구현체 갱신**

각 구현 클래스의 메서드 시그니처에서 `Account account` 파라미터를 `BrokerAccountRef account`로 교체. 내부에서 `account.id()`/`account.appKey()`/`account.secretKey()`/`account.accountNo()`/`account.brokerAccountCode()` 호출은 필드명이 동일하므로 **본문 코드는 변경 불필요**(레코드 필드명이 그대로 일치). `import com.kista.domain.model.account.Account;`를 `import com.kista.broker.domain.model.BrokerAccountRef;`로 교체. `SellableQuantity` import도 `com.kista.broker.domain.model.SellableQuantity`로 교체.

영향 파일: `KisAuthApi`/`KisBrokerAdapter`/`KisHttpClient`/`KisOrderApi`/`KisPriceApi`/`KisTradingApi`(KIS 6개), `TossAuthApi`/`TossBrokerAdapter`/`TossHoldingsApi`/`TossHttpClient`/`TossMarketApi`/`TossOrderApi`(Toss 6개), `MockAuthApi`/`MockBrokerAdapter`(Mock 2개) — 전부 `Account`→`BrokerAccountRef` 기계적 타입 치환.

`KisHttpClient.splitAccountNo(account.accountNo())` 등 계좌번호 파싱 로직은 `String` 파라미터라 무관.

- [ ] **Step 5: 호출부 20개 파일 매핑 추가**

아래 파일 각각에 `Account`를 `BrokerAccountRef`로 변환하는 인라인 호출을 추가한다. 패턴은 공통 — `registry.require(account, X.class)` → `registry.require(toBrokerRef(account), X.class)`이고, 파일당 아래 헬퍼 하나를 클래스 하단에 추가:

```java
// broker 모듈 순환 방지 — Account → BrokerAccountRef 변환 (broker는 Account를 직접 참조하지 않음)
// Account.Broker → BrokerAccountRef.Broker는 상수명 byte-identical이라 valueOf(name())으로 매핑
private static BrokerAccountRef toBrokerRef(Account account) {
    return new BrokerAccountRef(
            account.id(), account.appKey(), account.secretKey(),
            account.accountNo(), account.brokerAccountCode(),
            BrokerAccountRef.Broker.valueOf(account.broker().name()));
}
```

(`import com.kista.broker.domain.model.BrokerAccountRef;` 추가 필요)

영향 파일 20개(`registry.require`/`registry.find`/`connectionTesters.of` 호출 지점, Task 3.5 작성 시점 실측):
- `src/main/java/com/kista/admin/application/service/AdminReorderService.java` (`registry.require(account, BrokerOrderCorrectionPort.class)` 2곳)
- `src/main/java/com/kista/application/service/account/AccountStatisticsService.java` (`registry.require(account, BrokerPricePort.class)` 2곳)
- `src/main/java/com/kista/application/service/account/TossStatisticsService.java` (`registry.require(account, X.class)` 5곳 — CandlePort/StockInfoPort/ExchangeRatePort/BrokerMarketCalendarPort/BrokerAccountPort)
- `src/main/java/com/kista/application/service/account/BrokerStatisticsRouter.java` (`registry.require(account, PortfolioPort.class)`, `registry.require(account, MarginPort.class)`)
- `src/main/java/com/kista/application/service/account/AccountService.java` (`connectionTesters.of(broker)` 2곳 — `broker` 변수는 `Account.Broker` 타입이므로 `connectionTesters.of(BrokerAccountRef.Broker.valueOf(broker.name()))`로 교체, `toBrokerRef` 헬퍼 불필요)
- `src/main/java/com/kista/application/service/strategy/StrategyService.java` (`registry.require(account, BrokerPricePort.class)`, `registry.require(account, MarginPort.class)`)
- `src/main/java/com/kista/trading/application/service/TradingPriceFetcher.java` (6곳, 람다 파라미터 `acc`)
- `src/main/java/com/kista/trading/application/service/TradingReporter.java` (2곳)
- `src/main/java/com/kista/trading/application/service/OrderCancelService.java` (2곳)
- `src/main/java/com/kista/trading/application/service/TradingOrderBudgetAllocator.java` (2곳)
- `src/main/java/com/kista/trading/application/service/CycleRotationService.java` (1곳)
- `src/main/java/com/kista/trading/application/service/StrategyOrderPlanBuilder.java` (1곳)
- `src/main/java/com/kista/trading/application/service/VrReconfigureService.java` (1곳)
- `src/main/java/com/kista/trading/application/service/VrCycleRolloverService.java` (1곳, `ctx.account()`)
- `src/main/java/com/kista/trading/application/service/TradingSellSufficiencySimulator.java` (1곳)
- `src/main/java/com/kista/trading/application/service/ManualTradingService.java` (2곳)
- `src/main/java/com/kista/trading/application/service/PreviewDepositCache.java` (1곳)
- `src/main/java/com/kista/trading/application/service/TradingOrderExecutor.java` (1곳)

각 파일에서 람다(`TradingPriceFetcher`의 `(t, acc) -> registry.require(acc, ...)`) 패턴은 람다 파라미터 `acc`가 이미 `Account`이므로 `(t, acc) -> registry.require(toBrokerRef(acc), ...)`로 교체.

- [ ] **Step 6: 컴파일 + 어댑터/호출부 테스트 갱신**

```bash
./gradlew compileJava compileTestJava 2>&1 | grep -E "error:" | head -80
```

컴파일 에러가 대량으로 날 것 — 대부분 테스트 파일의 mock stub이 `Account`를 여전히 넘기는 경우다. `src/test/java/com/kista/broker/adapter/out/{kis,toss,mock}/*.java`와 호출부 20개의 대응 테스트 파일에서, `Account` 픽스처를 만드는 부분은 그대로 두고 `registry.require(...)`/포트 메서드 호출부에 넘기는 인자만 `toBrokerRef(account)` 변환을 거치도록(또는 테스트가 포트를 직접 mock하는 경우 `BrokerAccountRef` 픽스처를 새로 만들어 stub) 수정. 각 파일이 컴파일 에러로 드러나는 대로 개별 처리 — 파일 수가 많아 일괄 sed로 처리 불가능한 케이스가 많다(Account 픽스처 구성 방식이 파일마다 다름).

```bash
./gradlew compileJava compileTestJava 2>&1 | grep -E "error:|FAILED"
```
Expected: 에러 없음.

- [ ] **Step 7: 전체 broker + 호출부 테스트**

```bash
./gradlew test --tests 'com.kista.broker.*' 2>&1 | grep -E "FAILED|BUILD"
./gradlew test --tests 'com.kista.trading.application.service.*' --tests 'com.kista.admin.application.service.AdminReorderServiceTest' --tests 'com.kista.application.service.account.*' --tests 'com.kista.application.service.strategy.StrategyServiceTest' 2>&1 | grep -E "FAILED|BUILD"
```
Expected: `BUILD SUCCESSFUL` 양쪽.

- [ ] **Step 8: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(modulith): broker↔account 순환 사전 해소 — BrokerAccountRef own-type 전환

Task 4(account 물리 이전) 1차 실행에서 ApplicationModules.verify()가
발견한 account→broker(BrokerConnectionTesters 직접 호출)↔broker→account
(11개 포트+KIS/Toss/Mock 어댑터 176개 이상 지점이 Account 직접 참조)
순환을 broker 소유 BrokerAccountRef/SellableQuantity own-type 신설로
해소(broker↔trading 디커플링, Direction/OrderType 패턴과 동일).
Account→BrokerAccountRef 변환은 각 호출부(trading/admin/stats/account)
20개 파일이 담당 — broker 모듈 코드는 Account를 전혀 참조하지 않는다.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: account 물리 이전 — domain/application/adapter 이동 + 모듈 선언

> **배경:** Task 1~2·3.5로 순환을 사전 해소했으니 이제 실제 패키지를 옮긴다. admin/privacy/user 선례와 동일하게 domain+application을 먼저 옮기고 adapter를 옮긴 뒤, 마지막에 `@ApplicationModule` 선언 + `verify()` 게이트를 통과시킨다. 이 태스크가 이 계획에서 가장 크고 실패 시 즉시 멈춰야 하는 태스크다. **이 태스크는 이미 한 번 실행되어 Step 1~9까지 정상 완료됐으나 Step 10의 `verify()`에서 `account↔broker` 순환이 발견돼 BLOCKED, 커밋 없이 `git reset --hard`로 되돌려졌다** — Task 3.5가 그 순환을 해소하는 태스크로 신설됐다. 이번 재실행에서는 Task 3.5가 이미 broker 11개 포트를 `BrokerAccountRef` 기반으로 바꿔놓았으므로, Step 3(전 소비 파일 import 치환)이 처리할 `AccountService.java`의 `BrokerConnectionTesters` 관련 import는 이미 Task 3.5에서 `toBrokerRef` 헬퍼와 함께 정리돼 있다 — 이 태스크는 패키지 경로만 옮기면 되고 broker 관련 로직은 다시 손댈 필요 없다.

**Files:**
- Move: `src/main/java/com/kista/domain/model/account/{Account,RegisterAccountCommand,UpdateAccountCommand,SellableQuantity,AccountNumberMasker}.java` → `src/main/java/com/kista/account/domain/model/`
- Move: `src/main/java/com/kista/application/usecase/AccountUseCase.java` → `src/main/java/com/kista/account/application/usecase/`
- Move: `src/main/java/com/kista/application/port/output/AccountPort.java` → `src/main/java/com/kista/account/application/port/output/`
- Move: `src/main/java/com/kista/application/port/output/BrokerEnabledPort.java` → `src/main/java/com/kista/account/application/port/output/`
- Move: `src/main/java/com/kista/application/event/AccountDeletedEvent.java` → `src/main/java/com/kista/account/application/event/`
- Move: `src/main/java/com/kista/application/service/account/AccountService.java` → `src/main/java/com/kista/account/application/service/`
- Move: `src/main/java/com/kista/adapter/in/web/AccountController.java` → `src/main/java/com/kista/account/adapter/in/web/`
- Move: `src/main/java/com/kista/adapter/in/web/dto/{AccountRequest,AccountResponse,TestConnectionRequest}.java` → `src/main/java/com/kista/account/adapter/in/web/dto/`
- Move: `src/main/java/com/kista/adapter/out/persistence/account/{AccountEntity,AccountJpaRepository,AccountPersistenceAdapter}.java` → `src/main/java/com/kista/account/adapter/out/persistence/`
- Create: `src/main/java/com/kista/account/package-info.java`
- Create: `src/main/java/com/kista/account/domain/model/package-info.java`
- Create: `src/main/java/com/kista/account/application/usecase/package-info.java`
- Create: `src/main/java/com/kista/account/application/port/output/package-info.java`
- Create: `src/main/java/com/kista/account/application/event/package-info.java`
- Move (test 동반): `src/test/java/com/kista/adapter/in/web/AccountControllerTest.java` → `src/test/java/com/kista/account/adapter/in/web/`
- Move (test 동반): `src/test/java/com/kista/adapter/out/persistence/account/AccountPersistenceAdapterTest.java` → `src/test/java/com/kista/account/adapter/out/persistence/`
- Move (test 동반): `src/test/java/com/kista/application/service/account/AccountServiceTest.java` → `src/test/java/com/kista/account/application/service/`
- Modify (import 경로 갱신만): Task 3에서 색출한 전체 소비 파일

**Interfaces:**
- Produces: `com.kista.account.domain.model.Account`("domain"), `com.kista.account.application.usecase.AccountUseCase`("usecase"), `com.kista.account.application.port.output.{AccountPort,BrokerEnabledPort}`("port"), `com.kista.account.application.event.AccountDeletedEvent`("event") — 이후 모든 소비자가 이 경로를 참조.
- Consumes: 없음(account는 리프 모듈).

- [ ] **Step 1: domain/model 이동**

```bash
mkdir -p src/main/java/com/kista/account/domain/model
git mv src/main/java/com/kista/domain/model/account/Account.java src/main/java/com/kista/account/domain/model/Account.java
git mv src/main/java/com/kista/domain/model/account/RegisterAccountCommand.java src/main/java/com/kista/account/domain/model/RegisterAccountCommand.java
git mv src/main/java/com/kista/domain/model/account/UpdateAccountCommand.java src/main/java/com/kista/account/domain/model/UpdateAccountCommand.java
git mv src/main/java/com/kista/domain/model/account/SellableQuantity.java src/main/java/com/kista/account/domain/model/SellableQuantity.java
git mv src/main/java/com/kista/domain/model/account/AccountNumberMasker.java src/main/java/com/kista/account/domain/model/AccountNumberMasker.java

for f in src/main/java/com/kista/account/domain/model/*.java; do
  perl -pi -e 's/^package com\.kista\.domain\.model\.account;/package com.kista.account.domain.model;/' "$f"
done
```

`src/main/java/com/kista/account/domain/model/package-info.java`:
```java
// account 모듈의 공개 계약 일부 — 불변 값 객체(Account/Command/SellableQuantity/AccountNumberMasker). "domain" 이름으로 공개된다.
@org.springframework.modulith.NamedInterface("domain")
package com.kista.account.domain.model;
```

- [ ] **Step 2: application 계층 이동**

```bash
mkdir -p src/main/java/com/kista/account/application/usecase
mkdir -p src/main/java/com/kista/account/application/port/output
mkdir -p src/main/java/com/kista/account/application/event
mkdir -p src/main/java/com/kista/account/application/service

git mv src/main/java/com/kista/application/usecase/AccountUseCase.java src/main/java/com/kista/account/application/usecase/AccountUseCase.java
git mv src/main/java/com/kista/application/port/output/AccountPort.java src/main/java/com/kista/account/application/port/output/AccountPort.java
git mv src/main/java/com/kista/application/port/output/BrokerEnabledPort.java src/main/java/com/kista/account/application/port/output/BrokerEnabledPort.java
git mv src/main/java/com/kista/application/event/AccountDeletedEvent.java src/main/java/com/kista/account/application/event/AccountDeletedEvent.java
git mv src/main/java/com/kista/application/service/account/AccountService.java src/main/java/com/kista/account/application/service/AccountService.java

perl -pi -e 's/^package com\.kista\.application\.usecase;/package com.kista.account.application.usecase;/' src/main/java/com/kista/account/application/usecase/AccountUseCase.java
perl -pi -e 's/^package com\.kista\.application\.port\.output;/package com.kista.account.application.port.output;/' src/main/java/com/kista/account/application/port/output/AccountPort.java src/main/java/com/kista/account/application/port/output/BrokerEnabledPort.java
perl -pi -e 's/^package com\.kista\.application\.event;/package com.kista.account.application.event;/' src/main/java/com/kista/account/application/event/AccountDeletedEvent.java
perl -pi -e 's/^package com\.kista\.application\.service\.account;/package com.kista.account.application.service;/' src/main/java/com/kista/account/application/service/AccountService.java
perl -pi -e 's/^import com\.kista\.domain\.model\.account\./import com.kista.account.domain.model./g' src/main/java/com/kista/account/application/usecase/AccountUseCase.java src/main/java/com/kista/account/application/port/output/AccountPort.java src/main/java/com/kista/account/application/port/output/BrokerEnabledPort.java src/main/java/com/kista/account/application/service/AccountService.java
```

`AccountService.java`의 나머지 import(`com.kista.application.port.output.AccountPort` → `com.kista.account.application.port.output.AccountPort`, `com.kista.application.port.output.BrokerEnabledPort` → `com.kista.account.application.port.output.BrokerEnabledPort`, `com.kista.application.usecase.AccountUseCase` → `com.kista.account.application.usecase.AccountUseCase`, `com.kista.application.event.AccountDeletedEvent` → `com.kista.account.application.event.AccountDeletedEvent`)도 동일하게 치환:
```bash
perl -pi -e '
  s/^import com\.kista\.application\.port\.output\.AccountPort;/import com.kista.account.application.port.output.AccountPort;/;
  s/^import com\.kista\.application\.port\.output\.BrokerEnabledPort;/import com.kista.account.application.port.output.BrokerEnabledPort;/;
  s/^import com\.kista\.application\.usecase\.AccountUseCase;/import com.kista.account.application.usecase.AccountUseCase;/;
  s/^import com\.kista\.application\.event\.AccountDeletedEvent;/import com.kista.account.application.event.AccountDeletedEvent;/;
' src/main/java/com/kista/account/application/service/AccountService.java
```

`src/main/java/com/kista/account/application/usecase/package-info.java`:
```java
// account 모듈의 공개 계약 일부 — UseCase 인터페이스. "usecase" 이름으로 공개된다.
@org.springframework.modulith.NamedInterface("usecase")
package com.kista.account.application.usecase;
```

`src/main/java/com/kista/account/application/port/output/package-info.java`:
```java
// account 모듈의 공개 계약 일부 — *Port 접미사 출력 포트. "port" 이름으로 공개된다.
// AccountPort/BrokerEnabledPort(BrokerEnabledPort는 admin의 RuntimeSettingsService가 구현하는 포트 역전).
@org.springframework.modulith.NamedInterface("port")
package com.kista.account.application.port.output;
```

`src/main/java/com/kista/account/application/event/package-info.java`:
```java
// account 모듈의 공개 계약 일부 — 도메인 이벤트. "event" 이름으로 공개된다.
// AccountDeletedEvent — strategy-config가 구독해 cascade soft-delete를 수행한다.
@org.springframework.modulith.NamedInterface("event")
package com.kista.account.application.event;
```

- [ ] **Step 3: 전 소비 파일 import 경로 일괄 치환**

Task 3 Step 1에서 색출한 파일 목록을 대상으로 실행:

```bash
FILES=$(git grep -l "com\.kista\.domain\.model\.account\.\|com\.kista\.application\.port\.output\.AccountPort\|com\.kista\.application\.port\.output\.BrokerEnabledPort\|com\.kista\.application\.usecase\.AccountUseCase\|com\.kista\.application\.event\.AccountDeletedEvent" -- src/main/java src/test/java)
for f in $FILES; do
  perl -pi -e '
    s/com\.kista\.domain\.model\.account\./com.kista.account.domain.model./g;
    s/com\.kista\.application\.port\.output\.AccountPort/com.kista.account.application.port.output.AccountPort/g;
    s/com\.kista\.application\.port\.output\.BrokerEnabledPort/com.kista.account.application.port.output.BrokerEnabledPort/g;
    s/com\.kista\.application\.usecase\.AccountUseCase/com.kista.account.application.usecase.AccountUseCase/g;
    s/com\.kista\.application\.event\.AccountDeletedEvent/com.kista.account.application.event.AccountDeletedEvent/g;
  ' "$f"
done
```

와일드카드 import(`import com.kista.domain.model.account.*;` 등, Task 3 Step 3에서 색출됨)를 쓰는 파일이 있으면 위 치환으로 안 잡히므로 개별 확인 후 명시 import로 전환.

- [ ] **Step 4: 컴파일하며 잔여 오류 처리**

```bash
./gradlew compileJava compileTestJava 2>&1 | grep -E "error:" | head -50
```
남은 `cannot find symbol` 대부분은 개별 클래스 import(`Account.Broker` 등 nested enum 참조)가 패키지 이동으로 깨진 것 — 파일별로 `import com.kista.account.domain.model.Account;` 확인 후 누락분 추가.

- [ ] **Step 5: adapter 계층 이동**

```bash
mkdir -p src/main/java/com/kista/account/adapter/in/web/dto
mkdir -p src/main/java/com/kista/account/adapter/out/persistence

git mv src/main/java/com/kista/adapter/in/web/AccountController.java src/main/java/com/kista/account/adapter/in/web/AccountController.java
git mv src/main/java/com/kista/adapter/in/web/dto/AccountRequest.java src/main/java/com/kista/account/adapter/in/web/dto/AccountRequest.java
git mv src/main/java/com/kista/adapter/in/web/dto/AccountResponse.java src/main/java/com/kista/account/adapter/in/web/dto/AccountResponse.java
git mv src/main/java/com/kista/adapter/in/web/dto/TestConnectionRequest.java src/main/java/com/kista/account/adapter/in/web/dto/TestConnectionRequest.java
git mv src/main/java/com/kista/adapter/out/persistence/account/AccountEntity.java src/main/java/com/kista/account/adapter/out/persistence/AccountEntity.java
git mv src/main/java/com/kista/adapter/out/persistence/account/AccountJpaRepository.java src/main/java/com/kista/account/adapter/out/persistence/AccountJpaRepository.java
git mv src/main/java/com/kista/adapter/out/persistence/account/AccountPersistenceAdapter.java src/main/java/com/kista/account/adapter/out/persistence/AccountPersistenceAdapter.java

perl -pi -e 's/^package com\.kista\.adapter\.in\.web;/package com.kista.account.adapter.in.web;/' src/main/java/com/kista/account/adapter/in/web/AccountController.java
perl -pi -e 's/^package com\.kista\.adapter\.in\.web\.dto;/package com.kista.account.adapter.in.web.dto;/' src/main/java/com/kista/account/adapter/in/web/dto/*.java
perl -pi -e 's/^package com\.kista\.adapter\.out\.persistence\.account;/package com.kista.account.adapter.out.persistence;/' src/main/java/com/kista/account/adapter/out/persistence/*.java

perl -pi -e '
  s/import com\.kista\.adapter\.in\.web\.dto\.AccountRequest;/import com.kista.account.adapter.in.web.dto.AccountRequest;/;
  s/import com\.kista\.adapter\.in\.web\.dto\.AccountResponse;/import com.kista.account.adapter.in.web.dto.AccountResponse;/;
  s/import com\.kista\.adapter\.in\.web\.dto\.TestConnectionRequest;/import com.kista.account.adapter.in.web.dto.TestConnectionRequest;/;
' src/main/java/com/kista/account/adapter/in/web/AccountController.java

perl -pi -e '
  s/import com\.kista\.domain\.model\.account\./import com.kista.account.domain.model./g;
  s/import com\.kista\.application\.usecase\.AccountUseCase;/import com.kista.account.application.usecase.AccountUseCase;/;
' src/main/java/com/kista/account/adapter/in/web/AccountController.java src/main/java/com/kista/account/adapter/in/web/dto/*.java

perl -pi -e '
  s/import com\.kista\.domain\.model\.account\./import com.kista.account.domain.model./g;
  s/import com\.kista\.application\.port\.output\.AccountPort;/import com.kista.account.application.port.output.AccountPort;/;
' src/main/java/com/kista/account/adapter/out/persistence/*.java
```

`AccountEntity.java`의 `import com.kista.adapter.out.persistence.BaseAuditEntity;`는 그대로 유지(공통 인프라, 레거시 최상위 잔류 클래스 — 다른 8개 모듈도 동일하게 이 클래스를 참조).

`AccountJpaRepository.java`는 `AccountEntity` 같은 패키지 참조라 import 불필요(같은 디렉토리).

- [ ] **Step 6: `@Table` 스키마 확인**

`AccountEntity.java`의 `@Table(name = "accounts", schema = "kista")` — 스키마 이름은 패키지 이동과 무관하게 그대로 유지(테이블 위치 변경 아님, DB 스키마는 도메인 성격 기준이지 Java 패키지 기준이 아니므로 이 이전으로 변경하지 않는다).

- [ ] **Step 7: 테스트 파일 이동**

```bash
mkdir -p src/test/java/com/kista/account/adapter/in/web
mkdir -p src/test/java/com/kista/account/adapter/out/persistence
mkdir -p src/test/java/com/kista/account/application/service

git mv src/test/java/com/kista/adapter/in/web/AccountControllerTest.java src/test/java/com/kista/account/adapter/in/web/AccountControllerTest.java
git mv src/test/java/com/kista/adapter/out/persistence/account/AccountPersistenceAdapterTest.java src/test/java/com/kista/account/adapter/out/persistence/AccountPersistenceAdapterTest.java
git mv src/test/java/com/kista/application/service/account/AccountServiceTest.java src/test/java/com/kista/account/application/service/AccountServiceTest.java

perl -pi -e 's/^package com\.kista\.adapter\.in\.web;/package com.kista.account.adapter.in.web;/' src/test/java/com/kista/account/adapter/in/web/AccountControllerTest.java
perl -pi -e 's/^package com\.kista\.adapter\.out\.persistence\.account;/package com.kista.account.adapter.out.persistence;/' src/test/java/com/kista/account/adapter/out/persistence/AccountPersistenceAdapterTest.java
perl -pi -e 's/^package com\.kista\.application\.service\.account;/package com.kista.account.application.service;/' src/test/java/com/kista/account/application/service/AccountServiceTest.java

for f in src/test/java/com/kista/account/adapter/in/web/AccountControllerTest.java src/test/java/com/kista/account/adapter/out/persistence/AccountPersistenceAdapterTest.java src/test/java/com/kista/account/application/service/AccountServiceTest.java; do
  perl -pi -e '
    s/import com\.kista\.domain\.model\.account\./import com.kista.account.domain.model./g;
    s/import com\.kista\.application\.port\.output\.AccountPort;/import com.kista.account.application.port.output.AccountPort;/;
    s/import com\.kista\.application\.port\.output\.BrokerEnabledPort;/import com.kista.account.application.port.output.BrokerEnabledPort;/;
    s/import com\.kista\.application\.usecase\.AccountUseCase;/import com.kista.account.application.usecase.AccountUseCase;/;
    s/import com\.kista\.application\.event\.AccountDeletedEvent;/import com.kista.account.application.event.AccountDeletedEvent;/;
    s/import com\.kista\.adapter\.in\.web\.AccountController;/import com.kista.account.adapter.in.web.AccountController;/;
  ' "$f"
done
```

`AccountControllerTest.java`는 `@WebMvcTest(AccountController.class)`가 이미 파일 내에서 클래스명만 참조하므로(같은 파일 상단 import) 위 치환으로 커버됨.

- [ ] **Step 8: `DomainFixtures` import 경로 갱신**

```bash
perl -pi -e 's/import com\.kista\.domain\.model\.account\.Account;/import com.kista.account.domain.model.Account;/' src/test/java/com/kista/support/DomainFixtures.java
```

- [ ] **Step 9: 전체 컴파일**

```bash
./gradlew compileJava compileTestJava 2>&1 | grep -E "error:|FAILED"
```
Expected: 에러 없음. 남으면 파일별 import 누락 — 위 패턴과 동일하게 개별 처리.

```bash
git grep -n "com\.kista\.domain\.model\.account\.\|com\.kista\.application\.service\.account\.AccountService\b\|com\.kista\.application\.port\.output\.AccountPort\b\|com\.kista\.application\.port\.output\.BrokerEnabledPort\b\|com\.kista\.application\.usecase\.AccountUseCase\b\|com\.kista\.application\.event\.AccountDeletedEvent\b\|com\.kista\.adapter\.in\.web\.AccountController\b\|com\.kista\.adapter\.out\.persistence\.account\." -- src/main/java src/test/java
```
Expected: 출력 없음(전부 `com.kista.account.*`로 치환 완료).

- [ ] **Step 10: 모듈 선언 + `verify()` 게이트**

`src/main/java/com/kista/account/package-info.java`:
```java
// account 애그리게이트(계좌 자격증명·브로커 연결) 모듈 —
// domain.model·application.{usecase,port.output,event}만 공개 계약, application.service·adapter 전체 internal.
@org.springframework.modulith.ApplicationModule
package com.kista.account;
```

```bash
./gradlew test --tests 'com.kista.architecture.*' 2>&1 | tail -60
```

**여기서 `verify()`가 실패하면(예측 못한 순환 보고) 즉시 멈추고 실패 메시지 전체를 그대로 사용자에게 보고한다. 추측으로 코드를 고치지 않는다.** 성공하면 다음 Step으로.

- [ ] **Step 11: 전체 테스트**

```bash
./gradlew test 2>&1 | grep -E "FAILED|BUILD"
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 12: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(modulith): account 모듈 선언(@ApplicationModule CLOSED)

레거시 최상위(com.kista.domain/application/adapter)의 account
애그리게이트(계좌 자격증명·브로커 연결)를 com.kista.account
Spring Modulith CLOSED 모듈로 물리 이전. domain.model·
application.{usecase,port.output,event} 4개 NamedInterface 공개
— application.service·adapter는 internal.

Task 1~2에서 사전 해소한 account↔strategy-config(cascade 이벤트
전환)·admin↔account(BrokerEnabledPort 포트 역전) 순환 위에서
ApplicationModules.verify() 통과 확인.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: 문서 갱신 (architecture.md/constraints.md)

> **배경:** 다른 8개 모듈 이전 때와 동일하게, 물리 이전 완료 후 프로젝트 문서를 실제 코드 상태에 맞춰 갱신한다. 문서 전용 태스크라 리뷰어 검수는 생략 가능(전역 CLAUDE.md 규칙 — 문서 전용 변경 예외).

**Files:**
- Modify: `docs/agents/architecture.md`
- Modify: `docs/agents/constraints.md`
- Modify: `docs/superpowers/specs/2026-08-31-legacy-module-catalog-design.md` (착수 순서 각주 갱신)

- [ ] **Step 1: `architecture.md` "Spring Modulith 점진 도입" 절에 account 추가**

기존 9개 모듈(finance→...→user) 서술 패턴을 그대로 따라 account를 10번째 CLOSED 모듈로 추가. 새 최상위 패키지 트리(`com.kista.account/`) 설명 블록을 market/privacy 등과 동일한 형식으로 삽입 — domain/model, application/{usecase,port/output,event}, adapter/{in,out} 각 파일 목록과 NamedInterface 공개 여부, `BrokerEnabledPort` 포트 역전 설명 포함.

- [ ] **Step 2: `constraints.md` "Spring Modulith 이전 중 신규 파일 배치" 절에 account 항목 추가**

기존 8개 항목과 동일한 형식으로: "account 애그리게이트(계좌 자격증명·브로커 연결)는 `com.kista.account`로 이미 옮겨졌다 — 신규 관련 코드도 레거시 최상위가 아닌 `com.kista.account` 안에 추가. `domain/model`이 "domain"으로, `application/usecase`가 "usecase"로, `application/port/output`이 "port"로, `application/event`가 "event"로 NamedInterface 공개 — `application/service`·`adapter/*`는 비공개(internal). `AccountStatisticsService`/`TossStatisticsService`/`BrokerStatisticsRouter`는 실질 stats 관심사라 account로 옮기지 않고 레거시 잔류(향후 stats 재배치 또는 strategy-config 이전 때 정리 대상)."

- [ ] **Step 3: `2026-08-31-legacy-module-catalog-design.md` 착수 순서 각주 갱신**

"4단계" 각주에 `[^8]` 형태로 추가: account 완료(날짜, 커밋 범위) + strategy-config는 trading과의 원자적 트랜잭션 결합(`CycleSnapshotCreator.reconfigureVrCycle`)이 실측으로 드러나 별도 스펙 필요하다는 사실을 다음 세션을 위해 명시.

- [ ] **Step 4: 커밋**

```bash
git add docs/agents/architecture.md docs/agents/constraints.md docs/superpowers/specs/2026-08-31-legacy-module-catalog-design.md
git commit -m "$(cat <<'EOF'
docs(modulith): account 모듈 이전 반영 — architecture.md/constraints.md/스펙 갱신

com.kista.account CLOSED 모듈 이전 완료 반영. strategy-config는
trading과의 원자적 트랜잭션 결합(CycleSnapshotCreator.
reconfigureVrCycle)으로 별도 스펙 필요함을 명시.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review 메모 (계획 작성자 기록)

- **스펙 커버리지**: 스펙의 "해소 방식" 7개 항목 중 notify own-type(2)와 trading own-type(3), 통계 서비스 재배치(5), `VrStrategyLifecycle` 재배치(6), sharedkernel enum 이관(7)은 계획 작성 중 재실측 결과 **불필요 또는 스코프 아웃으로 판정**해 태스크에서 제외했다. **broker own-type(1)은 최초 판정이 틀렸음이 Task 4 1차 실행의 `verify()` 게이트에서 드러나 Task 3.5로 부활시켰다** — 스펙의 "해소 방식" 절이 원래 옳았던 항목을 계획 작성 시점 재확인이 성급하게 기각한 사례. cascade 이벤트 전환(4)은 Task 1, 신규 발견된 admin↔account 순환은 Task 2, 신규 발견된 broker↔account 순환은 Task 3.5로 반영.
- **타입 일관성**: `AccountDeletedEvent`/`BrokerEnabledPort`는 Task 1~2에서 레거시 위치에 신설 후 Task 4에서 물리 이동 — 이름과 시그니처가 태스크 간 일관됨을 확인. `BrokerAccountRef`는 Task 3.5에서 broker 소유로 신설, Task 4는 이를 건드리지 않고 패키지 경로만 옮긴다.
- **플레이스홀더 스캔**: "TBD"/"추후" 등 없음. 모든 Step에 실행 가능한 명령/코드 포함.
- **Task 4 재실행 이력**: 1차 실행(Steps 1-9 완료, Step 10 `verify()`에서 `account↔broker` 순환 발견, BLOCKED, 커밋 없이 revert)에 대응해 Task 3.5를 삽입했다. Task 4의 브리핑·File Structure 자체는 broker 로직을 건드리지 않는 순수 패키지 이동이라 변경 없이 재사용 가능(Task 4 배경 절에 이력 명시).
