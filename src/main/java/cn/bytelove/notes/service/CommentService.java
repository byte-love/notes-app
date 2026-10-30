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
 * 正文评论：一条评论可以挂在整篇上（quote 为空），也可以锚定到正文里的一句话（quote = 句子原文）。
 * 楼中楼用 parent_id 表达，渲染时按层缩进。
 */
@Service
public class CommentService {

    public record Comment(long id, Long parentId, String author, String body, Instant createdAt,
                          boolean mine, List<Comment> replies) {
    }

    /** 一句话的评论串：quote 为空表示「整篇」下面那些。 */
    public record Thread(String quote, List<Comment> comments, int count) {
    }

    private final JdbcTemplate jdbc;

    public CommentService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Thread> threads(long itemId, Long viewerId) {
        List<Row> rows = jdbc.query(
                "select c.id, c.parent_id, c.quote, c.body, c.created_at, c.user_id, u.display_name "
                        + "from content_comment c join app_user u on u.id = c.user_id "
                        + "where c.item_id = ? and c.deleted_at is null order by c.id",
                this::map, itemId);

        Map<String, List<Comment>> byQuote = new LinkedHashMap<>();
        Map<Long, Comment> placed = new LinkedHashMap<>();
        List<Row> replies = new ArrayList<>();

        for (Row r : rows) {
            if (r.parentId == null) {
                Comment c = new Comment(r.id, null, r.author, r.body, r.createdAt,
                        viewerId != null && viewerId == r.userId, new ArrayList<>());
                placed.put(r.id, c);
                byQuote.computeIfAbsent(r.quote == null ? "" : r.quote, k -> new ArrayList<>()).add(c);
            } else {
                replies.add(r);
            }
        }
        // 楼中楼：父还在（没被删）就挂上去，否则提到顶层，别让回复凭空消失
        for (Row r : replies) {
            Comment child = new Comment(r.id, r.parentId, r.author, r.body, r.createdAt,
                    viewerId != null && viewerId == r.userId, new ArrayList<>());
            Comment parent = placed.get(r.parentId);
            if (parent != null) {
                parent.replies().add(child);
            } else {
                byQuote.computeIfAbsent(r.quote == null ? "" : r.quote, k -> new ArrayList<>()).add(child);
            }
            placed.put(r.id, child);
        }

        List<Thread> out = new ArrayList<>(byQuote.size());
        byQuote.forEach((quote, cs) -> out.add(new Thread(quote, cs, 0)));
        // 没锚点的放最前面，其余按评论数多的在前
        out.sort((a, b) -> {
            if (a.quote().isEmpty() != b.quote().isEmpty()) {
                return a.quote().isEmpty() ? -1 : 1;
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

    public long add(long itemId, long userId, String quote, Long parentId, String body) {
        Long id = jdbc.queryForObject(
                "insert into content_comment (item_id, user_id, parent_id, quote, body) "
                        + "values (?, ?, ?, ?, ?) returning id",
                Long.class, itemId, userId, parentId, quote, body);
        return id == null ? 0 : id;
    }

    /** 删自己的；管理员能删所有人的。返回是否真的删了。 */
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
                       long userId, String author) {
    }

    private Row map(ResultSet rs, int index) throws SQLException {
        return new Row(rs.getLong("id"),
                (Long) rs.getObject("parent_id"),
                rs.getString("quote"),
                rs.getString("body"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getLong("user_id"),
                rs.getString("display_name"));
    }
}
