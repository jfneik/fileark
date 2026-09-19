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

    private BufferedImage verticalGradient(int w, int h, boolean flipped) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            int v = flipped ? 255 - y * 255 / h : y * 255 / h;
            int rgb = (v << 16) | (v << 8) | v;
            for (int x = 0; x < w; x++) {
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
        long a = DHash.hash(verticalGradient(100, 100, false));
        long b = DHash.hash(verticalGradient(100, 100, true));
        assertThat(DHash.distance(a, b)).isEqualTo(64);
    }

    @Test
    void resizedGradientKeepsDirection() {
        long big = DHash.hash(verticalGradient(200, 200, false));
        long small = DHash.hash(verticalGradient(60, 40, false));
        assertThat(DHash.distance(big, small)).isLessThanOrEqualTo(8);
    }
}
