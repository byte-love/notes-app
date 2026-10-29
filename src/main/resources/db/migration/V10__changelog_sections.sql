-- 一条条目属于哪个小节。参考的样式是在一次发布里再分若干小节，每节几条要点。
alter table changelog_entry add column if not exists section varchar(200);

-- 旧的扁平数据（带 [新增]/[优化] 前缀）按新格式重录，先清掉
delete from changelog_entry;
