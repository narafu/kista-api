# 스케쥴러 인터럽트 시 사용자 알림 — 설계 문서

## Context

1차 4렌즈 검토(2026-07-11)에서 발견해 트랙 C(기능·안정성)로 이월했던 항목. 배포·재기동으로 `TradingOpenScheduler`/`TradingCloseScheduler`의 배치 실행이 `InterruptedException`으로 중단되면, `SchedulerJobRunner.run()`이 관리자에게만 텔레그램 알림(`notifyPort.notifyError(e)`)을 보내고 락을 즉시 해제한다. 이때 실제로 매매가 처리되지 않은 사용자들은 자신의 주문이 오늘 접수되지 않았다는 사실을 알 방법이 없다 — 관리자가 인지하고 수동으로 재실행하기 전까지는 조용히 넘어간다.

기존에 이미 `TradingService.runSafely()`가 일반 예외(개별 전략 계산 실패 등)에 대해서는 관리자 알림과 함께 `userNotificationPort.notifyError(ctx.user(), e)`로 해당 사용자에게도 알리고 있으나, `InterruptedException`만은 배치 전체를 즉시 중단하기 위해 의도적으로 그대로 재던져(락을 빨리 풀기 위함, 코드 주석에 명시됨) 사용자 알림 없이 넘어간다. 이번 설계는 정확히 이 갭 — 인터럽트 시점에 아직 처리되지 않은 전략의 사용자에게만 — 을 메운다.

## 확정된 결정 사항 (브레인스토밍에서 승인됨)

1. **알림 대상**: 배치 전체가 아니라, 인터럽트 시점에 **아직 처리되지 않은 전략의 사용자만** 정확히 특정해서 알린다. 이미 처리 완료된 사용자(성공했든 개별 실패로 이미 알림을 받았든)에게 중복·불필요한 알림을 보내지 않는다.
2. **알림 메서드**: 기존 `notifyError(User, Exception)`("매매 오류" 맥락)를 재사용하지 않고, `notifyBatchInterrupted(User, Account)` 전용 메서드를 신설한다 — 사용자가 자신의 매매 로직이 잘못됐다고 오해하지 않도록 "시스템 재배포로 인한 일시 중단" 의미를 명확히 담은 별도 문구를 쓴다.
3. **추적 방식**: 각 배치 진입점(`executeBatch`, `placeOpenOrders`) 내부에 지역 `Set<UUID>`(처리 완료된 전략 ID)를 두고, 메서드 최상위에 단일 `catch (InterruptedException e)`를 추가해 `contexts` 중 이 Set에 없는 전략들만 걸러 알림을 보낸 뒤 재던진다. 3단계 루프(`planAll`/`placeAll`/`reportAll`)나 `waitFor()` 대기 구간별로 개별 catch를 두는 대신, 단일 catch + Set 추적으로 통일한다 — waitFor 도중 인터럽트(아직 아무 전략도 처리 안 됨)와 루프 도중 인터럽트(일부만 처리됨) 모두 이 방식 하나로 정확히 커버된다.

## 아키텍처

### 알림 포트 확장

`domain/port/out/UserNotificationPort.java`에 추가:
```java
void notifyBatchInterrupted(User user, Account account); // 사용자에게 스케쥴러 인터럽트(배포·재기동) 알림
```

구현: `UserNotificationPort`를 구현하는 3개 어댑터 전부에 메서드 추가 필요 —
- `adapter/out/notify/CompositeUserNotificationAdapter.java`: 기존 `notifyError` 패턴과 동일하게 `route(user, p -> p.notifyBatchInterrupted(user, account))` 형태로 위임.
- `adapter/out/notify/TelegramUserNotificationAdapter.java`: 전용 문구 구현 — 예: "[계좌 닉네임] 시스템 재배포로 오늘 매매가 일시 중단됐습니다. 잠시 후 자동 재시도되거나, 관리자에게 문의해주세요".
- `adapter/out/notify/FcmAdapter.java`: 동일 의미의 푸시 알림 문구로 구현.

### `TradingService` 추적·알림 로직

