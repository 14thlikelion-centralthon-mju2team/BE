create table email_verification_token (
    email_verification_token_id uuid primary key default gen_random_uuid(),
    user_id uuid not null references users(user_id) on delete cascade,
    token_hash varchar(64) not null unique,
    expires_at timestamptz not null,
    used_at timestamptz,
    invalidated_at timestamptz,
    created_at timestamptz not null default now()
);

create index ix_email_verification_token_active
    on email_verification_token (user_id, created_at desc)
    where used_at is null and invalidated_at is null;
