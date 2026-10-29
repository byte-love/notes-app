-- 回收站：删除改成打标记，30 天后由定时任务彻底清掉
alter table content_item add column if not exists deleted_at timestamptz;

create index if not exists idx_content_deleted
    on content_item (owner_id, deleted_at) where deleted_at is not null;

-- 阅读位置：每个分区只记最后一次，用来在列表和树里标出「上次看到这了」
create table if not exists reading_position (
    user_id    bigint      not null references app_user (id) on delete cascade,
    kind       varchar(16) not null,
    item_id    bigint      not null references content_item (id) on delete cascade,
    updated_at timestamptz not null default now(),
    primary key (user_id, kind)
);
