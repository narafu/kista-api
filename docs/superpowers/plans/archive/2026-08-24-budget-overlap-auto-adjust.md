# 예산 등록 겹침 자동조정 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `FinanceBudgetService.create()`에서 같은 카테고리·개인 스코프로 겹치는 기존 예산을 규칙에 따라 자동 트림/삭제하고, 규칙이 불명확한 형태로 겹치면 409(`OverlappingPeriodException`)로 거부한다.

**Architecture:** `FinanceBudgetPort`에 개인 스코프 겹침 후보 조회 메서드를 추가하고(`findOverlapping`), 네이티브 쿼리로 구현한다. `FinanceBudgetService.create()`에서 후보 전체를 먼저 판정(트림/삭제/거부)하고, 거부 조건이 하나라도 있으면 아무것도 변경하지 않고 즉시 예외를 던진다. 통과하면 트림·삭제를 실행한 뒤 신규 예산을 저장한다.

**Tech Stack:** Java 21, Spring Boot 3, Spring Data JPA(네이티브 쿼리), JUnit 5 + Mockito

설계 문서: `docs/superpowers/specs/2026-08-24-budget-overlap-auto-adjust-design.md`

---

### Task 1: `FinanceBudgetPort`에 `findOverlapping` 추가 + 어댑터/리포지토리 구현

**Files:**
- Modify: `src/main/java/com/kista/domain/port/out/FinanceBudgetPort.java`
- Modify: `src/main/java/com/kista/adapter/out/persistence/finance/FinanceBudgetJpaRepository.java`
- Modify: `src/main/java/com/kista/adapter/out/persistence/finance/FinanceBudgetPersistenceAdapter.java`
- Test: `src/test/java/com/kista/adapter/out/persistence/finance/FinanceBudgetPersistenceAdapterTest.java`

- [ ] **Step 1: `findOverlapping` 실패하는 테스트 작성**

`FinanceBudgetPersistenceAdapterTest.java`는 `personalBudget(owner, categoryId, start, end)` 헬퍼와 `CATEGORY_A`(V13 시드 카테고리 UUID)를 이미 갖고 있다. 파일 끝(마지막 `}` 앞)에 추가:

```java
    @Test
    void findOverlapping_returnsOnlyOverlappingPersonalBudgetsInSameCategory() {
        FinanceBudget notOverlapping = adapter.save(
                personalBudget(userId, CATEGORY_A, LocalDate.of(2020, 1, 1), LocalDate.of(2020, 12, 31)));
        FinanceBudget overlapping = adapter.save(
                personalBudget(userId, CATEGORY_A, LocalDate.of(2026, 1, 1), null));
        FinanceBudget differentCategory = adapter.save(
                personalBudget(userId, CATEGORY_B, LocalDate.of(2026, 6, 1), null));

        java.util.List<FinanceBudget> result = adapter.findOverlapping(userId, CATEGORY_A,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 12, 31));

        assertThat(result).extracting(FinanceBudget::id).containsExactly(overlapping.id());
    }
```

`notOverlapping`(2020년)과 `overlapping`(2026년 무기한)은 서로 겹치지 않으므로 같은 개인 스코프·카테고리에 저장해도 `finance_budgets_personal_no_overlap` EXCLUDE 제약에 걸리지 않는다.

- [ ] **Step 2: 테스트 실행해 실패 확인**

```bash
./gradlew test --tests "com.kista.adapter.out.persistence.finance.FinanceBudgetPersistenceAdapterTest" 2>&1 | grep -E "FAILED|BUILD|error:"
```

Expected: 컴파일 에러(`findOverlapping` 메서드 없음) 또는 테스트 실패.

- [ ] **Step 3: `FinanceBudgetPort`에 메서드 추가**

`FinanceBudgetPort.java`의 `findMyScope` 아래에 추가:

```java
    // 개인 스코프(group_id IS NULL, userId 소유) + 동일 categoryId 중 [startDate, endDate]와 겹치는 예산 조회.
    // endDate=null이면 무기한 새 예산 — 시작일 이후 전부와 겹침 대상.
    List<FinanceBudget> findOverlapping(UUID userId, UUID categoryId, LocalDate startDate, LocalDate endDate);
```

