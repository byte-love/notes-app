-- 角色改名：VIEWER -> USER，OWNER -> ADMIN
-- 必须和 Role 枚举的改动同一个版本发布，Flyway 在 JPA 之前跑，顺序是安全的
update app_user set role = 'USER' where role = 'VIEWER';
update app_user set role = 'ADMIN' where role = 'OWNER';
