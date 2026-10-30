package cn.bytelove.notes.service;

import cn.bytelove.notes.domain.ContentItem;
import cn.bytelove.notes.domain.ContentKind;
import cn.bytelove.notes.repo.ContentItemRepository;
import cn.bytelove.notes.service.VisibilityService.Viewer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 推荐栏：把公开的推荐内容当成模板，复制一份到自己的空间。
 * 复制出来的是一条独立副本（copiedFromId 记来源），之后各改各的，原版更新不影响副本。
 */
@Service
public class RecommendService {

    private final ContentItemRepository items;
    private final VisibilityService visibility;

    public RecommendService(ContentItemRepository items, VisibilityService visibility) {
        this.items = items;
        this.visibility = visibility;
    }

    /** 推荐栏的一张卡片。count 是这条加它下面所有子孙的条数。 */
    public record Card(ContentItemRepository.CardRow item, long count) {
        public boolean isContainer() {
            return count > 1;
        }
    }

    /** 当前分区的所有可读推荐内容。 */
    public List<ContentItem> readable(ContentKind kind, Viewer viewer) {
        List<ContentItem> out = new ArrayList<>();
        for (ContentItem item : items.findByKindAndRecommendedTrueOrderBySortOrderAscIdAsc(kind)) {
            if (visibility.canRead(item, viewer)) {
                out.add(item);
            }
        }
        return out;
    }

    /** 当前分区的全部推荐内容，不看可见性。结构、条数、复制都按这个算，才和实际复制量一致。 */
    public List<ContentItem> all(ContentKind kind) {
        return items.findByKindAndRecommendedTrueOrderBySortOrderAscIdAsc(kind);
    }

    /**
     * 顶层卡片：父节点不在推荐集合里的那些。
     * 条数按完整子树算——卡片上写 70 条，点「加入我的空间」就真的进来 70 条。
     */
    public List<Card> cards(ContentKind kind, Viewer viewer) {
        // 只查需要的列：body 是表里最大的列，算数量和看标题用不到它
        List<ContentItemRepository.CardRow> pool = items.findCardRows(kind);
        Set<Long> ids = new HashSet<>();
        for (ContentItemRepository.CardRow i : pool) {
            ids.add(i.getId());
        }
        Map<Long, List<Long>> childrenOf = new HashMap<>();
        for (ContentItemRepository.CardRow i : pool) {
            if (i.getParentId() != null) {
                childrenOf.computeIfAbsent(i.getParentId(), k -> new ArrayList<>()).add(i.getId());
            }
        }

        List<Card> cards = new ArrayList<>();
        for (ContentItemRepository.CardRow item : pool) {
            boolean top = item.getParentId() == null || !ids.contains(item.getParentId());
            if (top && canRead(item, viewer)) {
                Set<Long> seen = new HashSet<>();
                collect(item.getId(), childrenOf, seen);
                cards.add(new Card(item, seen.size()));
            }
        }
        return cards;
    }

    /** 和 VisibilityService.canRead 同一套判断，只是对象换成了投影。 */
    private boolean canRead(ContentItemRepository.CardRow row, Viewer viewer) {
        if (viewer.userId() != null && viewer.userId().equals(row.getOwnerId())) {
            return true;
        }
        if (viewer.admin()) {
            return true;
        }
        if (!row.isRecommended()) {
            return false;
        }
        return switch (row.getVisibility()) {
            case PUBLIC -> true;
            case MEMBER -> viewer.authenticated();
            case PRIVATE -> false;
        };
    }

