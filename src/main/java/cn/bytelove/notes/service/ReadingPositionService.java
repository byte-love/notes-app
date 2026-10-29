package cn.bytelove.notes.service;

import cn.bytelove.notes.domain.ContentKind;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 阅读位置：每个分区只记最后一次看到的那条，用来在列表和树里标「上次看到这了」。
 * 和收藏夹是两回事——收藏夹是用户主动收的，这里是自动记的。
 */
@Service
public class ReadingPositionService {

    private final JdbcTemplate jdbc;

    public ReadingPositionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void remember(Long userId, ContentKind kind, Long itemId) {
        if (userId == null || kind == null || itemId == null) {
            return;
        }
        jdbc.update("insert into reading_position (user_id, kind, item_id, updated_at) "
                        + "values (?, ?, ?, now()) "
                        + "on conflict (user_id, kind) do update "
                        + "set item_id = excluded.item_id, updated_at = now()",
                userId, kind.name(), itemId);
    }

    public Long find(Long userId, ContentKind kind) {
        if (userId == null) {
            return null;
        }
        var list = jdbc.queryForList(
                "select item_id from reading_position where user_id = ? and kind = ?",
                Long.class, userId, kind.name());
        return list.isEmpty() ? null : list.get(0);
    }

    public Map<String, Long> all(Long userId) {
        Map<String, Long> out = new HashMap<>();
        if (userId == null) {
            return out;
        }
        jdbc.query("select kind, item_id from reading_position where user_id = ?",
                rs -> {
                    out.put(rs.getString("kind"), rs.getLong("item_id"));
                }, userId);
        return out;
    }

    /** 内容被彻底删除或进了回收站时，把指向它的书目位置清掉。 */
    public void forgetItem(Long itemId) {
        jdbc.update("delete from reading_position where item_id = ?", itemId);
    }
}
