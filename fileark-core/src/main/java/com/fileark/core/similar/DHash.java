package com.fileark.core.similar;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorConvertOp;
import java.io.IOException;
import java.nio.file.Path;

/**
 * 手写 dHash 感知哈希：解码 → 灰度 → 缩放 9×8 → 相邻像素比较得 64-bit。
 * 汉明距离 ≤ 阈值（建议 10）视为相似图。
 */
public final class DHash {

    private static final int W = 9;
    private static final int H = 8;
    private static final ColorConvertOp GRAY =
            new ColorConvertOp(ColorSpace.getInstance(ColorSpace.CS_GRAY), null);

    private DHash() {
    }

    public static long hashFile(Path image) throws IOException {
        BufferedImage img = ImageIO.read(image.toFile());
        if (img == null) {
            throw new IOException("无法解码图片: " + image);
        }
        return hash(img);
    }

    public static long hash(BufferedImage img) {
        BufferedImage small = new BufferedImage(W, H, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = small.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(GRAY.filter(img, null), 0, 0, W, H, null);
        } finally {
            g.dispose();
        }
        long hash = 0;
        int bit = 0;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W - 1; x++) {
                // 约定：左像素比右像素暗 → 该位为 1
                if (gray(small, x, y) < gray(small, x + 1, y)) {
                    hash |= (1L << bit);
                }
                bit++;
            }
        }
        return hash;
    }

    public static int distance(long a, long b) {
        return Long.bitCount(a ^ b);
    }

    private static int gray(BufferedImage img, int x, int y) {
        return img.getRaster().getSample(x, y, 0);
    }
}
