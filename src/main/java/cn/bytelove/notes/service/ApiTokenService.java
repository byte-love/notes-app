package cn.bytelove.notes.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * AI 接入用的个人 token。
 * <p>
 * 明文只在生成时回一次，库里只放哈希（token 本身就是高熵随机串，用 SHA-256 够，
 * 没必要上 bcrypt——那反而让每次请求都要算一次慢哈希）。
 * 一个账号同时只有一个有效 token，重新生成会把旧的顶掉。
 */
@Service
public class ApiTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String PREFIX = "bl_";

    private final JdbcTemplate jdbc;

    public ApiTokenService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 生成一个新 token，返回明文（只此一次）；旧 token 立即失效。 */
    public String issue(Long userId) {
        byte[] raw = new byte[24];
        RANDOM.nextBytes(raw);
        String token = PREFIX + HexFormat.of().formatHex(raw);
        jdbc.update("update app_user set api_token_hash = ?, api_token_hint = ?,"
                        + " api_token_created_at = now() where id = ?",
                sha256(token), hint(token), userId);
        return token;
    }

    public void revoke(Long userId) {
        jdbc.update("update app_user set api_token_hash = null, api_token_hint = null,"
                + " api_token_created_at = null where id = ?", userId);
    }

    /** 当前这个人的 token 提示（前几位）和生成时间，页面上显示用；没有就是空。 */
    public Optional<String[]> describe(Long userId) {
        List<String[]> rows = jdbc.query(
                "select api_token_hint, to_char(api_token_created_at, 'YYYY-MM-DD HH24:MI')"
                        + " from app_user where id = ? and api_token_hash is not null",
                (rs, n) -> new String[]{rs.getString(1), rs.getString(2)}, userId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 按 token 明文找用户 id；找不到返回空。 */
    public Optional<Long> userIdOf(String token) {
        if (token == null || !token.startsWith(PREFIX) || token.length() > 128) {
            return Optional.empty();
        }
        List<Long> ids = jdbc.queryForList(
                "select id from app_user where api_token_hash = ? and enabled", Long.class, sha256(token));
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }

    private static String hint(String token) {
        return token.length() <= 8 ? token : token.substring(0, 8) + "…";
    }

    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("算 token 哈希失败", e);
        }
    }
}
