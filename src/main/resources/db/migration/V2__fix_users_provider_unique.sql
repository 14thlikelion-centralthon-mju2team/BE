-- unique(provider, provider_uid)가 archived_at을 무시해서, idx_users_email_active
-- (활성 사용자만 대상)와 어긋난다. 탈퇴 후 같은 이메일/구글 계정으로 재가입하면
-- email 쪽은 통과하는데 이 제약에서 막혀 INSERT가 실패한다.
-- 활성 사용자만 대상으로 하는 partial unique index로 교체한다.

alter table users drop constraint users_provider_provider_uid_key;

create unique index idx_users_provider_active
  on users (provider, provider_uid) where archived_at is null;
