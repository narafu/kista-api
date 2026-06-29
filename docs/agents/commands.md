## 자주 쓰는 명령어

### Gradle
```bash
./gradlew bootJar                                               # app.jar 빌드
./gradlew bootRun --args='--spring.profiles.active=local'      # 로컬 실행
./gradlew test                                                  # 전체 테스트
./gradlew compileJava                                           # 컴파일만
./gradlew test --tests 'com.kista.architecture.*'               # ArchUnit 규칙만
./gradlew test --tests 'com.kista.domain.*'                     # 도메인 단위 테스트
./gradlew test --tests 'com.kista.adapter.out.kis.*'            # KIS Adapter 테스트
./gradlew test --rerun-tasks                                    # 캐시 무시 강제 재실행
./gradlew clean compileJava                                     # QueryDSL 생성파일 캐시 오염 시 (QXxxEntity.java "error reading")
# 테스트 실패 진단: stdout보다 XML이 신뢰성 높음
grep -oP 'failures="\K[^"]+' build/test-results/test/TEST-*.xml | grep -v ':0'
```

### 로컬 admin 토큰 발급 (DevAuthController, local 프로파일 전용)
```bash
# 일반 사용자 토큰
TOKEN=$(curl -s -X POST localhost:8080/api/auth/dev-token | jq -r .accessToken)
curl -i -H "Authorization: Bearer $TOKEN" localhost:8080/api/admin/_ping  # 403 기대

# ADMIN 토큰 (고정 UUID 00000000-0000-0000-0000-000000000002)
ADMIN_TOKEN=$(curl -s -X POST localhost:8080/api/auth/dev-admin-token | jq -r .accessToken)
curl -i -H "Authorization: Bearer $ADMIN_TOKEN" localhost:8080/api/admin/_ping  # 200 기대
```

### Docker (로컬)
```bash
docker compose up -d                                            # PostgreSQL + Prometheus + Grafana (앱은 IntelliJ 또는 bootRun으로 별도 실행)
docker compose up -d postgres                                   # DB만 기동
docker compose build <service> && docker compose up -d --force-recreate <service>
```

### 로컬 서버 로그 (IntelliJ 실행 시)
```bash
tail -f logs/kista-api.log
```
# application-local.yml에 logging.file.name: {프로젝트루트}/logs/kista-api.log 설정됨

## 배포/인프라/외부 연동 런북

저빈도 운영 작업 — 필요시 `docs/agents/docker-infra.md` 참고:
- Fly.io 배포 모니터링/환경변수 설정
- kista-ui 운영 로그 조회, kista-api↔kista-ui URL 변경 연동
- kis-trade-mcp 재시작, .mcp.json 경로 이식성
- Privacy 기준표 운영 → 로컬 마이그레이션 (supabase-cli)
