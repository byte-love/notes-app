package cn.bytelove.notes.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 正文评论。锚点有两种（参考 Hypothesis 的 TextQuoteSelector / PositionSelector）：
 * <ul>
 *   <li>{@code text}：选中一段文字，quote 是原文，prefix/suffix 是前后各二十字，用来在同名句子里定位</li>
 *   <li>{@code block}：整段 / 整个代码块，block_index 是正文里顶层块的序号</li>
 * </ul>
 * 楼中楼用 parent_id 表达，渲染时最多两层。
 */
@Service
public class CommentService {

    public record Comment(long id, Long parentId, String author, String body, Instant createdAt,
                          boolean mine, List<Comment> replies) {
    }

    /** 同样的锚点合成一串：kind + 位置 + 原文。 */
    public record Thread(String quote, String kind, Integer blockIndex, String prefix, String suffix,
                         List<Comment> comments) {
        public String key() {
            return kind + "|" + (blockIndex == null ? "" : blockIndex) + "|" + quote;
        }
    }

    private final JdbcTemplate jdbc;

    public CommentService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Thread> threads(long itemId, Long viewerId) {
        List<Row> rows = jdbc.query(
                "select c.id, c.parent_id, c.quote, c.body, c.created_at, c.user_id, c.anchor_kind, "
                        + "c.block_index, c.prefix, c.suffix, u.display_name "
                        + "from content_comment c join app_user u on u.id = c.user_id "
                        + "where c.item_id = ? and c.deleted_at is null order by c.id",
                this::map, itemId);

        Map<String, Thread> byKey = new LinkedHashMap<>();
        Map<Long, Comment> placed = new LinkedHashMap<>();
        List<Row> replies = new ArrayList<>();

        for (Row r : rows) {
            String quote = r.quote == null ? "" : r.quote;
            String kind = r.kind == null ? "text" : r.kind;
            String key = kind + "|" + (r.blockIndex == null ? "" : r.blockIndex) + "|" + quote;
            if (r.parentId == null) {
                Comment c = new Comment(r.id, null, r.author, r.body, r.createdAt,
                        viewerId != null && viewerId == r.userId, new ArrayList<>());
                placed.put(r.id, c);
                byKey.computeIfAbsent(key, k -> new Thread(quote, kind, r.blockIndex, r.prefix, r.suffix,
                        new ArrayList<>())).comments().add(c);
            } else {
                replies.add(r);
            }
        }
        // 楼中楼：父还在就挂上去，否则提到顶层，别让回复凭空消失
        for (Row r : replies) {
            Comment child = new Comment(r.id, r.parentId, r.author, r.body, r.createdAt,
                    viewerId != null && viewerId == r.userId, new ArrayList<>());
            Comment parent = placed.get(r.parentId);
            if (parent != null) {
                parent.replies().add(child);
            } else {
                String quote = r.quote == null ? "" : r.quote;
                String kind = r.kind == null ? "text" : r.kind;
                String key = kind + "|" + (r.blockIndex == null ? "" : r.blockIndex) + "|" + quote;
                byKey.computeIfAbsent(key, k -> new Thread(quote, kind, r.blockIndex, r.prefix, r.suffix,
                        new ArrayList<>())).comments().add(child);
            }
            placed.put(r.id, child);
        }

        List<Thread> out = new ArrayList<>(byKey.values());
        // 没锚点的（整篇评论）放最前，其余按评论多的在前
        out.sort((a, b) -> {
            boolean aTop = a.quote().isEmpty();
            boolean bTop = b.quote().isEmpty();
            if (aTop != bTop) {
                return aTop ? -1 : 1;
            }
            return Integer.compare(total(b.comments()), total(a.comments()));
        });
        return out;
    }

    private static int total(List<Comment> cs) {
        int n = 0;
        for (Comment c : cs) {
            n += 1 + c.replies().size();
        }
        return n;
    }

    public long add(long itemId, long userId, String kind, String quote, String prefix, String suffix,
                    Integer blockIndex, Long parentId, String body) {
        Long id = jdbc.queryForObject(
                "insert into content_comment (item_id, user_id, parent_id, quote, body, "
                        + "anchor_kind, block_index, prefix, suffix) values (?, ?, ?, ?, ?, ?, ?, ?, ?) returning id",
                Long.class, itemId, userId, parentId, quote, body,
                kind == null || kind.isBlank() ? "text" : kind, blockIndex, prefix, suffix);
        return id == null ? 0 : id;
    }

    /** 删自己的；管理员能删所有人的。 */
    public boolean remove(long id, long userId, boolean admin) {
        int n = jdbc.update("update content_comment set deleted_at = now() "
                        + "where id = ? and deleted_at is null and (user_id = ? or ?)",
                id, userId, admin);
        return n > 0;
    }

    public int count(long itemId) {
        Integer n = jdbc.queryForObject(
                "select count(*) from content_comment where item_id = ? and deleted_at is null",
                Integer.class, itemId);
        return n == null ? 0 : n;
    }

    private record Row(long id, Long parentId, String quote, String body, Instant createdAt,
                       long userId, String author, String kind, Integer blockIndex,
                       String prefix, String suffix) {
    }

    private Row map(ResultSet rs, int index) throws SQLException {
        return new Row(rs.getLong("id"),
                (Long) rs.getObject("parent_id"),
                rs.getString("quote"),
                rs.getString("body"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getLong("user_id"),
                rs.getString("display_name"),
                rs.getString("anchor_kind"),
                (Integer) rs.getObject("block_index"),
                rs.getString("prefix"),
                rs.getString("suffix"));
    }
}
