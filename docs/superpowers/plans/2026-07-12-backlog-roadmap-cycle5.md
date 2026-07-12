# 백로그 통합 로드맵 — 1~4차 사이클 이월 항목 정리 + 5차 실행 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement 트랙 A (5차 사이클) task-by-task. 트랙 B/C는 실행 계획이 아니라 백로그 카탈로그이므로 이 스킬로 실행하지 말 것 — 개별 브레인스토밍 세션이 선행돼야 한다.

## Context

1~4차 클린코드 사이클과 1차 4렌즈 검토를 거치며 "보류"로 남긴 항목들이 여러 메모리 파일과 계획 문서에 흩어져 있다. 사용자가 이를 하나로 모아 계획하라고 요청했다. 항목들을 직접 재확인한 결과 성격이 뚜렷이 다른 두 트랙으로 나뉜다:
- **트랙 A/B (클린코드)**: 3~4차 사이클에서 "다음 사이클 후보"로 보류한 것 — 일부는 즉시 실행 가능한 소규모 항목(트랙 A), 일부는 설계 판단이 선행돼야 하는 대규모 항목(트랙 B)이다.
- **트랙 C (기능·안정성)**: 1차 4렌즈 검토(2026-07-11) 때 발견했으나 당시 10-Task 수정 계획에는 포함하지 않고 "다음 사이클 후보"로 미룬 항목 — 클린코드가 아닌 기능/안정성 개선이라 별도 트랙으로 관리해왔다.

아래에서 각 항목을 재검증한 현재 상태를 반영했다(코드를 직접 grep/Read로 재확인 — 메모리 기록 당시와 달라진 점이 있으면 갱신).

## 트랙 A — 즉시 실행 가능 (5차 사이클, 지금 바로 실행)

### Task 1 [ui, haiku]: `RangeFilterBar` → `UrlRangeFilterBar` 리네임

**파일**: `shared/ui/RangeFilterBar.tsx` + 사용처 6곳(`app/(admin)/admin/{trades,accounts,privacy-trades,users}/page.tsx`, `widgets/admin-log-list/{ErrorLogsSection,AuditLogsSection,AnomaliesSection}.tsx`)

**왜**: 재확인 결과 이 컴포넌트는 URL 쿼리파라미터(`useSearchParams`/`router.push`) 기반 네비게이션 필터다. 3차 사이클에서 신설한 `shared/ui/range-filter/RangeFilterControls.tsx`(클라이언트 state 기반 프레젠테이셔널 컴포넌트)와 이름이 거의 같아 혼동 소지가 있다. 기능 변경 없이 이름만 바꿔 역할을 명확히 한다.

**방법**: 파일명 `RangeFilterBar.tsx` → `UrlRangeFilterBar.tsx`, export 함수명 및 6개 사용처의 import를 전부 교체. 로직·props는 완전히 동일하게 유지.

**검증**: `npm run typecheck` + `npm run test`.

### Task 2 [api, sonnet]: `DomainFixtures`에 status/role 파라미터 오버로드 추가

**파일**: `src/test/java/com/kista/support/DomainFixtures.java`, `AdminServiceTest.java`, `UserServiceTest.java`, `DevAuthControllerTest.java`

**왜**: 재확인 결과 이 3개 테스트는 `User`를 PENDING/REJECTED/ACTIVE 등 다양한 status로 직접 생성한다(`UserServiceTest.java:51,57,63,68,171,333,360` 등). 기존 `DomainFixtures.activeUser()`/`activeUserWithTelegram()`은 status가 ACTIVE로 고정돼 있어 이 테스트들은 3차 사이클 이관 대상에서 의도적으로 제외됐었다.

**방법**: `DomainFixtures`에 `userWithStatus(UUID id, User.UserStatus status)` (또는 status+role 둘 다 받는 오버로드)를 추가하고, 3개 테스트 파일의 로컬 `new User(...)` 반복 호출을 이 헬퍼로 교체. 기존 `activeUser()` 계열 메서드는 그대로 둔다(내부에서 새 오버로드를 호출하도록 리팩토링해도 무방).

**검증**: `./gradlew test --tests 'com.kista.application.service.admin.AdminServiceTest,com.kista.application.service.user.UserServiceTest,com.kista.adapter.in.web.DevAuthControllerTest'` + 전체 테스트.

