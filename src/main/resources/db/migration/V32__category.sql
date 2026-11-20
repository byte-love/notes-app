-- 「我的笔记」的分类：从写死的五个分区（kind）变成每个用户自己的一组分类。
-- kind 仍然保留在条目上（决定模板、配色、详情路由），分类只是上层的一层组织。

create table note_category (
    id         bigserial primary key,
    owner_id   bigint      not null references app_user (id) on delete cascade,
    slug       varchar(80) not null,
    name       varchar(80) not null,
    kind       varchar(16) not null,
    sort_order integer     not null default 0,
    builtin    boolean     not null default false,
    created_at timestamp with time zone not null default now(),
    constraint uq_note_category_owner_slug unique (owner_id, slug)
);

create index ix_note_category_owner on note_category (owner_id, sort_order);

-- 每个已有用户先落五个默认分类，和原来的五个分区一一对应
insert into note_category (owner_id, slug, name, kind, sort_order, builtin)
select u.id, c.slug, c.name, c.kind, c.sort_order, true
from app_user u
cross join (values
    ('note', '闪念', 'NOTE', 0),
    ('project', '项目', 'PROJECT', 1),
    ('bagu', '八股', 'BAGU', 2),
    ('algo', '算法', 'ALGO', 3),
    ('interview', '面试', 'INTERVIEW', 4)
) as c(slug, name, kind, sort_order)
on conflict (owner_id, slug) do nothing;

alter table content_item
    add column category_id bigint references note_category (id) on delete set null;

create index ix_content_item_category on content_item (category_id);

-- 现有内容按 kind 归到对应的默认分类
update content_item i
set category_id = c.id
from note_category c
where c.owner_id = i.owner_id
  and c.builtin
  and c.kind = i.kind
  and i.category_id is null;
