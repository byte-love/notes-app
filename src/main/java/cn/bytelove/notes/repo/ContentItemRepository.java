package cn.bytelove.notes.repo;

import cn.bytelove.notes.domain.ContentCard;
import cn.bytelove.notes.domain.ContentItem;
import cn.bytelove.notes.domain.ContentKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ContentItemRepository
        extends JpaRepository<ContentItem, Long>, JpaSpecificationExecutor<ContentItem> {

    /**
     * 列表 / 目录 / 广场用的查询：直接查出 {@link ContentCard}，不含正文。
     * <p>
     * body 是表里最大的列（每篇几 KB 到几十 KB），摆几十张卡片用不到它；
     * 几条卡片查询都用这一个 select，别各写各的。要正文的地方查 {@link ContentItem}。
     */
    String CARD_COLUMNS = "select new cn.bytelove.notes.domain.ContentCard("
            + "i.id, i.ownerId, i.kind, i.slug, i.title, i.summary, i.visibility, i.parentId, "
            + "i.sortOrder, i.rating, i.recommended, i.copiedFromId, i.folder, i.supplementOfId, "
            + "i.createdAt, i.updatedAt) from ContentItem i ";

    /** 自己某个分区的全部条目（列表页的内容池）。 */
    @Query(CARD_COLUMNS + "where i.ownerId = :ownerId and i.kind = :kind order by i.updatedAt desc")
    List<ContentCard> findCardsByOwnerAndKind(@Param("ownerId") Long ownerId, @Param("kind") ContentKind kind);

    /** 推荐栏（游客看的内容池）。 */
    @Query(CARD_COLUMNS + "where i.kind = :kind and i.recommended = true order by i.updatedAt desc")
    List<ContentCard> findRecommendedCards(@Param("kind") ContentKind kind);

    /** 广场：推荐内容的完整集合，按目录顺序（分组页和卡片条数都按这个算）。 */
    @Query(CARD_COLUMNS + "where i.kind = :kind and i.recommended = true order by i.sortOrder asc, i.id asc")
    List<ContentCard> findRecommendedCardsInOrder(@Param("kind") ContentKind kind);

    @Query(CARD_COLUMNS + "where i.id in :ids")
    List<ContentCard> findCardsByIds(@Param("ids") Collection<Long> ids);

    @Query(CARD_COLUMNS + "where i.ownerId = :ownerId order by i.kind asc, i.sortOrder asc")
    List<ContentCard> findCardsByOwner(@Param("ownerId") Long ownerId);

    /** 管理页列表：整表按目录顺序取一遍（几百条），筛选条件在控制器里过——省得为「可选条件」写一堆动态 SQL。 */
    @Query(CARD_COLUMNS + "order by i.sortOrder asc, i.title asc, i.id asc")
    List<ContentCard> findAllCards();

    /**
     * 卡片附件上显示的体积：正文的 UTF-8 字节数。走原生查询是因为 JPQL 的 length() 数字符，
     * 中文按字符算会小三分之二，卡片上的「9.1 KB」就变成「3.1 KB」了。
     */
    @Query(value = "select id, octet_length(body) from content_item where id in (:ids)", nativeQuery = true)
    List<Object[]> bodySizes(@Param("ids") Collection<Long> ids);

    /** 某个用户自己的某一类内容（含正文）。 */
    List<ContentItem> findByOwnerIdAndKindOrderByUpdatedAtDesc(Long ownerId, ContentKind kind);

    List<ContentItem> findByKindAndRecommendedTrueOrderBySortOrderAscIdAsc(ContentKind kind);

    List<ContentItem> findByOwnerIdAndKindOrderBySortOrderAsc(Long ownerId, ContentKind kind);

    List<ContentItem> findByOwnerIdOrderByKindAscSortOrderAsc(Long ownerId);

    Optional<ContentItem> findByOwnerIdAndSlug(Long ownerId, String slug);

    /** 挂在某一条下面的补充笔记（有多篇时取排最前面的那条）。 */
    Optional<ContentItem> findFirstBySupplementOfIdOrderBySortOrderAscIdAsc(Long supplementOfId);

    /** slug 只在本人范围内唯一，跨用户可能重名，所以要按 id 取第一条能看的。 */
    List<ContentItem> findBySlugAndRecommendedTrueOrderByIdAsc(String slug);

    /**
     * slug 是否被占用。**回收站里的行也算占用**：唯一索引 {@code uq_content_owner_slug} 只看
     * {@code (owner_id, slug)}，不看 {@code deleted_at}，而实体上的 {@code @SQLRestriction} 只过滤活行——
     * 用派生查询就会「查不出来但插不进去」，表现是「加入我的空间 → 移除 → 再加入」直接 500。
     * 所以这里走原生查询，把已删除的行一起算上。
     */
    @Query(value = "select exists(select 1 from content_item where owner_id = :ownerId and slug = :slug)",
            nativeQuery = true)
    boolean existsByOwnerIdAndSlug(@Param("ownerId") Long ownerId, @Param("slug") String slug);

    long countByOwnerId(Long ownerId);

    long countByOwnerIdAndKind(Long ownerId, ContentKind kind);

    long countByOwnerIdAndCopiedFromId(Long ownerId, Long copiedFromId);

    @Query("select i.copiedFromId from ContentItem i where i.ownerId = ?1 "
            + "and i.copiedFromId is not null and i.deletedAt is null")
    List<Long> findCopiedFromIds(Long ownerId);
}
