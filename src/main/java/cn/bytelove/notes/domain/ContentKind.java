package cn.bytelove.notes.domain;

/**
 * 内容分区。name() 是数据库里存的值，label 是给人看的名字。
 */
public enum ContentKind {
    NOTE("笔记", "学习记录、踩坑总结一类的笔记"),
    PROJECT("项目", "自己做过的东西，会出现在作品集里"),
    BAGU("八股", "面试题和标准答案，通常按分类归档"),
    ALGO("算法", "题解和思路");

    private final String label;
    private final String hint;

    ContentKind(String label, String hint) {
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
