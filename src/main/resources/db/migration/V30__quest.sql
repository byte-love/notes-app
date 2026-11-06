-- 知识考核：一题一关的通关记录 + 累计积分
-- 只记「过没过」和正确率，不存挖空内容：挖空是按正文规则现算的，正文改了关卡跟着变
create table if not exists quest_progress (
    user_id    bigint      not null references app_user (id) on delete cascade,
    item_id    bigint      not null references content_item (id) on delete cascade,
    clears     int         not null default 0,
    passed     boolean     not null default false,
    best_rate  double precision not null default 0,
    last_rate  double precision not null default 0,
    updated_at timestamptz not null default now(),
    primary key (user_id, item_id)
);

create index if not exists idx_quest_progress_user on quest_progress (user_id);

-- 每个人的总分和连续天数；xp 是累加的，页面不靠 sum(quest_event) 现算
create table if not exists quest_point (
    user_id     bigint primary key references app_user (id) on delete cascade,
    xp          int  not null default 0,
    streak_days int  not null default 0,
    last_day    date
);
