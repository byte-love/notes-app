-- 正文评论：可以评论整篇，也可以锚定到某一句话（quote 存句子原文，渲染时按文本匹配加下划线）
create table if not exists content_comment (
    id         bigserial primary key,
    item_id    bigint      not null references content_item (id) on delete cascade,
    user_id    bigint      not null references app_user (id) on delete cascade,
    parent_id  bigint      references content_comment (id) on delete cascade,
    quote      text,
    body       text        not null,
    created_at timestamptz not null default now(),
    deleted_at timestamptz
);

create index if not exists idx_comment_item on content_comment (item_id, id);
create index if not exists idx_comment_parent on content_comment (parent_id);
