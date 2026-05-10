# Extract Strategies Table Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `accounts` 테이블의 `strategy`/`strategy_status` 컬럼을 별도 `strategies` 테이블로 분리하여 Account:Strategy = 1:N 구조를 준비한다.

**Architecture:** 도메인 모델(`Account` record)과 서비스 레이어는 변경 없음. 변경은 persistence 레이어에만 국한된다. `StrategyEntity`를 신규 생성하고, `AccountPersistenceAdapter`가 두 테이블을 조합해 `Account` 도메인 객체를 반환한다.

**Tech Stack:** Java 21, Spring Boot 3, Spring Data JPA, Flyway, PostgreSQL, JUnit 5, Mockito

---

## 변경 파일 맵

| 구분 | 파일 |
|------|------|
| **신규** | `src/main/resources/db/migration/V11__extract_strategies_table.sql` |
| **신규** | `src/main/java/com/kista/adapter/out/persistence/StrategyEntity.java` |
| **신규** | `src/main/java/com/kista/adapter/out/persistence/StrategyJpaRepository.java` |
| **신규** | `src/test/java/com/kista/adapter/out/persistence/AccountPersistenceAdapterTest.java` |
| **수정** | `src/main/java/com/kista/adapter/out/persistence/AccountEntity.java` |
| **수정** | `src/main/java/com/kista/adapter/out/persistence/AccountJpaRepository.java` |
| **수정** | `src/main/java/com/kista/adapter/out/persistence/AccountPersistenceAdapter.java` |

**변경 없음:** `Account.java` (도메인 모델), `AccountService.java`, `AccountController.java`, `AccountRequest.java`, `AccountResponse.java`, `AccountServiceTest.java`, `AccountControllerTest.java`

---

## Task 1: Flyway V11 마이그레이션 작성

**Files:**
- Create: `src/main/resources/db/migration/V11__extract_strategies_table.sql`

- [ ] **Step 1: 마이그레이션 SQL 파일 작성**

```sql
-- strategies 테이블 생성 (account:strategy = 1:N 대비)
CREATE TABLE strategies (
    id          UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id  UUID            NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    type        strategy_type   NOT NULL,
    status      strategy_status NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE INDEX idx_strategies_account_id ON strategies(account_id);

-- 기존 accounts 데이터를 strategies 테이블로 이전
INSERT INTO strategies (account_id, type, status, created_at, updated_at)
SELECT id, strategy, strategy_status, created_at, updated_at
FROM accounts;

-- accounts 테이블에서 strategy 컬럼 제거
ALTER TABLE accounts DROP COLUMN strategy;
ALTER TABLE accounts DROP COLUMN strategy_status;
```

- [ ] **Step 2: Flyway 마이그레이션 동작 확인**

```bash
./gradlew compileJava
```

Expected: BUILD SUCCESSFUL (마이그레이션 파일 자체는 SQL이므로 컴파일 오류 없음)

> **참고:** 실제 마이그레이션은 Task 4 이후 앱 기동 시 자동 실행됨. 지금은 파일만 작성.

- [ ] **Step 3: 커밋**

```bash
git add src/main/resources/db/migration/V11__extract_strategies_table.sql
git commit -m "chore: V11 마이그레이션 - strategies 테이블 분리"
```

---

## Task 2: StrategyEntity + StrategyJpaRepository 신규 생성

**Files:**
- Create: `src/main/java/com/kista/adapter/out/persistence/StrategyEntity.java`
- Create: `src/main/java/com/kista/adapter/out/persistence/StrategyJpaRepository.java`

- [ ] **Step 1: StrategyEntity 작성**

```java
package com.kista.adapter.out.persistence;

import com.kista.domain.model.Strategy;
import com.kista.domain.model.StrategyStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

@Entity
@Table(name = "strategies")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class StrategyEntity extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "account_id", nullable = false, columnDefinition = "UUID")
    private UUID accountId; // FK → accounts.id (ON DELETE CASCADE)

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "type", nullable = false, length = 20)
    private Strategy type; // 매매 전략 종류 (INFINITE, PRIVACY)

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false, length = 10)
    private StrategyStatus status; // 실행 상태 (ACTIVE, PAUSED)
}
```

- [ ] **Step 2: StrategyJpaRepository 작성**

```java
package com.kista.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface StrategyJpaRepository extends JpaRepository<StrategyEntity, UUID> {

    // 계좌 ID로 전략 조회 (현재 1:1 — 향후 1:N 전환 시 findAllByAccountId로 교체)
    Optional<StrategyEntity> findByAccountId(UUID accountId);
}
```

- [ ] **Step 3: 컴파일 확인**

