# 남은 작업사항 — 사용자 입력 대기 중

개인 API 키·서버 접속정보 등 박찬(백A) 본인 입력이 필요해서 진행하지 못한 작업을 여기에 기록한다.
항목 추가 시 막힌 작업은 미뤄두고 다른 작업을 먼저 진행한다. 해결되면 항목을 지운다.

## Google OAuth 클라이언트 ID (`feat/auth-google`)

- **무엇**: `POST /auth/google`이 구글 토큰의 `aud` 클레임을 검증할 때 쓸 `OAUTH_GOOGLE_CLIENT_ID` 환경변수가 비어있음.
- **왜 막혔는지**: Vium용 구글 OAuth 클라이언트 ID가 아직 발급되지 않음 (Google Cloud Console 프로젝트 생성 + OAuth 동의 화면 설정 필요, 박찬 본인 계정으로 발급해야 함).
- **현재 동작**: `application.yaml`에서 기본값을 빈 문자열로 둬서, 클라이언트 ID가 없으면 `aud` 비교가 항상 실패해 fail-closed로 동작함 (코드/테스트는 완성, 실제 구글 토큰으로만 아직 검증 불가).
- **필요 조치**: 구글 클라이언트 ID 발급 후 `OAUTH_GOOGLE_CLIENT_ID` 환경변수로 주입.

## AWS 계정 / EC2 / 도메인 (배포, TRD 2.3)

- **무엇**: TRD 2.3 배포 토폴로지(단일 VM, Docker Compose로 api/scheduler/db/proxy 구성)를 실제로 띄우려면 EC2 인스턴스, 도메인, TLS 인증서가 필요함.
- **왜 막혔는지**: 이슈 #40에서 논의만 되고 미확정 상태 — AWS 계정 소유자, EC2 스펙, 도메인, RDS 분리 여부, FastAPI(백엔드B) 서버와 인스턴스 공유 여부가 모두 박찬 본인 결정/입력 필요.
- **현재 동작**: `Dockerfile`과 `docker-compose.yml`의 `api`/`scheduler`/`proxy` 서비스 정의까지만 코드로 준비, 실 배포(EC2 프로비저닝, 도메인 연결, 인증서 발급)는 보류.
- **필요 조치**: AWS 계정/EC2 스펙/도메인 확정 후 실제 프로비저닝 진행. 8/17 코드 프리즈 직전 EC2 stop 금지·고정 IP 확인은 박찬 책임으로 별도 확정 필요.

