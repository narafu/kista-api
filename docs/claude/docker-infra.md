## Docker / 인프라

### WSL2 Gradle 설정
- `gradle.properties`의 `org.gradle.vfs.watch=false` — WSL2에서 Gradle FileHasher가 Windows NTFS 파일 감시 API 호출 시 IOException 발생, 제거 금지
- `gradle.properties`의 `org.gradle.native=false` — WSL2 재시작/슬립 후 stale 데몬의 네이티브 파일 핸들이 무효화되어 `createFileHasher()` IOException 발생, 제거 금지
- WSL2 Docker Desktop 환경에서 `infra/` 하위 파일이 root 소유로 생성될 수 있음 → `sudo chown user:user <경로>` 필요