```bash
./gradlew compileJava
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 커밋**

```bash
git add src/main/java/com/kista/adapter/out/persistence/StrategyEntity.java \
        src/main/java/com/kista/adapter/out/persistence/StrategyJpaRepository.java
git commit -m "feat: StrategyEntity + StrategyJpaRepository 추가"
```

---

## Task 3: AccountEntity / AccountJpaRepository 수정

**Files:**
- Modify: `src/main/java/com/kista/adapter/out/persistence/AccountEntity.java`
- Modify: `src/main/java/com/kista/adapter/out/persistence/AccountJpaRepository.java`

- [ ] **Step 1: AccountEntity에서 strategy 필드 제거**

`AccountEntity.java`에서 아래 블록을 삭제:

```java
// 삭제할 import
import com.kista.domain.model.Strategy;
import com.kista.domain.model.StrategyStatus;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
```

```java
// 삭제할 필드 (2개)
@Enumerated(EnumType.STRING)
@JdbcTypeCode(SqlTypes.NAMED_ENUM)
@Column(nullable = false, length = 20)
private Strategy strategy;

@Enumerated(EnumType.STRING)
@JdbcTypeCode(SqlTypes.NAMED_ENUM)
@Column(name = "strategy_status", nullable = false, length = 10)
private StrategyStatus strategyStatus;
```

- [ ] **Step 2: AccountJpaRepository의 findAllActive 쿼리 수정**

`strategy_status = 'ACTIVE'` 필터를 accounts 대신 strategies 테이블 JOIN으로 변경:

```java
// ACTIVE 사용자의 ACTIVE 전략을 가진 계좌 전체 조회 (스케줄러용)
@Query(value = """
        SELECT a.* FROM accounts a
        JOIN users u ON a.user_id = u.id
        JOIN strategies s ON s.account_id = a.id
        WHERE u.status = 'ACTIVE' AND s.status = 'ACTIVE'
        """, nativeQuery = true)
List<AccountEntity> findAllActive();
```

- [ ] **Step 3: 컴파일 확인 (AccountPersistenceAdapter 오류 예상)**

```bash
./gradlew compileJava
```

Expected: FAILED — `AccountPersistenceAdapter`에서 `e.getStrategy()`, `e.getStrategyStatus()`, `e.setStrategy()`, `e.setStrategyStatus()` 참조 오류. Task 4에서 수정.

> 이 오류는 예상된 것으로, 계속 진행.

---

## Task 4: AccountPersistenceAdapter 수정 + 테스트 작성

**Files:**
- Modify: `src/main/java/com/kista/adapter/out/persistence/AccountPersistenceAdapter.java`
- Create: `src/test/java/com/kista/adapter/out/persistence/AccountPersistenceAdapterTest.java`

- [ ] **Step 1: 실패 테스트 작성**

```java
package com.kista.adapter.out.persistence;

import com.kista.adapter.out.crypto.AesCryptoService;
import com.kista.domain.model.Account;
import com.kista.domain.model.Strategy;
import com.kista.domain.model.StrategyStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AccountPersistenceAdapter 단위 테스트")
class AccountPersistenceAdapterTest {

    @Mock AccountJpaRepository accountJpaRepository;
    @Mock StrategyJpaRepository strategyJpaRepository;
    @Mock AesCryptoService crypto;
    @InjectMocks AccountPersistenceAdapter adapter;

