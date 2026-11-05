package cn.bytelove.notes.service;

import cn.bytelove.notes.domain.ContentCard;
import cn.bytelove.notes.domain.ContentItem;
import cn.bytelove.notes.domain.ContentKind;
import cn.bytelove.notes.domain.Visibility;
import cn.bytelove.notes.repo.ContentItemRepository;
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
 * 技术广场：把公开的推荐内容当成模板，复制一份到自己的空间。
 * 复制出来的是一条独立副本（copiedFromId 记来源），之后各改各的，原版更新不影响副本。
 */
@Service
public class RecommendService {

    private final ContentItemRepository items;
    private final TagService tags;
    private final HighlightService highlights;
    private final TrashService trash;

    public RecommendService(ContentItemRepository items, TagService tags, HighlightService highlights,
                            TrashService trash) {
        this.items = items;
        this.tags = tags;
        this.highlights = highlights;
        this.trash = trash;
    }

    /** 广场上的一张卡片。count 是这条加它下面所有子孙的条数。 */
    public record Card(ContentCard item, long count) {
        public boolean isContainer() {
            return count > 1;
        }
    }

    /** 当前分区的全部推荐内容（含正文），只有「复制到我的空间」需要它。 */
    public List<ContentItem> all(ContentKind kind) {
        return items.findByKindAndRecommendedTrueOrderBySortOrderAscIdAsc(kind);
    }

    /**
     * 技术广场的顶层卡片：父节点不在推荐集合里的那些。
     * 条数按完整子树算——卡片上写 70 条，点「加入我的空间」就真的进来 70 条。
     * 只收公开的：广场上摆的是谁都能看的东西，会员可见的靠链接分享，不在这里挂着。
     */
    public List<Card> cards(ContentKind kind) {
        // 只查需要的列：body 是表里最大的列，算数量和看标题用不到它
        List<ContentCard> pool = cardsOf(kind);
        List<Slot> slots = new ArrayList<>(pool.size());
        for (ContentCard i : pool) {
            slots.add(new Slot(i.getId(), i.getParentId(), i.getSlug(), i.getVisibility()));
        }
        Map<Long, Long> parentOf = effectiveParents(slots);
        Map<Long, List<Long>> childrenOf = childrenMap(slots, parentOf);

        List<Card> cards = new ArrayList<>();
        for (ContentCard item : pool) {
            boolean top = !parentOf.containsKey(item.getId());
            if (top && item.getVisibility() == Visibility.PUBLIC) {
                Set<Long> seen = new HashSet<>();
                collect(item.getId(), childrenOf, seen);
                cards.add(new Card(item, seen.size()));
            }
        }
        return cards;
    }

    /**
     * 一个分组里的全部条目（含它自己），按层级顺序。
     * 只有公开的分组摆在广场上；组里的条目本身不过滤可见性，
     * 游客看不全的那部分在页面上显示成锁住的标题。
     */
    public List<ContentCard> group(ContentKind kind, String slug) {
        List<ContentCard> pool = cardsOf(kind);
        ContentCard root = null;
        for (ContentCard i : pool) {
            if (i.getSlug().equals(slug) && i.getVisibility() == Visibility.PUBLIC) {
                root = i;
                break;
            }
        }
        if (root == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        List<Slot> slots = new ArrayList<>(pool.size());
        for (ContentCard i : pool) {
            slots.add(new Slot(i.getId(), i.getParentId(), i.getSlug(), i.getVisibility()));
        }
        Map<Long, List<Long>> childrenOf = childrenMap(slots, effectiveParents(slots));
        Set<Long> keep = new HashSet<>();
        collect(root.getId(), childrenOf, keep);
        List<ContentCard> out = new ArrayList<>();
        for (ContentCard i : pool) {
            if (keep.contains(i.getId())) {
                out.add(i);
            }
        }
        return out;
    }

    /** 广场的内容池：推荐内容，按目录顺序，不含正文。 */
    private List<ContentCard> cardsOf(ContentKind kind) {
        return items.findRecommendedCardsInOrder(kind);
    }

    /** 广场里一个条目的最小信息：算层级只用得到这几个字段。 */
    private record Slot(Long id, Long parentId, String slug, Visibility visibility) {
    }

    /**
     * 每个条目在广场树上实际的父级。
     * <p>
     * 父节点没被推荐、但它的目录在广场上另有正本（slug 相同）时，把子条目挂到正本下面 ——
     * 否则「谁在自己那份副本目录里建了一篇公开笔记」就会变成一张 1 条的假分类卡片
     * （2026-11-04 就是这么冒出来一张「内存泄漏与内存溢出」）。
     * 没有可挂的父级就不放进返回的 map，调用方按顶层处理。
     */
    private Map<Long, Long> effectiveParents(List<Slot> pool) {
        Set<Long> ids = new HashSet<>();
        Map<String, Slot> canonical = new HashMap<>();
        for (Slot s : pool) {
            ids.add(s.id());
            if (s.slug() == null) {
                continue;
            }
            Slot cur = canonical.get(s.slug());
            // 正本优先：公开的优先，其次 id 小的（id 小的那份是复制来源）
            boolean better = cur == null
                    || (s.visibility() == Visibility.PUBLIC && cur.visibility() != Visibility.PUBLIC)
                    || (s.visibility() == cur.visibility() && s.id() < cur.id());
            if (better) {
                canonical.put(s.slug(), s);
            }
        }

        Set<Long> missing = new HashSet<>();
        for (Slot s : pool) {
            if (s.parentId() != null && !ids.contains(s.parentId())) {
                missing.add(s.parentId());
            }
        }
        Map<Long, String> missingSlug = new HashMap<>();
        if (!missing.isEmpty()) {
            for (ContentItem p : items.findAllById(missing)) {
                missingSlug.put(p.getId(), p.getSlug());
            }
        }

        Map<Long, Long> out = new HashMap<>();
        for (Slot s : pool) {
            Long pid = s.parentId();
            if (pid != null && ids.contains(pid)) {
                out.put(s.id(), pid);
                continue;
            }
            String parentSlug = pid == null ? null : missingSlug.get(pid);
            Slot canon = parentSlug == null ? null : canonical.get(parentSlug);
            if (canon != null && !canon.id().equals(s.id())) {
                out.put(s.id(), canon.id());
            }
        }
        return out;
    }

    /** 这个用户已经复制过哪些模板（copied_from_id），用来把按钮显示成「已加入」。 */    public java.util.Set<Long> joined(Long userId) {
        if (userId == null) {
            return java.util.Set.of();
        }
        return new java.util.HashSet<>(items.findCopiedFromIds(userId));
    }

    /**
     * 把一条（连同它的子孙）复制到指定用户名下。
     * 根挂到顶层，父节点按 id 映射到新复制出来的节点。
     */
    @Transactional
    public int copyTo(Long userId, Long sourceId) {
        // 已经加过就别再复制一份，重复点按钮不该长出一堆同样的内容
        if (items.countByOwnerIdAndCopiedFromId(userId, sourceId) > 0) {
            return 0;
        }

        ContentItem source = items.findById(sourceId)
                .filter(ContentItem::isRecommended)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        List<ContentItem> pool = items.findByKindAndRecommendedTrueOrderBySortOrderAscIdAsc(source.getKind());
        Map<Long, List<Long>> childrenOf = childrenMapOf(pool);
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
            // 星级跟着内容走：加入我的空间以后不用再一题一题重标
            copy.setRating(src.getRating());
            // 复制出来的先只属于自己，不进推荐栏
            copy.setRecommended(false);
            copy.setCopiedFromId(src.getId());
            ContentItem saved = items.save(copy);
            tags.copy(src.getId(), saved.getId());
            // 高亮也跟着走：原作者标在正文里的重点，复制过来还看得见
            highlights.copy(src.getId(), saved.getId(), src.getOwnerId(), userId);
            newIdOf.put(src.getId(), saved.getId());
            copied++;
        }
        return copied;
    }

