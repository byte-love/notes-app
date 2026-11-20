package cn.bytelove.notes.repo;

import cn.bytelove.notes.domain.ContentKind;
import cn.bytelove.notes.domain.NoteCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NoteCategoryRepository extends JpaRepository<NoteCategory, Long> {

    List<NoteCategory> findByOwnerIdOrderBySortOrderAscIdAsc(Long ownerId);

    Optional<NoteCategory> findFirstByOwnerIdOrderBySortOrderAscIdAsc(Long ownerId);

    Optional<NoteCategory> findByOwnerIdAndSlug(Long ownerId, String slug);

    Optional<NoteCategory> findFirstByOwnerIdAndKindAndBuiltinTrue(Long ownerId, ContentKind kind);

    Optional<NoteCategory> findFirstByOwnerIdAndKindOrderBySortOrderAscIdAsc(Long ownerId, ContentKind kind);

    long countByOwnerId(Long ownerId);

    boolean existsByOwnerIdAndSlug(Long ownerId, String slug);

    Optional<NoteCategory> findByIdAndOwnerId(Long id, Long ownerId);
}
