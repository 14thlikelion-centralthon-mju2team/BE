-- Google Calendar API syncToken — 증분 동기화 재개 지점. null이면 다음 동기화는 전체 조회.
alter table calendar_connection add column sync_token text;
