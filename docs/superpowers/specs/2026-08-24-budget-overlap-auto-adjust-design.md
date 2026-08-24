# 예산 등록 시 겹치는 이전 값 자동 종료 처리

## 배경

`FinanceBudgetService.create()`는 현재 카테고리·기간 겹침을 `finance_budgets_no_overlap` 계열 EXCLUDE 제약으로 막고 `OverlappingPeriodException`(409)을 반환한다. 사용자는 예산을 기간 단위로 갱신할 때(예: 2026년 예산을 등록하며 기존 무기한 예산을 대체) 매번 기존 예산을 수동으로 종료일 수정한 뒤 새로 등록해야 했다.

이 기능은 새 예산 등록 시, 같은 카테고리·같은 스코프(개인 소유)에서 겹치는 기존 예산(들)을 규칙에 따라 자동으로 종료/삭제 처리한다. 규칙이 판단 불가능한 형태로 겹치면 기존과 동일하게 409로 거부한다.

## 적용 범위

- `FinanceBudgetService.create()`에만 적용. `update()`는 현행대로 DB EXCLUDE 위반 시 409 유지(사용자가 직접 기간을 조율하는 행위라 자동 흡수 대상 아님).
- 대상 스코프는 신규 등록이 항상 개인 소유로 저장되는 현재 정책(`requestedGroupId` 무시)에 따라 개인 예산(`user_id = :userId AND group_id IS NULL`)으로 한정.

## 판정 규칙

새 예산을 `[Sn, En]`(En=null이면 무기한), 기존 후보를 `[Se, Ee]`로 표기.

| 조건 | 처리 |
|---|---|
| `Se < Sn` AND (`Ee == null` OR `En == null` OR `Ee <= En`) | 기존 종료일을 `Sn - 1일`로 트림(update) |
| `Se < Sn` AND `Ee > En`(En not null) | 거부 — 409 (기존이 새 예산 뒤로도 이어짐, 중간에 낌) |
| `Se >= Sn` AND (`En == null` OR (`Ee != null` AND `Ee <= En`)) | 기존 전체 삭제 |
| `Se >= Sn` AND (`Ee == null` OR `Ee > En`) | 거부 — 409 (기존이 새 예산 종료일 뒤로도 이어짐) |

겹치는 후보가 여러 건이면 각각 독립적으로 판정한다. **하나라도 거부 조건이면 어떤 후보도 변경/삭제하지 않고 즉시 409를 던진다** — 부분 적용 후 실패로 인한 불일치 방지.

## 데이터 접근 계층

`FinanceBudgetPort`에 메서드 추가:

```java
// 개인 스코프(userId, group_id IS NULL) + 동일 categoryId 중 [startDate, endDate]와 겹치는 예산 조회
List<FinanceBudget> findOverlapping(UUID userId, UUID categoryId, LocalDate startDate, LocalDate endDate);
```

`FinanceBudgetJpaRepository`에 네이티브 쿼리 신설(`findMyScope`의 `CAST(:date AS date) IS NULL` 우회 패턴 재사용):

```sql
SELECT * FROM finance_budgets WHERE
  user_id = :userId AND group_id IS NULL AND category_id = :categoryId
  AND (CAST(:endDate AS date) IS NULL OR apply_start_date <= CAST(:endDate AS date))
  AND (apply_end_date IS NULL OR apply_end_date >= :startDate)
```

## 서비스 로직

`FinanceBudgetService.create()`:

1. 기존 `verifyBudgetCommand` 검증(카테고리 접근 가능·ASSET 타입 아님·종료일>=시작일) 유지.
2. `budgetPort.findOverlapping(userId, categoryId, applyStartDate, applyEndDate)`로 후보 조회.
3. 각 후보에 판정 규칙 적용해 액션 리스트(TRIM/DELETE) 계산. 거부 조건 발견 시 즉시 `OverlappingPeriodException` 던지고 종료(DB 변경 없음).
4. 액션 리스트 실행: TRIM은 `budgetPort.save(existing 종료일만 교체한 새 FinanceBudget)`, DELETE는 `budgetPort.delete(existing.id())`.
5. 기존 로직대로 신규 예산 `budgetPort.save(budget)` 실행.

## 예외

기존 `FinanceBudget.OverlappingPeriodException` 재사용(컨트롤러 409 매핑 변경 없음). 메시지: "해당 기간에 자동조정할 수 없는 기존 예산이 있습니다" — DB EXCLUDE 위반 catch와 규칙 판정 거부를 구분하지 않고 동일 메시지로 통일(사용자 입장에서 둘 다 "직접 기간을 조율해야 함"이라는 동일한 의미).

## 테스트

`FinanceBudgetServiceTest`에 시나리오별 단위 테스트 추가:
- 단순 트림: 기존 무기한 → 새 예산 시작일 기준 종료일 트림
- 뒷부분 흡수 삭제: 기존이 새 예산 범위 안에 완전히 들어가 삭제
- 중간 끼임 거부: 기존이 새 예산 앞뒤로 모두 걸쳐 409
- 역트림 거부: 기존이 새 예산 시작일 이후 시작 + 종료일 이후까지 이어져 409
- 여러 후보 혼재: 트림 대상 1건 + 삭제 대상 1건 동시 처리
- 겹침 없음: 기존 동작 그대로 신규 insert만 수행
- 거부 케이스에서 다른 정상 후보도 변경되지 않는지(부분 적용 방지) 확인

기존 `finance_budgets_group_no_overlap`/`finance_budgets_personal_no_overlap` DB 제약은 유지(최종 안전망). 규칙 판정과 DB 제약 사이에 갭이 있다면(예: 동시성 레이스) 여전히 `save()`의 `DataIntegrityViolationException` catch가 백업으로 작동.
