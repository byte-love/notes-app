package cn.bytelove.notes.service;

import cn.bytelove.notes.domain.ContentKind;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 更新记录：自动那部分靠内容的时间戳推，手写那部分存 maintenance_log。
 * 两块按日期合并成一条时间线。
 */
@Service
public class UpdateFeedService {

    /** 只回看这么多天，再往前的没人看。 */
    private static final int WINDOW_DAYS = 60;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final JdbcTemplate jdbc;

    public UpdateFeedService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record AutoItem(ContentKind kind, String label, String title, String slug,
                            boolean created, String basePath) {
    }

    public record ManualLog(String author, String body) {
    }

    public record Day(LocalDate date, String label, List<AutoItem> items, List<ManualLog> logs) {
    }

    public List<Day> timeline(String filterKind) {
        Map<LocalDate, List<AutoItem>> autoByDay = new LinkedHashMap<>();
        Map<LocalDate, List<ManualLog>> logByDay = new LinkedHashMap<>();

        String sql = "select kind, slug, title, created_at, updated_at from content_item "
                + "where recommended and visibility = 'PUBLIC' and deleted_at is null "
                + "and updated_at >= now() - interval '" + WINDOW_DAYS + " days' "
                + (filterKind == null ? "" : "and kind = ? ")
                + "order by updated_at desc limit 300";

        Object[] args = filterKind == null ? new Object[]{} : new Object[]{filterKind};
        jdbc.query(sql, rs -> {
            Instant created = rs.getTimestamp("created_at").toInstant();
            Instant updated = rs.getTimestamp("updated_at").toInstant();
            String kindName = rs.getString("kind");
            ContentKind kind;
            try {
                kind = ContentKind.valueOf(kindName);
            } catch (IllegalArgumentException e) {
                return;
            }
            // 创建后一分钟内没再改过，就算「新增」，否则算「更新」
            boolean isNew = Duration.between(created, updated).abs().toMinutes() < 1;
            LocalDate day = updated.atZone(ZONE).toLocalDate();
            autoByDay.computeIfAbsent(day, k -> new ArrayList<>())
                    .add(new AutoItem(kind, kind.getLabel(), rs.getString("title"),
                            rs.getString("slug"), isNew, basePathOf(kind)));
        }, args);

        jdbc.query("select a.display_name, m.log_date, m.body from maintenance_log m "
                        + "join app_user a on a.id = m.author_id "
                        + "where m.log_date >= current_date - " + WINDOW_DAYS + " "
                        + "order by m.log_date desc, m.created_at desc",
                rs -> {
                    LocalDate day = rs.getDate("log_date").toLocalDate();
                    logByDay.computeIfAbsent(day, k -> new ArrayList<>())
                            .add(new ManualLog(rs.getString("display_name"), rs.getString("body")));
                });

        List<LocalDate> days = new ArrayList<>();
        for (LocalDate d : autoByDay.keySet()) {
            if (!days.contains(d)) {
                days.add(d);
            }
        }
        for (LocalDate d : logByDay.keySet()) {
            if (!days.contains(d)) {
                days.add(d);
            }
        }
        days.sort((a, b) -> b.compareTo(a));

        List<Day> out = new ArrayList<>();
        for (LocalDate d : days) {
            out.add(new Day(d, dayLabel(d), autoByDay.getOrDefault(d, List.of()),
                    logByDay.getOrDefault(d, List.of())));
        }
        return out;
    }

    private static String basePathOf(ContentKind kind) {
        return switch (kind) {
            case BAGU -> "/bagu";
            case ALGO -> "/algo";
            case PROJECT -> "/projects";
            default -> "/notes";
        };
    }

    private static String dayLabel(LocalDate day) {
        LocalDate today = LocalDate.now(ZONE);
        if (day.equals(today)) {
            return "今天";
        }
        if (day.equals(today.minusDays(1))) {
            return "昨天";
        }
        return day.getMonthValue() + " 月 " + day.getDayOfMonth() + " 日";
    }

    public void addLog(Long authorId, String body) {
        String text = body == null ? "" : body.trim();
        if (text.isEmpty()) {
            return;
        }
        jdbc.update("insert into maintenance_log (author_id, log_date, body) values (?, current_date, ?)",
                authorId, text);
    }

    public void deleteLog(Long id, Long authorId, boolean admin) {
        if (admin) {
            jdbc.update("delete from maintenance_log where id = ?", id);
        } else {
            jdbc.update("delete from maintenance_log where id = ? and author_id = ?", id, authorId);
        }
    }
}