- [ ] **Step 4: `FinanceBudgetJpaRepository`에 네이티브 쿼리 추가**

`FinanceBudgetJpaRepository.java`의 `findMyScope` 아래에 추가:

```java
    // 개인 스코프(user_id 일치, group_id IS NULL) + 동일 category에서 [startDate, endDate]와 겹치는 예산.
    // endDate가 NULL(무기한)이면 시작일 이후 전부가 겹침 대상 — findMyScope와 동일한 CAST(:x AS date) IS NULL
    // 우회 패턴 사용(PostgreSQL이 NULL 파라미터 타입을 추론 못 하는 문제 회피).
    @Query(nativeQuery = true, value = "SELECT * FROM finance_budgets WHERE " +
            "user_id = :userId AND group_id IS NULL AND category_id = :categoryId " +
            "AND (CAST(:endDate AS date) IS NULL OR apply_start_date <= CAST(:endDate AS date)) " +
            "AND (apply_end_date IS NULL OR apply_end_date >= :startDate)")
    List<FinanceBudgetEntity> findOverlapping(@Param("userId") UUID userId, @Param("categoryId") UUID categoryId,
            @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);
```

- [ ] **Step 5: `FinanceBudgetPersistenceAdapter`에 구현 추가**

`FinanceBudgetPersistenceAdapter.java`의 `findMyScope` 아래에 추가:

```java
    @Override
    public List<FinanceBudget> findOverlapping(UUID userId, UUID categoryId, LocalDate startDate, LocalDate endDate) {
        return jpaRepository.findOverlapping(userId, categoryId, startDate, endDate).stream()
                .map(FinanceBudgetEntity::toDomain)
                .toList();
    }
```

- [ ] **Step 6: 테스트 실행해 통과 확인**

```bash
./gradlew test --tests "com.kista.adapter.out.persistence.finance.FinanceBudgetPersistenceAdapterTest" 2>&1 | grep -E "FAILED|BUILD"
```

Expected: `BUILD SUCCESSFUL`, FAILED 없음. (테스트 DB가 필요 — `docker-compose up -d postgres` 사전 기동 확인)

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/kista/domain/port/out/FinanceBudgetPort.java \
        src/main/java/com/kista/adapter/out/persistence/finance/FinanceBudgetJpaRepository.java \
        src/main/java/com/kista/adapter/out/persistence/finance/FinanceBudgetPersistenceAdapter.java \
        src/test/java/com/kista/adapter/out/persistence/finance/FinanceBudgetPersistenceAdapterTest.java
git commit -m "$(cat <<'EOF'
feat(finance): 예산 겹침 후보 조회 findOverlapping 포트/어댑터 추가

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: `FinanceBudgetService.create()`에 자동조정 로직 적용

**Files:**
- Modify: `src/main/java/com/kista/application/service/finance/FinanceBudgetService.java`
- Test: `src/test/java/com/kista/application/service/finance/FinanceBudgetServiceTest.java`

- [ ] **Step 1: 실패하는 테스트들 작성**

`FinanceBudgetServiceTest.java`에 다음 6개 테스트를 추가한다(파일 하단, `unshare` 섹션 뒤). 헬퍼 `personalBudget()`은 `LocalDate.of(2026,1,1)`~무기한 고정이므로, 새 헬퍼 `budgetOf(start, end)`를 함께 추가한다:

