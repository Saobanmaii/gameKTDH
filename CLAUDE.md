# CLAUDE.md

File này cung cấp hướng dẫn cho Claude Code (claude.ai/code) khi làm việc với code trong repository này.

---

## Build & Chạy

Maven **không có trong PATH** — dùng Maven tích hợp sẵn trong IntelliJ:

```powershell
# Biên dịch
& "C:\Program Files\JetBrains\IntelliJ IDEA 2025.2.6.1\plugins\maven\lib\maven3\bin\mvn.cmd" clean compile

# Chạy game
& "C:\Program Files\JetBrains\IntelliJ IDEA 2025.2.6.1\plugins\maven\lib\maven3\bin\mvn.cmd" exec:java -Dexec.mainClass=org.example.Main
```

Yêu cầu: Java 17, LWJGL 3.3.3 (natives-windows), JOML 1.10.5. Tất cả dependencies khai báo trong `pom.xml`.

---

## Kiến trúc tổng quan

### Sơ đồ phân cấp class

```
Main
 └─ GameEngine          (cửa sổ GLFW, vòng lặp, state machine)
     ├─ Player          (vật lý, trạng thái người chơi)
     ├─ EntityManager   (quản lý danh sách entity)
     │   ├─ Obstacle    (dữ liệu chướng ngại vật)
     │   ├─ Pickup      (dữ liệu vật phẩm)
     │   ├─ Particle    (dữ liệu hạt hiệu ứng)
     │   └─ TrailSegment(dữ liệu vết trail)
     └─ Renderer        (toàn bộ OpenGL)
         ├─ ShaderProgram (biên dịch GLSL)
         └─ Transform3D   (xây dựng ma trận 3D)
```

### Bảng trách nhiệm từng class

| Class | Trách nhiệm (IS) | KHÔNG phải trách nhiệm (IS NOT) |
|-------|------------------|---------------------------------|
| `Main` | Điểm khởi động duy nhất | Bất kỳ logic game nào |
| `GameEngine` | Cửa sổ GLFW; vòng lặp; state machine (READY/PLAYING/DEAD); input; điểm số; điều phối update + render | Vật lý player; chi tiết OpenGL; xây dựng entity |
| `Player` | Vị trí/vật lý (`playerX/Y/Z`, `velY`); nhảy/trượt/đổi làn; bộ đếm power-up; `deathShear` | Va chạm; render; biết entity nào tồn tại |
| `EntityManager` | Tạo, lưu trữ, cập nhật Obstacle/Pickup/Particle/TrailSegment; spawn particle; va chạm pickup | Render; logic GLFW; trạng thái player |
| `Obstacle` | Dữ liệu thuần: vị trí, `shape`, kích thước (`w/h/d`) | Cập nhật chính mình; render |
| `Pickup` | Dữ liệu thuần: vị trí, `type`, `bobPhase` | Áp dụng hiệu ứng; render |
| `Particle` | Dữ liệu thuần: vị trí, vận tốc, `life`, `size`, `color` | Vật lý nâng cao; render |
| `TrailSegment` | Dữ liệu thuần: vị trí, kích thước (`sx/sy`), `alpha` | Cập nhật chính mình; render |
| `Renderer` | Khởi tạo VAO/VBO; biên dịch shader; `renderFrame()`; tất cả draw call; hằng số render | Vật lý; logic game; spawn entity |
| `ShaderProgram` | Nhận chuỗi GLSL, biên dịch và link thành program ID; ném `RuntimeException` khi lỗi | Quản lý uniform; lifecycle program sau khi trả về |
| `Transform3D` | Xây dựng ma trận 4×4 column-major tĩnh (Translation, Scale, Shear, Compose) | Giữ trạng thái; gọi OpenGL API |

---

## Thuật toán đồ họa 3D

Tất cả ma trận được xây dựng **thủ công** trong `Transform3D.java` theo quy ước **column-major** của OpenGL.
JOML lưu theo cột: `m.mCOL_ROW` (ví dụ `m.m30` = cột 3, hàng 0 = thành phần tx).

### 1. Phép tịnh tiến — T(tx, ty, tz)

Dịch chuyển vật thể theo vectơ (tx, ty, tz).

```
         [ 1   0   0   tx ]
T =      [ 0   1   0   ty ]
         [ 0   0   1   tz ]
         [ 0   0   0    1 ]
```

JOML: `m.m30(tx).m31(ty).m32(tz)` (cột cuối chứa tịnh tiến)

**Áp dụng:** mọi draw call dùng Translation để đặt vật thể vào đúng vị trí thế giới — player, obstacle, pickup, tòa nhà, particle.

---

### 2. Phép biến đổi tỷ lệ — S(sx, sy, sz)

Co giãn vật thể theo từng trục riêng biệt.

```
         [ sx   0   0   0 ]
S =      [  0  sy   0   0 ]
         [  0   0  sz   0 ]
         [  0   0   0   1 ]
```

`uniformScale(s)` = `S(s, s, s)` — co giãn đều cả 3 trục.

**Áp dụng:**
- `player.getScale()` trả về `0.5f` (power-up shrink) hoặc `1.0f` — nhân vào mọi scale của player
- Obstacle: shape 0 `scale(0.9, 1.1, 0.8)`, shape 1 `scale(0.9, 0.5, 0.8)`, shape 2 `scale(3.8, 0.38, 0.8)`
- Shield glow: `uniformScale(scale * PLAYER_SHIELD_SCALE)`
- Coin: `scale(0.36, 0.36, 0.055)` — dẹt thành đĩa

