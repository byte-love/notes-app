-- 分类改由「小节」承载，category 列不再使用；它还是 not null 会挡住插入
alter table changelog_entry drop column if exists category;