    /**
     * 一个分组里的全部条目（含它自己），按层级顺序。
     * 不过滤可见性：游客看不全的那部分在页面上显示成锁住的标题。
     */
    public List<ContentItem> group(ContentKind kind, String slug) {
        List<ContentItem> pool = all(kind);
        ContentItem root = null;
        boolean visible = false;
        for (ContentItem i : pool) {
            if (i.getSlug().equals(slug)) {
                if (root == null) {
                    root = i;
                }
            }
        }
        if (root == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        Map<Long, List<Long>> childrenOf = childrenMap(pool);
        Set<Long> keep = new HashSet<>();
        collect(root.getId(), childrenOf, keep);
        List<ContentItem> out = new ArrayList<>();
        for (ContentItem i : pool) {
            if (keep.contains(i.getId())) {
                out.add(i);
            }
        }
        return out;
    }

    /**
     * 把一条（连同它的子孙）复制到指定用户名下。
     * 根挂到顶层，父节点按 id 映射到新复制出来的节点。
     */
    @Transactional
    /** 这个用户已经复制过哪些模板（copied_from_id），用来把按钮显示成「已加入」。 */
    public java.util.Set<Long> joined(Long userId) {
        if (userId == null) {
            return java.util.Set.of();
        }
        return new java.util.HashSet<>(items.findCopiedFromIds(userId));
    }

    public int copyTo(Long userId, Long sourceId) {
        // 已经加过就别再复制一份，重复点按钮不该长出一堆同样的内容
        if (items.countByOwnerIdAndCopiedFromId(userId, sourceId) > 0) {
            return 0;
        }

        ContentItem source = items.findById(sourceId)
                .filter(ContentItem::isRecommended)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        List<ContentItem> pool = items.findByKindAndRecommendedTrueOrderBySortOrderAscIdAsc(source.getKind());
        Map<Long, List<Long>> childrenOf = childrenMap(pool);
        Set<Long> keep = new HashSet<>();
        collect(source.getId(), childrenOf, keep);

        Map<Long, ContentItem> byId = new LinkedHashMap<>();
        for (ContentItem i : pool) {
            if (keep.contains(i.getId())) {
                byId.put(i.getId(), i);
            }
        }

        Map<Long, Long> newIdOf = new HashMap<>();
        int copied = 0;
        // 按「父在子前」的顺序处理，保证子节点拿到的新父 id 已经生成
        for (ContentItem src : inParentFirstOrder(source.getId(), byId)) {
            Long newParent = src.getId().equals(source.getId())
                    ? null
                    : newIdOf.get(src.getParentId());

            ContentItem copy = new ContentItem(userId, src.getKind(),
                    uniqueSlug(userId, src.getSlug()), src.getTitle(), src.getSummary(),
                    src.getBody(), src.getVisibility());
            copy.setParentId(newParent);
            copy.setSortOrder(src.getSortOrder());
            // 复制出来的先只属于自己，不进推荐栏
            copy.setRecommended(false);
            copy.setCopiedFromId(src.getId());
            ContentItem saved = items.save(copy);
            newIdOf.put(src.getId(), saved.getId());
            copied++;
        }
        return copied;
    }

    private List<ContentItem> inParentFirstOrder(Long rootId, Map<Long, ContentItem> byId) {
        Map<Long, List<Long>> childrenOf = childrenMap(new ArrayList<>(byId.values()));
        List<ContentItem> out = new ArrayList<>();
        List<Long> queue = new ArrayList<>();
        queue.add(rootId);
        Set<Long> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            Long id = queue.remove(0);
            if (!seen.add(id)) {
                continue;
            }
            ContentItem item = byId.get(id);
            if (item != null) {
                out.add(item);
            }
            queue.addAll(childrenOf.getOrDefault(id, List.of()));
        }
        return out;
    }

    /** 每次复制都换一个不冲突的 slug：notes、notes-2、notes-3…… */
    private String uniqueSlug(Long userId, String base) {
        if (!items.existsByOwnerIdAndSlug(userId, base)) {
            return base;
        }
        for (int i = 2; i < 1000; i++) {
            String candidate = base + "-" + i;
            if (!items.existsByOwnerIdAndSlug(userId, candidate)) {
                return candidate;
            }
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "同名的内容太多了");
    }

    private Map<Long, List<Long>> childrenMap(List<ContentItem> pool) {
        Map<Long, List<Long>> map = new HashMap<>();
        for (ContentItem i : pool) {
            if (i.getParentId() != null) {
                map.computeIfAbsent(i.getParentId(), k -> new ArrayList<>()).add(i.getId());
            }
        }
        return map;
    }

    private void collect(Long id, Map<Long, List<Long>> childrenOf, Set<Long> into) {
        if (!into.add(id)) {
            return;
        }
        for (Long child : childrenOf.getOrDefault(id, List.of())) {
            collect(child, childrenOf, into);
        }
    }

    private long sizeOf(Long id, Map<Long, List<Long>> childrenOf) {
        Set<Long> all = new HashSet<>();
        collect(id, childrenOf, all);
        return all.size();
    }
}
