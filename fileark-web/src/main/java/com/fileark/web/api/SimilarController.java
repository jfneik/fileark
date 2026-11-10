package com.fileark.web.api;

import com.fileark.core.model.Category;
import com.fileark.core.model.FileMeta;
import com.fileark.core.port.FileRepository;
import com.fileark.core.port.ScanStore;
import com.fileark.core.similar.DHash;
import com.fileark.core.similar.SimilarImageGrouper;
import com.fileark.core.exception.ErrorCode;
import com.fileark.core.exception.FileArkException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 相似图片（二期 F7）：对扫描索引中的图片现场计算 dHash 并分组。 */
@RestController
@RequestMapping("/api/scans/{id}/similar")
public class SimilarController {

    private final FileRepository fileRepository;
    private final ScanStore scanStore;
    private final SimilarImageGrouper grouper = new SimilarImageGrouper();

    public SimilarController(FileRepository fileRepository, ScanStore scanStore) {
        this.fileRepository = fileRepository;
        this.scanStore = scanStore;
    }

    @GetMapping
    public List<List<FileEntryDto>> similar(@PathVariable long id,
                                                @RequestParam(defaultValue = "10") int threshold) {
        scanStore.find(id).orElseThrow(() ->
                new FileArkException(ErrorCode.INVALID_REQUEST, "扫描不存在: " + id));
        List<FileMeta> images = fileRepository.byScan(id).stream()
                .filter(f -> f.category() == Category.IMAGE)
                .toList();
        Map<FileMeta, Long> hashes = new HashMap<>();
        for (FileMeta img : images) {
            try {
                hashes.put(img, DHash.hashFile(img.path()));
            } catch (IOException e) {
                // 无法解码的图片跳过，不阻塞分组
            }
        }
        return grouper.group(hashes, threshold).stream()
                .map(group -> group.stream().map(FileEntryDto::of).toList())
                .toList();
    }
}
