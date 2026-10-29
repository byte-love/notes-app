-- 手动书签：用户自己插的旗，可以有很多个，和「上次看到」（每个分区自动记一个）不是一回事
create table if not exists content_bookmark (
    user_id    bigint      not null references app_user (id) on delete cascade,
    item_id    bigint      not null references content_item (id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (user_id, item_id)
);
