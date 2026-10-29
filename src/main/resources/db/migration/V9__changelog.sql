-- 系统更新日志：记录功能层面「新增了什么 / 改了什么 / 修了什么」
-- 和时间线不同的地方在于它按发布分组，一组里面按分类列条目
create table if not exists changelog_entry (
    id          bigserial primary key,
    version     varchar(32),
    released_on date        not null,
    title       varchar(200) not null,
    category    varchar(16)  not null,
    content     text         not null,
    sort_order  integer      not null default 0,
    created_at  timestamptz  not null default now()
);

create index if not exists idx_changelog_released on changelog_entry (released_on desc, sort_order);
