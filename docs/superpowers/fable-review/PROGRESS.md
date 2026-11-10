# kista-api Fable 검토 진행 원장

새 세션은 `CHARTER.md`를 먼저 읽는다.

## 규모 기준선 (2026-11-11)

- main 약 55,500줄: root 16,501 / trading-core 35,581 / shared 3,436
- test 약 74,500줄: root 21,102 / trading-core 53,406
- 큰 모듈: tradingcore.trading 16,324, broker 7,632, stats 5,357, api.finance 5,024, admin 3,697, matching 2,725

## 상태

| 단계 | 상태 | 산출물 |
|---|---|---|
| 0 기준 합의 | 완료 2026-11-11 | CHARTER.md |
| 1 측정 | 진행 중 (서브에이전트 6개 디스패치) | findings/ |
| 2 주제 선정 | 대기 | |
| 3 지침서 | 대기 | guides/ |
| 4 레포 간 계약 | 대기 | cross-repo.md |

## 1단계 측정 에이전트

| 파일 | 내용 | 상태 |
|---|---|---|
| findings/01-vertical-slices.md | 대표 기능 수직 추적(파일·줄 수) | 디스패치 |
| findings/02-type-duplication.md | 개념별 타입 복제, 포트·구현 1:1, 단일 호출 간접층 | 디스패치 |
| findings/03-parallel-structures.md | 전략 종류 × 브로커 평행 구조 | 디스패치 |
| findings/04-test-bloat.md | 테스트 비대 원인 | 디스패치 |
| findings/05-frozen-decisions.md | 유지 확정·재론 금지 목록 요약(재심 대상) | 디스패치 |
| ../../reference/quantstack/ | quantstack.app 전 페이지 정리 | 디스패치 |

## 다음 할 일

1. findings 6종이 모이면 Fable이 읽고 주제 5~8개 선정, 이 표에 기록
2. quantstack 정리본과 `docs/agents/modules/tradingcore/trading-formulas.md`(무한매수법 4.0) 대조는 별도 주제

## 다른 세션

- kista-ui, kista-infra Fable 세션에 2026-11-11 착수 메시지 전송함(헌장 경로 안내)
