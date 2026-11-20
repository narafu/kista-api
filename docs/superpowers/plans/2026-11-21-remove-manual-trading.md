# "바로 주문"(수동 실행) 삭제 계획

워크트리: `/Users/phs/workspace/kista/kista-api/.worktrees/remove-manual-trading` (브랜치 `remove-manual-trading`, main 기반)

## Global Constraints

- `POST /api/trading-cycles/{id}/execute`(바로 주문)만 삭제한다. `DELETE /api/trading-cycles/{id}/execute`(cancelByCycle)·주문 단건 취소·미리보기·개장/마감 배치는 유지한다.
- `ErrorCode.ALREADY_ORDERED_TODAY`·`DCA_MANUAL_EXECUTION_UNSUPPORTED` 상수는 삭제·개명 금지(kista-ui 계약). 주석만 "발행처 없음(2026-11 바로 주문 삭제), kista-ui 계약상 보존"으로 바꾼다.
- `TradingCycleResponse.todayOpenBatchMissed` 필드는 유지(정보는 여전히 참). 주석만 고친다.
- `TradingService`의 STARTED→DONE 덮어쓰기 코드는 관리자 runNow도 쓰므로 그대로, 주석만 고친다.
- 공유 부품(`StrategyOrderPlanBuilder`, `StrategyLedgerBudget`, `PrivacyBaseGuard`, `TradingOrderExecutor` 등)은 본체 유지. 수동 전용 파라미터·메서드·주석만 걷어낸다.
- 신규/수정 주석은 한국어 한 줄, Javadoc·블록 주석 금지. 동작 변경 금지(삭제 대상 제외).
- git add/commit 금지. 파일만 수정.

## Task 1: 코드 삭제 (trading-core + shared + 루트 테스트 주석)

삭제:
- `trading-core/.../trading/application/service/ManualTradingService.java` 전체
- 예외 `ManualTradingException`, `ManualTradingFailedException`, `AlreadyOrderedTodayException`, `DcaManualExecutionUnsupportedException` (trading/domain/model)
- DTO `trading/adapter/in/web/dto/ExecuteOrdersResponse.java`
- `TradingExecutionFacade`의 `manualTradingService` 필드와 `executeManually` 메서드
- `TradingCycleController.executeManually`(POST `/api/trading-cycles/{id}/execute`, 189-196행 부근)
- `TradingProblemDetails`의 위 4개 예외 매핑·import·관련 주석(18행)
- `TradingPriceFetcher.fetchPrices(List, BrokerAccountRef)`(126행 부근, 유일한 호출처가 ManualTradingService) — private 헬퍼(`fetchCurrentPrices`, `alertFailed`)는 다른 호출처가 있어 유지
- `AccountBalance.hasSufficientDepositFor` — 기존부터 main 호출처 0인 죽은 코드(먼저 grep으로 재확인, 테스트 참조도 정리)

파라미터 정리:
- `StrategyOrderPlanBuilder.build(strategy, account, cycle, today, accountId, prevCloseCache)`의 `accountId` 파라미터 제거 — 남은 호출처(`TradingPreviewService` 158·180행)는 항상 `account.id()`를 넘긴다. 내부에서 `account.id()` 사용. 캐시 미스 fallback은 유지.

테스트:
- `ManualTradingServiceTest.java` 전체 삭제
- `TradingCycleControllerTest`: `executeManually_*` 7개 테스트(95-160행)·미사용 import 제거. DELETE `/execute` 테스트는 유지
- `TradingExecutionFacadeTest`: ManualTradingService mock·생성자 인자·`executeManually_delegates` 제거
- `ExceptionResponseTableTest`: 4개 행(79-84행)·import 제거
- `TradingPreviewServiceTest:13` 미사용 import 제거
- `TradingPriceFetcherTest`: `fetchPrices_*` 2개 테스트(53-82행) 제거, 미사용 import 정리
- `StrategyOrderPlanBuilderTest` 등 `build(...)` 호출부를 새 시그니처로

