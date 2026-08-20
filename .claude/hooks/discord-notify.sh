#!/bin/bash
# Sends a Claude Code Notification/Stop/StopFailure event to a Discord webhook.
# Webhook URL lives in .claude/discord_config.json (gitignored).

config="$(dirname "$0")/../discord_config.json"
[ -f "$config" ] || exit 0
webhook_url=$(jq -r '.webhook_url // empty' "$config")
[ -n "$webhook_url" ] || exit 0

input=$(cat)
event=$(jq -r '.hook_event_name // empty' <<<"$input")

case "$event" in
  Notification)
    text=$(jq -r '.message // "Claude needs your attention"' <<<"$input")
    ;;
  Stop)
    text=$(jq -r '.last_assistant_message // "Claude finished a turn"' <<<"$input")
    ;;
  StopFailure)
    # ponytail: 정확한 페이로드 필드명이 문서에 없어 후보 필드 + raw JSON을 함께 보냄
    text="🔴 사용량/레이트리밋 도달 가능성 — $(jq -r '.error.message // .message // .reason // "세부 메시지 없음"' <<<"$input")
raw: $(jq -c . <<<"$input" | cut -c1-800)"
    ;;
  *)
    exit 0
    ;;
esac

text="[$event] ${text:0:1500}"
payload=$(jq -n --arg content "$text" '{content: $content}')
curl -s -X POST -H "Content-Type: application/json" -d "$payload" "$webhook_url" > /dev/null
exit 0
