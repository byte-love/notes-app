-- 书签定位到具体行的功能已回退（V18 加了 progress 列，这里把它去掉）
alter table content_bookmark drop column if exists progress;
