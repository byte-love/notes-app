package cn.bytelove.notes.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 星级标注：0 = 没标，1-3。
 * <p>
 * 直接写 SQL 而不是走 JPA save：保存实体会触发 updated_at 刷新，列表是按 updated_at 倒序排的，
 * 标个星就把这条顶到最前面，翻着翻着顺序就乱了。
 */
@Service
public class RatingService {

    private final JdbcTemplate jdbc;

    public RatingService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void set(Long itemId, int rating) {
        jdbc.update("update content_item set rating = ? where id = ?", clamp(rating), itemId);
    }

    private static int clamp(int rating) {
        return Math.max(0, Math.min(3, rating));
    }
}
