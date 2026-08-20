# AI 일정 분류 운영 런북

## 배포 기본값

AI 일정 분류는 `OPENAI_CLASSIFICATION_ENABLED=false`, rollout `0`으로 배포한다. 활성화 전에 전용 OpenAI project에서 발급한 키와 현재 privacy policy 버전을 주입하고, pinned model `gpt-4o-mini-2024-07-18` 및 3,000ms/10,000ms 제한을 유지한다. AI 결과는 review만 만들며 Event를 자동 변경하지 않는다.

`/actuator/health`만 public ingress에 노출한다. 이 기능의 meter는 애플리케이션 내부 registry에만 기록하며, Prometheus scrape는 private ingress와 인증·네트워크 정책이 준비된 별도 배포 환경에서만 활성화한다. title, user/event/review ID, model 문자열, 요청/응답 본문, 예외 메시지를 tag 또는 로그에 기록하지 않는다.

## 비용·공급자 대응

전용 OpenAI project에 일일 비용 cap을 설정한다. 80% 도달은 온콜 alert, 100% 도달은 `OPENAI_CLASSIFICATION_ENABLED=false`로 즉시 호출을 차단한다. provider 오류·timeout·invalid schema가 지속되면 enabled flag를 false로 내리고, key·policy version·pinned model을 확인한 뒤 sandbox에서 정상 응답을 검증한다. 재시도 루프를 추가하지 않는다.

## 롤아웃과 롤백

0%에서 시작해 동의된 사용자만 대상으로 작은 비율씩 올린다. `ai_classification_calls_total`의 timeout/HTTP/invalid_schema와 review 생성률, `ai_classification_retention_*`를 확인한다. 이상 징후 또는 비용 alert 시 rollout을 `0`으로 내린 뒤 enabled를 false로 설정하고 재배포한다. 이미 생성된 review는 title purge(24시간)와 review delete(90일) 정책을 계속 따른다.
