-- 注释和评论分家：同一张表，用 entry_kind 区分
--   COMMENT    = 右栏评论（整篇评、选中文字评、楼中楼回复）
--   ANNOTATION = 注释（正文短虚下划线，点开弹窗看）
alter table content_comment add column entry_kind varchar(16) not null default 'COMMENT';

create index if not exists idx_comment_item_kind on content_comment (item_id, entry_kind);
