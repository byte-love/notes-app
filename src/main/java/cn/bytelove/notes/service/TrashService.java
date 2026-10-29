package cn.bytelove.notes.service;

import cn.bytelove.notes.domain.ContentKind;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 回收站。删除只是打个 deleted_at 标记，30 天后由定时任务彻底清掉。
 * 实体的 @SQLRestriction 会把打进回收站的行挡在所有普通查询之外，所以这里都走 JDBC。
 */
@Service
public class TrashService {

    public static final int KEEP_DAYS = 30;

    private final JdbcTemplate jdbc;

    public TrashService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record TrashItem(Long id, String kindLabel, String slug, String title,
                            Instant deletedAt, long daysLeft) {
    }

    public void softDelete(Long id, Long ownerId) {
        jdbc.update("update content_item set deleted_at = now() where id = ? and owner_id = ?",
                id, ownerId);
    }

    public void restore(Long id, Long ownerId) {
        jdbc.update("update content_item set deleted_at = null where id = ? and owner_id = ?",
                id, ownerId);
    }

    /** 彻底删除，连 project_meta 一起走（外键 on delete cascade）。 */
    public void purge(Long id, Long ownerId) {
        jdbc.update("delete from content_item where id = ? and owner_id = ? and deleted_at is not null",
                id, ownerId);
    }

    public List<TrashItem> list(Long ownerId) {
        if (ownerId == null) {
            return List.of();
        }
        List<TrashItem> out = new ArrayList<>();
        jdbc.query("select id, kind, slug, title, deleted_at from content_item "
                        + "where owner_id = ? and deleted_at is not null order by deleted_at desc",
                rs -> {
                    Instant deletedAt = rs.getTimestamp("deleted_at").toInstant();
                    long passed = Duration.between(deletedAt, Instant.now()).toDays();
                    String kind = rs.getString("kind");
                    String label;
                    try {
                        label = ContentKind.valueOf(kind).getLabel();
                    } catch (IllegalArgumentException e) {
                        label = kind;
                    }
                    out.add(new TrashItem(rs.getLong("id"), label, rs.getString("slug"),
                            rs.getString("title"), deletedAt, Math.max(KEEP_DAYS - passed, 0)));
                },
                ownerId);
        return out;
    }

    /** 超过保留期的彻底删掉，返回删了几条。 */
    public int purgeExpired() {
        return jdbc.update("delete from content_item where deleted_at is not null "
                + "and deleted_at < now() - interval '" + KEEP_DAYS + " days'");
    }

    /** 每天 4:30 清一次，和数据库备份错开。 */
    @Scheduled(cron = "0 30 4 * * *")
    public void purgeExpiredDaily() {
        int n = purgeExpired();
        if (n > 0) {
            org.slf4j.LoggerFactory.getLogger(TrashService.class)
                    .info("回收站清理：彻底删除 {} 条超过 {} 天的内容", n, KEEP_DAYS);
        }
    }
}
