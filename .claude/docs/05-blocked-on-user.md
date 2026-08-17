# 남은 작업사항 — 사용자 입력 대기 중

개인 API 키·서버 접속정보 등 박찬(백A) 본인 입력이 필요해서 진행하지 못한 작업을 여기에 기록한다.
항목 추가 시 막힌 작업은 미뤄두고 다른 작업을 먼저 진행한다. 해결되면 항목을 지운다.

## Google OAuth 클라이언트 ID (`feat/auth-google`)

- **무엇**: `POST /auth/google`이 구글 토큰의 `aud` 클레임을 검증할 때 쓸 `OAUTH_GOOGLE_CLIENT_ID` 환경변수가 비어있음.
- **왜 막혔는지**: Vium용 구글 OAuth 클라이언트 ID가 아직 발급되지 않음 (Google Cloud Console 프로젝트 생성 + OAuth 동의 화면 설정 필요, 박찬 본인 계정으로 발급해야 함).
- **현재 동작**: `application.yaml`에서 기본값을 빈 문자열로 둬서, 클라이언트 ID가 없으면 `aud` 비교가 항상 실패해 fail-closed로 동작함 (코드/테스트는 완성, 실제 구글 토큰으로만 아직 검증 불가).
- **필요 조치**: 구글 클라이언트 ID 발급 후 `OAUTH_GOOGLE_CLIENT_ID` 환경변수로 주입.

## 가비아 서버 / 도메인 (배포, TRD 2.3)

- **무엇**: TRD 2.3 배포 토폴로지(단일 VM, Docker Compose로 api/scheduler/db/proxy 구성)를 실제로 띄우려면 서버 접속정보, 도메인, TLS 인증서가 필요함.
- **왜 막혔는지**: 실제 배포 대상은 AWS EC2가 아니라 가비아(Gabia) 서버로 정정됨(#56 리뷰, 김민형). 서버 스펙, 도메인, DB 분리 여부, FastAPI(백엔드B) 서버와 인스턴스 공유 여부가 모두 박찬 본인 결정/입력 필요.
- **현재 동작**: `Dockerfile`과 `docker-compose.yml`의 `api`/`scheduler`/`proxy` 서비스 정의까지만 코드로 준비. `postgres` 포트 퍼블리시(`5432:5432`)와 DB 자격증명 하드코딩도 #56 리뷰에서 지적됨 — AWS라면 보안 그룹으로 막으면 되지만 가비아는 방화벽/iptables 직접 설정이 필요해 네트워크 격리 방식이 다름. 실 배포(서버 접속, 도메인 연결, 인증서 발급, 네트워크 격리)는 보류.
- **필요 조치**: 가비아 서버 접속정보/도메인 확정 후 실제 프로비저닝 및 네트워크 격리 설정 진행.

## 공공데이터포털 인증키 (`feat/be-provider-adapters`)

- **무엇**: 기상청 단기예보(`KmaEnvironmentProvider`)가 쓸 `DATA_GO_KR_SERVICE_KEY`가 비어있음.
  같은 키로 에어코리아(PM10)·자외선지수 API도 쓸 예정.
- **왜 막혔는지**: 공공데이터포털(data.go.kr)에서 각 API별로 활용신청 후 발급되는 인증키인데
  아직 신청 전. 박찬 본인 계정으로 신청해야 함.
- **현재 동작**: `KmaEnvironmentProvider`는 TMP(기온)·POP(강수확률) 파싱까지 작성했지만
  아직 `@Component`가 아니라서 `StubEnvironmentProvider`가 계속 쓰인다. 실 키로 응답을
  검증하기 전까지는 교체하지 않는다.
- **필요 조치**: 인증키 발급 후 `DATA_GO_KR_SERVICE_KEY` 환경변수로 주입, 실제 응답으로
  파싱 검증 후 `@Component`로 전환.
- **별도 미구현**: PM10(에어코리아)은 좌표→측정소 매핑이 더 필요하고, 자외선지수는 응답
  필드 구조를 검증 못 해 파싱 로직 자체가 아직 없음(-1로 채워둠).

## ODsay API 키 (`feat/be-provider-adapters`)

- **무엇**: 경로 탐색(`OdsayRouteProvider`)이 쓸 API 키가 없음.
- **왜 막혔는지**: ODsay 개발자센터에서 앱 등록 후 발급받는 키인데 아직 신청 전. 응답
  스키마(`path[].subPath[]` 필드 구성)도 실제 응답으로 검증 못 해서 파싱 로직 자체를
  아직 작성하지 않았다 (`UnsupportedOperationException`만 던짐).
- **현재 동작**: 클래스만 만들어두고 `@Component`로 등록하지 않아 `StubRouteProvider`가
  계속 쓰인다.
- **필요 조치**: ODsay API 키 발급 후 실제 응답을 같이 보면서 파싱 로직 작성.

