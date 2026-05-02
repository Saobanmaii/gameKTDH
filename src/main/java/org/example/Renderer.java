package org.example;

import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

public class Renderer {

    // ── Window ────────────────────────────────────────────────────────────────
    private static final int WIN_W = 900, WIN_H = 600;

    // ── OpenGL handles ────────────────────────────────────────────────────────
    private int shaderProgram;
    private int vaoBox, vboBox, eboBox;
    private int vaoPlane, vboPlane, eboPlane;

    // ── Uniform locations ─────────────────────────────────────────────────────
    private int uniProj, uniView, uniModel, uniColor, uniAlpha, uniEmissive, uniCamPos;

    // ── Reused buffers ────────────────────────────────────────────────────────
    private final FloatBuffer matBuf = BufferUtils.createFloatBuffer(16);
    private final Matrix4f proj  = new Matrix4f();
    private final Matrix4f view  = new Matrix4f();
    private final Matrix4f model = new Matrix4f();

    // ── Constants ─────────────────────────────────────────────────────────────
    private static final float LANE_GAP    = 2.2f;
    private static final float TILE_LENGTH = 20f;
    private static final float BASE_SPEED  = 0.18f;
    private static final float MAX_SPEED   = 0.70f;

    // Camera
    private static final float FOV_BASE           = 58f;
    private static final float FOV_RANGE          = 20f;
    private static final float NEAR_PLANE         = 0.05f;
    private static final float FAR_PLANE          = 200f;
    private static final float CAM_FOLLOW_X       = 0.08f;
    private static final float CAM_Y              = 3.2f;
    private static final float CAM_Z              = 5.5f;
    private static final float CAM_TARGET_X_SCALE = 0.04f;
    private static final float CAM_TARGET_Y       = 0.3f;
    private static final float CAM_TARGET_Z       = -8f;
    private static final float SHAKE_SCALE_X      = 0.4f;
    private static final float SHAKE_SCALE_Y      = 0.25f;

    // Player rendering
    private static final float DEATH_FADE_THRESHOLD = 0.18f;
    private static final float PLAYER_SHIELD_SCALE  = 1.75f;

    // 3D Shear algorithm constants (Transform3D)
    private static final float WIDE_SHEAR          = 0.06f;  // shearXZ cho wide obstacle
    private static final float BUILDING_SHEAR_MAX  = 0.09f;  // shearZY max cho tòa nhà

    // =========================================================================
    //  INIT
    // =========================================================================
    public void init() {
        GL.createCapabilities();
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glEnable(0x809D); // GL_MULTISAMPLE

        String vert =
            "#version 330 core\n" +
            "layout(location=0) in vec3 aPos;\n" +
            "layout(location=1) in vec3 aNorm;\n" +
            "uniform mat4 proj, view, model;\n" +
            "out vec3 fragPos;\n" +
            "out vec3 fragNormal;\n" +
            "void main(){\n" +
            "  vec4 wp = model * vec4(aPos,1.0);\n" +
            "  fragPos = wp.xyz;\n" +
            "  // Normal matrix = transpose(inverse(model)) — approx with mat3(model) for uniform scale\n" +
            "  mat3 normalMat = transpose(inverse(mat3(model)));\n" +
            "  fragNormal = normalize(normalMat * aNorm);\n" +
            "  gl_Position = proj * view * wp;\n" +
            "}";

        String frag =
            "#version 330 core\n" +
            "in vec3 fragPos;\n" +
            "in vec3 fragNormal;\n" +
            "out vec4 FragColor;\n" +
            "uniform vec3  color;\n" +
            "uniform float alpha;\n" +
            "uniform float emissive;\n" +
            "uniform vec3  camPos;\n" +
            "void main(){\n" +
            "  vec3 skyColor = vec3(0.02, 0.02, 0.10);\n" +
            "  vec3 lightDir = normalize(vec3(0.5, 2.0, 1.0));\n" +
            "  vec3 norm     = normalize(fragNormal);\n" +
            "  // Ambient\n" +
            "  float ambient = 0.28;\n" +
            "  // Diffuse (Lambertian)\n" +
            "  float diff = max(dot(norm, lightDir), 0.0);\n" +
            "  // Specular (Blinn-Phong)\n" +
            "  vec3 viewDir  = normalize(camPos - fragPos);\n" +
            "  vec3 halfDir  = normalize(lightDir + viewDir);\n" +
            "  float spec    = pow(max(dot(norm, halfDir), 0.0), 48.0) * 0.45;\n" +
            "  vec3 lit = color * (ambient + diff) + vec3(spec);\n" +
            "  vec3 final_color = mix(lit, color, emissive);\n" +
            "  // Distance fog\n" +
            "  float dist = length(fragPos - camPos);\n" +
            "  float fog  = clamp((dist - 28.0) / 55.0, 0.0, 0.82);\n" +
            "  final_color = mix(final_color, skyColor, fog);\n" +
            "  FragColor = vec4(final_color, alpha);\n" +
            "}";

        shaderProgram = ShaderProgram.compile(vert, frag);
        uniProj     = glGetUniformLocation(shaderProgram, "proj");
        uniView     = glGetUniformLocation(shaderProgram, "view");
        uniModel    = glGetUniformLocation(shaderProgram, "model");
        uniColor    = glGetUniformLocation(shaderProgram, "color");
        uniAlpha    = glGetUniformLocation(shaderProgram, "alpha");
        uniEmissive = glGetUniformLocation(shaderProgram, "emissive");
        uniCamPos   = glGetUniformLocation(shaderProgram, "camPos");

        buildGeometry();
    }

