# TLS 배포 가이드

## 전제 조건

- 가비아 서버 (1.201.116.84)에 SSH 접근 가능
- 도메인이 해당 IP로 DNS A 레코드 설정됨
- Docker Compose가 설치됨

## 현재 상태 (TLS 미적용)

```
http://1.201.116.84  →  nginx(80)  →  api(8080)
```

JWT, 비밀번호 등 인증 데이터가 평문 전송됨.

## TLS 적용 후

```
https://api.ensom.app  →  nginx(443, TLS)  →  api(8080)
http://api.ensom.app   →  301 redirect   →  https://
```

## 적용 방법

### 1. 도메인 DNS 설정

가비아 DNS에서 A 레코드 추가:
```
api.ensom.app  →  1.201.116.84
```

### 2. 서버에서 스크립트 실행

```bash
ssh root@1.201.116.84
cd /path/to/project
chmod +x deploy/setup-tls.sh
./deploy/setup-tls.sh api.ensom.app
```

### 3. docker-compose.yml 수정

```yaml
nginx:
  ports:
    - "80:80"
    - "443:443"
  volumes:
    - ./nginx/default.conf:/etc/nginx/conf.d/default.conf:ro
    - /etc/letsencrypt:/etc/letsencrypt:ro
    - /var/www/certbot:/var/www/certbot:ro
```

### 4. 재시작

```bash
docker compose up -d
```

### 5. 인증서 자동 갱신

```bash
crontab -e
# 추가:
0 3 * * * certbot renew --quiet && docker compose restart nginx
```

## 도메인 없이 IP만 사용하는 경우

Let's Encrypt는 IP 주소에 인증서를 발급하지 않음. 이 경우:
- 자체 서명 인증서 사용 (개발/테스트용)
- 또는 Cloudflare Flexible SSL (프록시)

## 확인

```bash
curl -I https://api.ensom.app/health
# HTTP/2 200, Strict-Transport-Security 헤더 확인
```

## 관련

- BE 이슈 #199
- nginx/default.conf — HTTP/HTTPS 설정
- deploy/setup-tls.sh — 자동화 스크립트
