# Link 개발 가이드

[README](README.md) · [설계](docs/link-implementation.md) · [검증](docs/validation.md)

## 소스와 패치

Link는 ASP `dev/26.3` 기반이며 Paper 리비전과 Minecraft 버전은 `gradle.properties`에 고정합니다. Upstream 모듈명·패키지·라이선스 고지는 유지합니다.

| 경로 | 용도 |
| --- | --- |
| `aspaper-server/minecraft-patches/features/` | NMS 기능 패치 |
| `aspaper-server/paper-patches/` | Paper 서버 패치 |
| `aspaper-server/src/main/java/io/linkmc/` | 추적되는 Link 런타임 코드 |
| `aspaper-server/src/minecraft/java/` | 패치로 생성되는 NMS 작업 트리. 루트 저장소에서 무시됨 |
| `link-api/`, `link-core/`, `link-velocity/` | API, 데이터 계층, 프록시 모듈 |
| `link-integration/` | 테스트 전용 플러그인·클라이언트 |

NMS 작업 트리만 수정하고 끝내면 변경이 포크에 포함되지 않습니다. 변경을 기능 패치로 내보내고 패치 재적용으로 재현되는지 확인하세요. 생성된 소스에 미수출 변경이 있으면 재적용 전에 보존해야 합니다.

```powershell
.\gradlew.bat applyAllPatches
git -C aspaper-server/src/minecraft/java status --short
```

`applyPaperSingleFilePatches`와 `applyAllServerPatches`를 같은 Gradle 호출에 묶으면 upstream 작업 의존성 검증 문제가 발생할 수 있습니다. 각각 분리해서 실행하세요. Windows에서 긴 경로 문제가 발생하면 해당 저장소와 생성된 Git 저장소의 `core.longpaths` 설정을 확인합니다.

## 변경 검증

Java 25를 사용합니다. DB 테스트에는 폐기 가능한 PostgreSQL 또는 MariaDB와 Redis가 필요하며 테스트가 Link 테이블을 초기화합니다. 환경 변수와 실제 서버 검사 절차는 [검증 문서](docs/validation.md)에 있습니다.

```powershell
.\gradlew.bat :link-core:test
.\gradlew.bat :link-velocity:shadowJar :link-integration:jar :aspaper-server:test --tests 'io.linkmc.server.Link*TestSuite'
.\gradlew.bat linkDist
```

NMS 패치를 바꿨다면 패치 적용과 서버 빌드도 검사합니다. 문서만 수정했다면 링크, 명령어, 소스 계약 및 검증 수치의 일치 여부를 확인합니다. 기존 성공 결과를 변경 후의 새 검증 결과처럼 기록하지 않습니다.

## 데이터·호환성 기준

- 게임 상태가 커밋되기 전에 출력되지 않도록 프레임 경계를 유지합니다.
- 재시도에는 같은 작업 ID와 내용이 필요합니다. 이전 epoch의 쓰기를 허용하지 않습니다.
- 여러 상태의 원자성을 단일 월드·플레이어 저장으로 분해하지 않습니다.
- API 완료 콜백의 스레드를 가정하지 않습니다. 메인 스레드에서 비동기 결과를 기다리지 않습니다.
- 새 이벤트 스키마는 이전 기록의 읽기·복구와 함께 설계합니다.
- 성능 개선은 정원, 소유권, 실패 시 중단, 아이템 수량 보존을 다시 검증합니다.

## 이슈와 PR

버그 보고에는 Link 커밋, Minecraft·Java·Velocity 버전, 실행 모드, 재현 절차와 로그 시각을 포함하세요. 데이터 문제는 기대 수량과 실제 수량, 이동·장애 발생 단계를 함께 기록합니다. DB 비밀번호, forwarding secret, 인증 토큰은 포함하지 않습니다.

PR에는 변경 후 동작과 검증 결과를 적고 통과·실패·미실행을 구분합니다. 새 제한이나 발견한 결함은 [검증 문서](docs/validation.md)에 반영합니다. 현재 운영 차단 문제를 해결하지 않은 상태에서 `strict`를 활성화하거나 무손실을 보장한다고 표시하지 않습니다.
