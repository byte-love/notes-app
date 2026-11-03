package cn.bytelove.notes.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "notes")
public record AppProperties(
        String ownerUsername,
        String ownerPassword,
        String viewerUsername,
        String viewerPassword,
        String tabProjectsHref,
        Boolean registerEnabled,
        /** 「我的」页展示 / 复制给 AI 的 MCP 地址；留空就按当前请求的地址拼（测试环境可以指到正式站） */
        String mcpUrl
) {
}
