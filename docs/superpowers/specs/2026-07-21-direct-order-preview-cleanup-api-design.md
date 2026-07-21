# 바로주문/미리보기 정리 (API)

> kista-ui companion: `../../../kista-ui/docs/superpowers/specs/2026-07-21-direct-order-preview-cleanup-ui-design.md`
> 관련 선행 스펙: `2026-07-17-buy-competition-preview-design.md`(`TradingBuyCompetitionSimulator` 원 설계), kista-ui `2026-07-18-strategy-card-deficit-accuracy-design.md`(카드 예산부족 배지가 왜 필요한지의 근거)

## 배경

"바로주문/미리보기" 영역이 반복 수정으로 여러 개념이 겹쳐 쌓였다는 사용자 지적으로 전체를 다시 훑었다.

**검토했지만 채택하지 않은 방향**: `GET /preview`가 카드 목록에서 전략 수만큼 반복 호출되며 매번 계좌 내 전략 전체를 순회하는 `TradingBuyCompetitionSimulator`까지 수행하는 게 무겁다고 보고, 카드 전용 경량 `today-orders` 엔드포인트(DB 조회만, 경쟁 시뮬레이션 없음) 신설을 검토했다. 그런데 kista-ui `2026-07-18-strategy-card-deficit-accuracy-design.md`를 다시 확인한 결과, 카드의 예산부족 배지(`competition`)는 단순 비용 문제가 아니라 **"SELL만 성공 접수되고 BUY는 예산 부족으로 실패했는데 목록에서 티가 안 남"** 버그(이번 세션 전체의 발단이 된 바로 그 문제)를 막는 안전장치였다. 경량 엔드포인트로 바꾸면 이 안전장치가 카드에서 사라져 7월 18일에 고친 버그가 재발한다. 그래서 이 방향은 폐기했다 — **API 응답·엔드포인트는 변경하지 않는다.**

대신 비용 문제는 kista-ui 쪽에서 `staleTime`으로 재조회 빈도만 낮춰서 해결한다(정확도·안전장치 100% 유지, API 변경 없음 — UI 스펙 참고).

## 이번 라운드에서 실제로 하는 것

### 필드명 통일: `todayPlannedOrders` → `todayOrders`

`domain/model/order/NextOrdersPreview.java`의 필드명이 DTO(`NextOrdersResponse.todayOrders`)와 다르다. 도메인 쪽을 DTO 이름에 맞춰 통일한다:

```java
public record NextOrdersPreview(
        LocalDate tradeDate,
        InfinitePosition position,
        List<Order> orders,
        SkipReason skipReason,
        List<Order> todayOrders,              // was: todayPlannedOrders
        BigDecimal otherStrategiesPlannedBuyUsd,
        BuyCompetitionPreview competition
) { ... }
```

영향 범위: `application/service/trading/TradingPreviewService.java`의 생성자 호출부(`new NextOrdersPreview(..., todayPlannedOrders, ...)` → `todayOrders`), `adapter/in/web/dto/NextOrdersResponse.java`의 `NextOrdersResponse.from()`에서 `result.todayPlannedOrders()` → `result.todayOrders()`. 이 필드를 참조하는 테스트(`TradingPreviewServiceTest` 등)도 함께 수정.

그 외 `TradingPreviewService`·`TradingBuyCompetitionSimulator`·`BuyCompetitionPreview`·컨트롤러·DTO 구조는 **전부 무변경**.

## 테스트 계획

- 필드명 변경에 따른 기존 테스트 컴파일 오류 전수 수정 (`todayPlannedOrders` 참조 grep으로 확인).
- 기존 `TradingPreviewServiceTest`가 그대로 통과하는지만 확인 — 새 동작 없음, 리네임 회귀만 방지.

## 구현 범위 요약

- 수정: `domain/model/order/NextOrdersPreview.java`, `application/service/trading/TradingPreviewService.java`, `adapter/in/web/dto/NextOrdersResponse.java` (필드명 대응)
- 신규 엔드포인트 없음, DB 마이그레이션 없음
- manualOrders 섀도우 상태 정리·예수금 부족액 표시 복원은 전부 kista-ui 단독 변경 — UI 스펙 참고
