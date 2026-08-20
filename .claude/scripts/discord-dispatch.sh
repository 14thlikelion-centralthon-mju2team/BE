#!/bin/bash
# Long-running daemon: polls the Discord channel for "!claude <prompt>" messages
# and dispatches each as an independent headless Claude Code task, posting the
# result back to the same channel via webhook.
#
# Permission model: --permission-mode bypassPermissions (no one's at the keyboard
# to click "allow"). The PreToolUse hook (discord-ask.sh, registered project-wide
# in .claude/settings.local.json) still fires under this mode and gates the actual
# dangerous command subset via its own Discord approval prompt — bypassPermissions
# only skips the interactive prompt for whatever that hook doesn't flag.
#
# ponytail: one task at a time, no queue. A "!claude" message that arrives while a
# task is running just waits for the next poll after the current one finishes.

set -euo pipefail
cd "$(dirname "$0")/../.."  # project root

config=".claude/discord_config.json"
state=".claude/discord_dispatch_state"
session_file=".claude/discord_dispatch_session_id"
claude_bin="/Users/parkchan/.npm-global/bin/claude"
# 이 디스패처를 만든 대화 세션의 ID. 최초 실행 시 여기서 한 번만 분기(fork)한다 —
# 이후 세션 파일이 생기면 그쪽으로 계속 이어가고, 이 값은 다시 안 쓴다.
bootstrap_session_id="3d235543-56c1-4288-ac19-2b8914d73316"

pidfile=".claude/discord_dispatch.pid"
# ponytail: 중복 실행 방지 락 — 여러 경로(수동 nohup, launchd 등)로 겹쳐 뜨면
# 같은 메시지를 서로 집어가서 중복 응답이 발생하기 때문에 여기서 막는다.
if [ -f "$pidfile" ] && kill -0 "$(cat "$pidfile")" 2>/dev/null; then
  echo "already running (pid $(cat "$pidfile")), exiting" >&2
  exit 0
fi
echo $$ > "$pidfile"

[ -f "$config" ] || { echo "missing $config" >&2; exit 1; }
webhook_url=$(jq -r '.webhook_url // empty' "$config")
bot_token=$(jq -r '.bot_token // empty' "$config")
channel_id=$(jq -r '.channel_id // empty' "$config")
[ -n "$webhook_url" ] && [ -n "$bot_token" ] && [ -n "$channel_id" ] || { echo "config incomplete" >&2; exit 1; }

api="https://discord.com/api/v10"
poll_interval=10
task_timeout=1800  # 30min per dispatched task

post() {
  payload=$(jq -n --arg c "$1" '{content: $c}')
  curl -s -X POST -H "Content-Type: application/json" -d "$payload" "$webhook_url" > /dev/null
}

# 프롬프트 하나를 새 headless claude 프로세스로 실행한다.
# stdout에 원본 JSON을 뱉고, exit code로 성공 여부를 알린다(타임아웃 포함).
# ponytail: macOS엔 GNU timeout이 기본으로 없어서 백그라운드 watcher로 직접 구현
run_claude() {
  local prompt="$1"; shift
  local args=(-p "$prompt" --permission-mode bypassPermissions --output-format json "$@")

  local tmpfile; tmpfile=$(mktemp)
  "$claude_bin" "${args[@]}" > "$tmpfile" 2>&1 &
  local task_pid=$!
  ( sleep "$task_timeout"; kill -TERM "$task_pid" 2>/dev/null ) &
  local watcher_pid=$!

  local rc
  if wait "$task_pid" 2>/dev/null; then rc=0; else rc=$?; fi
  kill "$watcher_pid" 2>/dev/null

  cat "$tmpfile"
  rm -f "$tmpfile"
  return "$rc"
}

# resume from last-seen message id, or start from "now" on first run
if [ -f "$state" ]; then
  last_id=$(cat "$state")
else
  last_id=$(curl -s -H "Authorization: Bot $bot_token" "$api/channels/$channel_id/messages?limit=1" | jq -r '.[0].id // "0"')
  echo "$last_id" > "$state"
fi

echo "dispatcher started, watching channel $channel_id from message $last_id"

while true; do
  sleep "$poll_interval"
  messages=$(curl -s -H "Authorization: Bot $bot_token" "$api/channels/$channel_id/messages?after=$last_id&limit=20" | jq -c 'sort_by(.id | tonumber)')
  [ "$messages" = "[]" ] && continue

  latest_id=$(echo "$messages" | jq -r '.[-1].id')

  while read -r msg; do
    [ -n "$msg" ] || continue
    content=$(echo "$msg" | jq -r '.content')
    case "$content" in
      "!claude "*)
        prompt="${content#\!claude }"
        echo "dispatching: $prompt"
        post "▶️ 작업 시작: $prompt"

        if [ -s "$session_file" ]; then
          first_args=(--resume "$(cat "$session_file")")
        else
          # 최초 실행 — 이 디스패처를 만든 대화 세션(bootstrap_session_id)에서
          # 그 시점까지의 맥락을 그대로 분기해서 시작한다. 이후로는 이 원본
          # 세션과 서로 동기화되지 않는, 별개의 갈라진 스레드로 이어진다.
          first_args=(--resume "$bootstrap_session_id" --fork-session)
        fi

        if raw=$(run_claude "$prompt" "${first_args[@]}"); then rc=0; else rc=$?; fi
        if [ "$rc" -ne 0 ]; then
          # 분기 또는 재개 실패 — 세션을 새로 시작해서 한 번 더 시도
          rm -f "$session_file"
          if raw=$(run_claude "$prompt"); then rc=0; else rc=$?; fi
        fi

        new_sid=$(echo "$raw" | jq -r '.session_id // empty' 2>/dev/null || true)
        [ -n "$new_sid" ] && echo "$new_sid" > "$session_file"

        result=$(echo "$raw" | jq -r '.result // empty' 2>/dev/null || true)
        [ -n "$result" ] || result="(실패 또는 타임아웃) $raw"

        post "✅ 완료: ${result:0:1900}"
        ;;
      "!"*)
        # ponytail: 오타(예: !cluade)가 조용히 무시되던 걸 눈에 보이게
        post "🤷 인식 못한 명령: \`$content\` — \`!claude <지시>\` 형식으로 보내주세요."
        ;;
    esac
  done < <(echo "$messages" | jq -c '.[] | select(.author.bot != true)')

  last_id="$latest_id"
  echo "$last_id" > "$state"
done
