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

1. **`TradingPriceFetcher` 가격 조회 캐싱/배치 재구성** — 매매 타이밍에 영향을 줄 수 있는 캐시 레이어를 스케쥴러 배치 실행 도중에 추가하는 것이라, "캐시 무효화 시점"과 "가격 재조회가 필요한 시점"의 경계를 먼저 설계로 확정해야 한다.
2. **`CycleOrderStrategy`를 persistence/execution 레이어까지 확장** — `CyclePositionPersistor`/`TradingOrderExecutor`의 `isInfinite()/isPrivacy()/isVr()` 분기를 인터페이스 메서드로 옮기는 안. 3차 사이클에서 보류했던 "`domain/strategy` 3개 클래스(`InfiniteCycleOrderStrategy` 등) package-private 전환을 위한 테스트 인프라 리팩토링"과 사실상 같은 방향이므로 **하나로 묶어서** 다룰 것 — 인터페이스를 확장하면 테스트에서의 `new XxxCycleOrderStrategy(...)` 직접 생성 의존도 함께 정리될 가능성이 높다.
3. **`CycleState` sealed interface화** — `TradingService.java:52`의 `private record CycleState(...)`가 `position`(INFINITE 전용)/`privacyBase`(PRIVACY 전용) 등 전략 타입별로만 채워지는 nullable 필드를 갖고 있다(재확인 완료). 2번 항목과 함께 검토하면 시너지가 크다 — `CycleOrderStrategy` 다형성 확장과 `CycleState`의 타입별 분리를 같은 설계 세션에서 다루는 게 효율적.
4. **React Query staleTime 정책 통일** — 현재 hook마다 30초~30분으로 제각각인데, 이건 코드 문제가 아니라 "데이터 종류별로 얼마나 자주 갱신돼야 하는가"라는 제품 판단이 먼저 필요하다.
5. **`CycleHistoryTable`의 12개 prop drilling 개선** — 상태 객체화든 context든, 이후 유사 테이블(예: `StrategyOrderHistory`가 3차·4차 걸쳐 반복 정리된 것처럼)에도 적용할 공통 패턴으로 만들지 이 컴포넌트만 국소적으로 고칠지 먼저 결정 필요.
6. **Null 체크 반복(105개 파일) → Optional/Null Object 패턴 전사 적용** — 범위가 프로젝트 전역이라 장기 과제. 3번(`CycleState`)이 먼저 정리되면 이 항목의 실제 범위가 줄어들 수 있어 3번 이후로 순서를 미루는 게 합리적.

**권장 순서**: 2번+3번을 묶어 다음 브레인스토밍 세션 1순위로 — 나머지(1, 4, 5, 6)보다 다른 이월 항목(6번)의 범위 축소 효과가 있고, 3차 사이클부터 이월된 항목이라 가장 오래 대기 중이다.

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
