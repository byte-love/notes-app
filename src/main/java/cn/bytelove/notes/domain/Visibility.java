package cn.bytelove.notes.domain;

/**
 * 内容可见性。角色不是目的，可见性才是。
 * name() 是数据库里存的值，label 是给人看的两三个字，hint 是一句话解释。
 */
public enum Visibility {
    PUBLIC("公开", "谁都能看"),
    MEMBER("登录可见", "登录后才能看"),
    PRIVATE("仅自己", "只有你能看");

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
