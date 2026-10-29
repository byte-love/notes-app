package cn.bytelove.notes.service;

import java.util.Locale;

/**
 * 顶栏头像块用的小工具：首字母 + 由用户名算出来的固定色相。
 * 同一个人每次进来颜色一样，不同人尽量分开。
 */
public final class UserBadge {

    private UserBadge() {
    }

    public static String initial(String name) {
        if (name == null || name.isBlank()) {
            return "?";
        }
        return name.substring(0, 1).toUpperCase(Locale.ROOT);
    }

    public static int hue(String seed) {
        if (seed == null || seed.isEmpty()) {
            return 210;
        }
        int h = 0;
        for (int i = 0; i < seed.length(); i++) {
            h = (h * 31 + seed.charAt(i)) % 360;
        }
        return Math.floorMod(h, 360);
    }
}
