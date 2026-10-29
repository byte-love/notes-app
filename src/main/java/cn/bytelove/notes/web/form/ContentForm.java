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

    @NotBlank
    private String body;

    @NotNull
    private Visibility visibility = Visibility.MEMBER;

    private Long parentId;

    private Integer sortOrder = 0;

    /** 项目的结构化字段（中英双语、action/result 是数组），存 project_meta.doc，只在类型为 PROJECT 时生效。 */
    private String projectDoc;

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
}