- `executeBatch(List<BatchContext> contexts, DstInfo dst)`: 메서드 본문 전체를 감싸는 지역 `Set<UUID> settledStrategyIds = new HashSet<>()`을 두고, `planAll`/`placeAll`/`reportAll` 내부의 `runSafely()` 호출이 끝날 때마다(성공/실패 무관 — 실패는 이미 `runSafely`가 개별 알림 처리) 해당 `ctx.strategy().id()`를 추가한다. 메서드 최상위에 `catch (InterruptedException e)`를 추가해:
  ```java
  contexts.stream()
      .filter(ctx -> !settledStrategyIds.contains(ctx.strategy().id()))
      .forEach(ctx -> userNotificationPort.notifyBatchInterrupted(ctx.user(), ctx.account()));
  throw e;
  ```
  (기존 로직에서 InterruptedException을 그대로 두던 지점에 이 처리를 추가하는 것 — 관리자 알림·락 해제 rethrow 흐름은 `SchedulerJobRunner`가 그대로 담당하므로 변경 없음)
- `placeOpenOrders(List<BatchContext> contexts, DstInfo dst)`: 동일 패턴 — 단일 루프이므로 `settledStrategyIds` 갱신 지점이 한 곳뿐이라 더 단순하다.
- `settledStrategyIds`를 채우는 지점은 `runSafely()` 자체에 넣지 않는다 — `runSafely`는 `executeBatch`/`placeOpenOrders` 양쪽에서 호출되는 하부 헬퍼라 어느 배치의 Set인지 알 수 없다. 대신 각 loop(`planAll`/`placeAll`/`reportAll`/`placeOpenOrders`의 loop)에서 `runSafely()` 호출 직후 갱신한다.

### 적용 범위

`executeBatch`와 `placeOpenOrders` 양쪽 모두 적용 — 마감 스케쥴러(`TradingCloseScheduler`)와 개장 스케쥴러(`TradingOpenScheduler`) 둘 다 배포 중 인터럽트될 수 있으므로 동일한 갭이 존재한다.

## 에러 처리

- 알림 발송 자체가 실패해도(텔레그램 API 오류 등) 원래의 `InterruptedException` rethrow는 반드시 이뤄져야 한다 — 알림 발송 루프는 `MarketEventNotifier.notify()`와 동일하게 개별 사용자 알림 실패를 `try/catch`로 흡수하고 로그만 남긴다(락 해제가 알림 발송 성공 여부에 의존하면 안 됨).
- `notifyBatchInterrupted` 자체 예외로 인해 원본 `InterruptedException`이 사라지면 안 되므로, 알림 발송은 반드시 `catch` 블록 안에서 `throw e` 이전에, 그리고 알림 발송 자체의 예외는 별도로 흡수해서 처리한다.

## 테스트

- `TradingServiceTest`의 기존 패턴(`executeBatch(List, DstInfo)` package-private 오버로드로 `DstInfo` 주입, sleep 우회)을 그대로 활용.
- 시나리오: 3개 `BatchContext`를 담은 리스트에서 두 번째 전략 처리 중 `InterruptedException`을 던지도록 stub 구성 → 첫 번째 전략 사용자에게는 `notifyBatchInterrupted` 미호출(정상 처리됨), 두 번째·세 번째 전략 사용자에게는 `notifyBatchInterrupted` 호출 확인, `executeBatch`가 `InterruptedException`을 재던지는지 확인.
- `placeOpenOrders`도 동일 구조로 별도 테스트 케이스 추가.
- 기존 `runSafely` 개별 실패 알림(`notifyError`) 테스트는 그대로 유지 — 이번 변경이 그 경로를 건드리지 않음을 회귀 테스트로 확인.

## 범위 밖 (이번 설계에서 다루지 않음)

- 인터럽트로 중단된 배치를 관리자가 수동 재실행했을 때 이미 알림 받은 사용자에게 "재개됨" 알림을 보낼지 여부 — 별도 후속 논의 대상.
- 트랙 C의 다른 항목(잔고 급변 안전장치, 브로커 서킷브레이커)과의 통합 여부 — 이번 설계는 스케쥴러 인터럽트 알림 단독 스코프.
