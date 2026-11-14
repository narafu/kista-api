#!/usr/bin/env bash
# 문서 크기 예산 검사 — 항상 로드 문서와 모듈 문서가 바이트 예산을 넘으면 실패
# 사용: check-doc-budget.sh [레포 루트] (기본: 스크립트 기준 ../..). 파일이 없으면 건너뛴다.
set -euo pipefail

root="${1:-$(cd "$(dirname "$0")/../.." && pwd)}"
cd "$root"
fail=0

over() { echo "$1 $2 > $3" >&2; fail=1; }

# 항상 로드 5파일 합계
sum=0
for f in CLAUDE.md docs/agents/{constraints,architecture,testing,commands}.md; do
  [[ -f $f ]] && sum=$((sum + $(wc -c < "$f")))
done
((sum <= 26000)) || over "always-loaded(합계)" "$sum" 26000

# 모듈 문서별 예산 (trading-formulas.md 제외, 벤더 *-api.md는 대상 아님)
while IFS= read -r f; do
  case "$f" in
    */trading-formulas.md) continue ;;
    docs/agents/modules/tradingcore/trading.md) budget=10000 ;;
    docs/agents/modules/shared/platform.md) budget=8000 ;;
    docs/agents/modules/tradingcore/stats.md|docs/agents/modules/tradingcore/broker.md|docs/agents/modules/tradingcore/dca.md|docs/agents/modules/tradingcore/matching.md) budget=6000 ;;
    *) budget=5000 ;;
  esac
  size=$(($(wc -c < "$f")))
  ((size <= budget)) || over "$f" "$size" "$budget"
done < <(find docs/agents/modules -name '*.md' 2>/dev/null | sort)

exit "$fail"
