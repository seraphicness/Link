# 설치와 실행

[README](../README.md) · [설계](link-implementation.md) · [API](plugin-api.md) · [검증](validation.md)

현재는 격리된 개발·검증 환경용입니다. 운영 적합성 검증은 실패 상태입니다.

## 빌드

Java 25와 Git이 필요합니다. SQL 저장소는 PostgreSQL 17 또는 MariaDB 11.4를 사용할 수 있습니다. Redis 7은 선택 사항이며 프록시는 Velocity 4.3.1을 사용합니다.

```powershell
.\gradlew.bat applyAllPatches
.\gradlew.bat linkDist
```

Linux/macOS에서는 `./gradlew`를 사용합니다. JAR은 `build/dist/`에 생성됩니다. 패치 작업과 Windows 긴 경로 설정은 [개발 가이드](../CONTRIBUTING.md)를 참고하세요.

## 실행 모드

| `-Dlink.mode=` | 동작 |
| --- | --- |
| `disabled` | 기본값. Upstream 동작 유지 |
| `tools` | 템플릿 도구 사용. Upstream 플레이어 파일 저장 유지 |
| `journal` | 실험적 네이티브 SQL 저장과 입장·이동 처리 |
| `strict` | 검증 미완료로 시작 거부 |

## 백엔드 `link.yml`

첫 실행 시 작업 디렉터리(보통 서버 JAR을 실행한 폴더)에 `link.yml`을 자동 생성합니다. EULA 확인과 월드 초기화 전에 생성하며 기존 파일은 덮어쓰지 않습니다. 수정 후 서버를 재시작하세요.

```yaml
mode: disabled
node:
  id: node-a
  capacity: 150
database:
  jdbc-url: 'jdbc:postgresql://127.0.0.1:5432/link'
  username: 'link'
  password: ''
storage:
  worlds: database
  slimevault:
    endpoint: 'http://127.0.0.1:3900'
    region: slimevault
    bucket: worlds
    prefix: link
    access-key: ''
    secret-key: ''
redis:
  enabled: false
  uri: 'redis://127.0.0.1:6379'
spawn:
  template: lobby
  version: 1
  capacity: 50
  max-worlds: 3
```

PostgreSQL 또는 MariaDB에 DB·계정을 먼저 생성하고 `database` 항목을 입력하세요. MariaDB의 URL 예시는 `jdbc:mariadb://127.0.0.1:3306/link`입니다. MariaDB는 InnoDB를 사용하며 `innodb_flush_log_at_trx_commit=1`, binlog 활성 시 `sync_binlog=1`이 필요합니다. Velocity 설정까지 준비한 뒤 `mode: journal`로 변경합니다. 비밀번호는 따옴표로 감싸고 작은따옴표 자체는 YAML 작은따옴표 문자열 안에서 두 번 적습니다. Redis 사용 시 `redis.enabled: true`로 바꿉니다.

YAML을 설정하면 `java -Xms512M -Xmx2G -jar Link-26.3-dev.jar --nogui`로 실행할 수 있습니다. 접속 정보가 들어간 `link.yml`은 커밋하지 않습니다.

### SlimeVault에 월드 저장

