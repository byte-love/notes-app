-- 列表页对游客只取「推荐」那部分，分组节点也得进来，否则游客看到的还是硬排的一条长列表。
-- 只给「下面真的有推荐内容」的分组节点打推荐位，避免空分类冒出来。
update content_item n
set recommended = true
where n.kind = 'PROJECT' and n.slug in ('proj-internship', 'proj-personal') and n.deleted_at is null
  and exists (
    select 1 from content_item c
    where c.parent_id = n.id and c.deleted_at is null and c.recommended
  );