    // ── Build cube with per-face normals (24 verts × [pos+norm] = 24×6 floats) ──
    private void buildGeometry() {
        // Each face: 4 vertices, each = (x,y,z, nx,ny,nz)
        float[] cv = {
            // +Z face  normal (0,0,1)
            -0.5f,-0.5f, 0.5f,  0,0,1,
             0.5f,-0.5f, 0.5f,  0,0,1,
             0.5f, 0.5f, 0.5f,  0,0,1,
            -0.5f, 0.5f, 0.5f,  0,0,1,
            // -Z face  normal (0,0,-1)
             0.5f,-0.5f,-0.5f,  0,0,-1,
            -0.5f,-0.5f,-0.5f,  0,0,-1,
            -0.5f, 0.5f,-0.5f,  0,0,-1,
             0.5f, 0.5f,-0.5f,  0,0,-1,
            // +X face  normal (1,0,0)
             0.5f,-0.5f, 0.5f,  1,0,0,
             0.5f,-0.5f,-0.5f,  1,0,0,
             0.5f, 0.5f,-0.5f,  1,0,0,
             0.5f, 0.5f, 0.5f,  1,0,0,
            // -X face  normal (-1,0,0)
            -0.5f,-0.5f,-0.5f, -1,0,0,
            -0.5f,-0.5f, 0.5f, -1,0,0,
            -0.5f, 0.5f, 0.5f, -1,0,0,
            -0.5f, 0.5f,-0.5f, -1,0,0,
            // +Y face  normal (0,1,0)
            -0.5f, 0.5f, 0.5f,  0,1,0,
             0.5f, 0.5f, 0.5f,  0,1,0,
             0.5f, 0.5f,-0.5f,  0,1,0,
            -0.5f, 0.5f,-0.5f,  0,1,0,
            // -Y face  normal (0,-1,0)
            -0.5f,-0.5f,-0.5f,  0,-1,0,
             0.5f,-0.5f,-0.5f,  0,-1,0,
             0.5f,-0.5f, 0.5f,  0,-1,0,
            -0.5f,-0.5f, 0.5f,  0,-1,0
        };
        // 6 faces × 2 tri × 3 verts
        int[] ci = new int[36];
        for (int f = 0; f < 6; f++) {
            int base = f * 4;
            int i    = f * 6;
            ci[i]   = base;   ci[i+1] = base+1; ci[i+2] = base+2;
            ci[i+3] = base+2; ci[i+4] = base+3; ci[i+5] = base;
        }
        int[] ids = buildVAO(cv, ci, true);
        vaoBox = ids[0]; vboBox = ids[1]; eboBox = ids[2];

        // Flat quad (no normals needed — only used for road plane)
        float[] pv = {
            -0.5f,0,-0.5f,  0,1,0,
             0.5f,0,-0.5f,  0,1,0,
             0.5f,0, 0.5f,  0,1,0,
            -0.5f,0, 0.5f,  0,1,0
        };
        int[] pi = { 0,1,2, 2,3,0 };
        int[] pids = buildVAO(pv, pi, true);
        vaoPlane = pids[0]; vboPlane = pids[1]; eboPlane = pids[2];
    }

