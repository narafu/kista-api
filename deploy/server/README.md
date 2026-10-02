# Server deployment (OCI)

`kista-api`(HTTP)·`kista-scheduler`(배치)·`kista-trading`(trading-core, 같은 이미지 3 role)을 단일 인스턴스(현재 OCI)에서 Docker Compose로 운영한다. 서버 적용은 `kista-infra`의 reconcile이 한다(아래 "배포 흐름"). 리버스 프록시(Caddy)·Postgres·Redis는
`kista-infra` 레포가 소유하며, 이 레포는 `shared_net`(Caddy 라우팅)·`data_net`(Postgres/Redis 접근) 두 외부
네트워크에 합류만 한다.

## 서버 레이아웃

```text
/opt/kista-api/
├── .env                    ← kista-infra 배포 워크플로가 렌더링·덮어쓴다 (이 레포의 Actions는 관여하지 않음, 아래 "GitHub Secrets" 참고)
├── releases/<id>/          ← kista-infra가 config SHA의 deploy/server/{docker-compose.yml,roles,readiness,bluegreen,caddy/} + images.env로 구성한 bundle
├── current → releases/<id> ← 마지막 적용 성공 release
├── previous → releases/<id>
├── caddy/kista-api.caddy   ← reconcile이 current bundle에서 설치(kista-infra caddy가 ro 마운트 — 경로 변경 금지)
└── reconcile.log           ← 마지막 reconcile 출력
```

## 초기 서버 설정 (최초 1회)

1. OCI 인스턴스(이미 생성됨): `VM.Standard.A1.Flex`(Ampere arm64), 2 OCPU, 12GB RAM, 부트 볼륨 50GB, Ubuntu 24.04 LTS — **arm64이므로 배포 워크플로가 `linux/arm64`로 이미지를 빌드한다**(다른 아키텍처로 재생성 시 `server-deploy.yml`의 `platforms` 값도 함께 변경 필요). 최초 12GB/부트 200GB로 생성했다가, free-tier 스토리지(리전당 200GB) 전량을 부트 볼륨 하나가 점유해 다른 인스턴스를 만들 여유가 없어져 부트 볼륨만 50GB로 재생성함(2026-08-03) — OCI는 볼륨 in-place 축소를 지원하지 않아 재생성이 유일한 방법. OCPU·메모리는 볼륨과 달리 Flexible shape라 `oci compute instance update --shape-config`로 실행 중에도 변경 가능(적용에 재부팅 필요) — 계획한 인스턴스 3대 합계가 OCI Always Free Ampere A1 총 한도(4 OCPU + 24GB RAM)를 정확히 채우도록 재생성 직후 8GB→12GB로 조정함
2. 정적 공인 IP: 인스턴스 생성 시 Reserved Public IP 할당(또는 별도 예약 공인 IP 연결) → 도메인 A 레코드 연결 — **DNS 제공자가 프록시 기능을 지원하면(예: Cloudflare) 반드시 "DNS only"(프록시 끔, 회색 구름)로 설정**. 프록시를 켜면 DNS 제공자가 TLS를 가로채 Caddy의 Let's Encrypt 자동 인증서 발급(HTTP-01 challenge)이 실패한다
   - **예약(Reserved) 공인 IP로 무중단 호스트 교체**: 반드시 Reserved(에페메럴 아님)로 할당해두면, 이후 인스턴스를 재생성해야 할 때(스펙 변경·볼륨 축소 등) 새 인스턴스를 임시 공인 IP로 완전히 기동·스모크 테스트한 뒤 예약 IP만 `oci network public-ip update --private-ip-id <새 인스턴스 private-ip-ocid>`로 재할당하면 된다 — 도메인·DNS·GitHub Secret(`SERVER_HOST`) 변경 없이 호스트를 교체할 수 있다(2026-08-03 부트 볼륨 축소 재생성 시 실사용)
