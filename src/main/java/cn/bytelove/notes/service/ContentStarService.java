package cn.bytelove.notes.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 备忘录。标记是「谁标的」，不是「这条被标过」，所以单独一张表而不是内容上的一个布尔值。
 */
@Service
public class ContentStarService {

    private final JdbcTemplate jdbc;

    public ContentStarService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isStarred(Long userId, Long itemId) {
        Integer n = jdbc.queryForObject(
                "select count(*) from content_star where user_id = ? and item_id = ?",
                Integer.class, userId, itemId);
        return n != null && n > 0;
    }

    public void toggle(Long userId, Long itemId) {
        if (isStarred(userId, itemId)) {
            jdbc.update("delete from content_star where user_id = ? and item_id = ?", userId, itemId);
        } else {
            jdbc.update("insert into content_star (user_id, item_id) values (?, ?) on conflict do nothing",
                    userId, itemId);
        }
    }

    public void remove(Long userId, Long itemId) {
        jdbc.update("delete from content_star where user_id = ? and item_id = ?", userId, itemId);
    }

    public Set<Long> starredIds(Long userId) {
        if (userId == null) {
            return Set.of();
        }
        return new HashSet<>(jdbc.queryForList(
                "select item_id from content_star where user_id = ?", Long.class, userId));
    }

    public List<Long> starredItemIds(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return jdbc.queryForList(
                "select item_id from content_star where user_id = ? order by created_at desc",
                Long.class, userId);
    }
}
