package com.fileark.core.model;

/** 文件业务类别，由魔数识别出的 MIME 归类而来。 */
public enum Category {
    IMAGE, VIDEO, AUDIO, DOCUMENT, ARCHIVE, CODE, OTHER;

    public static Category fromMime(String mime) {
        if (mime == null) {
            return OTHER;
        }
        if (mime.startsWith("image/")) {
            return IMAGE;
        }
        if (mime.startsWith("video/")) {
            return VIDEO;
        }
        if (mime.startsWith("audio/")) {
            return AUDIO;
        }
        if (mime.startsWith("text/") && (mime.contains("java") || mime.contains("script")
                || mime.contains("python") || mime.contains("xml") || mime.contains("html")
                || mime.contains("json") || mime.contains("css") || mime.contains("csv"))) {
            return CODE;
        }
        if (mime.startsWith("text/")) {
            return DOCUMENT;
        }
        return switch (mime) {
            case "application/pdf", "application/msword", "application/rtf",
                 "application/vnd.ms-excel", "application/vnd.ms-powerpoint" -> DOCUMENT;
            case "application/zip", "application/gzip", "application/x-tar",
                 "application/x-7z-compressed", "application/x-rar-compressed",
                 "application/java-archive" -> ARCHIVE;
            case "application/json", "application/javascript", "application/xml" -> CODE;
            default -> OTHER;
        };
    }
}