    private final UUID accountId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // 암호화/복호화 stub
        when(crypto.encrypt(anyString())).thenAnswer(inv -> "enc:" + inv.getArgument(0));
        when(crypto.decrypt(anyString())).thenAnswer(inv -> ((String) inv.getArgument(0)).replace("enc:", ""));
    }

    private AccountEntity accountEntityWithId(UUID id) {
        AccountEntity e = new AccountEntity();
        e.setId(id);
        e.setUserId(userId);
        e.setNickname("테스트계좌");
        e.setAccountNo("enc:74420614");
        e.setKisAppKey("enc:appKey");
        e.setKisSecretKey("enc:appSecret");
        e.setKisAccountType("01");
        e.setSymbol("SOXL");
        e.setExchangeCode("AMS");
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        return e;
    }

    private StrategyEntity strategyEntity(UUID accId, Strategy type, StrategyStatus status) {
        StrategyEntity s = new StrategyEntity();
        s.setAccountId(accId);
        s.setType(type);
        s.setStatus(status);
        return s;
    }

    @Test
    @DisplayName("신규 계좌 저장 시 StrategyEntity도 함께 생성")
    void save_new_account_creates_strategy() {
        Account newAccount = new Account(null, userId, "테스트계좌",
                "74420614", "appKey", "appSecret", "01",
                Strategy.INFINITE, StrategyStatus.ACTIVE,
                null, null, "SOXL", "AMS", null, null);

        AccountEntity saved = accountEntityWithId(accountId);
        when(accountJpaRepository.save(any())).thenReturn(saved);
        when(strategyJpaRepository.findByAccountId(accountId))
                .thenReturn(Optional.of(strategyEntity(accountId, Strategy.INFINITE, StrategyStatus.ACTIVE)));

        adapter.save(newAccount);

        // StrategyEntity가 저장되었는지 확인
        ArgumentCaptor<StrategyEntity> captor = ArgumentCaptor.forClass(StrategyEntity.class);
        verify(strategyJpaRepository).save(captor.capture());
        assertThat(captor.getValue().getAccountId()).isEqualTo(accountId);
        assertThat(captor.getValue().getType()).isEqualTo(Strategy.INFINITE);
        assertThat(captor.getValue().getStatus()).isEqualTo(StrategyStatus.ACTIVE);
    }

    @Test
    @DisplayName("기존 계좌 저장 시 StrategyEntity status 업데이트")
    void save_existing_account_updates_strategy_status() {
        Account existingAccount = new Account(accountId, userId, "테스트계좌",
                "74420614", "appKey", "appSecret", "01",
                Strategy.INFINITE, StrategyStatus.PAUSED,  // PAUSED로 변경
                null, null, "SOXL", "AMS", Instant.now(), Instant.now());

        AccountEntity entity = accountEntityWithId(accountId);
        StrategyEntity strategy = strategyEntity(accountId, Strategy.INFINITE, StrategyStatus.ACTIVE);

        when(accountJpaRepository.save(any())).thenReturn(entity);
        when(strategyJpaRepository.findByAccountId(accountId))
                .thenReturn(Optional.of(strategy));

        adapter.save(existingAccount);

        // status가 PAUSED로 변경되었는지 확인
        assertThat(strategy.getStatus()).isEqualTo(StrategyStatus.PAUSED);
        verify(strategyJpaRepository).save(strategy);
    }

    @Test
    @DisplayName("toDomain: strategy 정보가 StrategyEntity에서 로드됨")
    void findById_loads_strategy_from_strategies_table() {
        AccountEntity entity = accountEntityWithId(accountId);
        StrategyEntity strategy = strategyEntity(accountId, Strategy.INFINITE, StrategyStatus.ACTIVE);

        when(accountJpaRepository.findById(accountId)).thenReturn(Optional.of(entity));
        when(strategyJpaRepository.findByAccountId(accountId)).thenReturn(Optional.of(strategy));

        Optional<Account> result = adapter.findById(accountId);

        assertThat(result).isPresent();
        assertThat(result.get().strategy()).isEqualTo(Strategy.INFINITE);
        assertThat(result.get().strategyStatus()).isEqualTo(StrategyStatus.ACTIVE);
    }
}
```

- [ ] **Step 2: 테스트 실행 확인 (실패 예상)**

```bash
./gradlew test --tests "com.kista.adapter.out.persistence.AccountPersistenceAdapterTest"
```

Expected: FAILED (컴파일 오류 또는 `@InjectMocks` 주입 실패)

- [ ] **Step 3: AccountPersistenceAdapter 수정**

`AccountPersistenceAdapter.java`를 아래와 같이 수정:

```java
package com.kista.adapter.out.persistence;

