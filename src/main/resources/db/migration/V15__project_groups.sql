-- 项目分两类：实习项目 / 个人项目（和作品集里的分法一致）。
-- 分类用父节点表达：左侧树会显示成可折叠的分组，列表页也按它分小节。

insert into content_item (kind, slug, title, body, visibility, sort_order, owner_id)
select distinct on (owner_id) 'PROJECT', 'proj-internship', '实习项目', '', 'PUBLIC', 0, owner_id
from content_item
where kind = 'PROJECT' and deleted_at is null
order by owner_id, id
on conflict (owner_id, slug) do nothing;

insert into content_item (kind, slug, title, body, visibility, sort_order, owner_id)
select distinct on (owner_id) 'PROJECT', 'proj-personal', '个人项目', '', 'PUBLIC', 1, owner_id
from content_item
where kind = 'PROJECT' and deleted_at is null
order by owner_id, id
on conflict (owner_id, slug) do nothing;

update content_item c
set parent_id = p.id
from content_item p
where p.kind = 'PROJECT' and p.slug = 'proj-internship' and p.deleted_at is null
  and p.owner_id = c.owner_id
  and c.kind = 'PROJECT' and c.deleted_at is null
  and c.slug in ('logs-manage-system', 'paper-annotation-system', 'wechat-register-miniprogram',
                 'data-split-cv', 'patent-quality-pipeline', 'tech-complementarity-index',
                 'pdf-batch-extractor', 'home-service-coupon');

update content_item c
set parent_id = p.id
from content_item p
where p.kind = 'PROJECT' and p.slug = 'proj-personal' and p.deleted_at is null
  and p.owner_id = c.owner_id
  and c.kind = 'PROJECT' and c.deleted_at is null
  and c.slug in ('self-hosted-lab', 'nuanyang-eldercare', 'eduverse');