3. 인바운드 포트 개방 — 2단계:
   - OCI 콘솔: 인스턴스가 속한 VCN의 Security List(또는 연결된 NSG)에 Ingress Rule 추가 — TCP `80`, `443`, source `0.0.0.0/0` (`22`는 이미 열려있을 것)
   - **주의**: OCI Ubuntu 이미지는 콘솔 레벨 방화벽 외에 OS 레벨에서도 `iptables`(netfilter-persistent)로 SSH 외 인바운드를 기본 차단해두는 경우가 있다. 콘솔에서 포트를 열었는데 접속이 안 되면 인스턴스에서 `sudo iptables -L INPUT -n --line-numbers`로 OS 방화벽 규칙을 먼저 확인할 것 — 막혀 있으면 80/443 허용 규칙 추가 후 `sudo netfilter-persistent save`로 저장한다 (OCI 이미지 버전에 따라 달라질 수 있어 실제 접속 테스트로 최종 확인 필요)
   - `8080`은 비공개 유지 — kista-infra의 Caddy가 `shared_net`을 통해 접근한다
4. Docker 설치:
   ```bash
   curl -fsSL https://get.docker.com | sh
   sudo usermod -aG docker $USER
   ```
5. 배포 경로 생성 및 `.env` 작성:
   ```bash
   sudo mkdir -p /opt/kista-api
   sudo chown $USER:$USER /opt/kista-api
   vi /opt/kista-api/.env   # 아래 .env 내용 참고
   ```
6. 로그 로테이션 설정 (`/etc/docker/daemon.json`):
   ```json
   {
     "live-restore": true,
     "log-driver": "json-file",
     "log-opts": { "max-size": "50m", "max-file": "5" }
   }
   ```
7. 자동 재부팅 비활성화 (스케줄러 보호):
   ```bash
   sudo sed -i 's/^Unattended-Upgrade::Automatic-Reboot "true"/Unattended-Upgrade::Automatic-Reboot "false"/' \
     /etc/apt/apt.conf.d/50unattended-upgrades
   ```

## GitHub Secrets

| Secret | 설명 |
|--------|------|
| `INFRA_APP_PRIVATE_KEY` (secret) + `INFRA_APP_CLIENT_ID` (Actions variable) | GitHub App `kista-infra-dispatch`(kista-infra에만 설치, Contents read/write·Actions read) — Actions 변수 `INFRA_APP_CLIENT_ID` + secret `INFRA_APP_PRIVATE_KEY`, 워크플로가 실행마다 `actions/create-github-app-token`으로 1시간짜리 설치 토큰 발급(장기 PAT 없음). 이 레포는 서버 SSH 키를 갖지 않는다 |

`.env`는 이 레포의 Actions가 아니라 `kista-infra` 레포의 배포 워크플로가 관리한다 — `kista-infra`에 GPG로 암호화 커밋된 `secrets/kista-api.env.gpg`를 복호화해 매 배포마다 `/opt/kista-api/.env`를 렌더링·덮어쓴다. 값을 바꾸려면 `kista-infra`의 `scripts/env.sh edit kista-api`로 수정 후 커밋·배포해야 하며, 서버에서 `.env`를 직접 수정해도 다음 kista-infra 배포 시 되돌아간다.

`.env`가 바뀌면 kista-infra 배포가 `reconcile.sh kista-api current`로 current release를 재적용한다 — compose가 env_file 내용 변경을 감지해 3 role을 재생성하고 헬스 게이트까지 거친다(이 레포 재배포 불필요).

## .env 내용

Redis는 kista-infra 레포가 소유하는 컨테이너로 `data_net`에서 `redis` alias로 접근하며, `REDIS_URL`이
`docker-compose.yml`에 `redis://redis:6379`로 하드코딩되어 있다 — `.env`에 별도 설정 불필요.

