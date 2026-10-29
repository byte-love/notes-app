-- 项目的结构化字段：中英双语 + action/result 是数组 + 架构图/截图，整体按 JSON 存，
-- 这样静态作品集能原样渲染，页面排版不用改。
create table if not exists project_meta (
    item_id bigint primary key references content_item (id) on delete cascade,
    doc     jsonb not null
);
