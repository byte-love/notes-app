-- 补充笔记：一条内容可以指向另一条「本体」（例如八股正文下面挂的详解）。
-- 只加一条关系字段，不动层级：补充笔记的 parent_id 跟本体相同，二者在目录里仍是同级。
alter table content_item
    add column supplement_of_id bigint references content_item (id);

create index idx_item_supplement on content_item (supplement_of_id);
