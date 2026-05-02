package org.example;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.function.IntConsumer;

public class EntityManager {

    // ── Entity lists ──────────────────────────────────────────────────────────
    public final List<Obstacle>     obstacles = new ArrayList<>();
    public final List<Pickup>       pickups   = new ArrayList<>();
    public final List<Particle>     particles = new ArrayList<>();
    public final List<TrailSegment> trails    = new ArrayList<>();

    private static final float LANE_GAP    = 2.2f;
    private static final float TRAIL_DECAY = 1.8f;
    private final Random rng;

    public EntityManager(Random rng) { this.rng = rng; }

    public void clear() {
        obstacles.clear(); pickups.clear(); particles.clear(); trails.clear();
    }

    public void addTrail(float x, float y, float z, float sx, float scale) {
        trails.add(new TrailSegment(x, y, z, sx, scale * 0.55f));
    }

    public void updateTrails(float dt) {
        Iterator<TrailSegment> it = trails.iterator();
        while (it.hasNext()) {
            TrailSegment t = it.next();
            t.alpha -= TRAIL_DECAY * dt;
            if (t.alpha <= 0) it.remove();
        }
    }

    // ── Spawning ─────────────────────────────────────────────────────────────

    public void spawnWave(float z) {
        int lane = rng.nextInt(3) - 1;
        float r = rng.nextFloat();
        if (r < 0.70f) {
            float sr = rng.nextFloat();
            // shape 0=tall wall 30%, 1=low barrier 22%, 2=wide blocker 14%, 3=platform 14%, 4=low arch 20%
            int shape = sr < 0.30f ? 0 : (sr < 0.52f ? 1 : (sr < 0.66f ? 2 : (sr < 0.80f ? 3 : 4)));
            if (shape == 2 || shape == 4) lane = 0; // slide obstacles buộc ở giữa
            obstacles.add(new Obstacle(lane * LANE_GAP, z, shape));
        } else if (r < 0.90f) {
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
        boolean onAnyPlatform = false;

        Iterator<Obstacle> it = obstacles.iterator();
        while (it.hasNext()) {
            Obstacle o = it.next();
            o.z += gameSpeed;
            if (o.z > 8f) { it.remove(); continue; }

            // ── Shape 3: platform (jumpable) ──
            if (o.shape == 3) {
                float platformTop = o.h(); // 0.45f
                boolean overlapX = Math.abs(player.playerX - o.x) < (0.45f * scale + o.w() * 0.5f);
                boolean overlapZ = Math.abs(player.playerZ - o.z) < (0.6f + o.d() * 0.5f);

                if (overlapX && overlapZ) {
                    if (player.playerY >= platformTop - 0.15f) {
                        // Player đang ở trên hoặc đang đáp xuống platform
                        player.groundY = platformTop;
                        onAnyPlatform  = true;
                    } else {
                        // Va chạm từ phía bên (chưa nhảy đủ cao)
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
                continue;
            }

            // ── Shape 0, 1, 2: collision AABB thông thường ──
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

        if (!onAnyPlatform) player.groundY = 0f;
    }

    public void updatePickups(float gameSpeed, float time, Player player,
                              IntConsumer onScoreAdd, long frameCount) {
        float scale = player.getScale();
        Iterator<Pickup> it = pickups.iterator();
        while (it.hasNext()) {
            Pickup p = it.next();
            p.z += gameSpeed;
            p.y = 0.55f + (float) Math.sin(time * 4f + p.bobPhase) * 0.18f;
            if (p.z > 8f) { it.remove(); continue; }

            // Magnet: kéo coin về phía player từng frame (hiệu ứng nhìn thấy rõ)
            if (player.magnetized && p.type == 0) {
                float dx = player.playerX - p.x;
                float dz = player.playerZ - p.z;
                float dist = (float) Math.sqrt(dx * dx + dz * dz);
                if (dist < 10f && dist > 0.02f) {
                    float pull = 0.08f + 0.06f * (1f - dist / 10f);
                    p.x += dx * pull;
                    p.z += dz * 0.03f;
                }
            }

            float collectR = player.magnetized ? 1.8f * scale : 1.1f * scale;
            boolean collect = Math.abs(player.playerX - p.x) < collectR
                    && Math.abs(player.playerY - p.y) < 1.1f
                    && Math.abs(player.playerZ - p.z) < 1.0f;
            if (collect) {
                applyPickup(p, player, onScoreAdd, frameCount);
                it.remove();
            }
        }
    }

    private void applyPickup(Pickup p, Player player, IntConsumer onScoreAdd, long frameCount) {
        switch (p.type) {
            case 0:
                onScoreAdd.accept(10);
                spawnCoinBurst(p.x, p.y, p.z);
                break;
            case 1:
                player.shrunk    = true;
                player.shrinkEnd = frameCount + 360;
                spawnPowerupParticles(p.x, p.y, p.z, new Vector3f(0.4f, 0.8f, 1f));
                break;
            case 2:
                player.shielded  = true;
                player.shieldEnd = frameCount + 480;
                spawnPowerupParticles(p.x, p.y, p.z, new Vector3f(1f, 0.8f, 0.1f));
                break;
            case 3:
                player.magnetized = true;
                player.magnetEnd  = frameCount + 420; // ~7s @ 60fps
                spawnPowerupParticles(p.x, p.y, p.z, new Vector3f(1f, 0.3f, 0.9f));
                break;
        }
    }

    public void updateParticles(float dt) {
        Iterator<Particle> it = particles.iterator();
        while (it.hasNext()) {
            Particle p = it.next();
            p.x += p.vx * dt; p.y += p.vy * dt; p.z += p.vz * dt;
            p.vy -= 4f * dt;
            p.angle += p.angularVel * dt;
            p.life -= dt;
            if (p.life <= 0) it.remove();
        }
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

    public void spawnShatterFragments(float px, float py, float pz, float scale) {
        // Body=green, head=skin, legs=blue, hat=dark-blue
        float[][] cols = {
            {0.15f, 0.95f, 0.30f},
            {0.97f, 0.78f, 0.52f},
            {0.12f, 0.42f, 0.95f},
            {0.10f, 0.18f, 0.60f},
        };
        int[] counts = {10, 4, 7, 3};

        for (int g = 0; g < cols.length; g++) {
            for (int i = 0; i < counts[g]; i++) {
                // Văng ra sau (+Z) theo hình nón ±100° quanh trục +Z
                float arc = (rng.nextFloat() - 0.5f) * (float) Math.PI * 1.1f;
                float sp  = 2.0f + rng.nextFloat() * 4.5f;
                float vx  = (float) Math.sin(arc) * sp;
                float vz  = (float) Math.cos(arc) * sp + 1.0f; // bias +Z
                float vy  = 0.8f + rng.nextFloat() * 2.8f;

                float type = rng.nextFloat();
                float fx, fy, fz;
                if (type < 0.35f) {         // flat slab
                    fx = 0.18f + rng.nextFloat() * 0.22f;
                    fy = 0.04f + rng.nextFloat() * 0.07f;
                    fz = 0.16f + rng.nextFloat() * 0.18f;
                } else if (type < 0.65f) {  // elongated splinter
                    fx = 0.05f + rng.nextFloat() * 0.07f;
                    fy = 0.22f + rng.nextFloat() * 0.28f;
                    fz = 0.05f + rng.nextFloat() * 0.08f;
                } else {                    // chunky brick
                    float c = 0.10f + rng.nextFloat() * 0.18f;
                    fx = c; fy = c * (0.7f + rng.nextFloat() * 0.7f); fz = c;
                }

                Particle frag = new Particle(
                    px + (rng.nextFloat() - 0.5f) * 0.6f * scale,
                    py + 0.4f + rng.nextFloat() * 0.8f * scale,
                    pz + (rng.nextFloat() - 0.5f) * 0.4f,
                    vx, vy, vz,
                    1.8f + rng.nextFloat() * 0.9f,
                    0f,
                    new Vector3f(cols[g][0], cols[g][1], cols[g][2])
                );
                frag.scaleX = fx * scale;
                frag.scaleY = fy * scale;
                frag.scaleZ = fz * scale;
                frag.angularVel = (rng.nextFloat() - 0.5f) * 10f;
                particles.add(frag);
            }
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
