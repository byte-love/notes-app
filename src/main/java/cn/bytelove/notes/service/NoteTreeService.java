package cn.bytelove.notes.service;

import cn.bytelove.notes.domain.ContentCard;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 单父节点的树。列表已经在 SQL 里按 sortOrder 排过，这里只做组装和防环。
 * <p>
 * 节点上挂的是 {@link ContentCard}（不含正文）：目录一行只需要标题、地址、星级这些，
 * 几百条内容的分区把正文一起读进来纯属浪费。
 */
@Service
public class NoteTreeService {

    /**
     * lazy = 有子节点但没渲染出来（前端展开时再取），避免几百条内容把整棵树塞进每个页面。
     * ordinal = 在同一个父节点下的「第几篇」（只给叶子编号，1 起；分组为 0），左树用它当行号。
     */
    public record Node(ContentCard item, List<Node> children, boolean locked, boolean lazy, int ordinal) {

        public boolean hasChildren() {
            return !children.isEmpty();
        }
    }

    private static final Comparator<ContentCard> ORDER =
            Comparator.comparingInt(ContentCard::getSortOrder)
                    .thenComparing(ContentCard::getTitle, Comparator.nullsLast(String::compareTo));

    public List<Node> build(List<ContentCard> items) {
        return build(items, Set.of());
    }

    public List<Node> build(List<ContentCard> items, Set<Long> lockedIds) {
        Map<Long, ContentCard> byId = new LinkedHashMap<>();
        for (ContentCard item : items) {
            byId.put(item.getId(), item);
        }

        Map<Long, List<ContentCard>> childrenOf = new LinkedHashMap<>();
        List<ContentCard> roots = new ArrayList<>();
        for (ContentCard item : items) {
            // 补充笔记不单独上一个目录行：它跟在「本体」的卡片 / 正文里，目录里只剩本体那一行
            Long suppOf = item.getSupplementOfId();
            if (suppOf != null && byId.containsKey(suppOf) && !suppOf.equals(item.getId())) {
                continue;
            }
            Long pid = item.getParentId();
            // 父节点不可见（比如 PRIVATE 被游客访问）时，把子节点提到顶层，而不是让它消失
            if (pid == null || !byId.containsKey(pid) || pid.equals(item.getId())) {
                roots.add(item);
            } else {
                childrenOf.computeIfAbsent(pid, k -> new ArrayList<>()).add(item);
            }
        }

        roots.sort(ORDER);
        childrenOf.values().forEach(list -> list.sort(ORDER));
        return toNodes(roots, childrenOf, lockedIds);
    }

    private List<Node> toNodes(List<ContentCard> nodes, Map<Long, List<ContentCard>> childrenOf,
                               Set<Long> lockedIds) {
        List<Node> result = new ArrayList<>();
        int leafNo = 0;
        for (ContentCard item : nodes) {
            List<ContentCard> kids = childrenOf.getOrDefault(item.getId(), List.of());
            // 行号只给叶子：同一个父节点下按顺序 1、2、3…，分组不占号
            if (kids.isEmpty()) {
                leafNo++;
            }
            result.add(new Node(item, toNodes(kids, childrenOf, lockedIds),
                    lockedIds.contains(item.getId()), false, kids.isEmpty() ? leafNo : 0));
        }
        return result;
    }

    /**
     * 只保留「顶层的 + 通向 activeId 的那条路」的子节点，其余标成 lazy 让前端点了再取。
     * 三百条内容时，整棵树的 HTML 有 50KB 以上，比正文大得多。
     */
    public List<Node> pruned(List<Node> nodes, Long activeId) {
        Set<Long> keep = new LinkedHashSet<>();
        if (activeId != null) {
            collectPath(nodes, activeId, keep);
        }
        return prune(nodes, keep);
    }

    private boolean collectPath(List<Node> nodes, Long target, Set<Long> keep) {
        for (Node n : nodes) {
            if (n.item().getId().equals(target) || collectPath(n.children(), target, keep)) {
                keep.add(n.item().getId());
                return true;
            }
        }
        return false;
    }

    private List<Node> prune(List<Node> nodes, Set<Long> keep) {
        List<Node> out = new ArrayList<>();
        for (Node n : nodes) {
            List<Node> kids = n.children();
            if (!kids.isEmpty()) {
                kids = keep.contains(n.item().getId()) ? prune(kids, keep) : List.of();
            }
            boolean lazy = !n.children().isEmpty() && kids.isEmpty();
            out.add(new Node(n.item(), kids, n.locked(), lazy, n.ordinal()));
        }
        return out;
    }

    /** 从根到目标节点的路径，用于面包屑。 */
    public List<ContentCard> breadcrumb(List<ContentCard> all, ContentCard target) {
        Map<Long, ContentCard> byId = new LinkedHashMap<>();
        for (ContentCard item : all) {
            byId.put(item.getId(), item);
        }
        List<ContentCard> path = new ArrayList<>();
        ContentCard cursor = target;
        int guard = 0;
        while (cursor != null && guard++ < 64) {
            path.add(0, cursor);
            Long pid = cursor.getParentId();
            if (pid == null) {
                break;
            }
            ContentCard parent = byId.get(pid);
            if (parent == null || parent.getId().equals(cursor.getId())) {
                break;
            }
            cursor = parent;
        }
        return path;
    }

    /** 目标节点的所有子孙 ID，用于禁止把父节点设成自己的后代。 */
    public List<Long> descendantIds(List<ContentCard> all, Long rootId) {
        Map<Long, List<Long>> childrenOf = new LinkedHashMap<>();
        for (ContentCard item : all) {
            if (item.getParentId() != null) {
                childrenOf.computeIfAbsent(item.getParentId(), k -> new ArrayList<>()).add(item.getId());
            }
        }
        List<Long> result = new ArrayList<>();
        collect(childrenOf, rootId, result);
        return result;
    }

    private void collect(Map<Long, List<Long>> childrenOf, Long id, List<Long> out) {
        for (Long child : childrenOf.getOrDefault(id, List.of())) {
            if (out.contains(child)) {
                continue;
            }
            out.add(child);
            collect(childrenOf, child, out);
        }
    }
}
