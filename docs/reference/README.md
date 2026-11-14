# 매매 규칙 참고 문헌

KISTA 매매 공식의 근거 문헌 보존소다. 공식 자체와 KISTA 변형은 `docs/agents/modules/tradingcore/trading-formulas.md`가 SSOT이고, 여기는 그 근거만 둔다.

## 출처 위계 (충돌 시 위가 우선)

| 순위 | 출처 | 무한매수법 | VR |
|---|---|---|---|
| 1 | 원저작자 라오어 네이버 카페 글(카페 ID 30372164) | `laor/infinite-v4.md` | VR 공식 글은 영상 설명이라 수집하지 않았다. 백테스트 글 3건만 `laor/vr-velog.md` 9절에 반영 |
| 2 | 소유자 velog 글(카페 글 인용·요약) | — | `laor/vr-velog.md`(실력공식·G·밴드·Pool 한도) |
| 3 | quantstack.app 제3자 정리본(2026-11-11 수집) | `quantstack/infinite-buying-v4.md` B3 계산기 검증값·D 해설만 | `quantstack/value-rebalancing.md`(기본공식, 0일차 세팅 해설) |

## 파일

| 파일 | 내용 |
|---|---|
| `laor/infinite-v4.md` | 무한매수법 V4.0 일반·리버스모드 원문 규칙과 예시 수치(쪽 번호 포함) |
| `laor/vr-velog.md` | https://velog.io/@narafu/VR 정리 + 카페 백테스트 글 3건의 조건·결론 |
| `quantstack/infinite-buying-v4.md` | 계산기 로직·검증값(B), 사이트 구현 해석(C), 해설(D), 사이트 내 모순(E). 규칙(A)은 원문으로 대체됐다 |
| `quantstack/value-rebalancing.md` | VR 5.0 기본공식·Pool 한도·0일차 세팅·백테스트 표(해설) |

## 정리 규칙

- 원문에 없는 내용은 추측으로 메우지 않고 "원문에 없음"·"불명확"으로 표시한다.
- 원문 글·PDF 전문은 레포에 복제하지 않는다. 규칙 요약·예시 수치·링크만 둔다.
- quantstack의 나머지 47쪽(구버전 v2.x·v3.0, 입문·분석·FAQ, VR 유형별 해설, 계산기 UI, 사이트 메타·약관)은 KISTA 규칙과 무관하거나 위 두 파일과 중복이라 2026-11-15 삭제했다. 원본은 git 이력(`a7ffd4a0c`)에 있다.
- KISTA 코드와의 대조 기록: `docs/superpowers/fable-review/findings/08-formula-diff.md`(quantstack), `13-vr-velog-diff.md`(velog).
