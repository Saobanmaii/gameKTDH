package org.example;

import org.joml.Vector3f;

public class Particle {
    public float x, y, z, vx, vy, vz, life, maxLife, size;
    public Vector3f color;

    // Fragment-only fields (scaleX > 0 means this is a shard, not a spark)
    public float scaleX, scaleY, scaleZ;
    public float angle, angularVel;

    public Particle(float x, float y, float z,
                    float vx, float vy, float vz,
                    float life, float size, Vector3f color) {
        this.x = x; this.y = y; this.z = z;
        this.vx = vx; this.vy = vy; this.vz = vz;
        this.life = life; this.maxLife = life; this.size = size;
        this.color = color;
    }
}
