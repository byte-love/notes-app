create table app_user (
    id            bigserial primary key,
    username      varchar(64)  not null unique,
    password_hash varchar(100) not null,
    display_name  varchar(64)  not null,
    role          varchar(16)  not null,
    enabled       boolean      not null default true,
    created_at    timestamptz  not null default now()
);

create table content_item (
    id         bigserial primary key,
    kind       varchar(16)  not null,
    slug       varchar(128) not null unique,
    title      varchar(200) not null,
    summary    varchar(500),
    body       text         not null,
    visibility varchar(16)  not null,
    created_at timestamptz  not null default now(),
    updated_at timestamptz  not null default now()
);

create index idx_content_kind_visibility on content_item (kind, visibility);
create index idx_content_updated_at on content_item (updated_at desc);