```java
    // ----- create: 겹침 자동조정 -----

    private FinanceBudget budgetOf(LocalDate start, LocalDate end) {
        return new FinanceBudget(UUID.randomUUID(), null, categoryId, userId, start, end, 500_000L, null);
    }

    @Test
    @DisplayName("create: 기존이 새 예산 시작일 이전부터 시작해 시작일 이후로 걸치면 기존 종료일을 트림")
    void create_trimsExistingEndDate_whenExistingStartsBeforeNewStart() {
        FinanceBudget existing = budgetOf(LocalDate.of(2020, 1, 1), null); // 무기한
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.empty());
        when(financeCategoryPort.findByIdOrThrow(categoryId)).thenReturn(usableCategory());
        when(budgetPort.findOverlapping(userId, categoryId, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 12, 31)))
                .thenReturn(List.of(existing));
        when(budgetPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        budgetService.create(userId, null, command()); // command() = 2026/06/01~2026/12/31

        ArgumentCaptor<FinanceBudget> captor = ArgumentCaptor.forClass(FinanceBudget.class);
        verify(budgetPort, times(2)).save(captor.capture()); // 1) 트림된 기존 2) 신규
        FinanceBudget trimmed = captor.getAllValues().get(0);
        assertThat(trimmed.id()).isEqualTo(existing.id());
        assertThat(trimmed.applyEndDate()).isEqualTo(LocalDate.of(2026, 5, 31));
        verify(budgetPort, never()).delete(any());
    }

    @Test
    @DisplayName("create: 기존이 새 예산 범위 안에 완전히 들어가면(뒷부분 흡수) 기존을 삭제")
    void create_deletesExisting_whenFullyAbsorbedByNewRange() {
        FinanceBudget existing = budgetOf(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30));
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.empty());
        when(financeCategoryPort.findByIdOrThrow(categoryId)).thenReturn(usableCategory());
        when(budgetPort.findOverlapping(userId, categoryId, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 12, 31)))
                .thenReturn(List.of(existing));
        when(budgetPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        budgetService.create(userId, null, command());

        verify(budgetPort).delete(existing.id());
        verify(budgetPort, times(1)).save(any()); // 신규만 저장, 트림 없음
    }

    @Test
    @DisplayName("create: 기존이 새 예산 앞뒤로 모두 걸치면(중간에 낌) 409")
    void create_rejects_whenExistingWrapsAroundNewRange() {
        FinanceBudget existing = budgetOf(LocalDate.of(2020, 1, 1), LocalDate.of(2027, 12, 31));
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.empty());
        when(financeCategoryPort.findByIdOrThrow(categoryId)).thenReturn(usableCategory());
        when(budgetPort.findOverlapping(userId, categoryId, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 12, 31)))
                .thenReturn(List.of(existing));

        assertThatThrownBy(() -> budgetService.create(userId, null, command()))
                .isInstanceOf(FinanceBudget.OverlappingPeriodException.class);

        verify(budgetPort, never()).save(any());
        verify(budgetPort, never()).delete(any());
    }

    @Test
    @DisplayName("create: 기존이 새 예산 시작일 이후 시작해 종료일 뒤로도 이어지면(역트림 필요) 409")
    void create_rejects_whenExistingExtendsPastNewEnd() {
        FinanceBudget existing = budgetOf(LocalDate.of(2026, 9, 1), null); // 무기한
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.empty());
        when(financeCategoryPort.findByIdOrThrow(categoryId)).thenReturn(usableCategory());
        when(budgetPort.findOverlapping(userId, categoryId, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 12, 31)))
                .thenReturn(List.of(existing));

        assertThatThrownBy(() -> budgetService.create(userId, null, command()))
                .isInstanceOf(FinanceBudget.OverlappingPeriodException.class);

        verify(budgetPort, never()).save(any());
        verify(budgetPort, never()).delete(any());
    }

    @Test
    @DisplayName("create: 트림 대상과 삭제 대상이 함께 있으면 둘 다 처리")
    void create_handlesMixedTrimAndDeleteCandidates() {
        FinanceBudget toTrim = budgetOf(LocalDate.of(2020, 1, 1), null);
        FinanceBudget toDelete = budgetOf(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30));
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.empty());
        when(financeCategoryPort.findByIdOrThrow(categoryId)).thenReturn(usableCategory());
        when(budgetPort.findOverlapping(userId, categoryId, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 12, 31)))
                .thenReturn(List.of(toTrim, toDelete));
        when(budgetPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        budgetService.create(userId, null, command());

        verify(budgetPort).delete(toDelete.id());
        ArgumentCaptor<FinanceBudget> captor = ArgumentCaptor.forClass(FinanceBudget.class);
        verify(budgetPort, times(2)).save(captor.capture());
        assertThat(captor.getAllValues().get(0).id()).isEqualTo(toTrim.id());
        assertThat(captor.getAllValues().get(0).applyEndDate()).isEqualTo(LocalDate.of(2026, 5, 31));
    }

    @Test
    @DisplayName("create: 거부 후보가 하나라도 있으면 다른 정상 후보도 변경/삭제되지 않음")
    void create_rejectsWithoutPartialMutation_whenAnyCandidateRejected() {
        FinanceBudget validTrim = budgetOf(LocalDate.of(2020, 1, 1), null);
        FinanceBudget wrapsAround = budgetOf(LocalDate.of(2020, 1, 1), LocalDate.of(2027, 12, 31));
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.empty());
        when(financeCategoryPort.findByIdOrThrow(categoryId)).thenReturn(usableCategory());
        when(budgetPort.findOverlapping(userId, categoryId, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 12, 31)))
                .thenReturn(List.of(validTrim, wrapsAround));

        assertThatThrownBy(() -> budgetService.create(userId, null, command()))
                .isInstanceOf(FinanceBudget.OverlappingPeriodException.class);

        verify(budgetPort, never()).save(any());
        verify(budgetPort, never()).delete(any());
    }
```

