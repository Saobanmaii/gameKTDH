package org.example;

public class Player {

    // ── Constants ─────────────────────────────────────────────────────────────
    public static final float LANE_GAP      = 2.2f;
    public static final float GRAVITY       = -0.022f;
    public static final float JUMP_POWER    = 0.38f;
    public static final float SLIDE_DURATION = 0.7f;

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

    // ── Death shear factor (biến dạng khi chết) ───────────────────────────────
    public float deathShear = 0f;

    // ─────────────────────────────────────────────────────────────────────────

    public void reset() {
        playerX = 0; playerY = 0; playerZ = 0;
        velY = 0; targetLane = 0;
        jumping = false; sliding = false; slideTimer = 0;
        shrunk = false; shielded = false;
        pendingLane = 0; deathShear = 0;
    }

    public float getScale() { return shrunk ? 0.5f : 1.0f; }

    /**
     * Cập nhật vị trí, jump, slide mỗi frame.
     * @param dt        delta time (giây)
     * @param gameSpeed tốc độ hiện tại
     * @param onLand    callback khi đáp xuống đất
     */
    public float update(float dt, float gameSpeed, Runnable onLand) {
        long ms = System.currentTimeMillis();
        if (shrunk   && ms > shrinkEnd)  shrunk   = false;
        if (shielded && ms > shieldEnd)  shielded = false;

        // ── Horizontal smooth ──
        float prevX = playerX;
        float targetX = targetLane * LANE_GAP;
        playerX += (targetX - playerX) * (0.18f + gameSpeed * 0.3f);

        // ── Jump ──
        if (jumping) {
            velY    += GRAVITY;
            playerY += velY;
            if (playerY <= 0f) {
                playerY = 0f; jumping = false; velY = 0f;
                if (pendingLane != 0) {
                    targetLane = Math.max(-1, Math.min(1, targetLane + pendingLane));
                    pendingLane = 0;
                }
                if (onLand != null) onLand.run();
            }
        }

        // ── Slide ──
        if (sliding) {
            slideTimer -= dt;
            if (slideTimer <= 0) sliding = false;
        }

        return playerX - prevX; // lateral delta for trail
    }

    public void startJump() {
        jumping = true;
        sliding = false;
        velY    = JUMP_POWER;
    }

    public void startSlide() {
        sliding   = true;
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
