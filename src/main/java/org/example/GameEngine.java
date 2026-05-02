package org.example;

import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWKeyCallback;

import java.util.Random;

import static org.lwjgl.glfw.Callbacks.glfwFreeCallbacks;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryUtil.NULL;

public class GameEngine {

    // ── Window ────────────────────────────────────────────────────────────────
    private static final int WIN_W = 900, WIN_H = 600;
    private long window;

    // ── State machine ─────────────────────────────────────────────────────────
    private enum State { READY, PLAYING, DEAD }
    private State state = State.READY;

    // ── Game constants ────────────────────────────────────────────────────────
    private static final float BASE_SPEED  = 0.18f;
    private static final float MAX_SPEED   = 0.70f;
    private static final float ACCEL       = 0.00008f;
    private static final int   TILE_COUNT  = 8;
    private static final float TILE_LENGTH = 20f;

    // ── Game data ─────────────────────────────────────────────────────────────
    private int   score      = 0;
    private int   hiScore    = 0;
    private float gameSpeed  = BASE_SPEED;
    private long  frameCount = 0;

    private float deathTimer  = 0f;
    private float cameraShake = 0f;

    private final float[] tileZ = new float[TILE_COUNT];

    // ── Sub-systems ───────────────────────────────────────────────────────────
    private final Random        rng     = new Random();
    private final Player        player  = new Player();
    private final EntityManager em      = new EntityManager(rng);
    private final Renderer      renderer = new Renderer();

    // ── HUD state ─────────────────────────────────────────────────────────────
    private boolean readyShown    = false;
    private boolean gameOverShown = false;
    private int     lastHudScore  = -1;

    // =========================================================================
    //  ENTRY POINT
    // =========================================================================
    public void run() {
        initWindow();
        renderer.init();
        initRoad();
        gameLoop();
        cleanup();
    }

