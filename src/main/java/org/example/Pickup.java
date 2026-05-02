package org.example;

public class Pickup {
    public float x, y, z;
    public int type; // 0=coin, 1=shrink, 2=shield, 3=magnet
    public float bobPhase;

    public Pickup(float x, float z, int type, float phase) {
        this.x = x; this.y = 0.5f; this.z = z; this.type = type; this.bobPhase = phase;
    }
}
