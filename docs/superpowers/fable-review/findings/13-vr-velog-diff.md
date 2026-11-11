# 13. VR velog 글 vs KISTA 코드 대조

- 대상 글: https://velog.io/@narafu/VR (2026-11-12 수집, 정리본 `docs/reference/laor/vr-velog.md`). 소유자 글이며 원저작자 카페 글 인용. 카페 원문은 로그인 필요로 읽지 못함.
- 약칭 (모두 `trading-core/src/main/java/com/kista/tradingcore/` 아래): VRPOS=`matching/domain/model/VrPosition.java`, VRSTR=`matching/domain/strategy/VrStrategy.java`, VRCOS=`matching/domain/strategy/VrCycleOrderStrategy.java`, SVD=`matching/domain/model/StrategyVrDetail.java`, ROLL=`trading/application/service/VrCycleRolloverService.java`, RAMP=`trading/domain/creation/VrRampInput.java`, VAL=`trading/domain/creation/VrRampValidator.java`.
- 판정: 일치 / 차이 / 글에 없음(글이 해당 항목을 다루지 않아 대조 불가) / 불명확(글 안에서 모호하거나 모순).
- 코드 쪽 값은 기본값(`RAMP.withDefaults`)이다. 전략 등록 시 사용자가 override할 수 있다.
- 무한매수법: 글에 내용 없음(작성자의 velog 37건 중 관련 글은 VR 1건뿐) — InfinitePosition·InfiniteStrategy 대조 절 없음.

## 1. 대조표

