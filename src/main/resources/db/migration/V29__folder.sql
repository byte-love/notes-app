-- 目录（分类）：只当收纳用，不占列表里的卡片，可以继续在它下面新建
alter table content_item add column is_folder boolean not null default false;

comment on column content_item.is_folder is '只当目录用（分类 / 文件夹）：不进列表卡片，可以在它下面继续挂条目';