```dotenv
API_DOMAIN=api.example.com

DB_URL=
DB_USERNAME=
DB_PASSWORD=

JWT_SIGNING_KEY=
AES_ENCRYPTION_KEY=
ADMIN_KAKAO_IDS=

KAKAO_CLIENT_ID=
KAKAO_CLIENT_SECRET=
CORS_ALLOWED_ORIGINS=

TELEGRAM_BOT_TOKEN=
TELEGRAM_CHAT_ID=
INTERNAL_API_TOKEN=

TOSS_ADMIN_CLIENT_ID=
TOSS_ADMIN_CLIENT_SECRET=
ALPACA_API_KEY=
ALPACA_API_SECRET=
FIREBASE_SERVICE_ACCOUNT_JSON=   # 반드시 단일 행 JSON

GRAFANA_CLOUD_OTLP_ENABLED=true
GRAFANA_CLOUD_OTLP_ENDPOINT=       # 예: https://otlp-gateway-prod-ap-northeast-0.grafana.net/otlp
GRAFANA_CLOUD_OTLP_AUTH_HEADER=    # Grafana Cloud 발급 "Basic xxxx" 인증 헤더 값 전체

HEARTBEAT_OPEN_URL=          # healthchecks.io 개장 스케쥴러 dead-man's switch (미설정 시 핑 생략)
HEARTBEAT_CLOSE_URL=         # healthchecks.io 마감 스케쥴러 dead-man's switch (미설정 시 핑 생략)
```

Optional JVM override (기본값은 `docker-compose.yml`에 이미 OCI 12GB 인스턴스 기준으로 반영돼 있다 — 다른 값이 필요할 때만 `.env`에 명시):

```dotenv
JAVA_OPTS=-Xmx1280m -Xms256m -XX:MaxMetaspaceSize=320m -XX:ReservedCodeCacheSize=96m -XX:+UseG1GC -XX:+UseContainerSupport -Djava.security.egd=file:/dev/./urandom
```

## 배포 흐름

설계: `kista-infra/docs/superpowers/specs/2026-10-02-deploy-reconcile-design.md`

1. `plan` 잡 — 기준점은 kista-infra `state/kista-api.yml`(마지막 서버 적용 성공 상태). role별 `state SHA..이 커밋` diff를 `detect-deploy-scope.sh`로 분류해 바뀐 role만 이 커밋 SHA로, `deploy/server/**`·`deploy/hooks/**`가 바뀌면 `config`도 이 커밋으로(이미지 재빌드 없이 compose·Caddy만 재적용). state보다 옛 커밋(옛 run Re-run)이면 아무것도 하지 않는다. 수동 실행은 config·전 role을 이 커밋으로
2. `deploy-checks` — 배포 판정 스크립트·hook shellcheck·bats(`compose-invariants.bats` 포함) + Flyway 마이그레이션 검사
3. `verify`(전체 테스트 + `integration`, ArchUnit·`CaddyRoutingTest` 포함)와 이미지 빌드(role 변경 시만, 3 role 공용 단일 이미지, SHA 태그)가 병렬
4. `deploy` — kista-infra에 `repository_dispatch(deploy-kista-api)`로 `{config, roles(전 role SHA), request_id}`를 보내고 그 `Reconcile App` run이 끝날 때까지 대기(`wait-reconcile.sh`). 커밋의 초록불 = 서버 적용·헬스 게이트 통과. 대기 중 더 새 요청이 자리를 대체하면 생략으로 성공 처리(payload가 전체 상태라 손실 없음)
5. kista-infra `Reconcile App` — SHA·이미지 검증 → state와 신선도 병합 → config SHA로 bundle 구성 → `deploy/hooks/pre-apply.sh`(EPR 고아 검사 — kista-trading·kista-scheduler 이미지가 바뀔 때, `docs/agents/docker-infra.md` "배포 직전 EPR 정리 런북") → 서버 `reconcile.sh`:
   - `deploy/server/roles` 순서(kista-trading → kista-api → kista-scheduler)로 `docker compose up -d --no-deps <role>` — compose config-hash가 같으면 재생성하지 않는다
   - 컨테이너가 바뀐 role만 헬스 게이트: Docker 헬스(liveness) healthy **+** `deploy/server/readiness`의 readiness URL(readinessState·db·redis) UP, 10초 간격 최대 5분
   - 모든 role 통과 후 Caddy 스니펫 설치 + reload 1회
   - 실패 시 이번에 바꾼 role을 역순으로 직전 release(이미지·compose·스니펫)로 롤백 — 앱 단위 전부 아니면 전무
   - 성공 시 `current` 전환, 오래된 release·미사용 이미지 태그 정리 → kista-infra가 `state/kista-api.yml` 커밋
