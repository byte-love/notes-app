-- 术语表从 jar 里的 resources/glossary.json 搬到库：注解要能在页面上直接改（改解释 / 删掉 / 新增），
-- jar 里的 json 写不了，只能当种子（表为空时启动自动导入）。
--
-- sense 是「语境关键词」（逗号分隔），空串 = 通用义项。同一个术语可以有多个义项，只有关键词出现在
-- 这一段的正文里才命中那一条。典型例子：
--   标签 → sense=哈希,槽位,集群  : Redis 的 hash tag
--   标签 → sense=训练,样本,模型  : 模型里的 label
-- 一个术语只有语境义项、且都没命中时，宁可不标，也不要把另一行的解释套上去。
create table if not exists glossary_term (
    id         bigserial   primary key,
    term       varchar(120) not null,
    sense      varchar(240) not null default '',
    tip        text         not null,
    created_at timestamptz  not null default now(),
    updated_at timestamptz  not null default now()
);

create unique index if not exists uq_glossary_term_sense on glossary_term (term, sense);
