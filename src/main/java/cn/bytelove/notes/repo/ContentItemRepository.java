package cn.bytelove.notes.repo;

import cn.bytelove.notes.domain.ContentItem;
import cn.bytelove.notes.domain.ContentKind;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ContentItemRepository extends JpaRepository<ContentItem, Long> {

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
