# Broker Token Transaction Isolation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Commit KIS/Toss broker-token cache writes before releasing the account lock so outer request rollbacks and concurrent refreshes cannot discard newly issued tokens.

**Architecture:** Keep `DoubleCheckedTokenCache` unchanged and place the transaction boundary on the shared persistence adapter's `saveToken` method. Use `Propagation.REQUIRES_NEW` so invalidations and fresh-token upserts commit independently for both brokers.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA, PostgreSQL, JUnit 5, AssertJ, Gradle

## Global Constraints

- Preserve the shared `BrokerTokenCachePort`; do not add broker-specific caches.
- Do not increase the existing one-time 401 retry count.
- Verify the regression before changing production `app_error_logs`.
- Production cleanup must be a soft delete limited to the 11 identified active rows.

---

### Task 1: Prove and fix independent broker-token commits

**Files:**
- Modify: `src/test/java/com/kista/adapter/out/persistence/kistoken/KisTokenPersistenceAdapterTest.java`
- Modify: `src/main/java/com/kista/adapter/out/persistence/kistoken/KisTokenPersistenceAdapter.java`

**Interfaces:**
- Consumes: `BrokerTokenCachePort.saveToken(UUID, String, OffsetDateTime)`
- Produces: the same interface with an independently committed persistence implementation

- [ ] **Step 1: Make the persistence fixture visible to nested transactions**

Store both fixture IDs as fields. In `@BeforeEach`, insert the user and account, commit that setup with `TestTransaction.flagForCommit()` and `TestTransaction.end()`, then start the outer test transaction with `TestTransaction.start()`. In `@AfterEach`, end any active test transaction and delete `broker_tokens`, `accounts`, then `users` by their exact IDs.

- [ ] **Step 2: Write the failing rollback regression test**

Add `saveToken_outerTransactionRollsBack_tokenRemainsCommitted`: call `saveToken`, flag the active outer test transaction for rollback, end it, then query `broker_tokens` with `JdbcTemplate` and assert the access token equals `committed-token`.

- [ ] **Step 3: Run the focused test and verify RED**

Run: `./gradlew test --tests 'com.kista.adapter.out.persistence.kistoken.KisTokenPersistenceAdapterTest.saveToken_outerTransactionRollsBack_tokenRemainsCommitted'`

Expected: FAIL because the current `saveToken` joins the outer transaction and its row is rolled back.

- [ ] **Step 4: Add the minimal independent transaction boundary**

Annotate `KisTokenPersistenceAdapter.saveToken` with `@Transactional(propagation = Propagation.REQUIRES_NEW)` and add the corresponding Spring transaction imports. Keep repository and domain interfaces unchanged.

- [ ] **Step 5: Run the complete persistence test and verify GREEN**

Run: `./gradlew test --tests 'com.kista.adapter.out.persistence.kistoken.KisTokenPersistenceAdapterTest'`

Expected: all tests pass, including upsert, expiry, missing account, and outer rollback preservation.

### Task 2: Verify broker authentication regressions

**Files:**
- No production changes expected

**Interfaces:**
- Consumes: existing KIS/Toss authentication and `DoubleCheckedTokenCache` behavior
- Produces: verification evidence only

- [ ] **Step 1: Run focused token/auth tests**

Run: `./gradlew test --tests 'com.kista.adapter.out.broker.DoubleCheckedTokenCacheTest' --tests 'com.kista.adapter.out.kis.KisAuthApiTest' --tests 'com.kista.adapter.out.toss.TossAuthApiTest' --tests 'com.kista.adapter.out.persistence.kistoken.KisTokenPersistenceAdapterTest'`

Expected: all focused tests pass.

- [ ] **Step 2: Compile production code**

Run: `./gradlew compileJava`

Expected: `BUILD SUCCESSFUL`.

### Task 3: Soft-delete resolved production error logs

**Files:**
- No repository files

**Interfaces:**
- Consumes: linked Supabase production database
- Produces: 11 `app_error_logs.deleted_at` timestamps

- [ ] **Step 1: Recount the exact active target set**

Query active rows whose `created_at` is from `2026-07-18 14:36:14+00` through `2026-07-18 14:37:07+00` and whose `error_type` is `TossApiException` or `KisApiException`.

Expected: exactly 11 rows.

- [ ] **Step 2: Soft-delete only that target set**

Update `deleted_at = now()` with the same time, type, and `deleted_at IS NULL` predicates. Do not issue `DELETE`.

- [ ] **Step 3: Verify cleanup**

Re-run the target count and active error summary.

Expected: target count 0 and no active `TossApiException` or `KisApiException` from that incident.

### Task 4: Final repository verification

**Files:**
- Review all modified files

**Interfaces:**
- Produces: clean diff and test evidence

- [ ] **Step 1: Check formatting and repository state**

Run: `git diff --check && git status --short`

Expected: no whitespace errors; only intended implementation, tests, and documentation are changed.

- [ ] **Step 2: Commit the verified fix**

Commit the implementation and regression test with a Conventional Commit subject describing broker-token transaction isolation.
