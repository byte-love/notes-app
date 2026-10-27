package cn.bytelove.notes.service;

import cn.bytelove.notes.domain.ContentItem;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单父节点的树。列表已经在 SQL 里按 sortOrder 排过，这里只做组装和防环。
 */
@Service
public class NoteTreeService {

    public record Node(ContentItem item, List<Node> children) {

        public boolean hasChildren() {
            return !children.isEmpty();
        }
    }

    private static final Comparator<ContentItem> ORDER =
            Comparator.comparingInt(ContentItem::getSortOrder)
                    .thenComparing(ContentItem::getTitle, Comparator.nullsLast(String::compareTo));

    public List<Node> build(List<ContentItem> items) {
        Map<Long, ContentItem> byId = new LinkedHashMap<>();
        for (ContentItem item : items) {
            byId.put(item.getId(), item);
        }

        Map<Long, List<ContentItem>> childrenOf = new LinkedHashMap<>();
        List<ContentItem> roots = new ArrayList<>();
        for (ContentItem item : items) {
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
        return toNodes(roots, childrenOf);
    }

    private List<Node> toNodes(List<ContentItem> nodes, Map<Long, List<ContentItem>> childrenOf) {
        List<Node> result = new ArrayList<>();
        for (ContentItem item : nodes) {
            List<ContentItem> kids = childrenOf.getOrDefault(item.getId(), List.of());
            result.add(new Node(item, toNodes(kids, childrenOf)));
        }
        return result;
    }

    /** 从根到目标节点的路径，用于面包屑。 */
    public List<ContentItem> breadcrumb(List<ContentItem> all, ContentItem target) {
        Map<Long, ContentItem> byId = new LinkedHashMap<>();
        for (ContentItem item : all) {
            byId.put(item.getId(), item);
        }
        List<ContentItem> path = new ArrayList<>();
        ContentItem cursor = target;
        int guard = 0;
        while (cursor != null && guard++ < 64) {
            path.add(0, cursor);
            Long pid = cursor.getParentId();
            if (pid == null) {
                break;
            }
            ContentItem parent = byId.get(pid);
            if (parent == null || parent.getId().equals(cursor.getId())) {
                break;
            }
            cursor = parent;
        }
        return path;
    }

    /** 目标节点的所有子孙 ID，用于禁止把父节点设成自己的后代。 */
    public List<Long> descendantIds(List<ContentItem> all, Long rootId) {
        Map<Long, List<Long>> childrenOf = new LinkedHashMap<>();
        for (ContentItem item : all) {
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
