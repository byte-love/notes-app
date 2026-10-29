package cn.bytelove.notes.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 手动书签。和「上次看到」的区别：那个是自动记的分区位置，每个分区只有一条；
 * 这个是用户自己插的旗，想标几处标几处，不会因为继续往下读就被顶掉。
 */
@Service
public class BookmarkService {

    private final JdbcTemplate jdbc;

    public BookmarkService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isBookmarked(Long userId, Long itemId) {
        if (userId == null) {
            return false;
        }
        Integer n = jdbc.queryForObject(
                "select count(*) from content_bookmark where user_id = ? and item_id = ?",
                Integer.class, userId, itemId);
        return n != null && n > 0;
    }

    public void toggle(Long userId, Long itemId) {
        if (isBookmarked(userId, itemId)) {
            jdbc.update("delete from content_bookmark where user_id = ? and item_id = ?", userId, itemId);
        } else {
            jdbc.update("insert into content_bookmark (user_id, item_id) values (?, ?) on conflict do nothing",
                    userId, itemId);
        }
    }

    public Set<Long> ids(Long userId) {
        if (userId == null) {
            return Set.of();
        }
        return new HashSet<>(jdbc.queryForList(
                "select item_id from content_bookmark where user_id = ?", Long.class, userId));
    }

    public List<Long> itemIds(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return jdbc.queryForList(
                "select item_id from content_bookmark where user_id = ? order by created_at desc",
                Long.class, userId);
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