파일 상단 import에 `org.mockito.ArgumentCaptor`와 `static org.mockito.Mockito.times` 추가 필요(이미 `import static org.mockito.Mockito.*;`가 있으므로 `times`는 자동 포함 — `ArgumentCaptor`만 추가):

```java
import org.mockito.ArgumentCaptor;
```

- [ ] **Step 2: 테스트 실행해 실패 확인**

```bash
./gradlew test --tests "com.kista.application.service.finance.FinanceBudgetServiceTest" 2>&1 | grep -E "FAILED|BUILD|error:"
```

Expected: 컴파일 에러(`findOverlapping` mock stub 대상 메서드 없음) — Task 1 완료 후에는 컴파일은 되고 테스트 로직 자체가 실패(트림/삭제 미실행이라 `save` 1회만 호출됨 등).

- [ ] **Step 3: `FinanceBudgetService.create()` 로직 구현**

`FinanceBudgetService.java`의 `create()` 메서드를 아래로 교체:

```java
    // 신규 등록은 항상 개인 소유로 저장한다 — requestedGroupId는 무시(그룹 공유는 shareToGroup으로 별도 전환).
    // 등록 전, 같은 카테고리·개인 스코프에서 겹치는 기존 예산을 규칙에 따라 자동 트림/삭제한다.
    // 규칙으로 판단 불가한 겹침(중간에 끼거나 새 예산 종료일 뒤로 이어짐)은 409로 거부하며,
    // 거부 시 어떤 후보도 변경하지 않는다(전량 판정 후 실행) — resolveOverlapActions() 참고.
    @Override
    public FinanceBudget create(UUID userId, UUID requestedGroupId, FinanceBudgetCommand command) {
        UUID currentGroupId = financeGroupPort.findCurrentGroupId(userId).orElse(null);
        verifyBudgetCommand(userId, currentGroupId, command);

        List<FinanceBudget> candidates = budgetPort.findOverlapping(
                userId, command.categoryId(), command.applyStartDate(), command.applyEndDate());
        List<OverlapAction> actions = resolveOverlapActions(candidates, command);
        actions.forEach(action -> action.execute(budgetPort));

        FinanceBudget budget = new FinanceBudget(null, null, command.categoryId(), userId,
                command.applyStartDate(), command.applyEndDate(), command.amount(), null);
        // 기간 중첩 시 어댑터가 finance_budgets_no_overlap EXCLUDE 위반을 OverlappingPeriodException으로 변환한다.
        FinanceBudget saved = budgetPort.save(budget);
        log.info("예산 등록: userId={}, budgetId={}", userId, saved.id());
        return saved;
    }

    // 겹치는 후보 각각을 트림/삭제/거부로 판정한다. 하나라도 거부 조건이면 즉시 예외를 던져
    // create()가 어떤 DB 변경도 실행하지 않도록 한다(부분 적용 방지).
    private List<OverlapAction> resolveOverlapActions(List<FinanceBudget> candidates, FinanceBudgetCommand command) {
        LocalDate newStart = command.applyStartDate();
        LocalDate newEnd = command.applyEndDate();
        List<OverlapAction> actions = new java.util.ArrayList<>();
        for (FinanceBudget existing : candidates) {
            LocalDate existingStart = existing.applyStartDate();
            LocalDate existingEnd = existing.applyEndDate();
            if (existingStart.isBefore(newStart)) {
                boolean fitsWithinNewEnd = existingEnd == null || newEnd == null || !existingEnd.isAfter(newEnd);
                if (!fitsWithinNewEnd) {
                    throw new FinanceBudget.OverlappingPeriodException("해당 기간에 자동조정할 수 없는 기존 예산이 있습니다");
                }
                actions.add(OverlapAction.trim(existing, newStart.minusDays(1)));
            } else {
                boolean fullyWithinNewRange = newEnd == null || (existingEnd != null && !existingEnd.isAfter(newEnd));
                if (!fullyWithinNewRange) {
                    throw new FinanceBudget.OverlappingPeriodException("해당 기간에 자동조정할 수 없는 기존 예산이 있습니다");
                }
                actions.add(OverlapAction.delete(existing));
            }
        }
        return actions;
    }

    // 겹침 판정 결과 하나(트림 또는 삭제)를 표현하는 내부 값 객체. execute()가 실제 포트 호출을 수행한다.
    private record OverlapAction(FinanceBudget existing, LocalDate trimmedEndDate) {
        static OverlapAction trim(FinanceBudget existing, LocalDate trimmedEndDate) {
            return new OverlapAction(existing, trimmedEndDate);
        }

        static OverlapAction delete(FinanceBudget existing) {
            return new OverlapAction(existing, null);
        }

        void execute(FinanceBudgetPort budgetPort) {
            if (trimmedEndDate != null) {
                FinanceBudget trimmed = new FinanceBudget(existing.id(), existing.groupId(), existing.categoryId(),
                        existing.userId(), existing.applyStartDate(), trimmedEndDate, existing.amount(), existing.createdAt());
                budgetPort.save(trimmed);
            } else {
                budgetPort.delete(existing.id());
            }
        }
    }
```