---

### 3. Phép biến dạng — Sh (Shear)

Làm nghiêng/biến dạng hình học theo các cặp trục. Ma trận đầy đủ 6 hệ số:

```
         [ 1     shXY  shXZ  0 ]
Sh =     [ shYX  1     shYZ  0 ]
         [ shZX  shZY  1     0 ]
         [ 0     0     0     1 ]
```

**Quy ước:** `shAB` = "trục A bị dịch chuyển tỉ lệ theo trục B".

#### Ba trường hợp shear trong game:

**a) `shearZY(sh)` — tòa nhà nghiêng về phía trước**

Chỉ `shZY = sh`, các hệ số còn lại = 0. Z của mỗi đỉnh dịch thêm `sh × Y`.
Tòa nhà cao (Y lớn) bị kéo về phía trước nhiều hơn → cảm giác tốc độ cao.

Giá trị: `speedFactor * BUILDING_SHEAR_MAX` (tăng theo tốc độ game).

**b) `shearXZ(sh)` — obstacle wide blocker bị méo hình học**

Chỉ `shXZ = WIDE_SHEAR = 0.06f`. X của mỗi đỉnh dịch thêm `sh × Z`.
Chướng ngại vật rộng (shape 2) bị kéo lệch theo chiều sâu.

**c) `shearYX(sh)` — player "tan chảy" khi chết**

Chỉ `shYX = player.deathShear`. Y của mỗi đỉnh dịch thêm `sh × X`.
Các phần bên phải bị kéo lên/xuống → hình dạng tan chảy.

Giá trị: `deathTimer * 0.8f`, tăng dần đến tối đa 1.5.

---

### 4. Kết hợp phép biến đổi — M = T × R × S × Sh

```java
Transform3D.compose(t, r, s, sh)  // → new Matrix4f(t).mul(r).mul(s).mul(sh)
```

Thứ tự nhân: Scale → Shear → Rotate → Translate (áp dụng từ phải sang trái trên vector điểm).

Ví dụ player body khi chết:
```
M = T(playerX, bodyY, playerZ)  ×  R(lean, 0,0,1)  ×  S(0.75*scale, bodyH*scale, 0.6*scale)  ×  Sh_shearYX(deathShear)
```

Shortcut `Transform3D.ts(tx,ty,tz, sx,sy,sz)` = `T × S` — dùng cho road/buildings/pickups không có rotation hay shear.

---

## Quyết định thiết kế quan trọng

**Khối lập phương 24 đỉnh (không phải 8):** Mỗi mặt có 4 đỉnh riêng mang normal theo mặt. Stride = 24 byte, `aPos` offset 0, `aNorm` offset 12. Cần cho ánh sáng Blinn-Phong chính xác per-face.

**Không rebind VAO trong `drawBox36()`:** VAO bind một lần trước vòng lặp draw. `drawBox36()` chỉ gọi `glDrawElements(GL_TRIANGLES, 36, ...)`. Đây từng là bug trong bản cũ.

**Frame-count timer cho power-up:** `shrinkEnd` và `shieldEnd` lưu frame number hết hạn, không dùng `System.currentTimeMillis()`. Đảm bảo timer đồng bộ với vòng lặp game. Shrink = 360 frame (~6s @ 60fps), Shield = 480 frame (~8s @ 60fps).

**`IntConsumer onScoreAdd` thay `int[] scoreRef`:** `EntityManager.updatePickups()` nhận callback `IntConsumer onScoreAdd`. `GameEngine` truyền `delta -> score += delta`. Rõ ràng và type-safe hơn hack mảng một phần tử.

**Fragment shader uniforms:**
- `uniEmissive`: 0.0 = ánh sáng Phong đầy đủ; 1.0 = bỏ qua ánh sáng (glow thuần màu)
- `uniAlpha`: độ trong suốt [0..1]
- `uniCamPos`: vị trí camera cho tính specular Blinn-Phong và fog

**`vaoPlane`:** Quad phẳng 4 đỉnh dùng riêng cho bề mặt đường, tránh depth artifact mặt dưới của `vaoBox`.

---

## Luồng dữ liệu một frame

```
glfwPollEvents()
    └─ KeyCallback → player.handleLaneInput / startJump / startSlide

update(dt, time)
    ├─ frameCount++; score += 1 mỗi 6 frame
    ├─ gameSpeed tăng dần (ACCEL mỗi frame, tối đa MAX_SPEED)
    ├─ player.update(dt, gameSpeed, frameCount, onLand)   ← vật lý + timer power-up
    ├─ EntityManager.updateObstacles(...)                 ← di chuyển + AABB va chạm
    ├─ EntityManager.updatePickups(..., onScoreAdd, frameCount)
    ├─ EntityManager.updateParticles(dt)
    ├─ EntityManager.updateTrails(dt)
    └─ road tile recycling + spawnWave

renderer.renderFrame(...)
    ├─ Camera: proj (FOV động) + view (lookAt) + camera shake
    ├─ drawRoad      → vaoPlane + vaoBox  [T × S]
    ├─ drawObstacles → vaoBox             [T × S] hoặc [T × S × shearXZ]
    ├─ drawPickups   → vaoBox             [JOML rotation × S]
    ├─ drawTrails    → vaoBox             [T × S]
    ├─ drawPlayer    → vaoBox             [T × R × S × shearYX khi chết]
    ├─ drawParticles → vaoBox             [T × uniformScale]
    └─ drawBuildings → vaoBox             [T × S × shearZY theo tốc độ]
```
