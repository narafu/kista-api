# Server deployment (OCI)

`kista-web`(HTTP)·`kista-worker`(매매 엔진·배치, 같은 이미지 2 role)를 단일 인스턴스(현재 OCI)에서 Docker Compose로 운영한다. 서버 적용은 `kista-infra`의 reconcile이 한다(아래 "배포 흐름"). 리버스 프록시(Caddy)·Postgres·Redis는
`kista-infra` 레포가 소유하며, 이 레포는 `shared_net`(Caddy 라우팅)·`data_net`(Postgres/Redis 접근) 두 외부
네트워크에 합류만 한다.

## 서버 레이아웃

```text
/opt/kista-api/
├── .env                    ← kista-infra 배포 워크플로가 렌더링·덮어쓴다 (이 레포의 Actions는 관여하지 않음, 아래 "GitHub Secrets" 참고)
├── releases/<id>/          ← kista-infra가 config SHA의 deploy/server/{docker-compose.yml,roles,bluegreen,caddy/} + images.env로 구성한 bundle
├── current → releases/<id> ← 마지막 적용 성공 release
├── previous → releases/<id>
└── reconcile.log           ← 마지막 reconcile 출력
```

Caddy 라우팅 스니펫(`deploy/server/caddy/kista-api.caddy`)은 이 디렉토리가 아니라 reconcile이 `/opt/kista-infra/routes/kista-api.caddy`에 설치한다(kista-infra caddy가 `routes/`를 ro 마운트).

## 서버 초기 설정·백업·복구

서버 초기 설정(OCI 인스턴스·예약 IP·방화벽·Docker·로그 로테이션)·백업·복구는 kista-infra 소관이다 — `kista-infra/README.md` "서버 재구축"·"백업/복구" 참고.

## GitHub Secrets

| Secret | 설명 |
|--------|------|
| `INFRA_APP_PRIVATE_KEY` (secret) + `INFRA_APP_CLIENT_ID` (Actions variable) | GitHub App `kista-infra-dispatch`(kista-infra·kista-api·kista-ui에 설치, Contents read/write·Statuses write — statuses 쓰기는 kista-infra `reconcile.yml`이 한다) — Actions 변수 `INFRA_APP_CLIENT_ID` + secret `INFRA_APP_PRIVATE_KEY`, 워크플로가 실행마다 `actions/create-github-app-token`으로 1시간짜리 설치 토큰 발급(장기 PAT 없음). 이 레포는 서버 SSH 키를 갖지 않는다 |

`.env`는 이 레포의 Actions가 아니라 `kista-infra` 레포의 배포 워크플로가 관리한다 — `kista-infra`에 sops + age로 암호화 커밋된 `secrets/kista-api.env`를 복호화해 매 배포마다 `/opt/kista-api/.env`를 렌더링·덮어쓴다. 값을 바꾸려면 `kista-infra`에서 `sops edit secrets/kista-api.env`로 수정 후 커밋·배포해야 하며(절차는 `kista-infra/secrets/README.md`), 서버에서 `.env`를 직접 수정해도 다음 kista-infra 배포 시 되돌아간다.

`.env`가 바뀌면 kista-infra 배포가 `reconcile.sh kista-api current`로 current release를 재적용한다 — compose가 env_file 내용 변경을 감지해 2 role을 재생성하고 헬스 게이트까지 거친다(이 레포 재배포 불필요).

## .env 내용

운영 키 목록 원본은 레포 루트 `.env.example`이다. `REDIS_URL`은 `docker-compose.yml`에 `redis://redis:6379`로 하드코딩돼 `.env` 설정이 필요 없다. JVM 기본값도 compose에 반영돼 있다.

## 배포 흐름

설계: `kista-infra/docs/superpowers/specs/2026-10-02-deploy-reconcile-design.md`

