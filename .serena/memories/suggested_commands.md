# 운영 명령어

## 빌드/테스트
```bash
./gradlew compileJava          # 컴파일만 (BOM 삽입 버그 확인용)
./gradlew test                 # 전체 테스트 (병렬, TZ=KST)
./gradlew test --tests "com.kista.xxx.*"  # 특정 테스트
./gradlew bootJar              # app.jar 생성
```

## 로컬 실행
```bash
docker compose up -d           # PostgreSQL + API 로컬 실행
docker compose logs -f kista-api  # 실시간 로그
```

## 운영 로그 확인
```bash
render logs --resources srv-d7sir2jbc2fs73cptpm0
```

## 운영 DB (Supabase CLI)
```bash
supabase link --project-ref nnpchirdkaxvdybhqzct
supabase db query --linked "SELECT ..."
```

## BOM 제거 (서브에이전트 Java 파일 수정 후)
```bash
grep -rl $'\xef\xbb\xbf' src --include="*.java" | while read f; do sed -i '1s/^\xef\xbb\xbf//' "$f"; done
```

## 로컬 dev-token 발급
```bash
curl -s -X POST http://localhost:8080/api/auth/dev-token
# 응답: {"accessToken":"...", "tokenType":"bearer", "expiresIn":604800}
```

## 로컬 사용자 승인
```bash
curl -s -X POST http://localhost:8080/api/auth/dev-approve/<UUID>
# UUID 확인:
docker exec kista-api-postgres-1 psql -U kista -d kistadb -c "SELECT id, nickname, status FROM users ORDER BY created_at DESC LIMIT 5;"
```
