package cn.bytelove.notes.domain;

/**
 * 内容可见性分档：角色不是目的，可见性才是。
 * PUBLIC 面向所有访客，MEMBER 面向登录用户（含演示账号），PRIVATE 仅 OWNER。
 */
public enum Visibility {
    PUBLIC,
    MEMBER,
    PRIVATE
}
