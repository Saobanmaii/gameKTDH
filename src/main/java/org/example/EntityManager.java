package org.example;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

public class EntityManager {

    // ── Inner entity classes ─────────────────────────────────────────────────

    public static class Obstacle {
        public float x, y, z;
        public int shape; // 0=tall wall, 1=low barrier, 2=wide blocker
        public Obstacle(float x, float z, int shape) { this.x = x; this.y = 0; this.z = z; this.shape = shape; }
        public float w() { return shape == 2 ? 3.8f : 0.9f; }
        public float h() { return shape == 1 ? 0.5f : 1.1f; }
        public float d() { return 0.8f; }
    }

    public static class Pickup {
        public float x, y, z;
        public int type; // 0=coin, 1=shrink, 2=shield, 3=magnet
        public float bobPhase;
        public Pickup(float x, float z, int type, float phase) {
            this.x = x; this.y = 0.5f; this.z = z; this.type = type; this.bobPhase = phase;
        }
    }

    public static class Particle {
        public float x, y, z, vx, vy, vz, life, maxLife, size;
        public Vector3f color;
        public Particle(float x, float y, float z,
                        float vx, float vy, float vz,
                        float life, float size, Vector3f color) {
            this.x=x; this.y=y; this.z=z;
            this.vx=vx; this.vy=vy; this.vz=vz;
            this.life=life; this.maxLife=life; this.size=size;
            this.color=color;
        }
    }

    public static class TrailSegment {
        public float x, y, z, sx, sy, alpha;
        public TrailSegment(float x, float y, float z, float sx, float sy) {
            this.x=x; this.y=y; this.z=z; this.sx=sx; this.sy=sy; this.alpha=0.55f;
        }
    }

    // ── Entity lists ──────────────────────────────────────────────────────────
    public final List<Obstacle>     obstacles = new ArrayList<>();
    public final List<Pickup>       pickups   = new ArrayList<>();
    public final List<Particle>     particles = new ArrayList<>();
    public final List<TrailSegment> trails    = new ArrayList<>();

    private static final float LANE_GAP = 2.2f;
    private final Random rng;

    public EntityManager(Random rng) { this.rng = rng; }

    public void clear() {
        obstacles.clear(); pickups.clear(); particles.clear(); trails.clear();
    }

    // ── Spawning ─────────────────────────────────────────────────────────────

    public void spawnWave(float z) {
        int lane = rng.nextInt(3) - 1;
        float r = rng.nextFloat();
        if (r < 0.55f) {
            int shape = rng.nextInt(3);
            if (shape == 2) lane = 0;
            obstacles.add(new Obstacle(lane * LANE_GAP, z, shape));
        } else if (r < 0.85f) {
            int coinLane = rng.nextInt(3) - 1;
            for (int c = 0; c < 3; c++)
                pickups.add(new Pickup(coinLane * LANE_GAP, z - c * 2.5f, 0, rng.nextFloat() * 6.28f));
        } else {
            int type = rng.nextFloat() < 0.5f ? 1 : (rng.nextFloat() < 0.5f ? 2 : 3);
            pickups.add(new Pickup(lane * LANE_GAP, z, type, 0));
        }
    }

    // ── Update all entities ───────────────────────────────────────────────────

    public void updateObstacles(float gameSpeed, Player player, long frameCount,
                                 Runnable onShieldBreak, Runnable onDeath) {
        float scale = player.getScale();
        Iterator<Obstacle> it = obstacles.iterator();
        while (it.hasNext()) {
            Obstacle o = it.next();
            o.z += gameSpeed;
            if (o.z > 8f) { it.remove(); continue; }

            float ph = player.sliding ? 0.35f : (player.jumping && player.playerY > 0.3f ? 0.0f : scale);
            float py = player.playerY + (player.sliding ? 0.25f : scale * 0.5f);

            boolean hit = Math.abs(player.playerX - o.x) < (0.45f * scale + o.w() * 0.5f)
                    && Math.abs(py - (o.y + o.h() * 0.5f)) < (ph * 0.5f + o.h() * 0.5f)
                    && Math.abs(player.playerZ - o.z) < (0.5f + o.d() * 0.5f);

            if (hit) {
                if (player.shielded) {
                    player.shielded = false;
                    it.remove();
                    onShieldBreak.run();
                } else {
                    onDeath.run();
                    return;
                }
            }
        }
    }

    public void updatePickups(float gameSpeed, float time, Player player, int[] scoreRef) {
        float scale = player.getScale();
        Iterator<Pickup> it = pickups.iterator();
        while (it.hasNext()) {
            Pickup p = it.next();
            p.z += gameSpeed;
            p.y = 0.55f + (float) Math.sin(time * 4f + p.bobPhase) * 0.18f;
            if (p.z > 8f) { it.remove(); continue; }

            boolean collect = Math.abs(player.playerX - p.x) < 1.1f * scale
                    && Math.abs(player.playerY - p.y) < 1.1f
                    && Math.abs(player.playerZ - p.z) < 1.0f;
            if (collect) {
                applyPickup(p, player, scoreRef);
                it.remove();
            }
        }
    }

