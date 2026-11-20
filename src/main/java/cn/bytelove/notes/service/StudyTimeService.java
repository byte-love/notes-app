package cn.bytelove.notes.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * 学习时长：前端每秒累计、每 30 秒批量上报一次，这里只做累加和查询。
 * 日期用上海时区在库里算，避免应用和数据库时区不一致把跨零点的时长记错天。
 */
@Service
public class StudyTimeService {

    /** 只认这四个分类，别的一律当非法请求丢掉。 */
    private static final Set<String> CATEGORIES = Set.of("algo", "bagu", "project", "notes", "interview");

    private final JdbcTemplate jdbc;

    public StudyTimeService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(long userId, String category, int seconds) {
        if (category == null || !CATEGORIES.contains(category)) {
            throw new IllegalArgumentException("category");
        }
        if (seconds <= 0 || seconds > 3600) {
            throw new IllegalArgumentException("seconds");
        }
        jdbc.update("""
                insert into study_time (user_id, day, category, seconds)
                values (?, (now() at time zone 'Asia/Shanghai')::date, ?, ?)
                on conflict (user_id, day, category)
                do update set seconds = study_time.seconds + excluded.seconds, updated_at = now()
                """, userId, category, seconds);
    }

    public List<Row> range(long userId, LocalDate from) {
        return jdbc.query("""
                select day, category, seconds from study_time
                where user_id = ? and day >= ?
                order by day
                """, (rs, i) -> new Row(
                rs.getObject("day", LocalDate.class),
                rs.getString("category"),
                rs.getInt("seconds")), userId, from);
    }

    public record Row(LocalDate day, String category, int seconds) {
    }
}