`storage.worlds: slimevault`로 설정하고 [SlimeVault 0.2](https://github.com/seraphicness/SlimeVault)의 endpoint·bucket·접근 키를 입력하세요. 기존 `link.yml`에는 위 `storage` 블록을 직접 추가합니다. 버킷은 미리 생성하고 해당 키에 읽기·쓰기 권한을 부여해야 합니다. `LINK_S3_ACCESS_KEY`와 `LINK_S3_SECRET_KEY` 환경 변수로 YAML의 키를 덮어쓸 수 있습니다.

월드와 템플릿의 SRF 바이트는 SlimeVault에 저장하고, 플레이어 데이터·SavedData·이벤트·세션·정원·월드의 정확한 객체 리비전 참조는 SQL에 저장합니다. 따라서 S3만으로 실행할 수는 없습니다. 모든 백엔드는 같은 SQL DB와 SlimeVault endpoint·bucket·prefix를 사용합니다. Velocity에는 S3 자격 증명이 필요하지 않습니다.

업로드는 SigV4/path-style 요청으로 writer 임대를 얻은 뒤 새 `.slime` 키에 조건부 저장합니다. 성공한 VersionId와 SHA-256을 SQL에 기록하고 복구 시 그 리비전을 읽어 검증합니다. S3 오류 시 로컬 저장으로 우회하지 않습니다. SQL 실패 또는 응답 유실로 참조되지 않은 객체가 남을 수 있으며 자동 삭제하지 않습니다. SQL 이력이 참조하는 객체와 리비전을 SlimeVault에서 정리하면 복구할 수 없으므로 SQL과 S3를 함께 백업해야 합니다. S3 업로드를 기다리는 현재 구현은 틱 지연을 늘릴 수 있으며 성능 개선을 보장하지 않습니다.

기존 SQL 바이너리는 S3 모드에서도 읽을 수 있지만 DB 종류 변경과 기존 데이터의 일괄 이전은 자동으로 수행하지 않습니다. S3 참조가 생긴 뒤 저장소를 끄거나 bucket/prefix를 바꾸면 해당 데이터를 복구할 수 없습니다.

## 환경 변수와 기존 옵션

백엔드는 아래 환경 변수를 YAML보다 우선 적용합니다. 모드·스폰·정원은 기존 `-Dlink.*` 옵션이 우선합니다. 환경 변수의 비밀 값은 생성 파일에 복사하지 않습니다. **Velocity 플러그인과 오프라인 템플릿 발행 도구는 여전히 환경 변수를 사용합니다.** 백엔드의 YAML을 자동 공유하지 않습니다. 백엔드 노드 이름은 `node.id` 또는 `LINK_NODE_ID`로 지정합니다.

| 변수 | 대상 | 의미 |
| --- | --- | --- |
| `LINK_JDBC_URL` | 백엔드·프록시 | 예: `jdbc:postgresql://127.0.0.1:5432/link` |
| `LINK_DB_USER` | 백엔드·프록시 | DB 계정 |
| `LINK_DB_PASSWORD` | 백엔드·프록시 | DB 비밀번호 |
| `LINK_NODE_ID` | 백엔드 | 실행 중인 다른 노드와 중복되지 않는 이름 |
| `LINK_REDIS_URI` | 백엔드, 선택 | 예: `redis://127.0.0.1:6379` |

백엔드의 `node.id`(또는 `LINK_NODE_ID`)는 Velocity의 서버 등록 이름과 일치해야 합니다. 모든 백엔드와 프록시는 같은 Link DB를 사용합니다. 시작 시 스키마를 초기화하므로 DB 계정에 필요한 권한이 있어야 합니다. 자격 증명은 저장소에 커밋하지 않습니다.

## 첫 템플릿

`journal` 시작 시 설정한 스폰 템플릿 버전이 없으면 `link-template-editor`라는 편집 월드를 자동 생성하고, 접속자를 이 월드로 배정합니다. 9×9 기반암 발판에서 건축을 시작할 수 있으며 편집 내용은 DB에 저장되어 재시작 후 복구됩니다. 편집 상태에서는 추가 스폰 복제를 하지 않습니다. DB 장애나 템플릿 손상은 편집 월드로 우회하지 않고 부팅 오류로 처리합니다.

기본 설정이라면 건축 후 `/link publish link-template-editor lobby 1`을 실행하고 완료 메시지를 확인한 뒤 재시작하세요. 다음 부팅부터 발행한 템플릿으로 스폰을 구성하며 편집 월드는 보존됩니다. 템플릿 이름·버전을 바꿨다면 발행 명령도 `link.yml`의 `spawn.template`·`spawn.version`과 맞추세요. 템플릿 자동 전환에는 재시작이 필요합니다.

기존 SRF `.slime` 파일이 있다면 DB 환경 변수가 설정된 셸에서 미리 발행할 수도 있습니다.

```powershell
.\gradlew.bat :link-core:publishTemplate -PtemplateName=lobby -PtemplateVersion=1 -PtemplateFile=C:/maps/lobby.slime
```

템플릿 버전은 다른 내용으로 덮어쓸 수 없습니다. 수정본은 버전을 올려 발행하세요. 서버가 실행된 뒤에는 `/link editor`와 `/link publish`로 새 템플릿을 제작할 수 있습니다. 테스트용 빈 템플릿 생성기는 `link_runtime_test` DB에서만 동작하며 일반 템플릿 준비 절차를 대신하지 않습니다.

오프라인 발행 도구에서도 S3를 쓰려면 `LINK_WORLD_STORAGE=slimevault`, `LINK_S3_ENDPOINT`, `LINK_S3_BUCKET`, `LINK_S3_ACCESS_KEY`, `LINK_S3_SECRET_KEY`를 설정합니다. 선택 값은 `LINK_S3_REGION`(기본 `slimevault`), `LINK_S3_PREFIX`(기본 `link`)입니다. SQL 접속 환경 변수도 필요합니다.

## Velocity 연결

1. Velocity의 `plugins/`에 `Link-Velocity-dev.jar`를 설치합니다.
2. 서버 목록에 백엔드를 등록합니다. 등록 이름은 각 백엔드의 `node.id`(또는 `LINK_NODE_ID`)와 같아야 합니다.
3. 프록시의 modern forwarding과 온라인 인증을 설정합니다.
4. 백엔드 Paper 설정에서 Velocity 지원과 프록시 `online-mode`를 활성화하고 forwarding secret을 동일하게 설정합니다. `server.properties`의 백엔드 `online-mode`와 Paper의 프록시 `online-mode`는 서로 다른 설정입니다. 프록시 인증을 사용하는 백엔드는 일반적으로 `server.properties`에서 `online-mode=false`를 사용합니다.
5. 백엔드 포트에는 신뢰하는 프록시만 접근하도록 구성하고, 백엔드는 `link.yml`, 프록시는 환경 변수로 DB 접속 정보를 설정합니다.

`journal`은 백엔드의 Velocity 활성화·프록시 온라인 모드 설정·비어 있지 않은 secret을 검사합니다. 직접 백엔드 접속은 거부됩니다. 로컬 부하 검사는 루프백 offline-auth 프록시를 사용했으며 Mojang 인증 검증을 대신하지 않습니다.

## 백엔드 실행

Minecraft EULA를 읽고 동의한 테스트 디렉터리에 서버 JAR을 복사한 뒤 실행합니다. YAML 또는 환경 변수의 DB 설정과 forwarding 설정은 앞 단계에서 준비해야 합니다.

```powershell
java -Xms512M -Xmx2G `
  -Dlink.mode=journal `
  -Dlink.spawn.template=lobby `
  -Dlink.spawn.version=1 `
  -Dlink.spawn.capacity=50 `
  -Dlink.node.capacity=150 `
  -Dlink.spawn.max-worlds=3 `
  -jar Link-26.3-dev.jar --nogui
```

위 메모리와 정원은 실행 예시이며 검증된 운영 권장 사양이 아닙니다. 템플릿·DB 초기화 실패 시 바닐라 월드로 대체하지 않습니다. 종료·장애 직후에는 이전 lease 만료까지 같은 노드의 시작이 거부될 수 있습니다.

## 명령어

백엔드 관리 명령에는 `link.admin` 권한이 필요하며 기본값은 OP입니다. 완료 메시지를 확인한 뒤 의존하는 다음 명령을 실행하세요.

| 명령 | 기능 |
| --- | --- |
| `/link templates` | 템플릿 조회 |
| `/link worlds` | 로드된 관리 월드 조회 |
| `/link editor <name>` | 빈 편집 월드 생성 |
| `/link publish <editor> <template> <version>` | 템플릿 발행 |
| `/link create <name> <template> <version> <persistent 또는 temporary>` | 템플릿 복제. 마지막 인수는 둘 중 하나 |
| `/link save <name>` | 체크포인트 저장 |
| `/link unload <name>` | 언로드 |
| `/link load <name>` | 저장한 월드 로드 |
| `/link delete <name>` | 삭제 표시. 이름 재사용 불가 |

예: `/link create lobby-2 lobby 1 persistent`.

백엔드의 `/spawn`은 스폰 이동을 요청합니다. 백엔드의 `/warp <name>`에는 `link.warp` 권한이 필요하며 기본값은 OP입니다. 두 명령은 플레이어 전용이고 `journal` 모드에서 동작합니다. Velocity 모듈은 명령어를 등록하지 않으며, 최초 접속 배정과 백엔드에서 준비한 이동 요청의 연결 전달만 담당합니다. 다른 플러그인과 명령어 이름이 겹치면 `/link:spawn`, `/link:warp <name>`으로 실행하세요. 워프 목적지는 현재 API의 `setWarp`로 등록합니다. 월드 언로드는 플레이어 유무와 기본 월드 보호 등 엔진의 검사를 따릅니다.

`link-integration`은 일반 서버에 설치하지 마세요. 월드·아이템을 변경하거나 의도적으로 서버를 중단하는 검사 명령이 포함됩니다.
