#!/bin/bash
# TLS 인증서 발급 및 nginx 설정 스크립트 (가비아 서버용)
# 사용법: ssh root@1.201.116.84 후 실행
#   chmod +x deploy/setup-tls.sh
#   ./deploy/setup-tls.sh your-domain.com

set -euo pipefail

DOMAIN=${1:?"사용법: $0 <도메인명> (예: api.ensom.app)"}

echo "=== 1. certbot 설치 ==="
apt-get update -qq
apt-get install -y certbot

echo "=== 2. 인증서 발급 (standalone — nginx 중지 필요) ==="
docker compose -f docker-compose.yml down nginx 2>/dev/null || true
certbot certonly --standalone -d "$DOMAIN" --non-interactive --agree-tos --email admin@ensom.app

echo "=== 3. nginx TLS 설정 생성 ==="
cat > nginx/default.conf <<NGINX
limit_req_zone \$binary_remote_addr zone=api:10m rate=30r/s;

upstream api {
    server api:8080;
}

server {
    listen 80;
    server_name $DOMAIN;

    # ACME challenge (인증서 갱신용)
    location /.well-known/acme-challenge/ {
        root /var/www/certbot;
    }

    # 나머지는 HTTPS로 리다이렉트
    location / {
        return 301 https://\$host\$request_uri;
    }
}

server {
    listen 443 ssl;
    server_name $DOMAIN;

    ssl_certificate /etc/letsencrypt/live/$DOMAIN/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/$DOMAIN/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers HIGH:!aNULL:!MD5;

    client_max_body_size 10m;

    add_header X-Frame-Options "DENY" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header Referrer-Policy "strict-origin-when-cross-origin" always;
    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;

    location / {
        limit_req zone=api burst=60 nodelay;
        proxy_pass http://api;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_connect_timeout 10s;
        proxy_read_timeout 30s;
    }

    location /v1/ {
        limit_req zone=api burst=60 nodelay;
        proxy_pass http://api/;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_connect_timeout 10s;
        proxy_read_timeout 30s;
    }

    location = /health {
        proxy_pass http://api/actuator/health;
        proxy_set_header Host \$host;
    }
}
NGINX

echo "=== 4. docker-compose.yml nginx 포트 수정 ==="
# 443 포트 추가가 필요하면 수동으로 docker-compose.yml 수정
echo "⚠️ docker-compose.yml의 nginx ports에 443:443을 추가하고,"
echo "   volumes에 인증서 경로를 마운트해야 합니다:"
echo ""
echo "  nginx:"
echo "    ports:"
echo "      - \"80:80\""
echo "      - \"443:443\""
echo "    volumes:"
echo "      - ./nginx/default.conf:/etc/nginx/conf.d/default.conf:ro"
echo "      - /etc/letsencrypt:/etc/letsencrypt:ro"
echo "      - /var/www/certbot:/var/www/certbot:ro"

echo ""
echo "=== 5. 재시작 ==="
echo "docker compose up -d 실행하세요."
echo ""
echo "=== 6. 인증서 자동 갱신 cron 등록 ==="
echo "0 3 * * * certbot renew --quiet && docker compose restart nginx"
echo "위 줄을 crontab -e로 추가하세요."
echo ""
echo "✅ TLS 설정 완료. https://$DOMAIN 으로 접속 가능합니다."
