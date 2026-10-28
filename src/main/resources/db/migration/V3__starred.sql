-- 备忘录：标记总是记不住的条目
alter table content_item add column if not exists starred boolean not null default false;

create index if not exists idx_content_starred on content_item (starred) where starred;
