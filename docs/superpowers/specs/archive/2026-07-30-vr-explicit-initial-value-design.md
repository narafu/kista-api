# VR 초기 V값 직접 입력 설계

## 배경

VR 전략 등록 시 V값(리밸런싱 기준선)은 현재 항상 `전일종가 × 보유수량(initialHoldings)`으로 서버가 계산한다(`StrategyService.fetchMarketPrice` + `initialStockValue`). 보유수량을 입력하지 않으면 V=0으로 시작해 첫 매수 체결 후 산정된다.

사용자가 실제 보유수량과 무관하게(또는 보유수량 없이) V값 자체를 직접 지정하고 싶은 경우를 지원한다 — 예: 다른 계좌/브로커에서 이미 운용 중이던 VR 전략을 이어서 등록할 때 실제 매매 내역 없이 기준선만 이전하고 싶은 경우.

## 요구사항 (우선순위)

1. **초기 V 입력값이 있는 경우** → 그 값을 V값으로 저장한다.
2. **초기 V 입력값이 없는 경우** → 기존과 동일하게 평가금(전일종가×보유수량)을 V값으로 사용한다.
3. **초기 V, 평가금(보유수량·평단가), 예수금 모두 없는 경우** → 기존과 동일하게 V=0으로 시작해 첫 매수 체결 후 산정한다.

## API 변경

`TradingCycleRequest` / `RegisterStrategyCommand`에 필드 추가:

```java
@Schema(description = "VR: 초기 V값 직접 지정 (지정 시 전일종가×보유수량 계산을 대체, 생략 시 평가금 기준)", example = "5000.00")
BigDecimal initialVrValue;
```

- VR 전용, 비VR 등록 시 무시(다른 VR 전용 필드와 동일하게 서비스 계층에서만 사용)
- 음수 거부 (`initialVrValue.signum() < 0`이면 400)
- `initialHoldings`/`initialAvgPrice`와 독립적 — 동시에 입력해도 서로 다른 용도로 쓰인다: `initialHoldings`/`initialAvgPrice`는 여전히 실제 포지션(`CyclePosition`)을 여는 데 사용되고, `initialVrValue`는 V값 저장에만 관여한다

## 서버 로직 변경 (`StrategyService`)

`register()`에서 VR 분기:

```java
BigDecimal explicitInitialValue = cmd.initialVrValue(); // null 또는 >=0 (검증 후)
BigDecimal vrValue = (explicitInitialValue != null && explicitInitialValue.signum() > 0)
        ? explicitInitialValue
        : initialStockValue; // 기존 로직 그대로 (전일종가×보유수량, 없으면 0)
```

- `vrValue`는 **V값 저장**(`saveInitialCycleAndPosition` → `saveInitialCycleDetail`의 `initialValue` 파라미터)과, `validateVrCommand`의 **거치식/적립식 게이트**("초기 V값 + 예수금 > 0")에 사용한다.
- `startAmount`(현금+주식평가금, 사이클 총 시작자산)와 `CyclePosition`(실제 개장 포지션)은 override와 무관하게 항상 `initialStockValue`(실제 시장가 기준) 그대로 사용한다 — 회계상 총자산은 실제 평가금을 반영해야 하므로 V override로 왜곡되면 안 된다.
- **인출식 최소자산 검증**("인출식 초기 자산은 X 이상")은 override를 반영하지 않고 항상 `initialStockValue + initialUsdDeposit`(실제 평가금+예수금) 기준으로 계산한다 — 사용자가 임의로 입력한 V값으로 인출 안전장치를 우회할 수 없도록 한다.

`validateVrCommand` 시그니처 변경: 게이트 판정용 `vrValue`와 인출 검증용 `evaluatedAssets`(=`initialStockValue`)를 별도 파라미터로 받는다.

```java
private void validateVrCommand(RegisterStrategyCommand cmd, Integer intervalWeeks,
                               BigDecimal bandWidth, Integer recurringAmount,
                               BigDecimal vrValue, BigDecimal evaluatedStockValue,
                               VrRampParams ramp) {
    ...
    BigDecimal initialAssets = vrValue.add(initialUsdDeposit); // 게이트용
    if (normalizedRecurringAmount <= 0 && initialAssets.signum() <= 0) { ... }

    if (normalizedRecurringAmount < 0) {
        BigDecimal evaluatedAssets = evaluatedStockValue.add(initialUsdDeposit); // 인출 검증용 — override 미반영
        if (evaluatedAssets.compareTo(required) < 0) { ... }
    }
    ...
}
```

## 검증 규칙

- `initialVrValue < 0` → `IllegalArgumentException` ("VR 전략의 초기 V값(initialVrValue)은 0 이상이어야 합니다")

## 영향 범위

- `TradingCycleRequest.java`, `RegisterStrategyCommand.java`: 필드 추가
- `StrategyService.java`: `register()`, `validateVrCommand()` 수정
- `openapi.json`: 필드 추가 후 kista-ui가 `npm run fetch:spec` + `npm run gen:types`로 동기화
- DB 스키마 변경 없음 (기존 `cycle_vr.value` 컬럼에 저장되는 값의 산출 기준만 변경)

## 테스트

- `StrategyServiceTest`: 초기 V만 입력(보유수량 0) → V=입력값, startAmount=예수금만 반영 케이스 추가
- 초기 V + 보유수량 동시 입력 → V=입력값이지만 포지션·startAmount는 보유수량 기준 평가금 반영 케이스 추가
- 인출식에서 초기 V만 크고 실제 평가금은 작은 경우 → 여전히 최소자산 검증 실패(override로 우회 불가) 케이스 추가
- 초기 V 음수 입력 → 400
