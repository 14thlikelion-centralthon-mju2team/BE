# AI 일정 분류 운영 런북

## 배포 기본값

AI 일정 분류는 `OPENAI_CLASSIFICATION_ENABLED=false`, rollout `0`으로 배포한다. 활성화 전에 전용 OpenAI project에서 발급한 키와 현재 privacy policy 버전을 주입하고, pinned model `gpt-4o-mini-2024-07-18` 및 3,000ms/10,000ms 제한을 유지한다. AI 결과는 review만 만들며 Event를 자동 변경하지 않는다.

`/actuator/health`만 public ingress에 노출한다. 이 기능의 meter는 애플리케이션 내부 registry에만 기록하며, Prometheus scrape는 private ingress와 인증·네트워크 정책이 준비된 별도 배포 환경에서만 활성화한다. title, user/event/review ID, model 문자열, 요청/응답 본문, 예외 메시지를 tag 또는 로그에 기록하지 않는다.

## 비용·공급자 대응

전용 OpenAI project에 일일 비용 cap을 설정한다. cap의 80% 도달은 온콜 alert, 100% 도달은 `OPENAI_CLASSIFICATION_ENABLED=false`로 즉시 호출을 차단한다. provider 오류·timeout·invalid schema가 지속되면 enabled flag를 false로 내리고, key·policy version·pinned model을 확인한 뒤 sandbox에서 정상 응답을 검증한다. 재시도 루프를 추가하지 않는다.

외부 provider 처리는 내부 승인된 region에서만 수행한다. provider의 data retention/deletion 조건, project별 처리 region, 입력 데이터의 보관 기간을 배포 전에 확인하고, 개인정보 담당자·보안 담당자·서비스 오너의 내부 승인을 기록한다. 일정 title은 provider 요청 외에는 저장하지 않으며, review title snapshot은 24시간 이내 purge하고 review는 90일 후 삭제한다.

## 배포 전 승인 체크리스트

- [ ] 전용 OpenAI project의 processing region이 내부 승인 지역과 일치한다.
- [ ] provider의 retention 및 deletion 조건을 개인정보 담당자와 확인했다.
- [ ] title snapshot 24시간 purge와 review 90일 delete 정책을 보안 담당자가 확인했다.
- [ ] 비용 cap, 80% alert, 100% 호출 차단과 담당 온콜을 서비스 오너가 승인했다.

## 롤아웃과 롤백

0%에서 시작해 동의된 사용자만 대상으로 **5% → 25% → 100%** 순서로 올린다. 각 단계는 최소 7일, 답변 100건 이상을 관찰한 뒤 agreement 80% 이상, provider failure 5% 미만, p95 latency 5초 이하를 모두 충족해야 다음 단계로 진행한다. `ai_classification_calls_total`의 timeout/HTTP/invalid_schema와 review 생성률, `ai_classification_retention_*`를 확인한다.

privacy 위반, 사용자 값 overwrite, duplicate review 1건, provider failure 10% 이상, 또는 p95 latency 10초 이상이면 즉시 rollout을 `0`으로 내리고 `OPENAI_CLASSIFICATION_ENABLED=false`로 호출을 중단한다. 원인을 확인하고 승인 절차를 다시 통과하기 전에는 flag를 재활성화하지 않는다. 이미 생성된 review는 title purge(24시간)와 review delete(90일) 정책을 계속 따른다.
