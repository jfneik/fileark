package com.fileark.core.similar;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

class DHashTest {

    private BufferedImage solid(int w, int h, int rgb) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, rgb);
            }
        }
        return img;
    }

    private BufferedImage horizontalGradient(int w, int h, boolean flipped) {
        // dHash 比较水平相邻像素，必须用水平方向变化的梯度
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < w; x++) {
            int v = flipped ? 255 - x * 255 / w : x * 255 / w;
            int rgb = (v << 16) | (v << 8) | v;
            for (int y = 0; y < h; y++) {
                img.setRGB(x, y, rgb);
            }
        }
        return img;
    }

    @Test
    void identicalImagesDistanceZero() {
        assertThat(DHash.distance(DHash.hash(solid(64, 64, 0x336699)),
                DHash.hash(solid(64, 64, 0x336699)))).isZero();
    }

    @Test
    void differentSolidColorsStillVerySimilar() {
        // 纯色图梯度为零，dHash 无法区分颜色，仅验证稳定性
        int d = DHash.distance(DHash.hash(solid(64, 64, 0x000000)),
                DHash.hash(solid(64, 64, 0xFFFFFF)));
        assertThat(d).isBetween(0, 10);
    }

    @Test
    void gradientVsFlippedGradientIsFar() {
        long a = DHash.hash(horizontalGradient(100, 100, false));
        long b = DHash.hash(horizontalGradient(100, 100, true));
        assertThat(DHash.distance(a, b)).isEqualTo(64);
    }

    @Test
    void resizedGradientKeepsDirection() {
        long big = DHash.hash(horizontalGradient(200, 200, false));
        long small = DHash.hash(horizontalGradient(60, 40, false));
        assertThat(DHash.distance(big, small)).isLessThanOrEqualTo(8);
    }
}
