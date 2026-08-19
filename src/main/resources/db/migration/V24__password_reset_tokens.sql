create table password_reset_token (
    password_reset_token_id uuid primary key default gen_random_uuid(),
    token_hash varchar(64) not null unique,
    user_id uuid not null references users(user_id) on delete cascade,
    type varchar(20) not null default 'password_reset',
    new_email varchar(255),
    expires_at timestamptz not null,
    consumed_at timestamptz,
    created_at timestamptz not null default now()
);

create index ix_password_reset_token_active
    on password_reset_token (user_id, type, created_at desc)
    where consumed_at is null;
