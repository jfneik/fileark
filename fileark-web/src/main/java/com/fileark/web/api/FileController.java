package com.fileark.web.api;

import com.fileark.core.model.Category;
import com.fileark.core.model.FileMeta;
import com.fileark.core.port.FileRepository;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;

/**
 * 缩略图服务：按文件档案 id 读取图片并缩放，供相似图片/重复组页面预览。
 * 只接受**已索引档案**的 id（不收任意路径）——杜绝任意文件读取。
 */
@RestController
@RequestMapping("/api/files")
public class FileController {

    private static final int THUMB_MAX_WIDTH = 240;

    private final FileRepository fileRepository;

    public FileController(FileRepository fileRepository) {
        this.fileRepository = fileRepository;
    }

    @GetMapping("/{id}/thumbnail")
    public ResponseEntity<byte[]> thumbnail(@PathVariable long id) {
        FileMeta meta = fileRepository.findById(id)
                .filter(f -> f.category() == Category.IMAGE)
                .filter(f -> Files.isRegularFile(f.path()))
                .orElse(null);
        if (meta == null) {
            return ResponseEntity.notFound().build();
        }
        try (InputStream in = Files.newInputStream(meta.path())) {
            BufferedImage source = ImageIO.read(in);
            if (source == null) {
                return ResponseEntity.notFound().build();
            }
            int w = source.getWidth();
            int h = source.getHeight();
            int scale = Math.min(1, THUMB_MAX_WIDTH * 100 / Math.max(1, w));
            int tw = Math.max(1, w * scale / 100);
            int th = Math.max(1, h * scale / 100);
            BufferedImage thumb = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = thumb.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(source, 0, 0, tw, th, null);
            } finally {
                g.dispose();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(thumb, "png", out);
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .cacheControl(CacheControl.maxAge(java.time.Duration.ofHours(1)))
                    .body(out.toByteArray());
        } catch (Exception e) {
            // 损坏/不支持的图片一律 404，前端显示占位即可
            return ResponseEntity.notFound().build();
        }
    }
}
