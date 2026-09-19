package com.fileark.core.similar;

import com.fileark.core.model.FileMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 相似图分组：两两汉明距离 ≤ 阈值即并入同组。
 * 用并查集（Union-Find）传递闭包——A~B、B~C 则 A/B/C 一组。
 */
public final class SimilarImageGrouper {

    public List<List<FileMeta>> group(Map<FileMeta, Long> hashes, int threshold) {
        List<FileMeta> files = new ArrayList<>(hashes.keySet());
        int[] parent = new int[files.size()];
        for (int i = 0; i < parent.length; i++) {
            parent[i] = i;
        }
        for (int i = 0; i < files.size(); i++) {
            for (int j = i + 1; j < files.size(); j++) {
                if (DHash.distance(hashes.get(files.get(i)), hashes.get(files.get(j))) <= threshold) {
                    union(parent, i, j);
                }
            }
        }
        Map<Integer, List<FileMeta>> clusters = new HashMap<>();
        for (int i = 0; i < files.size(); i++) {
            clusters.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(files.get(i));
        }
        return clusters.values().stream()
                .filter(g -> g.size() > 1)
                .toList();
    }

    private void union(int[] parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra != rb) {
            parent[rb] = ra;
        }
    }

    private int find(int[] parent, int x) {
        while (parent[x] != x) {
            parent[x] = parent[parent[x]];
            x = parent[x];
        }
        return x;
    }
}