주석 재작성(수동 실행·ManualTradingService·바로주문 언급 제거, 의미 유지):
- `PrivacyBaseGuard.java:17,44`, `CycleOrderComputer.java:32,53,109`, `AccountBudgetLock.java:11`, `TradingCandidatePlanner.java:46,252,304`, `TradingService.java:85,282,333`, `support/TradingOrderPlanner.java:24`, `TradingAccount.java:14`(호출 경계 2곳), `TradingOrderExecutor.java:53`, `OrderPort.java:26,62,89`, `OrderPersistenceAdapter.java:34,79`, `TradingPreviewService.java:60`, `TradingCycleResponse.java:122-123`, `ErrorCode.java:11-12`
- 루트 `src/test/java/com/kista/api/architecture/HexagonalArchitectureTest.java:317,322` 주석
- 마지막에 `grep -rn "수동 실행\|수동실행\|바로 주문\|바로주문\|ManualTradingService\|executeManually" trading-core/src shared/src src/main src/test/java`로 잔여 확인(관리자 수동 트리거·ManualTradeCorrectionService는 별개 기능이라 제외)

검증:
- `./gradlew :trading-core:compileTestJava :compileTestJava 2>&1 | grep -E "error:|FAILED|BUILD"`
- `./gradlew :trading-core:test --tests '*TradingCycleControllerTest' --tests '*TradingExecutionFacadeTest' --tests '*ExceptionResponseTableTest' --tests '*TradingPriceFetcherTest' --tests '*TradingPreviewServiceTest' --tests '*StrategyOrderPlanBuilderTest' 2>&1 | grep -E "FAILED|BUILD|tests completed"`

## Task 2: 문서 동기화

- `src/test/resources/architecture/flows.yml`
  - 45행 landscape `manual-run` 제목 "바로 주문·주문 취소" → 미리보기·주문 취소 성격으로
  - 935행 `manual-run` 흐름: id는 유지(다른 곳 `flow: manual-run/...` 참조), title/trigger/summary에서 바로 주문 제거. `guard`·`plan` 등 수동 실행 전용 step 삭제(`preview`·취소 step 유지). preview step desc의 "바로 주문 활성화" 문구 정리. 삭제한 step id를 참조하는 `flow: manual-run/<id>`가 있으면 함께 정리
  - 125행 "(바로 주문·재개 등)", 537-538행 등록 흐름의 `ManualTradingService#execute` code 참조, 1627·1638(전이 삭제)·1665행
  - `code:`에 `ManualTradingService#*`·`TradingCycleController#executeManually` 잔존 금지
- `docs/agents/modules/tradingcore/trading-formulas.md` PRIVACY "주문 방식" 줄의 "바로 주문도 LIMIT_VWAP…" 문장 제거
- `docs/agents/workflow.md:9`, `workflow-batch.md:32-33`(수동 실행 절 삭제), `workflow-capper.md:25`, `workflow-ledger-price.md:6,9`, `workflow-orders.md:8`
- `docs/agents/modules/api/web.md:17` 예시를 남은 예외로 교체(삭제 금지 규칙은 유지)
- `docs/agents/modules/tradingcore/dca.md:11`, `modules/shared/platform.md:36`, `scheduler-time-table.md:66`, `docs/architecture-map.md:237`
- `docs/agents/decisions.md`: 34행 "4갈래"→"배치·미리보기·DCA 수집 3갈래", 381행 DCA 바로 주문 항목은 기능 삭제로 갱신, ErrorCode 2개 보존 이유 한 줄 추가
- `docs/superpowers/fable-review/guides/00-roadmap.md:75` 06 D3(수동 실행 차단 구간) — 바로 주문 삭제로 해소됐다고 표기
- `docs/superpowers/fable-review/questions.md:292` `hasSufficientDepositFor` 항목 해소 표기
- README·CLAUDE.md는 해당 없음(조사 확인)
