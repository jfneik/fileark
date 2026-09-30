package com.fileark.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 全部配置项集中在 application.yml 的 app.* 命名空间。路径支持 ~ 展开。 */
@ConfigurationProperties("app")
public record AppProperties(
        String dbPath,
        String quarantineDir,
        long retentionDays,
        int hashConcurrency,
        Boolean autoOpenBrowser
) {
    public AppProperties {
        if (dbPath == null || dbPath.isBlank()) {
            dbPath = "~/.fileark/fileark.db";
        }
        if (quarantineDir == null || quarantineDir.isBlank()) {
            quarantineDir = "~/.fileark/quarantine";
        }
        if (retentionDays <= 0) {
            retentionDays = 30;
        }
        if (hashConcurrency <= 0) {
            hashConcurrency = 64;
        }
        if (autoOpenBrowser == null) {
            autoOpenBrowser = true;
        }
    }

    /** 展开 ~ 为用户主目录；仅处理 "~/" 与单独的 "~"，"~other" 这类写法原样返回。 */
    public static java.nio.file.Path expand(String path) {
        if (path.equals("~")) {
            return java.nio.file.Path.of(System.getProperty("user.home"));
        }
        String p = path.startsWith("~/")
                ? System.getProperty("user.home") + path.substring(1) : path;
        return java.nio.file.Path.of(p);
    }
}
