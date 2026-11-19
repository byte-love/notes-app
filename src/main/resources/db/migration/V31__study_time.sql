-- 学习时长：按用户 + 日期 + 分类累计秒数，前端只在页面可见时上报
create table if not exists study_time (
    user_id    bigint      not null references app_user(id) on delete cascade,
    day        date        not null,
    category   varchar(24) not null,
    seconds    integer     not null default 0 check (seconds >= 0),
    updated_at timestamptz not null default now(),
    primary key (user_id, day, category)
);

create index if not exists idx_study_time_day on study_time (day desc);
