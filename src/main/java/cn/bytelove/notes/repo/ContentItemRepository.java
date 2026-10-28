package cn.bytelove.notes.repo;

import cn.bytelove.notes.domain.ContentItem;
import cn.bytelove.notes.domain.ContentKind;
import cn.bytelove.notes.domain.Visibility;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ContentItemRepository extends JpaRepository<ContentItem, Long> {

    List<ContentItem> findByKindAndVisibilityInOrderByUpdatedAtDesc(ContentKind kind,
                                                                   Collection<Visibility> visibilities);

    List<ContentItem> findByKindOrderByUpdatedAtDesc(ContentKind kind);

    /** 备忘录：按类型和题号顺序列出所有被标记的条目。 */
    List<ContentItem> findByStarredTrueOrderByKindAscSortOrderAsc();

    Optional<ContentItem> findBySlug(String slug);

    boolean existsBySlug(String slug);
}