1. `plan` 잡 — 기준점은 kista-infra `state/kista-api.yml`(마지막 서버 적용 성공 상태). role별 `state SHA..이 커밋` diff를 `detect-deploy-scope.sh`로 분류해 바뀐 role만 이 커밋 SHA로, `deploy/server/**`·`deploy/hooks/**`가 바뀌면 `config`도 이 커밋으로(이미지 재빌드 없이 compose·Caddy만 재적용). state보다 옛 커밋(옛 run Re-run)이면 아무것도 하지 않는다. 수동 실행은 config·전 role을 이 커밋으로
2. `deploy-checks` — 배포 판정 스크립트·hook shellcheck·bats(`compose-invariants.bats` 포함) + Flyway 마이그레이션 검사
3. `verify`(전체 테스트 + `integration`, ArchUnit·`CaddyRoutingTest` 포함)와 이미지 빌드(role 변경 시만, kista-web·kista-worker 공용 단일 이미지, SHA 태그)가 병렬
4. `deploy` — kista-infra에 `repository_dispatch(deploy)`로 `{app, config, roles(전 role SHA), sha(이 커밋), force}`를 보내고 끝난다(대기 없음). 서버 적용·헬스 게이트 결과는 kista-infra `reconcile.yml`이 이 커밋의 commit status `deploy/production`(pending → success/failure, `target_url`은 infra run)으로 되돌려 쓴다 — 워크플로 run의 초록불은 "요청 전송 완료"이고 배포 완료는 커밋 status로 본다
5. kista-infra `Reconcile App` — SHA·이미지 검증 → state와 신선도 병합 → config SHA로 bundle 구성 → `deploy/hooks/pre-apply.sh`(EPR 고아 검사 — kista-worker 이미지가 바뀔 때, `docs/agents/docker-infra.md` "배포 직전 EPR 정리 런북") → 서버 `reconcile.sh`:
   - `deploy/server/roles` 순서(kista-worker → kista-web)로 `docker compose up -d --no-deps <role>` — compose config-hash가 같으면 재생성하지 않는다
   - `deploy/server/bluegreen`에 있는 role(kista-web뿐)은 stop-first가 아니라 blue/green으로 바꾼다: 스케일 1→2로 새 컨테이너를 띄워 헬스 게이트를 통과시키고, 옛 컨테이너는 모든 role·Caddy 통과 뒤에 제거한다. kista-worker는 겹쳐 뜨면 이중 실행이라 stop-first다
   - 컨테이너가 바뀐 role만 헬스 게이트: Docker 헬스 healthy(compose healthcheck가 `/actuator/health/readiness` — readinessState·db·redis UP), 10초 간격 최대 5분. Caddy `health_uri`는 liveness
   - 모든 role 통과 후 Caddy 스니펫 설치 + reload 1회
   - 실패 시 이번에 바꾼 role을 역순으로 직전 release(이미지·compose·스니펫)로 롤백 — 앱 단위 전부 아니면 전무
   - 성공 시 `current` 전환, 오래된 release·미사용 이미지 태그 정리 → kista-infra가 `state/kista-api.yml` 커밋
6. Caddy `lb_try_duration 120s`(kista-infra Caddyfile `(upstream)` snippet)가 컨테이너 재시작 공백을 클라이언트에 투명하게 처리

## 배포 시간 제한

없다 — kista-worker는 재기동 시 진행 중이던 매매 배치를 재개한다(`docs/agents/workflow-batch.md` "재기동 종료·재개"). 전제: kista-worker는 단일 인스턴스·stop-first 교체·`stop_grace_period` ≥ 200s(`.github/tests/compose-invariants.bats`가 잠금). infra compose(postgres·redis) 변경만 kista-infra가 KST 시각 창으로 막는다.

## 롤백 Runbook

**자동 롤백**: reconcile 실패 시 그 요청에서 바꾼 role 전부를 직전 release로 되돌린다(exit 1, kista-infra run 실패 + 텔레그램 알림). 롤백한 컨테이너도 헬스 게이트를 거친다 — exit 1은 롤백 뒤 헬스까지 통과했다는 뜻이다. 롤백 불가·롤백 실패·롤백 후 헬스 게이트 실패는 exit 2 — 아래 수동 절차.