6. Caddy `lb_try_duration 120s`가 컨테이너 재시작 공백을 클라이언트에 투명하게 처리

## 배포 시간 제한

없다 — kista-trading은 재기동 시 진행 중이던 매매 배치를 재개한다(`docs/superpowers/specs/2026-10-02-trading-batch-resume-design.md`). 전제: kista-trading은 단일 인스턴스·stop-first 교체·`stop_grace_period` ≥ 200s(`.github/tests/compose-invariants.bats`가 잠금). infra compose(postgres·redis) 변경만 kista-infra가 KST 시각 창으로 막는다.

## 롤백 Runbook

**자동 롤백**: reconcile 실패 시 그 요청에서 바꾼 role 전부를 직전 release로 되돌린다(exit 1, kista-infra run 실패 + 텔레그램 알림). 롤백된 컨테이너의 헬스는 재검증되지 않으므로 알림을 받으면 `docker inspect --format '{{.State.Health.Status}}' <service>`로 확인. 롤백 불가·롤백 실패는 exit 2 — 아래 수동 절차.

**수동 롤백**: 서버에서 직전 release를 재적용한다(GHCR이 public이라 정리된 이미지도 다시 pull).
```bash
ls -l /opt/kista-api/current /opt/kista-api/previous
nohup bash /opt/kista-infra/bin/reconcile.sh kista-api "$(basename "$(readlink /opt/kista-api/previous)")"
```
그 뒤 kista-infra `state/kista-api.yml`을 실제 적용한 SHA로 커밋한다(안 하면 다음 요청의 기준점이 어긋난다). 특정 옛 SHA로 되돌리려면 state를 그 SHA로 수정·커밋한 뒤 kista-infra `Reconcile App`을 workflow_dispatch(app=kista-api, config/roles에 그 SHA)로 실행 — 신선도 병합은 state보다 옛 요청을 무시하므로 state 수정이 먼저다.

**Flyway 관련 롤백 주의**: 신규 마이그레이션이 포함된 배포는 `validate-on-migrate: true` 때문에 이전 이미지로 롤백 시 기동 실패할 수 있음. 이 경우 DB 마이그레이션 수동 롤백 후 이미지 롤백 필요. Breaking migration 배포는 별도 주의 필요. **스키마 재편 이행 릴리스는 자동 롤백 불가** — 옛 이미지의 `@Table(schema=...)`가 즉시 깨지고 헬스게이트 롤백도 옛 스키마명을 기대해 무력화된다. 수동 SSH 런북 `schema-reorg/RUNBOOK.md`(정방향·역방향 SQL)를 따른다. Flyway 이력 테이블은 서비스별로 `flyway_schema_history_api`(root)·`flyway_schema_history_trading`(trading)이다.

**이미지 디스크 정리 참고**: 적용 성공 시 이 레포 이미지(`ghcr.io/narafu/kista-api`) 중 컨테이너가 쓰지 않는 태그를 `docker rmi`로 지우고 dangling 레이어를 `prune -f`로 정리한다(`prune -a`는 다른 레포가 막 pull한 이미지까지 지울 수 있어 쓰지 않는다).

## Flyway 배포 주의사항

- **Additive migration** (컬럼 추가, 테이블 추가): 정상 무중단 배포
- **Breaking migration**: 구 컨테이너가 이미 종료된 후 실행되므로 충돌 없으나, 실패 시 이전 이미지 롤백 불가. 별도 다운타임 계획 필요.

