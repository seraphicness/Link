# Link

**Minecraft 26.3 · Slime 템플릿 월드 · PostgreSQL · Redis · Velocity**

Link는 AdvancedSlimePaper `dev/26.3`을 기반으로 개발하는 패치 방식의 서버 포크입니다. NMS를 수정해 Slime 템플릿으로 스폰과 인스턴스를 구성하고, 플레이어·월드 상태를 PostgreSQL에 저장하며, Velocity로 여러 백엔드 사이의 입장과 이동을 조정합니다.

> **개발 단계이며 현재 운영 투입 불가입니다.** 51명 부하에서 TPS 저하, 재접속 시 탑승 관계 유실, 서버 간 지도 픽셀 누락을 확인했습니다. `strict` 모드는 실행을 거부하며 `journal`은 격리된 테스트 환경용입니다. [검증 결과](docs/validation.md)를 먼저 확인하세요.

## 구현 범위

| 영역 | 현재 동작 |
| --- | --- |
| 월드 엔진 | 편집 월드 생성, 변경 불가능한 템플릿 버전 발행, 복제, 저장, 로드, 언로드, 삭제 |
| 스폰 | 기본 월드당 50명, 백엔드당 150명, 최대 스폰 월드 3개. 정원 예약과 여분의 템플릿 인스턴스 생성 |
| 서버 이동 | 원본 체크포인트 → 소유권 동결 → 입장권 → Velocity 연결 → 목적지 소유권 획득 및 예약 좌표 입장 |
| 저장 | 인벤토리·플레이어 NBT·통계·발전 과제·Slime 월드·SavedData를 네이티브 프레임 단위로 SQL 커밋 |
| 이벤트 기록 | 변경 후 상태 이벤트, 버전 검사, 재시도 중복 방지, 소유권 epoch 검사 |
| Redis | SQL outbox 알림 전달. 장애 시 기록을 유지하고 별도 작업에서 재시도 |
| 플러그인 API | 월드 생명주기, 워프·이동, 데이터 조회·커밋·이력, 세션, 네이티브 트랜잭션 |

`journal`은 템플릿 기반 구성을 사용하며 기본 보조 차원을 생성하지 않습니다. 야생 월드 운영 기능은 목표 범위가 아닙니다. 새 머신이나 JVM을 만드는 서버 오케스트레이터는 포함하지 않습니다.

## 문서

| 문서 | 내용 |
| --- | --- |
| [설치와 실행](docs/setup.md) | 빌드, 템플릿 등록, DB·Velocity 설정, 모드, 명령어 |
| [설계와 저장 경계](docs/link-implementation.md) | 네이티브 프레임, 이벤트 기록, 이동, 장애 동작 |
| [플러그인 API](docs/plugin-api.md) | 의존성 설정, 비동기 예제, 서비스별 계약 |
| [검증 결과](docs/validation.md) | 통과·실패·미검증 항목, 재현 방법, 부하 수치 |
| [개발 가이드](CONTRIBUTING.md) | 패치 관리, 테스트, 이슈·PR 작성 |

## 빌드

Java 25와 Git이 필요합니다. 저장소 루트에서 실행합니다.

```powershell
.\gradlew.bat applyAllPatches
.\gradlew.bat linkDist
```

Linux/macOS에서는 `./gradlew`를 사용합니다. 결과는 `build/dist/`에 생성됩니다.

| 파일 | 용도 |
| --- | --- |
| `Link-26.3-dev.jar` | Link 백엔드 서버 |
| `Link-Velocity-dev.jar` | Velocity의 `plugins/`에 설치하는 라우팅 모듈 |
| `Link-API-dev.jar` | 플러그인 컴파일용 API. 서버에 설치하는 플러그인이 아님 |

기본 모드는 `disabled`입니다. [실행 안내](docs/setup.md)에 따라 템플릿, DB, modern forwarding, `link.mode=journal`을 설정해야 합니다. Upstream 버전 문자열의 `stable`은 Link의 운영 안정성을 뜻하지 않습니다.

## 검증 상태

2026-10-30 기준 자동 테스트 40개와 실제 서버 강제 종료·DB 장애·Velocity 이동·네이티브 메뉴·월드 API 검사를 수행했습니다. 51명 접속과 50+1 정원 분산은 성공했지만 1분 평균 TPS가 4.6까지 하락했습니다. 테스트 성공이 모든 게임플레이와 플러그인 조합의 무손실을 보장하지는 않습니다.

## Upstream과 라이선스

Link는 [AdvancedSlimePaper](https://github.com/InfernalSuite/AdvancedSlimePaper)와 Paper의 작업을 기반으로 합니다. 기존 `aspaper-*` 모듈명과 패키지는 패치 호환성을 위해 남아 있습니다. 원본 저작권·라이선스 고지를 유지하며 [LICENSE](LICENSE)와 [기여자](CREDITS.md)를 참고하세요.
