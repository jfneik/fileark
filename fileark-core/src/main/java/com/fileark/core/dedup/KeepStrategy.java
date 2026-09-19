package com.fileark.core.dedup;

import com.fileark.core.model.FileMeta;

import java.util.Comparator;
import java.util.List;

/** 重复组内保留策略：pick 结果必须确定（并列时按路径字典序）。 */
public enum KeepStrategy {
    NEWEST {
        public FileMeta pick(List<FileMeta> members) {
            return members.stream()
                    .max(Comparator.comparing(FileMeta::mtime).thenComparing(f -> f.path().toString()))
                    .orElseThrow();
        }
    },
    OLDEST {
        public FileMeta pick(List<FileMeta> members) {
            return members.stream()
                    .min(Comparator.comparing(FileMeta::mtime).thenComparing(f -> f.path().toString()))
                    .orElseThrow();
        }
    },
    FIRST_PATH {
        public FileMeta pick(List<FileMeta> members) {
            return members.stream()
                    .min(Comparator.comparing(f -> f.path().toString()))
                    .orElseThrow();
        }
    };

    public abstract FileMeta pick(List<FileMeta> members);
}
