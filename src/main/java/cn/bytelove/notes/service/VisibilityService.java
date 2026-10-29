package cn.bytelove.notes.service;

import cn.bytelove.notes.domain.ContentItem;
import cn.bytelove.notes.domain.Visibility;
import org.springframework.stereotype.Service;

/**
 * 可读性判定。三档可见性只在「别人看推荐内容」时起作用：
 * 自己的内容永远可读，别人的内容先过 recommended 这道门，再看 visibility。
 */
@Service
public class VisibilityService {

    /** 谁在看。userId 为空表示游客。 */
    public record Viewer(Long userId, boolean admin, boolean authenticated) {
        public static Viewer anonymous() {
            return new Viewer(null, false, false);
        }

        public boolean isSelf(ContentItem item) {
            return userId != null && userId.equals(item.getOwnerId());
        }
    }

    public boolean canRead(ContentItem item, Viewer viewer) {
        if (viewer.isSelf(item)) {
            return true;
        }
        if (viewer.admin()) {
            return true;
        }
        if (!item.isRecommended()) {
            return false;
        }
        return switch (item.getVisibility()) {
            case PUBLIC -> true;
            case MEMBER -> viewer.authenticated();
            case PRIVATE -> false;
        };
    }

    /** 只给标题不给正文：推荐栏里 MEMBER 的内容对游客。 */
    public boolean isLocked(ContentItem item, Viewer viewer) {
        if (viewer.authenticated() || viewer.isSelf(item)) {
            return false;
        }
        return item.isRecommended() && item.getVisibility() == Visibility.MEMBER;
    }
}
