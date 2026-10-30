-- 评论锚点升级：支持「选中一段文字」和「整段/整个代码块」
--   anchor_kind = text  选中文字，quote 存选中的原文，prefix/suffix 存前后各二十字用于消歧
--   anchor_kind = block 整块（段落 / 代码块 / 列表），block_index 是正文里顶层块的序号
alter table content_comment
    add column if not exists anchor_kind varchar(16) not null default 'text',
    add column if not exists block_index integer,
    add column if not exists prefix text,
    add column if not exists suffix text;
