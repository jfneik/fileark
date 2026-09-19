package com.fileark.core.execute;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

/**
 * 文件系统门面。抽象出来是为了测试时可注入故障点，
 * 以及让执行器逻辑不直接触碰 java.nio.file。
 */
public interface FileOps {

    boolean exists(Path path);

    long size(Path path) throws IOException;

    FileTime mtime(Path path) throws IOException;

    /** 原子优先的移动；目标存在时抛 IOException（绝不覆盖）。 */
    void move(Path src, Path dst) throws IOException;

    /** 递归创建目录，返回本次真正新建的目录列表（按创建顺序）。 */
    List<Path> mkdirs(Path dir) throws IOException;

    /** 目录为空时删除，返回是否删除。 */
    boolean deleteDirIfEmpty(Path dir) throws IOException;

    long usableSpace(Path root);
}
