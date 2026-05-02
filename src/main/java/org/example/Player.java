package org.example;

public class Player {

    // ── Constants ─────────────────────────────────────────────────────────────
    public static final float LANE_GAP       = 2.2f;
    public static final float GRAVITY        = -0.022f;
    public static final float JUMP_POWER     = 0.38f;
    public static final float SLIDE_DURATION = 0.7f;
    private static final float JUMP_SPIN_SPEED = (float)(Math.PI * 2.4f); // rad/s ≈ full flip/jump

    // ── Position & physics ────────────────────────────────────────────────────
    public float playerX = 0f;
    public float playerY = 0f;
    public float playerZ = 0f;
    public float velY    = 0f;

    // ── State ─────────────────────────────────────────────────────────────────
    public int     targetLane  = 0;   // -1, 0, 1
    public boolean jumping     = false;
    public boolean sliding     = false;
    public float   slideTimer  = 0f;

    // ── Power-ups ─────────────────────────────────────────────────────────────
    public boolean shrunk    = false;
    public long    shrinkEnd = 0;
    public boolean shielded  = false;
    public long    shieldEnd = 0;

    // ── Input queue ───────────────────────────────────────────────────────────
    public int pendingLane = 0;

    // ── Visual & physics state ────────────────────────────────────────────────
    public float deathShear = 0f;  // shearYX tăng dần khi chết
    public float deathAngle = 0f;  // xoay quanh Y khi chết (phép xoay)
    public float jumpAngle  = 0f;  // xoay quanh X khi nhảy (forward flip)
    public float groundY    = 0f;  // sàn hiệu dụng: 0=mặt đất, >0=đứng trên platform

    // ─────────────────────────────────────────────────────────────────────────

    public void reset() {
        playerX = 0; playerY = 0; playerZ = 0;
        velY = 0; targetLane = 0;
        jumping = false; sliding = false; slideTimer = 0;
        shrunk = false; shielded = false;
        pendingLane = 0;
        deathShear = 0; deathAngle = 0; jumpAngle = 0; groundY = 0;
    }

    public float getScale() { return shrunk ? 0.5f : 1.0f; }

    public float update(float dt, float gameSpeed, long frameCount, Runnable onLand) {
        if (shrunk   && frameCount > shrinkEnd) shrunk   = false;
        if (shielded && frameCount > shieldEnd) shielded = false;

        // Rơi tự do khi platform biến mất dưới chân
        if (!jumping && playerY > groundY + 0.01f) {
            jumping = true;
            velY    = 0f;
        }

        // ── Horizontal smooth ──
        float prevX   = playerX;
        float targetX = targetLane * LANE_GAP;
        playerX += (targetX - playerX) * (0.18f + gameSpeed * 0.3f);

        // ── Jump & flip ──
        if (jumping) {
            velY      += GRAVITY;
            playerY   += velY;
            jumpAngle += dt * JUMP_SPIN_SPEED;
            if (playerY <= groundY) {
                playerY = groundY; jumping = false; velY = 0f; jumpAngle = 0f;
                if (pendingLane != 0) {
                    targetLane  = Math.max(-1, Math.min(1, targetLane + pendingLane));
                    pendingLane = 0;
                }
                if (onLand != null) onLand.run();
            }
        } else {
            jumpAngle = 0f;
        }

        // ── Slide ──
        if (sliding) {
            slideTimer -= dt;
            if (slideTimer <= 0) sliding = false;
        }

        return playerX - prevX;
    }

    public void startJump() {
        jumping = true;
        sliding = false;
        velY    = JUMP_POWER;
    }

    public void startSlide() {
        sliding    = true;
        slideTimer = SLIDE_DURATION;
    }

    public void handleLaneInput(int delta) {
        int next = targetLane + delta;
        if (next >= -1 && next <= 1) {
            targetLane = next;
        } else {
            pendingLane = delta;
        }
    }
}
