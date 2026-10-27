alter table content_item
    add column parent_id bigint references content_item (id) on delete set null;

alter table content_item
    add column sort_order integer not null default 0;

create index idx_content_parent on content_item (parent_id, sort_order);
