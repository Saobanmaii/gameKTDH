package org.example;

public class Obstacle {
    public float x, y, z;
    public int shape; // 0=tall wall, 1=low barrier, 2=wide blocker (slide), 3=platform (jump onto), 4=low arch (slide only)

    public Obstacle(float x, float z, int shape) {
        this.x = x;
        // shape 2: wide blocker nâng cao, player slide qua dưới
        // shape 4: low arch — đáy ở y=0.6, center=0.9, player phải slide (không nhảy thấp qua được)
        this.y = (shape == 2) ? 0.65f : (shape == 4) ? 0.6f : 0f;
        this.z = z;
        this.shape = shape;
    }

    public float w() {
        switch (shape) {
            case 2: case 4: return 3.8f;
            case 3: return 2.0f;
            default: return 0.9f;
        }
    }

    public float h() {
        switch (shape) {
            case 1: return 0.5f;
            case 2: return 0.5f;  // thin beam overhead
            case 3: return 0.45f;
            case 4: return 0.6f;
            default: return 1.1f;
        }
    }

    public float d() {
        switch (shape) {
            case 3: return 1.2f;
            default: return 0.8f;
        }
    }
}