    private int[] buildVAO(float[] verts, int[] inds, boolean hasNormals) {
        int vao = glGenVertexArrays();
        int vbo = glGenBuffers();
        int ebo = glGenBuffers();
        glBindVertexArray(vao);

        FloatBuffer vb = BufferUtils.createFloatBuffer(verts.length);
        vb.put(verts).flip();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, vb, GL_STATIC_DRAW);

        IntBuffer ib = BufferUtils.createIntBuffer(inds.length);
        ib.put(inds).flip();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, ib, GL_STATIC_DRAW);

        int stride = hasNormals ? 24 : 12;
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0);
        glEnableVertexAttribArray(0);
        if (hasNormals) {
            glVertexAttribPointer(1, 3, GL_FLOAT, false, stride, 12);
            glEnableVertexAttribArray(1);
        }
        glBindVertexArray(0);
        return new int[]{vao, vbo, ebo};
    }

    // =========================================================================
    //  RENDER FRAME
    // =========================================================================
    public void renderFrame(float time, float gameSpeed, float[] tileZ,
                            Player player, EntityManager em,
                            boolean isDead, float deathTimer, float cameraShake,
                            java.util.Random rng) {
        glClearColor(0.02f, 0.02f, 0.10f, 1f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glUseProgram(shaderProgram);

        // ── Camera ────────────────────────────────────────────────────────────
        float shakeX = cameraShake * (rng.nextFloat() - 0.5f) * SHAKE_SCALE_X;
        float shakeY = cameraShake * (rng.nextFloat() - 0.5f) * SHAKE_SCALE_Y;
        float fov    = FOV_BASE + (gameSpeed - BASE_SPEED) / (MAX_SPEED - BASE_SPEED) * FOV_RANGE;
        proj.identity().perspective((float) Math.toRadians(fov), (float) WIN_W / WIN_H, NEAR_PLANE, FAR_PLANE);

        float camX = player.playerX * CAM_FOLLOW_X + shakeX;
        float camY = CAM_Y + shakeY;
        float camZ = CAM_Z;
        view.identity().lookAt(camX, camY, camZ,
                player.playerX * CAM_TARGET_X_SCALE, CAM_TARGET_Y, CAM_TARGET_Z, 0, 1, 0);

        glUniformMatrix4fv(uniProj, false, proj.get(matBuf));
        glUniformMatrix4fv(uniView, false, view.get(matBuf));
        glUniform3f(uniCamPos, camX, camY, camZ);

        // ── Render order ──────────────────────────────────────────────────────
        drawRoad(time, tileZ);
        drawObstacles(em, time, gameSpeed);
        drawPickups(em, time);
        drawTrails(em);
        drawPlayer(player, time, gameSpeed, isDead, deathTimer);
        drawParticles(em);
        drawBuildings(time, tileZ, gameSpeed);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  ROAD
    // ─────────────────────────────────────────────────────────────────────────
    private void drawRoad(float time, float[] tileZ) {
        // Road surface (use plane VAO for flat look)
        glBindVertexArray(vaoPlane);
        setColor(0.10f, 0.10f, 0.14f, 1f, 0f);
        for (float z : tileZ) {
            setModel(Transform3D.ts(0, -0.50f, z, LANE_GAP * 3.2f, 1f, TILE_LENGTH));
            glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0);
        }

        // Lane dividers — cyan glow, pulsing
        glBindVertexArray(vaoBox);
        float pulse = 0.6f + (float) Math.sin(time * 3f) * 0.25f;
        setColor(0f, 0.9f, 0.85f, pulse, 0.7f);
        for (float z : tileZ) {
            for (int d = 0; d < 6; d++) {
                float dz = z - d * (TILE_LENGTH / 6f);
                setModel(Transform3D.ts( LANE_GAP * 0.5f, -0.445f, dz, 0.06f, 0.025f, 1.3f));
                drawBox36();
                setModel(Transform3D.ts(-LANE_GAP * 0.5f, -0.445f, dz, 0.06f, 0.025f, 1.3f));
                drawBox36();
            }
        }
        // Center stripe (bright white-blue)
        setColor(0.5f, 0.6f, 1.0f, 0.4f, 0.5f);
        for (float z : tileZ) {
            setModel(Transform3D.ts(0, -0.448f, z, 0.12f, 0.02f, TILE_LENGTH));
            drawBox36();
        }
        // Road edges — orange-red emissive
        setColor(0.85f, 0.35f, 0.05f, 1f, 0.45f);
        for (float z : tileZ) {
            setModel(Transform3D.ts( LANE_GAP * 1.6f, -0.32f, z, 0.18f, 0.22f, TILE_LENGTH));
            drawBox36();
            setModel(Transform3D.ts(-LANE_GAP * 1.6f, -0.32f, z, 0.18f, 0.22f, TILE_LENGTH));
            drawBox36();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  OBSTACLES
    // ─────────────────────────────────────────────────────────────────────────
    private void drawObstacles(EntityManager em, float time, float gameSpeed) {
        glBindVertexArray(vaoBox);
        float pulse = 0.45f + (float) Math.sin(time * 7f) * 0.25f;

        // Biến dạng shear cho obstacle wide blocker (shape 2)
        Matrix4f shearWide = Transform3D.shearXZ(WIDE_SHEAR);

        for (Obstacle o : em.obstacles) {
            switch (o.shape) {
                case 0: { // tall wall — hai trụ bên + tấm giữa
                    setColor(0.95f, 0.12f, 0.08f, 1f, pulse);
                    // Thân chính
                    Matrix4f t = Transform3D.translation(o.x, o.h() * 0.5f - 0.5f, o.z);
                    Matrix4f s = Transform3D.scale(o.w(), o.h(), o.d());
                    setModel(new Matrix4f(t).mul(s));
                    drawBox36();
                    // Stripe cam trên đỉnh
                    setColor(1f, 0.55f, 0.05f, 1f, 1.0f);
                    setModel(Transform3D.ts(o.x, o.h() - 0.5f, o.z, o.w() + 0.08f, 0.13f, o.d() + 0.08f));
                    drawBox36();
                    // Trụ bên trái
                    setColor(0.7f, 0.08f, 0.05f, 1f, 0.3f);
                    setModel(Transform3D.ts(o.x - o.w() * 0.45f, o.h() * 0.5f - 0.5f, o.z, 0.12f, o.h() + 0.1f, o.d() + 0.1f));
                    drawBox36();
                    // Trụ bên phải
                    setModel(Transform3D.ts(o.x + o.w() * 0.45f, o.h() * 0.5f - 0.5f, o.z, 0.12f, o.h() + 0.1f, o.d() + 0.1f));
                    drawBox36();
                    break;
                }
                case 1: { // low barrier — kẻ vàng-đen
                    setColor(0.85f, 0.28f, 0.04f, 1f, pulse * 0.6f);
                    setModel(Transform3D.ts(o.x, -0.25f, o.z, o.w(), o.h(), o.d()));
                    drawBox36();
                    // Warning stripe (vàng)
                    setColor(1f, 0.9f, 0.05f, 1f, 1.0f);
                    setModel(Transform3D.ts(o.x, -0.25f + o.h() * 0.5f, o.z, o.w() + 0.06f, 0.09f, o.d() + 0.06f));
                    drawBox36();
                    // Stripe đen xen kẽ
                    setColor(0.05f, 0.05f, 0.05f, 0.9f, 0f);
                    for (int s = 0; s < 3; s++) {
                        float sx = o.x - o.w() * 0.3f + s * o.w() * 0.3f;
                        setModel(Transform3D.ts(sx, -0.25f, o.z - 0.01f, 0.12f, o.h() - 0.02f, o.d() + 0.04f));
                        drawBox36();
                    }
                    break;
                }
                case 2: { // wide blocker — áp dụng PHÉP BIẾN DẠNG shearXZ
                    setColor(0.75f, 0.08f, 0.8f, 1f, pulse);
                    Matrix4f t = Transform3D.translation(o.x, 0.5f, o.z);
                    Matrix4f s = Transform3D.scale(o.w(), 0.38f, o.d());
                    setModel(Transform3D.compose(t, new Matrix4f(), s, shearWide));
                    drawBox36();
                    setColor(1f, 0.3f, 1f, 0.5f, 1f);
                    setModel(Transform3D.compose(
                            Transform3D.translation(o.x, 0.5f, o.z),
                            new Matrix4f(),
                            Transform3D.scale(o.w() + 0.12f, 0.5f, o.d() + 0.12f),
                            shearWide));
                    drawBox36();
                    break;
                }
                case 3: { // platform — tấm nhảy xanh lá, đứng trên mặt đất
                    setColor(0.05f, 0.80f, 0.45f, 1f, 0.15f);
                    setModel(Transform3D.ts(o.x, o.h() * 0.5f, o.z, o.w(), o.h(), o.d()));
                    drawBox36();
                    // Glow viền
                    setColor(0.2f, 1f, 0.6f, 0.5f, 1f);
                    setModel(Transform3D.ts(o.x, o.h() * 0.5f, o.z, o.w() + 0.10f, o.h() + 0.06f, o.d() + 0.10f));
                    drawBox36();
                    // Stripe trên mặt
                    setColor(0.8f, 1f, 0.3f, 0.9f, 1f);
                    setModel(Transform3D.ts(o.x, o.h() + 0.01f, o.z, o.w() * 0.7f, 0.04f, o.d() * 0.7f));
                    drawBox36();
                    break;
                }
                case 4: { // low arch — thanh ngang đỏ cam, player PHẢI slide
                    // Thanh ngang chính (ở độ cao đầu người)
                    setColor(0.95f, 0.25f, 0.0f, 1f, pulse);
                    setModel(Transform3D.ts(o.x, o.y + o.h() * 0.5f, o.z, o.w(), o.h(), o.d()));
                    drawBox36();
                    // Glow đỏ cam
                    setColor(1f, 0.5f, 0.05f, 0.4f, 1f);
                    setModel(Transform3D.ts(o.x, o.y + o.h() * 0.5f, o.z, o.w() + 0.14f, o.h() + 0.12f, o.d() + 0.10f));
                    drawBox36();
                    // Cột trụ trái
                    setColor(0.7f, 0.15f, 0.0f, 1f, 0.25f);
                    setModel(Transform3D.ts(o.x - o.w() * 0.46f, o.y * 0.5f, o.z, 0.14f, o.y, o.d()));
                    drawBox36();
                    // Cột trụ phải
                    setModel(Transform3D.ts(o.x + o.w() * 0.46f, o.y * 0.5f, o.z, 0.14f, o.y, o.d()));
                    drawBox36();
                    // Warning stripe vàng trên thanh
                    setColor(1f, 0.92f, 0.0f, 1f, 1f);
                    for (int s = 0; s < 5; s++) {
                        float sx = o.x - o.w() * 0.4f + s * (o.w() * 0.8f / 4f);
                        setModel(Transform3D.ts(sx, o.y + o.h() * 0.5f, o.z - 0.01f, 0.2f, o.h() * 0.85f, o.d() + 0.04f));
                        drawBox36();
                    }
                    break;
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  PICKUPS
    // ─────────────────────────────────────────────────────────────────────────
    private void drawPickups(EntityManager em, float time) {
        glBindVertexArray(vaoBox);
        for (Pickup p : em.pickups) {
            float spin = time * 3.5f + p.bobPhase;
            switch (p.type) {
                case 0: { // coin — flat disc quay nhanh
                    setColor(1f, 0.85f, 0.0f, 1f, 1.0f);
                    model.identity().translate(p.x, p.y, p.z)
                            .rotate(spin, 0, 1, 0)
                            .scale(0.36f, 0.36f, 0.055f);
                    setModel(model);
                    drawBox36();
                    // Inner ring (glow nhỏ hơn)
                    setColor(1f, 1f, 0.5f, 0.7f, 1f);
                    model.identity().translate(p.x, p.y, p.z)
                            .rotate(spin + 0.5f, 0, 1, 0)
                            .scale(0.22f, 0.22f, 0.065f);
                    setModel(model);
                    drawBox36();
                    break;
                }
                case 1: { // shrink — hộp xanh dương + outer glow
                    setColor(0.2f, 0.75f, 1.0f, 1f, 0.85f);
                    model.identity().translate(p.x, p.y, p.z)
                            .rotate(spin * 0.5f, 0, 1, 0)
                            .scale(0.38f, 0.55f, 0.38f);
                    setModel(model);
                    drawBox36();
                    setColor(0.4f, 0.9f, 1f, 0.28f, 1f);
                    model.identity().translate(p.x, p.y, p.z)
                            .rotate(spin * 0.5f, 0, 1, 0)
                            .scale(0.58f, 0.75f, 0.58f);
                    setModel(model);
                    drawBox36();
                    break;
                }
                case 2: { // shield — vàng kim + glow ngoài
                    setColor(1f, 0.88f, 0.08f, 1f, 0.95f);
                    model.identity().translate(p.x, p.y, p.z)
                            .rotate(spin * 0.7f, 1, 1, 0)
                            .scale(0.45f, 0.45f, 0.45f);
                    setModel(model);
                    drawBox36();
                    setColor(1f, 1f, 0.4f, 0.3f, 1f);
                    model.identity().translate(p.x, p.y, p.z)
                            .rotate(spin * 0.7f, 1, 1, 0)
                            .scale(0.68f, 0.68f, 0.68f);
                    setModel(model);
                    drawBox36();
                    break;
                }
                case 3: { // magnet — chữ U (3 hộp) màu hồng
                    setColor(1f, 0.18f, 0.95f, 1f, 0.9f);
                    // Nhánh trái
                    model.identity().translate(p.x - 0.17f, p.y, p.z)
                            .rotate(spin * 0.8f, 0, 0, 1)
                            .scale(0.14f, 0.48f, 0.18f);
                    setModel(model);
                    drawBox36();
                    // Nhánh phải
                    model.identity().translate(p.x + 0.17f, p.y, p.z)
                            .rotate(spin * 0.8f, 0, 0, 1)
                            .scale(0.14f, 0.48f, 0.18f);
                    setModel(model);
                    drawBox36();
                    // Đế ngang
                    model.identity().translate(p.x, p.y - 0.17f, p.z)
                            .rotate(spin * 0.8f, 0, 0, 1)
                            .scale(0.48f, 0.14f, 0.18f);
                    setModel(model);
                    drawBox36();
                    break;
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  PLAYER
    // ─────────────────────────────────────────────────────────────────────────
    private void drawPlayer(Player player, float time, float gameSpeed,
                             boolean isDead, float deathTimer) {
        if (isDead && deathTimer > DEATH_FADE_THRESHOLD) return;

        float scale   = player.getScale();
        float lean    = (player.playerX - player.targetLane * Player.LANE_GAP) * -0.18f;
        float squashY = player.jumping ? (1f + player.velY * 0.15f) : 1f;
        float bodyH   = player.sliding ? 0.45f : 1.0f;
        float bodyY   = player.playerY + (player.sliding ? -0.25f : 0f) * scale;

        // Biến dạng khi chết (phép biến dạng shearYX)
        Matrix4f deathShearMat = isDead
                ? Transform3D.shearYX(player.deathShear)
                : new Matrix4f();

        glBindVertexArray(vaoBox);

        // Shield glow
        if (player.shielded) {
            float glow = 0.5f + (float) Math.sin(time * 9f) * 0.32f;
            setColor(1f, 0.9f, 0.15f, glow * 0.3f, 1f);
            Matrix4f t = Transform3D.translation(player.playerX, player.playerY + 0.55f * scale, player.playerZ);
            Matrix4f s = Transform3D.uniformScale(scale * PLAYER_SHIELD_SCALE);
            setModel(new Matrix4f(t).mul(s));
            drawBox36();
        }

        // Shrunk aura — nhấp nháy cyan nhanh, rõ hơn shield
        if (player.shrunk) {
            float glow = 0.55f + (float) Math.sin(time * 14f) * 0.35f;
            setColor(0.0f, 0.95f, 1f, glow * 0.45f, 1f);
            Matrix4f t = Transform3D.translation(player.playerX, player.playerY + 0.55f * scale, player.playerZ);
            // Aura nhỏ hơn shield vì player đang bé
            setModel(new Matrix4f(t).mul(Transform3D.uniformScale(scale * 2.2f)));
            drawBox36();
        }

        // Body
        boolean shrunk = player.shrunk;
        float br = shrunk ? 0.25f : 0.08f, bg = shrunk ? 0.95f : 0.9f, bb = shrunk ? 0.8f : 0.25f;
        setColor(br, bg, bb, 1f, 0.07f);
        {
            Matrix4f t = Transform3D.translation(player.playerX, bodyY + bodyH * 0.5f * scale, player.playerZ);
            Matrix4f r = new Matrix4f().rotate(lean, 0, 0, 1);
            Matrix4f s = Transform3D.scale(0.75f * scale, bodyH * scale * squashY, 0.6f * scale);
            setModel(Transform3D.compose(t, r, s, deathShearMat));
        }
        drawBox36();

        // Head
        setColor(0.97f, 0.78f, 0.52f, 1f, 0.06f);
        float headY = player.playerY + (bodyH + 0.48f) * scale + (player.sliding ? -0.25f * scale : 0);
        {
            Matrix4f t = Transform3D.translation(player.playerX, headY, player.playerZ);
            Matrix4f r = new Matrix4f().rotate(lean * 0.5f, 0, 0, 1);
            Matrix4f s = Transform3D.uniformScale(0.55f * scale);
            setModel(Transform3D.compose(t, r, s, deathShearMat));
        }
        drawBox36();

        // Hat (mũ)
        setColor(0.1f, 0.15f, 0.55f, 1f, 0.1f);
        {
            Matrix4f t = Transform3D.translation(player.playerX, headY + 0.32f * scale, player.playerZ);
            Matrix4f s = Transform3D.scale(0.52f * scale, 0.22f * scale, 0.52f * scale);
            setModel(new Matrix4f(t).mul(s).mul(deathShearMat));
        }
        drawBox36();

        // Legs (animated)
        float legSwing = player.jumping ? 0.4f
                : (float) Math.sin(time * 12f * gameSpeed / BASE_SPEED) * 0.32f;
        setColor(0.08f, 0.38f, 0.95f, 1f, 0f);
        for (int lr = -1; lr <= 1; lr += 2) {
            float legY = player.playerY + (player.sliding ? -0.1f : 0.18f) * scale;
            Matrix4f t = Transform3D.translation(player.playerX + lr * 0.22f * scale, legY, player.playerZ);
            Matrix4f r = new Matrix4f().rotate(lean + lr * legSwing, 1, 0, 0);
            Matrix4f s = Transform3D.scale(0.28f * scale, 0.5f * scale, 0.28f * scale);
            setModel(Transform3D.compose(t, r, s, deathShearMat));
            drawBox36();
        }

        // Arms (tay — swing ngược chiều leg)
        setColor(br, bg * 0.85f, bb, 1f, 0.04f);
        float armSwing = player.jumping ? -0.3f
                : (float) Math.sin(time * 4f + Math.PI) * 0.3f;
        for (int lr = -1; lr <= 1; lr += 2) {
            float armY = player.playerY + (bodyH * 0.7f) * scale + (player.sliding ? -0.25f * scale : 0);
            Matrix4f t = Transform3D.translation(player.playerX + lr * 0.48f * scale, armY, player.playerZ);
            Matrix4f r = new Matrix4f().rotate(lean + lr * (float) armSwing, 1, 0, 0);
            Matrix4f s = Transform3D.scale(0.22f * scale, 0.44f * scale, 0.22f * scale);
            setModel(Transform3D.compose(t, r, s, deathShearMat));
            drawBox36();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  TRAILS
    // ─────────────────────────────────────────────────────────────────────────
    private void drawTrails(EntityManager em) {
        glBindVertexArray(vaoBox);
        for (TrailSegment t : em.trails) {
            float lifeRatio = t.alpha / 0.55f;
            // Gradient: cyan → green → transparent
            setColor(0.1f + lifeRatio * 0.1f, 0.9f, 0.5f + lifeRatio * 0.3f, t.alpha, 0.65f);
            setModel(Transform3D.ts(t.x, t.y + 0.5f * t.sy, t.z, t.sx * 0.7f, t.sy, 0.55f));
            drawBox36();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  PARTICLES
    // ─────────────────────────────────────────────────────────────────────────
    private void drawParticles(EntityManager em) {
        glBindVertexArray(vaoBox);
        for (Particle p : em.particles) {
            float a = p.life / p.maxLife;
            if (p.scaleX > 0) {
                // Shard fragment: lit with slight emissive, tumbling rotation
                setColor(p.color.x, p.color.y, p.color.z, a, 0.10f);
                model.identity()
                     .translate(p.x, p.y, p.z)
                     .rotate(p.angle, 0.4f, 1f, 0.3f)
                     .scale(p.scaleX, p.scaleY, p.scaleZ);
            } else {
                setColor(p.color.x, p.color.y * a + 0.2f * (1 - a), p.color.z * a, a * 0.9f, 0.75f);
                model.identity().translate(p.x, p.y, p.z).scale(p.size, p.size, p.size);
            }
            glUniformMatrix4fv(uniModel, false, model.get(matBuf));
            drawBox36();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  BUILDINGS — áp dụng PHÉP BIẾN DẠNG shearZY theo tốc độ
    // ─────────────────────────────────────────────────────────────────────────
    private void drawBuildings(float time, float[] tileZ, float gameSpeed) {
        glBindVertexArray(vaoBox);
        float speedFactor = (gameSpeed - BASE_SPEED) / (MAX_SPEED - BASE_SPEED);
        // Biến dạng tòa nhà nghiêng về phía trước theo tốc độ
        Matrix4f buildingShear = Transform3D.shearZY(speedFactor * BUILDING_SHEAR_MAX);

        int N = 6;
        float spacing = 20f;

        // Màu palette neon cho tòa nhà
        float[][] palettes = {
            {0.08f, 0.08f, 0.22f},  // xanh đêm
            {0.12f, 0.06f, 0.20f},  // tím đêm
            {0.06f, 0.14f, 0.22f},  // xanh biển đêm
        };

        for (int i = 0; i < N; i++) {
            float baseZ    = -i * spacing;
            float scrolledZ = baseZ + tileZ[i % tileZ.length] * 0.3f;
            float bh       = 3f + (float)((i * 7 + 3) % 5);
            float[] pal    = palettes[i % palettes.length];

            for (int side = -1; side <= 1; side += 2) {
                float bx = side * (LANE_GAP * 2.2f + 1.8f + (i % 3) * 1.4f);
                float bw = 1.4f + (i % 2) * 0.5f;

                // Body tòa nhà với PHÉP BIẾN DẠNG
                setColor(pal[0], pal[1], pal[2], 1f, 0f);
                {
                    Matrix4f t = Transform3D.translation(bx, bh * 0.5f - 0.5f, scrolledZ);
                    Matrix4f s = Transform3D.scale(bw, bh, 2f);
                    setModel(Transform3D.compose(t, new Matrix4f(), s, buildingShear));
                }
                drawBox36();

                // Viền neon trên đỉnh
                float neonR = (i % 3 == 0) ? 0f : (i % 3 == 1) ? 1f : 0.1f;
                float neonG = (i % 3 == 0) ? 1f : (i % 3 == 1) ? 0.1f : 0.4f;
                float neonB = (i % 3 == 0) ? 0.8f : (i % 3 == 1) ? 0.9f : 1f;
                setColor(neonR, neonG, neonB, 0.9f, 1f);
                {
                    Matrix4f t = Transform3D.translation(bx, bh - 0.45f, scrolledZ);
                    Matrix4f s = Transform3D.scale(bw + 0.06f, 0.12f, 2.06f);
                    setModel(Transform3D.compose(t, new Matrix4f(), s, buildingShear));
                }
                drawBox36();

                // Cửa sổ glowing
                float wglow = 0.45f + (float) Math.sin(time * 0.7f + i * 1.3f) * 0.25f;
                for (int row = 0; row < (int) bh; row++) {
                    boolean warmWindow = (row + i) % 2 == 0;
                    if (warmWindow) setColor(1f,  0.85f, 0.4f, wglow * 0.85f, 0.95f);
                    else            setColor(0.3f, 0.8f,  1.0f, wglow * 0.7f,  0.9f);
                    {
                        Matrix4f t = Transform3D.translation(bx, row - 0.2f, scrolledZ - 0.06f);
                        Matrix4f s = Transform3D.scale(bw * 0.7f, 0.2f, 0.1f);
                        setModel(Transform3D.compose(t, new Matrix4f(), s, buildingShear));
                    }
                    drawBox36();
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  HELPERS
    // ─────────────────────────────────────────────────────────────────────────
    private void setColor(float r, float g, float b, float a, float emissive) {
        glUniform3f(uniColor, r, g, b);
        glUniform1f(uniAlpha, a);
        glUniform1f(uniEmissive, emissive);
    }

    private void setModel(Matrix4f m) {
        glUniformMatrix4fv(uniModel, false, m.get(matBuf));
    }

    private void drawBox36() {
        glDrawElements(GL_TRIANGLES, 36, GL_UNSIGNED_INT, 0);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  CLEANUP
    // ─────────────────────────────────────────────────────────────────────────
    public void cleanup() {
        glDeleteVertexArrays(vaoBox);
        glDeleteBuffers(vboBox); glDeleteBuffers(eboBox);
        glDeleteVertexArrays(vaoPlane);
        glDeleteBuffers(vboPlane); glDeleteBuffers(eboPlane);
        glDeleteProgram(shaderProgram);
    }
}
