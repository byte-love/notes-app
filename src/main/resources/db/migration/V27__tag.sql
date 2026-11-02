-- 标签：一条内容可以挂多个标签（跨分类的概念，比如「超卖」既在项目里也在 Redis 里）。
-- 关键词表在 resources/tags.json，命中的词按「标题 3 分、正文 1 分」打分，取前 5 个。
create table if not exists content_tag (
    id         bigserial   primary key,
    item_id    bigint      not null references content_item (id) on delete cascade,
    tag        varchar(32) not null,
    score      integer     not null default 0,
    created_at timestamptz not null default now()
);

create unique index if not exists uq_content_tag on content_tag (item_id, tag);
create index if not exists idx_content_tag_tag on content_tag (tag);
