-- 重点高亮：选中一段文字标成重点，颜色自己选。
-- 和评论 / 注释不一样，高亮只给标的这个人自己看（阅读时的记号，不是给别人看的内容），
-- 所以查询一律带 user_id，其他人看不到。
create table if not exists content_highlight (
    id          bigserial primary key,
    item_id     bigint      not null references content_item (id) on delete cascade,
    user_id     bigint      not null references app_user (id) on delete cascade,
    quote       text        not null,
    anchor_kind varchar(16) not null default 'text',
    block_index integer,
    prefix      text,
    suffix      text,
    color       varchar(16) not null default 'yellow',
    created_at  timestamptz not null default now(),
    deleted_at  timestamptz
);

create index if not exists idx_highlight_item_user on content_highlight (item_id, user_id);
