# 검증 결과

[README](../README.md) · [설치](setup.md) · [설계](link-implementation.md) · [API](plugin-api.md)

**기준: 2026-10-30, 구현 커밋 `547fae12`. 현재 운영 투입 불가입니다.** 아래는 실제 수행한 검사와 남은 한계를 구분한 기록입니다. 문서 변경 자체를 새 런타임 검증으로 간주하지 않습니다.

## 운영을 막는 확인된 문제

| 항목 | 재현 결과 |
| --- | --- |
| 처리 성능 | 51개 정지 상태 프로토콜 클라이언트에서 1분 평균 TPS 4.6 |
| 탑승 관계 | 보트는 정확히 하나 복원되지만 플레이어는 재접속 시 내려진 상태 |
| 서버 간 지도 | 지도 아이템은 A→B로 이동하지만 원래 픽셀 데이터는 B에 없음 |

전체 월드 직렬화·동기 SQL 비용 개선, 탑승 관계 정책과 복원, 네트워크 전체 지도 ID·데이터 서비스가 필요합니다. `strict`를 활성화하지 않았으며 무손실·무복제를 보장하지 않습니다.

## 자동 테스트

| 검사 | 통과 수 |
| --- | ---: |
| PostgreSQL·Redis 통합 테스트 | 29 |
| 프레임 커밋·불확실한 커밋 재시도 | 4 |
| 분할 스냅샷 무결성 | 2 |
| Netty 패킷 보류·순서·프로토콜·압축 변경 | 5 |
| **합계** | **40** |

실패·건너뜀 없이 통과했습니다. `547fae12`의 GitHub push/PR CI도 성공했습니다. 테스트에는 예상 버전 충돌, 다중 상태 롤백, 작업 재시도, 만료된 소유자 차단, 정원 경쟁, 일회용 입장권, 변경 불가능한 목적지, Redis 중복 전달, 커넥션 풀 롤백이 포함됩니다.

이동 경계 6개(체크포인트, 이전 준비, 입장권 발급, READY, CONNECTING, 도착 승인)는 SQL 상태 머신에서 장애·만료 후 회복을 검사했습니다. 여섯 번의 별도 JVM 강제 종료 시험을 뜻하지 않습니다.

## 실제 서버 검사

| 검사 | 결과 | 확인 내용 |
| --- | --- | --- |
| 강제 종료·재시작 | 통과 | 상자 다이아몬드 7개, 장비 엔티티, 월드 PDC, 전역 스코어보드 복원 |
| 상자에서 커서로 이동 후 강제 종료 | 통과 | 플레이어에게 정확히 7개 복구, 원본 상자는 비어 있음 |
| 제작창·커서 복구 | 통과 | 임시 입력 다이아몬드 5개와 커서 에메랄드 3개 복구 |
| 상자·호퍼 | 통과 | 상자 다이아몬드 11개, 호퍼와 목적지의 철 주괴 합계 16개 |
| 네이티브 메뉴 | 통과 | 좌·우 클릭, Shift 이동, 숫자키 교환, 드래그, 제작, 거래, 줍기 7개 검사 |
| 사망·리스폰 | 통과 | 네더의 별 7개 새 드롭, 기존 인벤토리 비움, 살아 있는 상태로 리스폰 |
| 월드 API | 통과 | 편집→발행→복제→저장→언로드→재로드→삭제, 템플릿 블록 유지 |
| 엔더 진주 | 통과 | 단일 백엔드 재시작 후 한 개체, 소유자, 플레이어의 청크 유지 등록 복구 |
| 보트 개체 | 통과 | 재시작 후 원본 보트 하나 유지. 탑승 관계는 별도 실패 |
| 실제 Velocity 이동 | 통과 | A→B와 같은 백엔드 워프. `(8,66,8)`, yaw 90, 아이템·엔더 상자·레벨·체력 유지 |
| 목적지 강제 종료 후 재접속 | 통과 | B 종료 후 A에서 새 epoch 획득, 다이아몬드 7개·에메랄드 3개·레벨 9·체력 13 유지 |
| PostgreSQL 중단 | 통과 | 두 백엔드 JVM이 저장 실패 후 종료. 메모리만으로 게임 지속하지 않음 |
| 고의 네이티브 예외 | 통과 | 아이템 99개·블록 변경 후 예외로 중단. 재시작 시 이전 커밋의 아이템 7개·상자 11개 복원 |
| Redis 없이 시작 | 수정 후 통과 | 기존 부팅 실패를 수정. 별도 연결 재시도로 서버 시작 가능 |
| Redis 연결 후 장애 | 통과 | lease 기간을 넘겨도 서버 응답·소유권 갱신 유지. Redis 복구 후 outbox 전달 재개 |
| 프록시 우회 접속 | 통과 | modern forwarding 없는 직접 백엔드 접속 거부 |
| 정원·연결 해제 | 통과 | 51명 전원 접속, 월드별 50+1 배정. 모두 종료 후 ADMITTED 예약 0개 |

