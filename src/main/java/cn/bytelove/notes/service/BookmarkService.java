package cn.bytelove.notes.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 手动书签。可以标整篇（anchor 为空串），也可以标正文里的某个标题块（anchor 是标题 id）。
 * 和「上次看到」的区别：那个是自动记的分区位置，每个分区只有一条；这个想标几处标几处。
 */
@Service
public class BookmarkService {

    private final JdbcTemplate jdbc;

    public BookmarkService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Spot(Long itemId, String anchor) {
    }

    public void toggle(Long userId, Long itemId, String anchor) {
        String key = anchor == null ? "" : anchor;
        Integer n = jdbc.queryForObject(
                "select count(*) from content_bookmark where user_id = ? and item_id = ? and anchor = ?",
                Integer.class, userId, itemId, key);
        if (n != null && n > 0) {
            jdbc.update("delete from content_bookmark where user_id = ? and item_id = ? and anchor = ?",
                    userId, itemId, key);
        } else {
            jdbc.update("insert into content_bookmark (user_id, item_id, anchor) values (?, ?, ?) "
                    + "on conflict do nothing", userId, itemId, key);
        }
    }

    /** 某篇内容上，当前用户标了哪些位置。 */
    public Set<String> anchorsOf(Long userId, Long itemId) {
        if (userId == null) {
            return Set.of();
        }
        return new HashSet<>(jdbc.queryForList(
                "select anchor from content_bookmark where user_id = ? and item_id = ?",
                String.class, userId, itemId));
    }

    /** 有书签的内容 id（树里打标记用）。 */
    public Set<Long> ids(Long userId) {
        if (userId == null) {
            return Set.of();
        }
        return new HashSet<>(jdbc.queryForList(
                "select distinct item_id from content_bookmark where user_id = ?", Long.class, userId));
    }

    public List<Spot> spots(Long userId) {
        if (userId == null) {
            return List.of();
        }
        Set<Spot> out = new LinkedHashSet<>();
        jdbc.query("select item_id, anchor from content_bookmark where user_id = ? order by created_at desc",
                rs -> {
                    out.add(new Spot(rs.getLong("item_id"), rs.getString("anchor")));
                }, userId);
        return List.copyOf(out);
    }

    public long count(Long userId) {
        if (userId == null) {
            return 0;
        }
        Long n = jdbc.queryForObject(
                "select count(*) from content_bookmark where user_id = ?", Long.class, userId);
        return n == null ? 0 : n;
    }
}