> 4차 사이클에서 나왔던 "`UuidFixtures`로 55개 테스트의 로컬 UUID 상수를 통일하자"는 제안은 이번 로드맵에서 제외했다 — 재검증 결과 스캔 에이전트가 규모를 과장했을 가능성이 있고(3~4차에서 실제로 이미 상당수 정리됨), 55개 파일을 건드리는 리스크 대비 이득이 불분명하다. 실행하려면 먼저 정확한 대상 파일 목록을 다시 스캔·검증해야 한다.

---

## 트랙 B — 클린코드 대규모 (설계 판단 선행 필요, 개별 브레인스토밍 후 실행)

아래 항목은 "즉시 구현 계획"이 아니라 다음에 다룰 때 브레인스토밍부터 다시 시작해야 하는 이유를 명시한다.

1. **`TradingPriceFetcher` 가격 조회 캐싱/배치 재구성** — ✅ **완료 (2026-07-12, 재조사 후 축소 실행)**. 조사 결과 `TradingService`/`TradingPriceFetcher` 계층에는 중복 조회가 없음을 확인(원래 진단 오류) — 실제 비효율은 `KisPriceApi`/`TossPriceApi`의 전일종가(prevClose) 조회가 종목마다 개별 API 호출을 하는 구조였다. 하루(KST) 단위 `PrevCloseCache` 신설로 해결. 스펙 `docs/superpowers/specs/2026-07-12-cycle-order-strategy-capability-design.md`, 계획 `docs/superpowers/plans/2026-07-12-track-b-capability-cache-staletime-prop.md` Task 3. 커밋 `2ce28b86..28ca91fc`.
2. **`CycleOrderStrategy`를 persistence/execution 레이어까지 확장** — ✅ **완료 (2026-07-12, 방향 수정 후 실행)**. 당초 "포트 역전"(domain에 새 포트 + application이 구현)으로 승인됐으나, 코드 재확인 결과 불필요함을 발견해 사용자 재확인 후 더 단순한 "capability 메서드 확장"(순수 값 반환, `endsCycleOnLiquidation()`과 동일 패턴)으로 방향 전환. `CyclePositionPersistor`/`TradingOrderExecutor`의 `isInfinite()/isPrivacy()/isVr()` 분기를 `tracksReverseMode()`/`requiresRolloverCheck()`/`priceCapMode()` 3개 capability 메서드로 대체 — domain→application 의존 없이 해소. 계획 Task 1+2. 커밋 `7ade3874..2ce28b86`.
3. **`CycleState` sealed interface화** — **기각 (2026-07-12)**. 스펙 작성 단계에서 재검토한 결과, sealed interface로 나눠도 소비부(`placeAll`)에 새 `switch` 분기가 생길 뿐 분기 자체가 사라지지 않고, 오히려 현재의 "필드를 그대로 넘기고 받는 쪽이 null 체크"하는 단순함을 깨뜨린다고 판단해 명시적으로 기각. 재론 불필요.
4. **React Query staleTime 정책 통일** — ✅ **완료 (2026-07-12, 전면 정책화 대신 소규모 수정 3건으로 축소)**. 조사 결과 대부분 훅이 이미 합리적인 값을 쓰고 있어 전면 리팩토링 불필요 — Fear&Greed(주석-실제값 불일치 정정, 30분→6시간), 시장휴일 캘린더(1시간→24시간), 체결이력(0→1분 추가) 3건만 수정. 계획 Task 4. 커밋 `726c08c`.
5. **`CycleHistoryTable`의 12개 prop drilling 개선** — ✅ **완료 (2026-07-12)**. `StrategyOrderHistory` 패턴(자체 상태+자체 쿼리 소유, 부모는 식별자만 전달)을 적용해 prop 12개→4개로 축소, 두 부모(`StrategyTradesTab`/`TradesTab`)의 중복 보일러플레이트도 함께 제거. 계획 Task 5. 커밋 `d215c32`+`6afe0670`(1차 리뷰에서 발견된 ESLint `rules-of-hooks` 위반 즉시 수정 포함).
6. **Null 체크 반복(105개 파일) → Optional/Null Object 패턴 전사 적용** — 미착수, 여전히 보류. 범위가 프로젝트 전역이라 장기 과제로 남겨둔다.

