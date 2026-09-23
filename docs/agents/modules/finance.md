## com.kista.finance (`:api`)

com.kista.finance/   ← Spring Modulith 모듈(CLOSED) — 가계부 애그리게이트
  domain/model/      ← AssetSnapshot/FinanceAccount/FinanceBudget/FinanceCategory/FinanceGroup/FinanceTransaction/MonthlyClosing 등 record + Command — "domain" NamedInterface
  application/usecase/  ← UseCase 인터페이스(9개) — "usecase" NamedInterface
  application/port/output/ ← *Port(7개) — "port" NamedInterface
  application/service/  ← FinanceAccountService/FinanceBudgetService/FinanceCategoryService/FinanceGroupService/FinanceTransactionService/AssetSnapshotService/BulkFinanceRegisterService/MonthlyClosingService/FinanceRegistrationReminderNotifier + MonthlyClosingGuard(package-private) — internal
    - **마감월 쓰기 차단**: `MonthlyClosing.completed=true`인 달은 자산 스냅샷·거래의 create/update/delete/shareToGroup/unshare를 `MonthClosedException`(409)으로 전면 차단. `MonthlyClosingGuard.verifyMonthOpen(currentGroupId, userId, date)`가 SSOT(`AssetSnapshotService`/`FinanceTransactionService`가 주입). update는 기존 날짜+대상 날짜 양방향 검사. 마감 스코프는 `MonthlyClosingPort.isMonthClosed`의 either/or(그룹 있으면 그룹 마감 행, 없으면 개인 마감 행) — `findMyScope`의 union과 다르다(그룹 소속 유저는 개인 소유 record도 그룹 마감으로 판정). `MonthlyClosingService.upsert`(마감 해제)는 가드 대상 아님. bulk 등록은 항목별 try/catch라 마감월 항목만 실패 수집
  adapter/in/web/     ← Finance*Controller/AssetSnapshotController/MonthlyClosingController/AdminFinanceCategoryController(경로만 /api/admin/**, finance 소유) + dto/
  adapter/in/schedule/ ← FinanceRegistrationReminderScheduler
  adapter/out/persistence/ ← Entity + *JpaRepository + *PersistenceAdapter 3종
