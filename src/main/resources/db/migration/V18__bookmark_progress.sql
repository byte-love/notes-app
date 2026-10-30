-- 书签记一下具体位置（0~1 的滚动比例），这样整篇书签点了也能回到当时的行
alter table content_bookmark add column if not exists progress double precision not null default 0;