메뉴 검사는 연결된 플레이어를 대상으로 메인 스레드에서 NMS 메서드를 호출했습니다. 그래픽 클라이언트의 실제 마우스 입력 자동화나 외부 플러그인 호환성 시험은 아닙니다. 이동 검사는 별도의 TCP 프로토콜 클라이언트가 실제 Velocity를 통과했습니다.

초기 줍기 검사는 제거된 Java 엔티티 객체의 `playerTouch`를 다시 강제 호출하는 잘못된 절차여서 실제 충돌 처리에 맞게 수정했습니다. 체력 보존 검사는 평화로움 난이도의 자동 회복을 배제하도록 일반 난이도와 고정된 음식 상태를 사용했습니다. 사망 검사는 기존 드롭과 새 드롭을 구분해 수량을 확인했습니다.

## 51명 부하 결과

Windows 11, Java 25, 백엔드 `-Xmx2G`, 로컬 Docker PostgreSQL 17, Velocity 4.3.1 환경입니다. 스폰 월드 2개와 이전 편집 월드 1개가 로드돼 있었으며, 클라이언트를 4초 간격으로 접속시킨 뒤 최종 인원을 60초 유지했습니다. 측정 중 로컬 Gradle 빌드는 실행하지 않았습니다.

| 접속 인원 | 관측된 1분 평균 TPS |
| ---: | ---: |
| 9 | 18.3 |
| 21 | 11.0 |
| 33 | 7.5 |
| 41 | 6.2 |
| 50 | 5.1 |
| 51 | 4.6 |

점진적으로 부하를 올리는 동안의 이동 평균이며 정상 상태 처리량이나 운영 권장 인원이 아닙니다. `/mspt`에는 프레임 바깥의 저장 비용이 모두 포함되지 않아 낮은 값이 나와도 실제 TPS 저하를 무시하면 안 됩니다.

## 재현

자동 통합 테스트는 Link 테이블을 초기화합니다. 반드시 폐기 가능한 DB를 사용합니다.

```powershell
$env:LINK_TEST_JDBC_URL='jdbc:postgresql://127.0.0.1:55432/link_test'
$env:LINK_TEST_DB_USER='link_test'
$env:LINK_TEST_DB_PASSWORD='link_test_local'
$env:LINK_TEST_REDIS_URI='redis://127.0.0.1:56379'
.\gradlew.bat :link-core:test
.\gradlew.bat applyAllPatches
.\gradlew.bat :link-velocity:shadowJar :link-integration:jar :aspaper-server:test --tests 'io.linkmc.server.Link*TestSuite'
.\gradlew.bat linkDist
```

라이브 검사는 [설치 안내](setup.md)에 따라 별도 서버 두 개와 루프백 프록시를 준비하고 EULA에 동의한 뒤 수행합니다. `link-integration` JAR은 배포 서버 JAR에 포함되지 않으며 `LINK_NODE_ID`가 `test-*`인 검사 서버에만 설치합니다. 백엔드 이름은 `test-a`, `test-b`, 검사 월드는 `link-spawn`을 사용합니다.

기본 프로토콜 클라이언트는 `127.0.0.1:25594`의 offline-auth 테스트 프록시에 `LinkWire`로 접속합니다. 이 이름의 offline UUID를 테스트 백엔드 whitelist에 등록해야 합니다. 패킷 ID를 읽으므로 먼저 패치를 적용해야 합니다.

