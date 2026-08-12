create table gap_checks (
  id         bigserial primary key,
  user_id    uuid not null references users(id) on delete cascade,
  log_date   date not null,
  response   text not null check (response in ('completed', 'not_completed', 'unsure')),
  created_at timestamptz not null default now(),
  unique (user_id, log_date)
);
