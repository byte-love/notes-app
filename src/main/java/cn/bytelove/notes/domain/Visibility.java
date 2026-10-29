package cn.bytelove.notes.domain;

/**
 * 内容可见性。角色不是目的，可见性才是。
 * name() 是数据库里存的值，label 是给人看的名字，hint 是问号里那句话。
 */
public enum Visibility {
    PUBLIC("任何用户可见", "没登录的访客也能看到，包括搜索引擎抓到的"),
    MEMBER("登录用户可见", "没登录只能看到标题，登录之后才能看正文"),
    PRIVATE("仅自己", "只有你能看到，别人连标题都不会出现");

    private final String label;
    private final String hint;

    Visibility(String label, String hint) {
        this.label = label;
        this.hint = hint;
    }

    public String getLabel() {
        return label;
    }

    public String getHint() {
        return hint;
    }
}