**트랙 B 최종 결론**: 6개 항목 중 4개 완료(1,2,4,5), 1개 기각(3), 1개 보류(6). 최종 whole-branch 리뷰(opus) Ready to merge — Critical/Important/Minor 0건. kista-api 커밋 `488fe936..28ca91fc`(3개), kista-ui 커밋 `ce2fc9f..6afe0670`(3개), 양 레포 전체 테스트/typecheck green, 미푸시.

---

## 트랙 C — 기능·안정성 (1차 4렌즈 검토 이월, 클린코드와 별도 트랙)

1차 검토(2026-07-11) 당시 발견했으나 10-Task 수정 계획에는 넣지 않고 미룬 항목. 재확인 결과 전부 아직 미착수 상태다.

1. **잔고 급변 안전장치(balance-spike safety guard)** — 코드베이스에 관련 구현 없음(재확인 완료). 계좌 잔고가 한 배치 사이클 사이에 비정상적으로 급변했을 때(브로커 API 오류로 잘못된 값을 받는 경우 등) 매매를 일시 중단하고 알림을 보내는 가드가 없다.
2. **브로커 서킷브레이커** — 코드베이스에 관련 구현 없음(재확인 완료). KIS/Toss API가 연속 실패할 때 요청을 잠시 차단하는 회로차단기 패턴 부재.
3. **`CycleState` sealed interface** — 트랙 B 3번과 동일 항목. 원래 1차 검토에서 나온 항목인데 클린코드 관점(null 체크 감소)과 기능 관점(전략별 상태 안전성) 양쪽에 걸쳐 있어 두 트랙에 모두 기록해둔다 — 실행은 트랙 B 2번+3번 묶음에서 한 번만 하면 된다.
4. **스케쥴러 인터럽트 시 사용자 알림** — ✅ **완료 (2026-07-12)**. `docs/superpowers/specs/2026-07-12-scheduler-interrupt-user-notification-design.md` 브레인스토밍 → `docs/superpowers/plans/2026-07-12-scheduler-interrupt-user-notification.md` 구현. `UserNotificationPort.notifyBatchInterrupted(User, Account)` 신설 + `TradingService.executeBatch()`/`placeOpenOrders()` 연동 (증권사 접수 전 미처리 전략의 사용자에게만 알림, "마감 시각" 대기는 이미 접수 완료라 의도적으로 제외). 커밋 `9474e55c..548660cd`, 최종 whole-branch 리뷰(opus) Ready to merge. 미푸시.
5. **SaaS 상용화 갭(결제/구독/온보딩)** — 제품 전략 결정이 선행되는 별도 규모의 이니셔티브. 이번 로드맵에서는 "존재한다"는 사실만 기록하고 범위 산정은 하지 않는다(별도 세션에서 브레인스토밍 필요).

**권장 우선순위**: 4번(스케쥴러 인터럽트 사용자 알림)이 가장 작고 명확한 스코프이며 실제 사용자 피해(누락된 주문을 모르는 상태)와 직결되므로 트랙 C에서는 1순위로 제안. 1번(잔고 급변 가드)과 2번(서킷브레이커)은 둘 다 "브로커 API 신뢰성 저하 시 안전장치"라는 같은 문제의식이라 함께 설계하는 게 효율적 — 2순위로 묶어서 제안. 5번은 별도 이니셔티브라 순서 밖.

---

## 실행 계획

1. **지금**: 트랙 A(Task 1, 2)를 5차 사이클로 즉시 실행 — superpowers:subagent-driven-development, api/ui 레포 간 병렬, 태스크별 리뷰어 + 최종 리뷰.
2. **다음**: 트랙 B(2+3 묶음) 또는 트랙 C(4번)를 사용자가 고르면, 그 항목만 별도로 superpowers:brainstorming부터 다시 시작(설계 확정 → writing-plans → subagent-driven-development).
3. 트랙 B/C의 나머지 항목은 이 문서에 우선순위와 함께 대기.

## Global Constraints (5차 사이클 트랙 A 적용)

- 동작 완전 불변, 매매 공식 파일 변경 금지, BOM 삽입 금지, 커밋은 한글+Conventional Commit, author `narafu <narafu@kakao.com>`, **push 금지**
- kista-api 게이트: `./gradlew compileJava` + `./gradlew test` / kista-ui 게이트: `npm run typecheck` + `npm run test`
