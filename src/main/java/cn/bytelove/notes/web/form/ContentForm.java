package cn.bytelove.notes.web.form;

import cn.bytelove.notes.domain.ContentKind;
import cn.bytelove.notes.domain.Visibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class ContentForm {

    @NotNull
    private ContentKind kind = ContentKind.NOTE;

    @NotBlank
    @Size(max = 128)
    @Pattern(regexp = "[a-z0-9][a-z0-9-]*", message = "只能用小写字母、数字和连字符")
    private String slug;

    @NotBlank
    @Size(max = 200)
    private String title;

    @Size(max = 500)
    private String summary;

    /** 正文：目录可以不填，普通条目在控制器里另外校验非空。 */
    private String body;

    /** 只当目录用（分类 / 文件夹）：列表里不占卡片，可以在它下面继续新建。 */
    private boolean folder;

    @NotNull
    private Visibility visibility = Visibility.MEMBER;

    private Long parentId;

    private Integer sortOrder = 0;

    /** 项目的结构化字段（中英双语、action/result 是数组），存 project_meta.doc，只在类型为 PROJECT 时生效。 */
    private String projectDoc;

    /** 正文末尾挂的那张补充笔记卡片：标题和正文直接在编辑页里写，保存时建成挂在本文下面的一篇。 */
    private String supplementTitle;

    /** 补充笔记的正文（Markdown）。留空就是不挂。 */
    private String supplementBody;

    public String getSupplementTitle() {
        return supplementTitle;
    }

    public void setSupplementTitle(String supplementTitle) {
        this.supplementTitle = supplementTitle;
    }

    public String getSupplementBody() {
        return supplementBody;
    }

    public void setSupplementBody(String supplementBody) {
        this.supplementBody = supplementBody;
    }

    public String getProjectDoc() {
        return projectDoc;
    }

    public void setProjectDoc(String projectDoc) {
        this.projectDoc = projectDoc;
    }

    public ContentKind getKind() {
        return kind;
    }

    public void setKind(ContentKind kind) {
        this.kind = kind;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public Visibility getVisibility() {
        return visibility;
    }

    public void setVisibility(Visibility visibility) {
        this.visibility = visibility;
    }

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public boolean isFolder() {
        return folder;
    }

    public void setFolder(boolean folder) {
        this.folder = folder;
    }
}
