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

    /** 星级统计的四个数：总数、★★★、★★以上、没标的。只算八股里的叶子（目录节点不算）。 */
    public record Summary(int total, int r3, int r2, int none) {
    }

    public Summary summary(Long ownerId) {
        Summary empty = new Summary(0, 0, 0, 0);
        if (ownerId == null) {
            return empty;
        }
        return jdbc.queryForObject(
                "select count(*) as total,"
                        + " count(*) filter (where i.rating >= 3) as r3,"
                        + " count(*) filter (where i.rating >= 2) as r2,"
                        + " count(*) filter (where i.rating = 0) as none"
                        + " from content_item i"
                        + " where i.owner_id = ? and i.kind = 'BAGU' and i.deleted_at is null"
                        + " and not exists (select 1 from content_item c"
                        + "                  where c.parent_id = i.id and c.deleted_at is null)",
                (rs, n) -> new Summary(rs.getInt("total"), rs.getInt("r3"),
                        rs.getInt("r2"), rs.getInt("none")),
                ownerId);
    }

    private static int clamp(int rating) {
        return Math.max(0, Math.min(3, rating));
    }
}