```powershell
node link-integration/wire-client.cjs
```

검사용 콘솔 명령은 다음과 같습니다. `matrix-*`는 `MATRIX_PASS`·`MATRIX_FAIL`과 프레임 커밋 완료를 별도로 기록합니다.

| 명령 | 용도 |
| --- | --- |
| `linkprobe platform` | 발판과 일반 난이도 준비 |
| `linkprobe prepare` / `verify` | 월드·스코어보드 복구 |
| `linkprobe player-prepare` / `player-verify` | 합성 네이티브 플레이어 커서 복구 |
| `linkprobe player-transfer` / `player-arrive` | 합성 플레이어의 두 백엔드 이동 |
| `linkprobe wire-prepare` / `wire-transfer` / `wire-verify` | TCP 플레이어 상태 설정·B 이동·도착 검사 |
| `linkprobe matrix-menus` | 메뉴 조작 7개 |
| `linkprobe matrix-prepare` / `matrix-recover` | 제작창·호퍼·장비 엔티티 강제 종료 복구 |
| `linkprobe matrix-death` | 사망·리스폰 |
| `linkprobe matrix-worlds` | 공개 월드 API 생명주기 |
| `linkprobe matrix-relations-prepare` / `matrix-relations-recover` | 보트·엔더 진주 강제 종료 복구 |
| `linkprobe matrix-map-prepare` / `matrix-map-verify` | 지도 생성 후 이동 전·후 픽셀 검사 |
| `linkprobe matrix-rollback-baseline` / `matrix-abort` / `matrix-rollback-verify` | 커밋 기준 상태 생성, 고의 예외, 재시작 후 검사 |

준비와 확인 사이에 의도한 이동·강제 종료·재시작을 수행합니다. 생성되는 검사 엔티티 등은 반복 실행 시 정리하거나 새 테스트 DB·월드를 사용하세요. 강제 종료는 해당 테스트 JVM의 경로와 PID를 확인한 뒤에만 실행합니다.

부하 검사 전에는 `LinkLoad0`부터 `LinkLoad50`까지 offline UUID를 whitelist에 등록하고 테스트 백엔드 `max-players`를 51보다 크게 설정합니다. 한 백엔드의 50+1 분산을 재현할 때 다른 백엔드 채널은 배정 대상에서 제외합니다.

```powershell
node link-integration/load-clients.cjs 51 4000 60000
```

상태·개별 로그는 무시되는 `build/load-clients/`에 생성됩니다. `wire-client.cjs`는 `LINK_WIRE_NAME`, `LINK_WIRE_PORT`, `LINK_WIRE_DURATION_MS`로 테스트 이름·포트·실행 제한을 바꿀 수 있습니다. 로컬 `build/`의 시작·RCON 보조 스크립트와 비밀 설정은 저장소에 배포하지 않습니다.

## 백엔드 YAML 설정 추가 검증

`link.yml` 지원 추가 시 설정 테스트 7개와 기존 패킷 테스트 5개를 통과했습니다. 신규 파일 생성·기존 파일 보존·YAML 단독 값·환경 변수와 JVM 옵션 우선순위·잘못된 입력 거부·오류 메시지의 비밀 값 비노출을 확인했습니다. 새 작업 폴더에서 실제 JAR을 실행해 EULA 동의 전 파일 생성을 확인했고, DB 환경 변수와 `-Dlink.*` 없이 YAML만으로 `journal` 부팅을 확인했습니다. DB에서 노드 정원 34·월드 정원 17을 조회해 사용자 설정 적용도 검사했습니다. 9번째 Minecraft 패치 재적용이 성공했습니다.

## 미검증·미구현 범위

정품 클라이언트의 Mojang 인증, 전체 GUI 입력, 임의 플러그인 가상 메뉴, 비동기 변경, 대형 맵의 장시간 혼합 부하, 모든 네트워크 분리·이동 중 장애 조합은 인증하지 않았습니다. 네트워크 전역 지도 저장, 유휴 스폰 정리, 영속 대기열, 스키마 이전·가져오기·백업 복구 도구도 미완료입니다. 따라서 이 문서를 “모든 상황 검증 완료”로 해석하면 안 됩니다.
