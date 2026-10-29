-- 阅读位置再记一个滚动进度（0~1，按可滚动高度算），下次打开同一条能滚回去
alter table reading_position add column if not exists progress double precision not null default 0;
