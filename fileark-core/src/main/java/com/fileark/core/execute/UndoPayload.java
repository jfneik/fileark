package com.fileark.core.execute;

import java.util.List;

/**
 * 撤销日志载荷。全部用字符串路径，便于 JSON 序列化落库。
 * createdDirs 仅 MOVE 有：执行时因目标父目录不存在而新建的目录链，
 * 回滚时按逆序「空才删」回收。
 */
public record UndoPayload(String src, String dst, String quarantinePath, List<String> createdDirs) {
}