    private void applyPickup(Pickup p, Player player, int[] scoreRef) {
        switch (p.type) {
            case 0:
                scoreRef[0] += 10;
                spawnCoinBurst(p.x, p.y, p.z);
                break;
            case 1:
                player.shrunk = true;
                player.shrinkEnd = System.currentTimeMillis() + 6000;
                spawnPowerupParticles(p.x, p.y, p.z, new Vector3f(0.4f, 0.8f, 1f));
                break;
            case 2:
                player.shielded = true;
                player.shieldEnd = System.currentTimeMillis() + 8000;
                spawnPowerupParticles(p.x, p.y, p.z, new Vector3f(1f, 0.8f, 0.1f));
                break;
            case 3:
                spawnPowerupParticles(p.x, p.y, p.z, new Vector3f(1f, 0.3f, 0.9f));
                pickups.removeIf(c -> {
                    if (c.type == 0 && Math.abs(c.z - player.playerZ) < 12f) {
                        scoreRef[0] += 10; return true;
                    }
                    return false;
                });
                break;
        }
    }

    public void updateParticles(float dt) {
        Iterator<Particle> it = particles.iterator();
        while (it.hasNext()) {
            Particle p = it.next();
            p.x += p.vx * dt; p.y += p.vy * dt; p.z += p.vz * dt;
            p.vy -= 4f * dt;
            p.life -= dt;
            if (p.life <= 0) it.remove();
        }
    }

    public void updateTrails(float dt) {
        Iterator<TrailSegment> it = trails.iterator();
        while (it.hasNext()) {
            TrailSegment t = it.next();
            t.alpha -= dt * 2.5f;
            if (t.alpha <= 0) it.remove();
        }
    }

    public void addTrail(float x, float y, float z, float sx, float sy) {
        trails.add(new TrailSegment(x, y, z, sx, sy));
    }

    // ── Particle spawners ─────────────────────────────────────────────────────

    public void spawnJumpParticles(float px, float py, float pz) {
        for (int i = 0; i < 10; i++) {
            float a = rng.nextFloat() * 6.28f;
            particles.add(new Particle(px, 0.1f, pz,
                    (float) Math.cos(a) * 1.8f, rng.nextFloat() * 2.5f + 0.5f, (float) Math.sin(a) * 1.8f,
                    0.45f, 0.13f, new Vector3f(0.2f, 1f, 0.6f)));
        }
    }

    public void spawnLandParticles(float px, float py, float pz) {
        for (int i = 0; i < 8; i++) {
            float a = rng.nextFloat() * 6.28f;
            particles.add(new Particle(px, 0.05f, pz,
                    (float) Math.cos(a) * 2.5f, rng.nextFloat() * 1.5f + 0.5f, (float) Math.sin(a) * 2.5f,
                    0.35f, 0.11f, new Vector3f(0.9f, 0.8f, 0.2f)));
        }
    }

    public void spawnCoinBurst(float x, float y, float z) {
        for (int i = 0; i < 8; i++) {
            float a = rng.nextFloat() * 6.28f;
            particles.add(new Particle(x, y, z,
                    (float) Math.cos(a) * 2.5f, rng.nextFloat() * 3.5f + 1f, (float) Math.sin(a) * 2.5f,
                    0.55f, 0.09f, new Vector3f(1f, 0.9f, 0.1f)));
        }
    }

    public void spawnPowerupParticles(float x, float y, float z, Vector3f col) {
        for (int i = 0; i < 16; i++) {
            float a = rng.nextFloat() * 6.28f, e = rng.nextFloat() * 3.14f;
            float sp = rng.nextFloat() * 3.5f + 1.5f;
            particles.add(new Particle(x, y, z,
                    (float) (Math.cos(a) * Math.sin(e)) * sp,
                    (float) Math.cos(e) * sp,
                    (float) (Math.sin(a) * Math.sin(e)) * sp,
                    0.8f, 0.16f, col));
        }
    }

    public void spawnShieldBreak(float px, float py, float pz) {
        for (int i = 0; i < 20; i++) {
            float a = rng.nextFloat() * 6.28f, e = rng.nextFloat() * 3.14f;
            float sp = rng.nextFloat() * 5f + 2f;
            particles.add(new Particle(px, py + 0.5f, pz,
                    (float) (Math.cos(a) * Math.sin(e)) * sp,
                    (float) Math.cos(e) * sp,
                    (float) (Math.sin(a) * Math.sin(e)) * sp,
                    1.0f, 0.22f, new Vector3f(1f, 0.9f, 0.2f)));
        }
    }

    public void spawnDeathExplosion(float px, float py, float pz, Random r) {
        for (int i = 0; i < 40; i++) {
            float a = r.nextFloat() * 6.28f, e = r.nextFloat() * 3.14f;
            float sp = r.nextFloat() * 6f + 2f;
            particles.add(new Particle(px, py + 0.5f, pz,
                    (float) (Math.cos(a) * Math.sin(e)) * sp,
                    (float) Math.cos(e) * sp,
                    (float) (Math.sin(a) * Math.sin(e)) * sp,
                    1.8f, r.nextFloat() * 0.28f + 0.1f,
                    new Vector3f(1f, r.nextFloat() * 0.5f, 0f)));
        }
    }
}
