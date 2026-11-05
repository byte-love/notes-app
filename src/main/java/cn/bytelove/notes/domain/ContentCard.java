package cn.bytelove.notes.domain;

import java.time.Instant;

/**
 * 列表 / 目录 / 广场用的轻量视图：字段和 {@link ContentItem} 一样，唯独没有正文。
 * <p>
 * 列表页一次要摆几百条，正文是表里最大的列（每篇几 KB 到几十 KB），为了渲染头几十张卡片
 * 把整个分区的正文都读进内存没必要。所以凡是「只显示标题、摘要、星级」的页面都查这个视图，
 * 要正文的地方（正文页、编辑页、复制到我的空间）仍然查 {@link ContentItem}。
 * <p>
 * 取值器用 JavaBean 命名（{@code getTitle()} 而不是 {@code title()}）：
 * Thymeleaf 模板里是 {@code i.title} / {@code n.item.slug}，靠的就是这套名字。
 */
public final class ContentCard {

    private final Long id;
    private final Long ownerId;
    private final ContentKind kind;
    private final String slug;
    private final String title;
    private final String summary;
    private final Visibility visibility;
    private final Long parentId;
    private final int sortOrder;
    private final int rating;
    private final boolean recommended;
    private final Long copiedFromId;
    private final boolean folder;
    private final Long supplementOfId;
    private final Instant createdAt;
    private final Instant updatedAt;

    public ContentCard(Long id, Long ownerId, ContentKind kind, String slug, String title, String summary,
                       Visibility visibility, Long parentId, int sortOrder, int rating, boolean recommended,
                       Long copiedFromId, boolean folder, Long supplementOfId,
                       Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.ownerId = ownerId;
        this.kind = kind;
        this.slug = slug;
        this.title = title;
        this.summary = summary;
        this.visibility = visibility;
        this.parentId = parentId;
        this.sortOrder = sortOrder;
        this.rating = rating;
        this.recommended = recommended;
        this.copiedFromId = copiedFromId;
        this.folder = folder;
        this.supplementOfId = supplementOfId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** 正文页手里已经有整条记录了（正文要用来渲染），转成卡片塞进面包屑那一串。 */
    public static ContentCard of(ContentItem item) {
        return new ContentCard(item.getId(), item.getOwnerId(), item.getKind(), item.getSlug(),
                item.getTitle(), item.getSummary(), item.getVisibility(), item.getParentId(),
                item.getSortOrder(), item.getRating(), item.isRecommended(), item.getCopiedFromId(),
                item.isFolder(), item.getSupplementOfId(), item.getCreatedAt(), item.getUpdatedAt());
    }

    public Long getId() {
        return id;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public ContentKind getKind() {
        return kind;
    }

    public String getSlug() {
        return slug;
    }

    public String getTitle() {
        return title;
    }

    public String getSummary() {
        return summary;
    }

    public Visibility getVisibility() {
        return visibility;
    }

    public Long getParentId() {
        return parentId;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public int getRating() {
        return rating;
    }

    public boolean isRecommended() {
        return recommended;
    }

    public Long getCopiedFromId() {
        return copiedFromId;
    }

    public boolean isFolder() {
        return folder;
    }

    public Long getSupplementOfId() {
        return supplementOfId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