## 모니터링

- **메트릭**: 앱이 `micrometer-registry-otlp`로 Grafana Cloud에 60초 간격 직접 OTLP push (`management.otlp.metrics.export`, `GRAFANA_CLOUD_OTLP_*` 환경변수) — 별도 사이드카 불필요
- **Redis 영속성**: kista-infra 레포 소유 — 상세 설정(AOF 등)은 kista-infra README 참고. `kista-api`에 `depends_on: redis`는 의도적으로 미사용(Spring Data Redis lazy connection이라 앱 부팅을 막지 않음)
- **헬스체크**: UptimeRobot → `https://{API_DOMAIN}/actuator/health` 5분 간격 (full health — DB·Redis 포함)
- **스케줄러 감시**: Healthchecks.io dead-man's-switch — `TradingOpenScheduler`/`TradingCloseScheduler` 실행 완료 시 `HeartbeatPort.pingOpen()`/`pingClose()` GET 핑. `HEARTBEAT_OPEN_URL`/`HEARTBEAT_CLOSE_URL` 미설정 시 핑 생략(배포 안전). healthchecks.io 콘솔에서 각 체크의 예상 주기(개장 ~22:30 KST, 마감 ~04:30 KST + DST 여유)를 등록해야 실제로 미실행이 감지됨
- **로그**: `docker logs -f $(docker ps -qlf label=com.docker.compose.service=kista-api)` (서버 SSH — kista-api는 blue/green이라 컨테이너 이름 미고정, kista-scheduler·kista-trading은 `docker logs -f <이름>` — 서버 루트엔 compose 파일이 없어 `docker compose logs`는 쓰지 않는다)

## fida

fida는 이 인스턴스(A: kista-api/kista-ui/kista-infra)와 **별도의 OCI 인스턴스(B)**에서 독립 운영된다 — 같은 서버에
병행 배포되지 않으며, `kista-infra`의 Caddy·네트워크·시크릿 어느 것도 fida를 대상으로 하지 않는다. 따라서 fida 배포
절차는 이 레포·kista-infra 레포 양쪽 모두 범위 밖이다. fida→kista-api `/api/internal/**` 호출은 공인 도메인을
경유하므로(무변경) 이 전환과 무관하게 그대로 동작한다.

## 커트오버 체크리스트

- [ ] OCI 인스턴스 방화벽 확인(Security List/NSG + OS iptables) + 정적 IP + 도메인 A 레코드
- [ ] `.env` 작성 및 필수 키 검증
- [ ] `docker compose up -d` 수동 실행 + 헬스체크 확인
- [ ] `/actuator/health` 외부 접근 확인
- [ ] 카카오 OAuth redirect URI → 새 도메인으로 변경
- [ ] `CORS_ALLOWED_ORIGINS` → 새 API 도메인 포함 확인
- [ ] Telegram webhook 재등록: `https://{NEW_DOMAIN}/telegram/webhook`
- [ ] FIDA 호출측 URL → 새 도메인으로 변경 (`/api/internal/**`)
- [ ] kista-ui `NEXT_PUBLIC_API_URL` → 새 도메인으로 변경
- [ ] UptimeRobot 헬스체크 URL 업데이트
- [x] healthchecks.io 체크 2개 생성(개장/마감) + `HEARTBEAT_OPEN_URL`/`HEARTBEAT_CLOSE_URL` 등록 (API로 실제 ping_url·cron 스케쥴 일치 확인, 2026-08-04) — 알림 채널은 이메일만 연결됨, Grafana Cloud 연동은 미완료
- [ ] kista-infra 배포 **전에** 구 `kista-api-caddy`/`kista-api-redis` 컨테이너 정리: `docker rm -f kista-api-caddy kista-api-redis` (포트 80/443 선점 해제, 신규 kista-infra caddy가 대신 기동)
