package cn.bytelove.notes.repo;

import cn.bytelove.notes.domain.ContentItem;
import cn.bytelove.notes.domain.ContentKind;
import cn.bytelove.notes.domain.Visibility;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ContentItemRepository
        extends JpaRepository<ContentItem, Long>, JpaSpecificationExecutor<ContentItem> {

    /**
     * 推荐栏只用到 id/父节点/标题/摘要这些字段，body 是表里最大的列（几十上百 KB），
     * 只为了算数量和标题就把它一起读出来太贵，所以单独给一条只查需要的列的查询。
     */
    interface CardRow {
        Long getId();

        Long getParentId();

        Long getOwnerId();

        String getSlug();

        String getTitle();

        String getSummary();

        Visibility getVisibility();

        boolean isRecommended();
    }

    @Query("select i.id as id, i.parentId as parentId, i.ownerId as ownerId, i.slug as slug, "
            + "i.title as title, i.summary as summary, i.visibility as visibility, "
            + "i.recommended as recommended from ContentItem i "
            + "where i.kind = :kind and i.recommended = true order by i.sortOrder asc, i.id asc")
    List<CardRow> findCardRows(@Param("kind") ContentKind kind);

    /** 某个用户自己的某一类内容。 */
    List<ContentItem> findByOwnerIdAndKindOrderByUpdatedAtDesc(Long ownerId, ContentKind kind);

    /** 推荐栏：所有标为推荐的内容，不分作者。 */
    List<ContentItem> findByKindAndRecommendedTrueOrderByUpdatedAtDesc(ContentKind kind);

    List<ContentItem> findByKindAndRecommendedTrueOrderBySortOrderAscIdAsc(ContentKind kind);

    List<ContentItem> findByOwnerIdAndKindOrderBySortOrderAsc(Long ownerId, ContentKind kind);

    List<ContentItem> findByOwnerIdOrderByKindAscSortOrderAsc(Long ownerId);

    Optional<ContentItem> findByOwnerIdAndSlug(Long ownerId, String slug);

    /** slug 只在本人范围内唯一，跨用户可能重名，所以要按 id 取第一条能看的。 */
    List<ContentItem> findBySlugAndRecommendedTrueOrderByIdAsc(String slug);

    boolean existsByOwnerIdAndSlug(Long ownerId, String slug);

    long countByOwnerId(Long ownerId);

    long countByOwnerIdAndKind(Long ownerId, ContentKind kind);
}
