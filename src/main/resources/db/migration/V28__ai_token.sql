-- AI 接入用的个人 token：只存哈希，明文只在生成时显示一次。
alter table app_user add column if not exists api_token_hash varchar(100);
alter table app_user add column if not exists api_token_hint varchar(16);
alter table app_user add column if not exists api_token_created_at timestamptz;

-- 覆盖更新前的旧版本快照：AI 改错了还能捞回来，不指望 AI 自觉备份。
create table if not exists content_revision (
    id         bigserial   primary key,
    item_id    bigint      not null references content_item (id) on delete cascade,
    title      varchar(200) not null,
    summary    varchar(500),
    body       text        not null,
    visibility varchar(16) not null,
    parent_id  bigint,
    source     varchar(16) not null default 'AI',
    saved_at   timestamptz not null default now()
);

create index if not exists idx_content_revision_item on content_revision (item_id, saved_at desc);
