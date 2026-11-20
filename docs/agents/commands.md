## 자주 쓰는 명령어

```bash
./gradlew bootJar                                               # app.jar
./gradlew :bootRun --args='--spring.profiles.active=local'     # 로컬 실행(기본 role web, `:` 필수 — 없으면 :shared:bootRun 실패)
./gradlew test                                                  # 전체 테스트
./gradlew :test --tests 'com.kista.api.architecture.*'          # 루트 ArchUnit·Modulith(trading-core 클래스도 검사)
./gradlew :trading-core:test --tests 'com.kista.tradingcore.architecture.*'
./gradlew :trading-core:test --tests 'com.kista.tradingcore.trading.domain.*'  # trading-core 소속 테스트는 :trading-core:test로
grep -l 'failures="[1-9]' build/test-results/test/TEST-*.xml   # 실패 진단은 stdout보다 XML
```
- `--tests` 필터는 서브프로젝트를 지정한다(`:test`/`:trading-core:test`). 접두사가 없으면 `:shared`가 "No tests found"로 BUILD FAILED
- 로컬 테스트 JVM은 `testJvmSlot` 빌드 서비스로 하나씩만 뜬다(`CI` 없을 때)
- 배포 스크립트 검증(bats·shellcheck·actionlint) → `.github/CLAUDE.md`
- 로컬 dev 토큰(`POST /api/auth/dev-token` 등)·dev 시드(`scripts/dev-seed/seed.sh`)·매매 로컬 기동(`KISTA_ROLE=all` 또는 web·worker 2프로세스) → `local-dev.md`
- Docker: `docker compose up -d`(PostgreSQL·Redis·Prometheus·Grafana, 앱은 별도 실행), `docker compose down -v`(로컬 DB 초기화)
- 로컬 로그: `logs/kista-api.log`

**`KISTA_ROLE=all` 같은 일회성 override로 띄웠다가 원복했다면 재기동 후 실제 동작으로 확인한다**(예: `/api/admin/scheduler/*`가 404 — 로컬 root 기본은 `application-local.yml`의 `kista.role: web`). 검증 전에는 "원복 완료"라고 보고하지 않는다. IntelliJ MCP `execute_run_configuration` envs 등은 persist되지 않는다고 안내돼도 믿지 않는다. 로컬과 운영이 관리자 봇을 공유하고 스케줄러 락은 DB별이라, 켜 둔 채면 운영과 같은 cron이 돌아 알림이 중복 발송된다.