| # | 규칙 항목 | velog 글 | KISTA 코드 (file:line, 실제 식) | 판정 | 설명 |
|---|---|---|---|---|---|
| 1 | V 갱신식 (실력공식) | `V2 = V1 + pool/G + (E−V1)/(2√G) ± 적립금/인출금` | VRPOS:70-86 `nextValue` = `value + pool/gradient + recurringAmount + (evaluation − value)/(2×√gradient)` (중간 scale 10, 결과 scale 2 HALF_UP) | **일치** | 항 구성·계수 동일. 이 글이 KISTA 실력공식의 근거로 성립한다. quantstack 기본공식과의 차이(08-formula-diff V1)는 글이 "기본공식"과 "실력공식"을 모두 싣고 KISTA가 후자만 쓰는 것으로 설명된다 |
| 2 | 기본공식 (참고) | `V2 = V1 + pool/G ± 적립금/인출금` (3단 전개의 마지막 식) | 코드에 단독 경로 없음 — 항상 VRPOS:70 `nextValue` 사용 | KISTA 미사용 | 기본공식은 실력공식에서 `(E−V1)/(2√G)` 항을 뺀 것. KISTA는 이 항을 끌 수 없다(설정 없음) |
| 3 | 실력공식 항 E의 정의 | E = "2주 사이클이 종료된 후 평가금" | ROLL:120 `evaluation = postBalance.holdings() × evaluationClosingPrice` (종가 = due일, 휴장이면 직전 거래일 ROLL:78-80,172-178) | **일치** (세부 불명확) | "사이클 종료 후 평가금" = 보유 수량 × 종가. 예수금 미포함 평가금으로 해석. 글이 예수금 포함 여부를 적지 않아 해석은 글에 근거 없음 → U3. 수량은 롤오버 실행일 마감 잔고(postBalance)를 쓰므로 due일 이후 체결이 섞일 수 있다(ROLL:144 ponytail 주석 — 기존 인지 사항) |
| 4 | 실력공식 항의 적용 조건 | 글에 없음 (식만 나열) | 모든 롤오버에 무조건 적용 (ROLL:121) | 글에 없음 | 조건·예외(예: 적립식에만 적용) 서술이 없다 → U2 |
| 5 | pool(P)의 정의·시점 | "예수금 (Pool)", 적립/인출 전·후 불명확 | ROLL:123 `postBalance.deposit()` (적립/인출 반영 **전** 예수금, 마감 후) | 불명확 | 글에 시점이 없다 → U5. quantstack §U2도 불명확 |
| 6 | 적립·인출 항 | 식 끝 `± 적립금/인출금`, 거치는 언급 없음 | ROLL:105 `recurringAmount`(양수 적립·0 거치·음수 인출)를 V에 가산(VRPOS:81 `recurringPart`), 동시에 개장 예수금에도 가산(ROLL:106-107 `adjustedPool = deposit + recurringAmount`) | 일치 (부호 의미) | V 가산은 글과 같다. 개장 예수금에도 실제 가산하는 것은 글에 없는 구현 선택 |
| 7 | 적립·인출 반영 시점 | 글에 없음 | 사이클 롤오버 시점(ROLL:105-107) 한 번에 반영. 인출 후 예수금<0이면 롤오버 보류(ROLL:108-116) | 글에 없음 | → U5. 보류 규칙은 KISTA 고유 가드 |
| 8 | 리밸런싱 주기 | "2주 사이클" | `intervalWeeks` 사용자 설정(≥1주, VAL:17-19), due = `startDate + intervalWeeks` (ROLL:64) | 일치 (2주 설정 시) | 글은 2주만 언급하는데 KISTA는 가변. 글과 맞추려면 2로 입력 |
| 9 | G 기본값: 적립식·거치식 | G(10) | RAMP:26 `initialGradient = recurringAmount<0 ? 40 : 10` → 적립·거치 10 | **일치** | |
| 10 | G 기본값: 인출식 | G(20) | RAMP:26 → 인출 **40** | **차이** | 글 20, KISTA 40(gMax 50, RAMP:27). 08-formula-diff V2와 동일 차이이며 글이 원저작자 링크(38945)와 함께 20을 명시하므로 확정 차이 |
| 11 | G 변경 주기 | "1년마다 G값을 1씩 올려" | SVD:33-39 `gradientAt`: `weeks < gGraceWeeks(기본 52)`이면 초기값, 이후 `gStepWeeks(기본 26)`마다 +1, `gMax`(적립·거치 20, 인출 50) 상한 (RAMP:20-21,36) | **차이** | 글은 연 1회 +1. KISTA는 첫 상승이 52주 시점이고 이후 26주마다 +1 → 2년째부터 글보다 2배 빠름. 글에는 G 상한 개념 자체가 없음 |
| 12 | G와 시드 규모 | "시드 10억이면 10, 20억이면 20 ... 100억이면 100" | 연동 없음. `initialGradient`는 시드와 무관한 입력 | 불명확 | 위 9·10행(유형별 10/20)과 병립하는지 대체하는지 글에 없다 → U3. 코드엔 시드 비례 로직 없음 |
| 13 | G 의미(안정성) | 클수록 안정, 작을수록 공격 | VRPOS:70-86 gradient는 `pool/G`·`1/(2√G)` 분모 | 일치 | 분모에 들어가 큰 G가 V 상승폭을 줄이는 방향 |
| 14 | 밴드 폭 | ±10%·±15%·±20% 예시, `V×0.85 ~ V×1.15` | VRPOS:39-47 `lowerBand = V×(1−w/100)`, `upperBand = V×(1+w/100)`; 기본 15, 허용 10/15/20 (08-formula-diff V3) | **일치** | ±5%는 글에 없고 KISTA도 선택지 없음 |
| 15 | Pool 사용한도 시작값: 적립 | 100% | RAMP:28 `recurringAmount>0 → 1` | **일치** | |
| 16 | Pool 사용한도 시작값: 거치 | 90% | RAMP:29 `==0 → 0.75` | **차이** | 글 90, KISTA 75 |
| 17 | Pool 사용한도 시작값: 인출 | 80% | RAMP:29 `<0 → 0.1` | **차이** | 글 80, KISTA 10 |
| 18 | Pool 사용한도 도달값(유형별): 적립 | 75% | RAMP:30 floor `0.5` | **차이** | 글 75, KISTA 50. 단 글의 75가 하한인지 불명확 → U4 |
| 19 | Pool 사용한도 도달값(유형별): 거치 | 50% | RAMP:30 floor `0.5` | **일치** | |
| 20 | Pool 사용한도 도달값(유형별): 인출 | 25% | RAMP:30 `<0 → 0.1` | **차이** | 글 25, KISTA 10 |
| 21 | Pool 한도 감소 속도·하한 | "6개월마다 5%씩 줄여나간다. 최소 10%까지" | SVD:48-55 `poolLimitRateAt`: `weeks < pGraceWeeks(기본 52)`이면 초기값, 이후 `pStepWeeks(기본 26)`마다 −0.05(`POOL_LIMIT_STEP`), `poolLimitFloor` 하한 (RAMP:38-39) | 차이 (유예) | 5%p·6개월(26주)은 일치. KISTA는 52주 유예 후 시작, 글은 유예 언급 없음(→ U4). 하한은 유형별(적립·거치 0.5, 인출 0.1), 글은 최소 10% 하나 |
| 22 | Pool 한도 적용 기준 금액 | 글에 없음 | SVD:41-44 `poolLimit = 개장 예수금 × poolLimitRate`; VRSTR:78-82 `governanceLimit = poolLimit − poolUsed`(poolLimit=0이면 현재 pool), `remainingBudget = min(governanceLimit, pool)` | 글에 없음 | → U4. 기준 시점(사이클 개장 시점 vs 현재)이 글에 없다 |
| 23 | 인출금 크기 | "인출금은 매월 시드의 0.5%" | 인출액은 사용자 입력 `recurringAmount`. 등록·재설정 시 VAL:74-83 가드: `\|recurringAmount\| × 100 × 4 ÷ intervalWeeks ≤ 자산` → 4주당 인출 ≤ 자산의 1% | 차이 (가드 상한) | 글 0.5%/월은 권장 크기로 읽히나 KISTA 가드는 1%/4주(2배)까지 허용. 가드가 기본값이 아니라 상한이라 "위반"이 아닌 허용 범위 차이. 월 = 4주로 본 환산은 추정 |
| 24 | 매수 사다리 가격식·단 수 | 글에 없음 | VRPOS:54-57 `buyPrice(m) = lowerBand ÷ (holdings+m−1)`, VRSTR:130-148 m=1..20, 1주씩 | 글에 없음 | 근거는 quantstack 08-formula-diff V4(주문표 산식 자체가 LIT에 없음, U1 판정 유지) |
| 25 | 매도 사다리 가격식·단 수 | 글에 없음 | VRPOS:60-62 `sellPrice(s) = upperBand ÷ (holdings−s+1)`, VRSTR:187-198 최대 20단, holdings>20이면 마지막 단 잔여 전량 | 글에 없음 | |
| 26 | 주문 방식·주기 | 글에 없음 | 사다리 매수·매도 `LIMIT`+`AT_OPEN`(VRSTR:146,196), bootstrap `LOC`+`AT_CLOSE`(VRSTR:101), 접수 전 cap(현재가 ×1.05) 재산정(VRCOS:69-74) | 글에 없음 | 글에 사이클 첫날 주문표 작성이나 2주 고정 표 서술도 없음 |
| 27 | 0일차·시작 방법 | 글에 없음 | V=0 또는 첫 단이 잔여예산 초과 시 bootstrap: `remainingBudget`을 캡가 LOC 1건(VRSTR:33-35,68-71,93-103); `holdings>0 && V=0`이면 사다리 skip(VRSTR:41-43) | 글에 없음 | 글의 0.15 P/V 같은 배분 비율 서술도 없다. quantstack §B/§C(P/V표)와도 글이 연결되지 않는다 |
| 28 | 글의 첨부 이미지(P/V/G별 CAGR·MDD 표) | TQQQ 49.68%/−69.92%, QLD 36.19%/−51.72%, `P/V/5..100` CAGR 50.94→34.06% 등 | 코드·문서에 대응 항목 없음 | 글에 없음(설명) | 표의 조건이 글에 없어 대조하지 않음 → U6 |
| 29 | V'≤0·예수금 음수 보류 | 글에 없음 | ROLL:108-116, 130-141 | KISTA 고유 | 안전 가드. 글과 충돌하지 않음 |