**수동 롤백**: 서버에서 `nohup bash /opt/kista-infra/bin/reconcile.sh kista-api <release id>`를 실행한 뒤 kista-infra `state/kista-api.yml`을 실제 적용 SHA로 수동 커밋한다(GHCR이 public이라 정리된 이미지도 다시 pull). 배경은 `kista-infra/README.md` "수동 복구 (reconcile exit 2)". 특정 옛 SHA로 되돌리려면 state를 그 SHA로 수정·커밋한 뒤 kista-infra `Reconcile App`을 workflow_dispatch(app=kista-api, config/roles에 그 SHA)로 실행 — 신선도 병합은 state보다 옛 요청을 무시하므로 state 수정이 먼저다.

**Flyway 관련 롤백 주의**: 신규 마이그레이션이 포함된 배포는 `validate-on-migrate: true` 때문에 이전 이미지로 롤백 시 기동 실패할 수 있음. 이 경우 DB 마이그레이션 수동 롤백 후 이미지 롤백 필요. Breaking migration 배포는 별도 주의 필요. 스키마 재편 이행은 2026-09 완료, 절차는 git 태그 `archive/docs-2026-11` 참고. Flyway 이력 테이블은 서비스별로 `flyway_schema_history_api`(root)·`flyway_schema_history_trading`(trading)이다.

**이미지 디스크 정리 참고**: 적용 성공 시 이 레포 이미지(`ghcr.io/narafu/kista-api`) 중 컨테이너가 쓰지 않는 태그를 `docker rmi`로 지우고 dangling 레이어를 `prune -f`로 정리한다(`prune -a`는 다른 레포가 막 pull한 이미지까지 지울 수 있어 쓰지 않는다).

## Flyway 배포 주의사항

- **Additive migration** (컬럼 추가, 테이블 추가): 정상 무중단 배포
- **Breaking migration**: 구 컨테이너가 이미 종료된 후 실행되므로 충돌 없으나, 실패 시 이전 이미지 롤백 불가. 별도 다운타임 계획 필요.

## 모니터링

- **메트릭**: 앱이 `micrometer-registry-otlp`로 Grafana Cloud에 60초 간격 직접 OTLP push (`management.otlp.metrics.export`, `GRAFANA_CLOUD_OTLP_*` 환경변수) — 별도 사이드카 불필요
- **Redis 영속성**: kista-infra 레포 소유 — 상세 설정(AOF 등)은 kista-infra README 참고. 앱 서비스에 `depends_on: redis`는 의도적으로 미사용(Spring Data Redis lazy connection이라 앱 부팅을 막지 않음)
- **헬스체크**: UptimeRobot → `https://<API 도메인>/actuator/health` 5분 간격 (full health — DB·Redis 포함)
- **스케줄러 감시**: Healthchecks.io dead-man's-switch — `TradingOpenScheduler`/`TradingCloseScheduler` 실행 완료 시 `HeartbeatPort.pingOpen()`/`pingClose()` GET 핑. `HEARTBEAT_OPEN_URL`/`HEARTBEAT_CLOSE_URL` 미설정 시 핑 생략(배포 안전). healthchecks.io 콘솔에서 각 체크의 예상 주기(개장 ~22:30 KST, 마감 ~04:30 KST + DST 여유)를 등록해야 실제로 미실행이 감지됨
- **로그**: `docker logs -f $(docker ps -qlf label=com.docker.compose.service=kista-web)` (서버 SSH — kista-web은 blue/green이라 컨테이너 이름 미고정, kista-worker는 `docker logs -f <이름>` — 서버 루트엔 compose 파일이 없어 `docker compose logs`는 쓰지 않는다)

## fida

fida는 이 인스턴스(A: kista-api/kista-ui/kista-infra)와 **별도의 OCI 인스턴스(B)**에서 독립 운영된다 — 같은 서버에
병행 배포되지 않으며, `kista-infra`의 Caddy·네트워크·시크릿 어느 것도 fida를 대상으로 하지 않는다. 따라서 fida 배포
절차는 이 레포·kista-infra 레포 양쪽 모두 범위 밖이다. fida→kista-api `/api/internal/**` 호출은 공인 도메인을
경유하므로(무변경) 이 전환과 무관하게 그대로 동작한다.
