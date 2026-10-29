-- 手写的每日维护记录。自动那部分（内容新增/修改）直接从 content_item 的时间推，不落表。
create table if not exists maintenance_log (
    id         bigserial primary key,
    author_id  bigint      not null references app_user (id) on delete cascade,
    log_date   date        not null,
    body       text        not null,
    created_at timestamptz not null default now()
);

create index if not exists idx_maintenance_date on maintenance_log (log_date desc);
