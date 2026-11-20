package cn.bytelove.notes.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 「我的笔记」里的一个分类（页签）。每个用户一份，只属于自己。
 * <p>
 * kind 是这套分类沿用的内容语义：决定列表用哪个模板、详情走哪条路由、分区配色。
 * 默认的五个分类和原来的五个分区一一对应，用户只改名字和顺序，不改语义。
 */
@Entity
@Table(name = "note_category")
public class NoteCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(nullable = false, length = 80)
    private String slug;

    @Column(nullable = false, length = 80)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ContentKind kind;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    /** 默认分类：跟着站点一起建好的五个，能改名、能排序，但删不掉。 */
    @Column(nullable = false)
    private boolean builtin = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected NoteCategory() {
    }

    public NoteCategory(Long ownerId, String slug, String name, ContentKind kind, int sortOrder, boolean builtin) {
        this.ownerId = ownerId;
        this.slug = slug;
        this.name = name;
        this.kind = kind;
        this.sortOrder = sortOrder;
        this.builtin = builtin;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public ContentKind getKind() {
        return kind;
    }

    public void setKind(ContentKind kind) {
        this.kind = kind;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public boolean isBuiltin() {
        return builtin;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