    // =========================================================================
    //  INIT
    // =========================================================================
    private void initWindow() {
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) throw new IllegalStateException("GLFW init failed");

        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_SAMPLES, 4);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);
        glfwWindowHint(GLFW_DEPTH_BITS, 24);

        window = glfwCreateWindow(WIN_W, WIN_H, "Subway Runner 3D — KTDH", NULL, NULL);
        if (window == NULL) throw new RuntimeException("Window creation failed");

        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);

        // Centre window
        long mon = glfwGetPrimaryMonitor();
        int[] mx = new int[1], my = new int[1], mw = new int[1], mh = new int[1];
        glfwGetMonitorWorkarea(mon, mx, my, mw, mh);
        glfwSetWindowPos(window, mx[0] + (mw[0] - WIN_W) / 2, my[0] + (mh[0] - WIN_H) / 2);

        glfwSetKeyCallback(window, new GLFWKeyCallback() {
            @Override
            public void invoke(long win, int key, int sc, int action, int mods) {
                if (action != GLFW_PRESS) return;
                if (key == GLFW_KEY_ESCAPE) { glfwSetWindowShouldClose(win, true); return; }

                if (state == State.READY || state == State.DEAD) {
                    if (key == GLFW_KEY_SPACE || key == GLFW_KEY_ENTER) startGame();
                    return;
                }

                // In-game controls
                if (key == GLFW_KEY_A || key == GLFW_KEY_LEFT)  player.handleLaneInput(-1);
                else if (key == GLFW_KEY_D || key == GLFW_KEY_RIGHT) player.handleLaneInput(1);
                else if ((key == GLFW_KEY_SPACE || key == GLFW_KEY_UP || key == GLFW_KEY_W) && !player.jumping) {
                    player.startJump();
                    em.spawnJumpParticles(player.playerX, player.playerY, player.playerZ);
                } else if ((key == GLFW_KEY_S || key == GLFW_KEY_DOWN) && !player.jumping && !player.sliding) {
                    player.startSlide();
                }
            }
        });

        glfwShowWindow(window);
    }

    private void initRoad() {
        for (int i = 0; i < TILE_COUNT; i++)
            tileZ[i] = -i * TILE_LENGTH;
    }

    // =========================================================================
    //  GAME START / RESET
    // =========================================================================
    private void startGame() {
        state      = State.PLAYING;
        score      = 0;
        gameSpeed  = BASE_SPEED;
        frameCount = 0;
        deathTimer = 0; cameraShake = 0;
        readyShown = false; gameOverShown = false; lastHudScore = -1;
        player.reset();
        em.clear();
        initRoad();
        for (int i = 1; i <= 14; i++) em.spawnWave(-i * 12f);
        System.out.println("=== GAME START ===");
        System.out.println("A/D = lane  |  SPACE/W = jump  |  S = slide  |  ESC = quit");
    }

    // =========================================================================
    //  GAME LOOP
    // =========================================================================
    private double prevTime = 0;

    private void gameLoop() {
        prevTime = glfwGetTime();
        while (!glfwWindowShouldClose(window)) {
            double now = glfwGetTime();
            float  dt  = Math.min((float)(now - prevTime), 0.05f);
            prevTime   = now;

            glfwPollEvents();
            update(dt, (float) now);
            renderer.renderFrame((float) now, gameSpeed, tileZ,
                    player, em, state == State.DEAD, deathTimer, cameraShake, rng);
            printHUD();
            glfwSwapBuffers(window);
        }
    }

    // =========================================================================
    //  UPDATE
    // =========================================================================
    private void update(float dt, float time) {
        if (state == State.DEAD) {
            deathTimer  += dt;
            cameraShake  = Math.max(0, cameraShake - dt * 3f);
            player.deathShear = Math.min(deathTimer * 0.8f, 1.5f);
            em.updateParticles(dt);
            em.updateTrails(dt);
            return;
        }
        if (state != State.PLAYING) return;

        frameCount++;
        if (frameCount % 6 == 0) score++;

        gameSpeed = Math.min(MAX_SPEED, BASE_SPEED + frameCount * ACCEL);

        // Player movement (returns lateral delta for trail)
        float lateralDelta = player.update(dt, gameSpeed, frameCount,
                () -> em.spawnLandParticles(player.playerX, player.playerY, player.playerZ));

        // Trail
        if (player.jumping || Math.abs(lateralDelta) > 0.02f) {
            if (frameCount % 2 == 0) {
                float sx = 1.0f + lateralDelta * -3f;
                em.addTrail(player.playerX, player.playerY, player.playerZ, sx, player.getScale());
            }
        }
        em.updateTrails(dt);

        // Road tile recycling
        for (int i = 0; i < TILE_COUNT; i++) {
            tileZ[i] += gameSpeed;
            if (tileZ[i] > 10f) {
                tileZ[i] -= TILE_COUNT * TILE_LENGTH;
                int numWaves = 2 + rng.nextInt(2); // 2 hoặc 3 waves mỗi tile
                for (int w = 0; w < numWaves; w++) {
                    em.spawnWave(tileZ[i] - TILE_LENGTH * (0.15f + w * (0.70f / numWaves)));
                }
            }
        }

        // Entities
        em.updateObstacles(gameSpeed, player, frameCount,
                () -> em.spawnShieldBreak(player.playerX, player.playerY, player.playerZ),
                () -> triggerDeath());
        em.updatePickups(gameSpeed, time, player, delta -> score += delta, frameCount);
        em.updateParticles(dt);
    }

    private void triggerDeath() {
        if (state == State.DEAD) return;
        state       = State.DEAD;
        cameraShake = 1.0f;
        if (score > hiScore) hiScore = score;
        em.spawnDeathExplosion(player.playerX, player.playerY, player.playerZ, rng);
        em.spawnShatterFragments(player.playerX, player.playerY, player.playerZ, player.getScale());
        System.out.println("=== GAME OVER ===  Score: " + score + "  Hi: " + hiScore);
    }

    // =========================================================================
    //  HUD (console) — in 1 lần mỗi event, không spam mỗi frame
    // =========================================================================
    private void printHUD() {
        if (state == State.READY && !readyShown) {
            readyShown = true;
            System.out.println("╔══════════════════════════════════╗");
            System.out.println("║     SUBWAY RUNNER 3D — KTDH      ║");
            System.out.println("║  Nhấn SPACE hoặc ENTER để bắt đầu║");
            System.out.println("╚══════════════════════════════════╝");
        }
        if (state == State.PLAYING && score != lastHudScore && score % 50 == 0) {
            lastHudScore = score;
            System.out.printf("[SCORE] %d  |  Speed: %.2f  |  %s%s%s%n",
                    score, gameSpeed,
                    player.shielded   ? "[SHIELD] " : "",
                    player.shrunk     ? "[SHRINK] " : "",
                    player.magnetized ? "[MAGNET] " : "");
        }
        if (state == State.DEAD && !gameOverShown) {
            gameOverShown = true;
            System.out.println("╔══════════════════════╗");
            System.out.println("║      GAME  OVER      ║");
            System.out.printf( "║  Score  : %6d      ║%n", score);
            System.out.printf( "║  HiScore: %6d      ║%n", hiScore);
            System.out.println("║  [SPACE] để chơi lại ║");
            System.out.println("╚══════════════════════╝");
        }
    }

    // =========================================================================
    //  CLEANUP
    // =========================================================================
    private void cleanup() {
        renderer.cleanup();
        glfwFreeCallbacks(window);
        glfwDestroyWindow(window);
        glfwTerminate();
    }
}
