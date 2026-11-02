-- 星级：这条内容有多重要（0 = 没标，3 = 最该先看的）。
-- 原来飞书文档里题目标题后面的 ☆☆☆ 在导入时被当成装饰字符去掉了，这里改成独立字段，
-- 才能拿来筛选、排序、改。和「收藏」不是一回事：收藏是「我看过想留着」，星级是「这题值不值得花时间」。
alter table content_item add column if not exists rating smallint not null default 0;

alter table content_item drop constraint if exists chk_content_rating;
alter table content_item add constraint chk_content_rating check (rating between 0 and 3);

-- 列表按「本人某类内容 + 星级」筛，走这条；只给有星级的建索引，未评的占大多数不必进索引
create index if not exists idx_content_rating on content_item (owner_id, kind, rating) where rating > 0;