`OverlapAction.delete()`가 `trimmedEndDate=null`을 트림 대상과 구분하는 마커로 쓰는 점 주의 — 삭제 대상 후보의 원래 `applyEndDate()`가 우연히 null(무기한)이어도 `execute()`는 `trimmedEndDate` 필드(별도 값)만 보므로 오분류되지 않는다.

- [ ] **Step 4: 테스트 실행해 통과 확인**

```bash
./gradlew test --tests "com.kista.application.service.finance.FinanceBudgetServiceTest" 2>&1 | grep -E "FAILED|BUILD"
```

Expected: `BUILD SUCCESSFUL`, FAILED 없음. 기존 8개 + 신규 6개 테스트 전부 통과.

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/kista/application/service/finance/FinanceBudgetService.java \
        src/test/java/com/kista/application/service/finance/FinanceBudgetServiceTest.java
git commit -m "$(cat <<'EOF'
feat(finance): 예산 등록 시 겹치는 이전 값 자동 종료/삭제 처리

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: 전체 검증 및 검토자 검수

**Files:** 없음(검증 전용)

- [ ] **Step 1: 전체 finance 패키지 테스트 실행**

```bash
./gradlew test --tests "com.kista.*.finance.*" 2>&1 | grep -E "FAILED|BUILD"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: ArchUnit 규칙 확인**

```bash
./gradlew test --tests "com.kista.architecture.*" 2>&1 | grep -E "FAILED|BUILD"
```

Expected: `BUILD SUCCESSFUL` (신규 코드가 domain/application/adapter 레이어 의존 방향을 위반하지 않는지 확인 — `OverlapAction`은 `application/service/finance` 패키지 내부 private record이므로 위반 소지 없음).

- [ ] **Step 3: 컴파일 전체 확인**

```bash
./gradlew compileJava compileTestJava 2>&1 | grep -E "error:|BUILD"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: 커밋 전 검토자 검수**

CLAUDE.md 규칙에 따라 로직 변경 커밋 전 별도 리뷰어 서브에이전트로 `git diff HEAD~2`(Task 1+2 커밋) 검수. 발견된 결함은 수정 후 재검증.
