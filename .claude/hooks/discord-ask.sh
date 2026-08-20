#!/bin/bash
# PreToolUse hook: for genuinely irreversible/dangerous Bash commands only,
# pause and let a Discord reply approve/deny (deny reason = redirect instruction).
# Everything else falls through instantly (exit 0, no decision) — normal flow applies.
#
# ponytail: dangerous-pattern list is a heuristic denylist, not exhaustive.
# Add patterns below as new risky commands come up.

config="$(dirname "$0")/../discord_config.json"
[ -f "$config" ] || exit 0
webhook_url=$(jq -r '.webhook_url // empty' "$config")
bot_token=$(jq -r '.bot_token // empty' "$config")
channel_id=$(jq -r '.channel_id // empty' "$config")
[ -n "$webhook_url" ] && [ -n "$bot_token" ] && [ -n "$channel_id" ] || exit 0

input=$(cat)
tool_name=$(jq -r '.tool_name // empty' <<<"$input")
[ "$tool_name" = "Bash" ] || exit 0
command=$(jq -r '.tool_input.command // empty' <<<"$input")
[ -n "$command" ] || exit 0

dangerous_re='rm +-[a-z]*r[a-z]*f|rm +-[a-z]*f[a-z]*r|git +push +.*(--force|-f\b)|git +push +.*\b(origin +)?(dev|main)\b|git +reset +--hard|git +clean +-f|gh +pr +merge|gh +pr +create|gh +issue +create|DROP +(TABLE|DATABASE|SCHEMA)|TRUNCATE|dd +if=|mkfs|kubectl +delete|terraform +destroy|(aws +ec2 +)?stop-instances|(>|touch +|cp +|mv +).{0,80}migration/V[0-9_]+__'
echo "$command" | grep -qEi "$dangerous_re" || exit 0

api="https://discord.com/api/v10"

# baseline: latest message id in the channel before we post the alert
baseline=$(curl -s -H "Authorization: Bot $bot_token" "$api/channels/$channel_id/messages?limit=1" | jq -r '.[0].id // "0"')

payload=$(jq -n --arg content "⚠️ 위험한 작업 요청: \`$command\`
승인: 네/ok  |  다른 지시를 쓰면 그걸 사유로 거부하고 Claude에게 전달함 (20분 내 무응답시 자동 거부 — 자동 승인 없음)" '{content: $content}')
curl -s -X POST -H "Content-Type: application/json" -d "$payload" "$webhook_url" > /dev/null

# ponytail: 부재중 자동 승인 방지 — 20분 고정 대기 후에도 응답 없으면 거부. 더 긴 외출 대비 시간이 필요하면 wait_seconds를 늘릴 것
wait_seconds=1200
poll_interval=5
elapsed=0
while [ "$elapsed" -lt "$wait_seconds" ]; do
  sleep "$poll_interval"
  elapsed=$((elapsed + poll_interval))
  reply=$(curl -s -H "Authorization: Bot $bot_token" "$api/channels/$channel_id/messages?after=$baseline&limit=5" \
    | jq -r '[.[] | select(.author.bot != true)] | sort_by(.id) | .[0].content // empty')
  [ -n "$reply" ] || continue

  if echo "$reply" | grep -qiE '^(네|ok|yes|승인|ㅇㅇ|허용)$'; then
    jq -n '{hookSpecificOutput: {hookEventName: "PreToolUse", permissionDecision: "allow"}}'
  else
    jq -n --arg reason "$reply" '{hookSpecificOutput: {hookEventName: "PreToolUse", permissionDecision: "deny", permissionDecisionReason: $reason}}'
  fi
  exit 0
done

# 타임아웃: 무응답이면 자동 승인이 아니라 명시적으로 거부한다
jq -n '{hookSpecificOutput: {hookEventName: "PreToolUse", permissionDecision: "deny", permissionDecisionReason: "20분간 디스코드 응답 없음 — 안전을 위해 자동 거부됨. 필요하면 다시 요청하세요."}}'
exit 0
