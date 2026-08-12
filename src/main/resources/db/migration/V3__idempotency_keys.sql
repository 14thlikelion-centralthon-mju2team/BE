create table idempotency_keys (
  id             bigserial primary key,
  user_id        uuid not null references users(id) on delete cascade,
  idempotency_key text not null,
  endpoint       text not null,
  status_code    int not null,
  response_body  text not null,
  created_at     timestamptz not null default now(),
  unique (user_id, idempotency_key, endpoint)
);
