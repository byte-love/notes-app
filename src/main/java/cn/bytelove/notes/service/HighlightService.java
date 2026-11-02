package cn.bytelove.notes.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * 正文重点高亮：选中一段文字，挑个颜色标成重点。
 * 和评论 / 注释共用同一套锚点（原文 + 前后各二十字 + 块序号），但高亮是「自己看」的记号，
 * 只按 user_id 查，别人的高亮不会出现在你的页面上。
 */
@Service
public class HighlightService {

    /** 可选颜色。前端工具条按这个顺序摆色块，服务端只认这几个名字。 */
    public static final List<String> COLORS = List.of("yellow", "green", "blue", "pink", "purple");

    public static final String DEFAULT_COLOR = "yellow";

    public record Highlight(long id, String quote, String kind, Integer blockIndex,
                            String prefix, String suffix, String color) {
    }

    private final JdbcTemplate jdbc;

    public HighlightService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Highlight> list(long itemId, long userId) {
        return jdbc.query(
                "select id, quote, anchor_kind, block_index, prefix, suffix, color "
                        + "from content_highlight where item_id = ? and user_id = ? and deleted_at is null "
                        + "order by id",
                this::map, itemId, userId);
    }

    /**
     * 标一处高亮。同一段文字（同块、同原文）已经标过就只改颜色，不再插第二行——
     * 否则换颜色时页面上会叠两层，删一次还剩一层。
     */
    public long add(long itemId, long userId, String kind, String quote,
                    String prefix, String suffix, Integer blockIndex, String color) {
        String c = normalize(color);
        String k = kind == null || kind.isBlank() ? "text" : kind;
        Long existing = jdbc.query(
                "select id from content_highlight where item_id = ? and user_id = ? and deleted_at is null "
                        + "and anchor_kind = ? and quote = ? and coalesce(block_index, -1) = ? order by id limit 1",
                (rs, i) -> rs.getLong(1), itemId, userId, k, quote, blockIndex == null ? -1 : blockIndex)
                .stream().findFirst().orElse(null);
        if (existing != null) {
            jdbc.update("update content_highlight set color = ? where id = ?", c, existing);
            return existing;
        }
        Long id = jdbc.queryForObject(
                "insert into content_highlight (item_id, user_id, quote, anchor_kind, block_index, prefix, suffix, color) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?) returning id",
                Long.class, itemId, userId, quote, k, blockIndex, prefix, suffix, c);
        return id == null ? 0 : id;
    }

    /** 只能删自己的。 */
    public boolean remove(long id, long userId) {
        return jdbc.update("update content_highlight set deleted_at = now() "
                + "where id = ? and user_id = ? and deleted_at is null", id, userId) > 0;
    }

    private static String normalize(String color) {
        if (color == null) {
            return DEFAULT_COLOR;
        }
        String c = color.trim().toLowerCase(java.util.Locale.ROOT);
        return COLORS.contains(c) ? c : DEFAULT_COLOR;
    }

    private Highlight map(ResultSet rs, int index) throws SQLException {
        return new Highlight(rs.getLong("id"),
                rs.getString("quote"),
                rs.getString("anchor_kind"),
                (Integer) rs.getObject("block_index"),
                rs.getString("prefix"),
                rs.getString("suffix"),
                rs.getString("color"));
    }
}
