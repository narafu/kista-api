## com.kista.finance (`:api`)

com.kista.finance/   ← Spring Modulith 모듈(CLOSED) — 가계부 애그리게이트
  domain/model/      ← AssetSnapshot/FinanceAccount/FinanceBudget/FinanceCategory/FinanceGroup/FinanceTransaction/MonthlyClosing 등 record + Command — "domain" NamedInterface
  application/usecase/  ← UseCase 인터페이스(9개) — "usecase" NamedInterface
  application/port/output/ ← *Port(7개) — "port" NamedInterface
  application/service/  ← FinanceAccountService/FinanceBudgetService/FinanceCategoryService/FinanceGroupService/FinanceTransactionService/AssetSnapshotService/BulkFinanceRegisterService/MonthlyClosingService/FinanceRegistrationReminderNotifier(`UserPort.findIdsByStatus(ACTIVE)`로 사용자 id 목록만 얻어 이번 달 등록 여부만 판단하고, 미등록자마다 `sharedkernel.UserNotificationRequestedEvent(userId, FINANCE_REMINDER, title, body)`를 발행한다 — 사용자 알림 설정 게이트·채널 라우팅·문구 발송은 notify `UserNotificationRequestedListener`가 담당. virtual thread 팬아웃·세마포어는 등록 여부 조회(2회 DB 왕복)용) + MonthlyClosingGuard(package-private) — internal
    - **마감월 쓰기 차단**: `MonthlyClosing.completed=true`인 달은 자산 스냅샷·거래의 create/update/delete/shareToGroup/unshare를 `MonthClosedException`(409)으로 전면 차단. `MonthlyClosingGuard.verifyMonthOpen(currentGroupId, userId, date)`가 SSOT(`AssetSnapshotService`/`FinanceTransactionService`가 주입). update는 기존 날짜+대상 날짜 양방향 검사. 마감 스코프는 `MonthlyClosingPort.isMonthClosed`의 either/or(그룹 있으면 그룹 마감 행, 없으면 개인 마감 행) — `findMyScope`의 union과 다르다(그룹 소속 유저는 개인 소유 record도 그룹 마감으로 판정). `MonthlyClosingService.upsert`(마감 해제)는 가드 대상 아님. bulk 등록은 항목별 try/catch라 마감월 항목만 실패 수집
  adapter/in/web/     ← Finance*Controller/AssetSnapshotController/MonthlyClosingController/AdminFinanceCategoryController(경로만 /api/admin/**, finance 소유) + dto/
  adapter/in/schedule/ ← FinanceRegistrationReminderScheduler
  adapter/out/persistence/ ← Entity + *JpaRepository + *PersistenceAdapter 3종

**모듈 의존(2026-09-30 결합도 재검토 7단계)**: `finance → notify` import 0(리마인더가 이벤트 발행으로 전환됐고 `UserNotificationPort`의 finance 전용 메서드는 삭제). `finance → user`는 `UserPort.findIdsByStatus`(ACTIVE 사용자 id 조회) 1건뿐 — identity 조회는 customer/supplier 방향이라 허용하며, 사용자 설정·수신자 정보는 finance가 읽지 않는다.
