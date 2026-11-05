package cn.bytelove.notes.service;

import cn.bytelove.notes.domain.ContentCard;
import cn.bytelove.notes.domain.ContentItem;
import cn.bytelove.notes.domain.Visibility;
import org.springframework.stereotype.Service;

/**
 * 可读性判定。三档可见性只在「别人看推荐内容」时起作用：
 * 自己的内容永远可读，别人的内容先过 recommended 这道门，再看 visibility。
 * <p>
 * 判定只看归属和可见性两个字段，所以整条记录和列表用的 {@link ContentCard} 都能判。
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

        public boolean isSelf(ContentCard item) {
            return userId != null && userId.equals(item.getOwnerId());
        }
    }

    public boolean canRead(ContentItem item, Viewer viewer) {
        return readable(item.getOwnerId(), item.isRecommended(), item.getVisibility(), viewer);
    }

    public boolean canRead(ContentCard item, Viewer viewer) {
        return readable(item.getOwnerId(), item.isRecommended(), item.getVisibility(), viewer);
    }

    private static boolean readable(Long ownerId, boolean recommended, Visibility visibility, Viewer viewer) {
        if (viewer.userId() != null && viewer.userId().equals(ownerId)) {
            return true;
        }
        if (viewer.admin()) {
            return true;
        }
        if (!recommended) {
            return false;
        }
        return switch (visibility) {
            case PUBLIC -> true;
            case MEMBER -> viewer.authenticated();
            case PRIVATE -> false;
        };
    }

    /** 只给标题不给正文：推荐栏里 MEMBER 的内容对游客。 */
    public boolean isLocked(ContentItem item, Viewer viewer) {
        return lockable(item.getOwnerId(), item.isRecommended(), item.getVisibility(), viewer);
    }

    public boolean isLocked(ContentCard item, Viewer viewer) {
        return lockable(item.getOwnerId(), item.isRecommended(), item.getVisibility(), viewer);
    }

    private static boolean lockable(Long ownerId, boolean recommended, Visibility visibility, Viewer viewer) {
        boolean self = viewer.userId() != null && viewer.userId().equals(ownerId);
        if (viewer.authenticated() || self) {
            return false;
        }
        return recommended && visibility == Visibility.MEMBER;
    }
}
