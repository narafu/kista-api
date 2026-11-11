# 06. 문서·불필요 파일 전수 조사 (읽기 전용)

> 조사일 2026-11-11, 대상 kista-api. 제외: `docs/superpowers/fable-review/`, `docs/reference/quantstack/`. 토큰은 추정치(한글 1자≈1토큰, 그 외 3.5자≈1토큰; 실제는 최대 1.5배). 스크립트: scratchpad `t06/`.

## A. 문서 지도

### A-1. 디렉토리별 md (git 추적, 265개 = 조사 대상)

| 디렉토리 | 개수 | 줄 | 바이트 |
|---|---:|---:|---:|
| docs/superpowers/plans/archive | 110 | 81,226 | 4,777,398 |
| docs/superpowers/specs/archive | 69 | 9,529 | 871,871 |
| docs/agents (루트 21개) | 21 | 2,249 | 353,663 |
| docs/agents/modules/tradingcore | 12 | 398 | 134,891 |
| docs/history | 1 | 241 | 72,115 |
| docs/agents/modules/api | 7 | 147 | 45,223 |
| docs/reviews | 2 | 353 | 47,348 |
| docs/agents/modules/shared | 3 | 71 | 25,236 |
| docs (architecture-map.md) | 1 | 283 | 17,007 |
| deploy/server (+schema-reorg) | 2 | 301 | 29,579 |
| 루트 (CLAUDE.md, README.md) | 2 | 130 | 13,013 |
| .claude/skills·agents·rules | 8 | 347 | 24,000 |
| 모듈 CLAUDE.md 포인터(5줄짜리) | 31 | 약 155 | 약 7,000 |

핵심: **archive 2곳이 md 바이트의 약 90%(5.6MB / 약 6.2MB)**. 줄은 짧은데 바이트가 큰 docs/agents는 한 줄이 매우 긴 문서(밀도 높음) — `modules/tradingcore/stats.md` 30줄 20KB, `trading.md` 90줄 35KB.

### A-2. 자동 로드 사슬

| 로드 조건 | 파일 | 줄 | 토큰(추정) |
|---|---|---:|---:|
| 항상 | /kista/CLAUDE.md (상위) | 22 | 245 |
| 항상 | kista-api/CLAUDE.md | 63 | 2,358 |
| 항상 (@import) | docs/agents/commands.md | 48 | 1,268 |
| 항상 (@import) | docs/agents/architecture.md | 72 | 2,846 |
| 항상 (@import) | docs/agents/constraints.md | 141 | 5,375 |
| 항상 (@import) | docs/agents/testing.md | 50 | 1,806 |
| **항상 합계** | | **396줄** | **약 13,900 (범위 14k~20k)** |
| `*.java` 편집 시 | .claude/rules/java-encoding.md | | 약 200 |
| stats 컨트롤러 편집 시 | .claude/rules/statistics-controllers.md | | 약 260 |

(전역 ~/.claude/CLAUDE.md·MEMORY.md는 별도로 항상 로드, 이 수치에 미포함.)

모듈 디렉토리 CLAUDE.md는 모두 5줄짜리 포인터이며 `@` 상대경로로 하나의 문서만 import한다(디렉토리 파일 접근 시 로드):

| 디렉토리 | import 대상 | 토큰 |
|---|---|---:|
| tradingcore/trading (main) | modules/tradingcore/trading.md | 10,504 |
| tradingcore/trading (test) | modules/tradingcore/trading-testing.md | 1,535 |
| tradingcore/matching | matching.md | 3,032 |
| tradingcore/broker | broker.md | 3,907 |
| tradingcore/stats | stats.md | 6,250 |
| tradingcore/privacy·account·notify·marketcalendar·web | 각 모듈 md | 1,380 / 1,749 / 1,228 / 538 / 1,048 |
| broker/adapter/out/{kis,toss,meritz,binance,upbit} | docs/agents/*-api.md | kis 18KB, toss 17KB, binance 13KB, meritz 12KB, upbit 9KB (바이트) |
| api/{admin,benchmark,finance,market,notify,user,web} | modules/api/*.md | 4.7~9.6KB |
| shared/{contract,kernel,platform} | modules/shared/*.md | platform 16KB |
| db/migration, db/migration-trading | docs/agents/flyway.md | 약 3,000 |
| .github | (`.github/CLAUDE.md` 11줄 직접 기술) | |

**trading 모듈 작업 시 추가 로드**: 기본 13.9k + trading.md 10.5k = **약 24.4k**. 테스트 디렉토리 포함 시 +1.5k, `trading` 하위에서 matching·broker·stats도 건드리면 +3.0k/+3.9k/+6.3k → 최대 약 39k.

**자동 로드 안 되는 문서(필요 시 직접 Read)**: modules/tradingcore/dca.md(13KB), trading-formulas.md(18KB), own-type-ledger.md, workflow*.md(5개), local-dev.md, docker-infra.md(32KB), scheduler-time-table.md, code-quality-backlog.md(140KB), history/, reviews/, architecture-map.md, README.md. 참고: dca.md는 어떤 CLAUDE.md도 import하지 않음(고아 — 해당 모듈 작업 시 자동 로드 안 됨).