## 2. 요약

- **일치 (확정)**: 실력공식 식(#1), 적립·거치 G 기본 10(#9), 밴드(#14), 적립 시작 Pool 한도 100%(#15), 거치 Pool 한도 50%(#19), 적립·인출 항 부호(#6), 감소 단위 5%p/6개월(#21 일부).
- **차이 (글에 값이 명시된 항목)**: 8건 — 인출식 G 20 vs 40(#10), G 변경 주기 1년 vs 26주 램프(#11), 거치 Pool 시작 90 vs 75(#16), 인출 Pool 시작 80 vs 10(#17), 적립 Pool 도달 75 vs 하한 50(#18), 인출 Pool 도달 25 vs 10(#20), 한도 감소 유예 없음 vs 52주(#21), 인출금 0.5%/월 vs 가드 1%/4주(#23). (#21은 글의 유예 부재가 불명확이라 반쯤 추정, #23은 가드라서 약한 차이)
- **글에 없음 (근거 못 함)**: 실력공식 적용 조건·유도(#4), 적립·인출 반영 시점(#7), 한도 기준 금액(#22), 사다리 가격식·단 수(#24,25), 주문 방식·주기(#26), 0일차(#27). 08-formula-diff의 "LIT 불명확" 판정이 이 글로도 해소되지 않는다.
- **핵심 결론**: 소유자가 의도한 실력공식은 글과 완전히 일치한다. 값 표(G 인출식, Pool 한도율)는 글보다 KISTA 쪽이 더 보수적(인출 G 40, 인출 Pool 한도 10%)이며 08-formula-diff V2·V5가 글로도 확정된다. 즉 문서에는 이 차이가 "자체 확장·튜닝"인지 글의 오독인지 소유자 판정이 필요하다(코드 변경은 이 작업 범위 아님).

## 3. 불명확 (글이 모호하거나 내부 모순, 추측으로 메우지 않음)

- U1. 기본공식 2행 `V1×(1+pool/V1)/G`는 `(V1+pool)/G`로 계산되어 3행 `V1+pool/G`와 같지 않다. "상승률"도 미정의. 글 내부 모순이며 KISTA 코드는 3행 형태만 쓰므로 영향 없음(글 오타 추정은 하지 않음).
- U2. 실력공식의 유도·의미·적용 조건·근거 링크가 글에 없다. 기본·실력 중 무엇을 쓸지의 권고도 없다.
- U3. G 설명에서 유형별(10/20, 매년 +1)과 시드 규모 기준(10억→10 ... 100억→100)이 병기된다. 관계 불명. E에 예수금이 포함되는지도 글에 없다.
- U4. Pool 한도: 75/50/25와 시작값 100/90/80, 6개월 −5%p, 최소 10%의 관계(75/50/25에서 멈추는지, 10%까지 가는지), 유예 유무, 기준 금액 모두 불명확.
- U5. 적립·인출 반영 시점과 식의 pool이 반영 전·후 어느 쪽인지. "매월 0.5%"와 2주 사이클의 환산.
- U6. 첨부 표의 조건(기간·종목·P/V/G 매핑·"레버리지" 정의)과 표기 `V1`/`V_1` 혼용.