import com.kista.adapter.out.crypto.AesCryptoService;
import com.kista.domain.model.Account;
import com.kista.domain.port.out.AccountRepository;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class AccountPersistenceAdapter implements AccountRepository {

    private final AccountJpaRepository jpaRepository;
    private final StrategyJpaRepository strategyJpaRepository;
    private final AesCryptoService crypto;

    @Override
    public List<Account> findByUserId(UUID userId) {
        return jpaRepository.findByUserId(userId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public Optional<Account> findById(UUID id) {
        return jpaRepository.findById(id).map(this::toDomain);
    }

    @Override
    public int countByUserId(UUID userId) {
        return jpaRepository.countByUserId(userId);
    }

    @Override
    public List<Account> findAllActive() {
        return jpaRepository.findAllActive().stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public Account save(Account account) {
        AccountEntity entity = toEntity(account);
        AccountEntity saved = jpaRepository.save(entity);

        if (account.id() == null) {
            // 신규 계좌 - strategy 생성
            StrategyEntity strategyEntity = new StrategyEntity();
            strategyEntity.setAccountId(saved.getId());
            strategyEntity.setType(account.strategy());
            strategyEntity.setStatus(account.strategyStatus());
            strategyJpaRepository.save(strategyEntity);
        } else {
            // 기존 계좌 - strategy status/type 업데이트
            StrategyEntity strategyEntity = strategyJpaRepository
                    .findByAccountId(account.id()).orElseThrow();
            strategyEntity.setType(account.strategy());
            strategyEntity.setStatus(account.strategyStatus());
            strategyJpaRepository.save(strategyEntity);
        }

        return toDomain(saved);
    }

    @Override
    public void delete(UUID id) {
        jpaRepository.deleteById(id); // strategies는 ON DELETE CASCADE로 자동 삭제
    }

    // Account 도메인 모델 → 암호화 후 Entity 변환 (strategy 필드 제외)
    private AccountEntity toEntity(Account a) {
        AccountEntity e = new AccountEntity();
        e.setId(a.id()); // null이면 @GeneratedValue가 UUID 생성
        e.setUserId(a.userId());
        e.setNickname(a.nickname());
        e.setAccountNo(crypto.encrypt(a.accountNo()));
        e.setKisAppKey(crypto.encrypt(a.kisAppKey()));
        e.setKisSecretKey(crypto.encrypt(a.kisSecretKey()));
        e.setKisAccountType(a.kisAccountType());
        e.setTelegramBotToken(a.telegramBotToken() != null ? crypto.encrypt(a.telegramBotToken()) : null);
        e.setTelegramChatId(a.telegramChatId());
        e.setSymbol(a.symbol());
        e.setExchangeCode(a.exchangeCode());
        e.setCreatedAt(a.createdAt()); // null이면 @CreatedDate가 INSERT 시 자동 설정
        return e;
    }

    // Entity → 복호화 후 Account 도메인 모델 변환 (strategy는 strategies 테이블에서 로드)
    private Account toDomain(AccountEntity e) {
        StrategyEntity s = strategyJpaRepository.findByAccountId(e.getId()).orElseThrow();
        return new Account(
                e.getId(), e.getUserId(), e.getNickname(),
                crypto.decrypt(e.getAccountNo()),
                crypto.decrypt(e.getKisAppKey()),
                crypto.decrypt(e.getKisSecretKey()),
                e.getKisAccountType(), s.getType(), s.getStatus(),
                e.getTelegramBotToken() != null ? crypto.decrypt(e.getTelegramBotToken()) : null,
                e.getTelegramChatId(), e.getSymbol(), e.getExchangeCode(),
                e.getCreatedAt(), e.getUpdatedAt()
        );
    }
}
```

- [ ] **Step 4: 테스트 재실행**

```bash
./gradlew test --tests "com.kista.adapter.out.persistence.AccountPersistenceAdapterTest"
```

Expected: PASS (3개 테스트 모두 통과)

- [ ] **Step 5: 전체 테스트 실행**

```bash
./gradlew test
```

Expected: BUILD SUCCESSFUL — 기존 `AccountServiceTest`, `AccountControllerTest` 포함 전체 통과

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/kista/adapter/out/persistence/AccountEntity.java \
        src/main/java/com/kista/adapter/out/persistence/AccountJpaRepository.java \
        src/main/java/com/kista/adapter/out/persistence/AccountPersistenceAdapter.java \
        src/test/java/com/kista/adapter/out/persistence/AccountPersistenceAdapterTest.java
git commit -m "feat: strategies 테이블 분리 - AccountPersistenceAdapter 리팩터링"
```

---

## 검증

### 컴파일 검증
```bash
./gradlew compileJava compileTestJava
```

### 전체 테스트 검증
```bash
./gradlew test
```
Expected: 기존 테스트 포함 전체 PASS

### 로컬 Docker 기동 검증 (Flyway 마이그레이션 실행)
```bash
docker compose build app && docker compose up -d --force-recreate app
docker compose logs -f app | grep -E "V11|strategies|ERROR"
```
Expected:
- `Successfully applied 1 migration to schema "public" (V11__extract_strategies_table)` 로그 확인
- ERROR 없음

### 계좌 등록 → 스케줄러 조회 시나리오 검증 (로컬)
```bash
# 1. dev-token 발급
curl -s -X POST http://localhost:8080/api/auth/dev-token | jq .

# 2. 계좌 등록
curl -s -X POST http://localhost:8080/api/accounts \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"nickname":"테스트","accountNo":"74420614","kisAppKey":"key","kisSecretKey":"secret","kisAccountType":"01","strategy":"INFINITE","symbol":"SOXL","exchangeCode":"AMS"}' | jq .

# 3. strategies 테이블 데이터 확인 (PostgreSQL)
docker exec -it kista-api-postgres-1 psql -U kista -d kistadb \
  -c "SELECT a.nickname, s.type, s.status FROM strategies s JOIN accounts a ON a.id = s.account_id;"
```
Expected: `strategies` 테이블에 계좌별 행이 존재, `accounts` 테이블에 `strategy`/`strategy_status` 컬럼 없음