    /**
     * 这条内容所在的分组页地址（用来从广场的「移除」按钮跳回原处）；找不到就返回 null。
     * 从自己往上找到最顶层，用最顶层那条的 slug 当分组 Key。
     */
    public String groupPathOf(Long itemId) {
        ContentItem cur = items.findById(itemId).orElse(null);
        int guard = 0;
        while (cur != null && cur.getParentId() != null && guard++ < 20) {
            ContentItem parent = items.findById(cur.getParentId()).orElse(null);
            if (parent == null) {
                break;
            }
            cur = parent;
        }
        if (cur == null) {
            return null;
        }
        String base = switch (cur.getKind()) {
            case BAGU -> "/bagu";
            case ALGO -> "/algo";
            case PROJECT -> "/projects";
            default -> "/notes";
        };
        return "/explore/" + cur.getKind().name().toLowerCase(java.util.Locale.ROOT) + "/" + cur.getSlug();
    }

    /**
     * 把我复制来的那份（连同它下面复制下来的子节点）移进回收站；正本和别人的副本都不动。
     * 广场分组页那个「移除」按钮走这里。没有复制过就什么也不做，返回 0。
     */
    public int removeCopy(Long userId, Long sourceId) {
        if (userId == null) {
            return 0;
        }
        List<ContentCard> mine = cardsOfOwner(userId);
        Map<Long, List<Long>> childrenOf = new HashMap<>();
        for (ContentCard i : mine) {
            if (i.getParentId() != null) {
                childrenOf.computeIfAbsent(i.getParentId(), k -> new ArrayList<>()).add(i.getId());
            }
        }
        int removed = 0;
        for (ContentCard c : mine) {
            if (!sourceId.equals(c.getCopiedFromId())) {
                continue;
            }
            // 副本是深拷贝，子节点一起进回收站，免得留一堆孤儿
            Set<Long> seen = new HashSet<>();
            java.util.ArrayDeque<Long> queue = new java.util.ArrayDeque<>();
            queue.add(c.getId());
            while (!queue.isEmpty()) {
                Long id = queue.poll();
                if (!seen.add(id)) {
                    continue;
                }
                trash.softDelete(id, userId);
                removed++;
                queue.addAll(childrenOf.getOrDefault(id, List.of()));
            }
        }
        return removed;
    }

    private List<ContentCard> cardsOfOwner(Long userId) {
        return items.findCardsByOwner(userId);
    }

    private List<ContentItem> inParentFirstOrder(Long rootId, Map<Long, ContentItem> byId) {
        Map<Long, List<Long>> childrenOf = childrenMapOf(new ArrayList<>(byId.values()));
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

    private Map<Long, List<Long>> childrenMapOf(List<ContentItem> pool) {
        Map<Long, List<Long>> map = new HashMap<>();
        for (ContentItem i : pool) {
            if (i.getParentId() != null) {
                map.computeIfAbsent(i.getParentId(), k -> new ArrayList<>()).add(i.getId());
            }
        }
        return map;
    }

    /** 按“广场上的实际父级”（{@link #effectiveParents}）建子节点表。 */
    private Map<Long, List<Long>> childrenMap(List<Slot> pool, Map<Long, Long> parentOf) {
        Map<Long, List<Long>> map = new HashMap<>();
        for (Slot s : pool) {
            Long pid = parentOf.get(s.id());
            if (pid != null) {
                map.computeIfAbsent(pid, k -> new ArrayList<>()).add(s.id());
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
}
