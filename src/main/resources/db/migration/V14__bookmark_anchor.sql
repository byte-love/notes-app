-- 书签可以定位到正文里的某个标题块：anchor 存标题的 id，整篇书签则为空串
alter table content_bookmark add column if not exists anchor varchar(200) not null default '';

alter table content_bookmark drop constraint if exists content_bookmark_pkey;
alter table content_bookmark add primary key (user_id, item_id, anchor);
