-- 多用户：内容归属到创建者；slug 的唯一范围从「全站」改成「每人」
alter table content_item add column if not exists owner_id bigint;

update content_item
   set owner_id = (select coalesce(min(id), 1) from app_user where role = 'ADMIN')
 where owner_id is null;

alter table content_item alter column owner_id set not null;

alter table content_item
    add constraint fk_content_owner foreign key (owner_id) references app_user (id) on delete cascade;

alter table content_item drop constraint if exists content_item_slug_key;
create unique index if not exists uq_content_owner_slug on content_item (owner_id, slug);
create index if not exists idx_content_owner_kind on content_item (owner_id, kind);

-- 推荐栏：标为 true 的条目作为模板/样例对外开放
alter table content_item add column if not exists recommended boolean not null default false;
update content_item set recommended = true where owner_id is not null;

-- 从哪一条复制来的（「加入我的空间」留下的溯源）
alter table content_item add column if not exists copied_from_id bigint
    references content_item (id) on delete set null;

-- 备忘录改成按用户存
create table if not exists content_star (
    user_id    bigint      not null references app_user (id) on delete cascade,
    item_id    bigint      not null references content_item (id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (user_id, item_id)
);

-- 把旧的全局标记迁到内容所有者名下
insert into content_star (user_id, item_id)
select i.owner_id, i.id
  from content_item i
 where i.starred
on conflict do nothing;

drop index if exists idx_content_starred;
alter table content_item drop column if exists starred;
