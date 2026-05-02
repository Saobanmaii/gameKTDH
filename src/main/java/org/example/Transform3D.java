package org.example;

import org.joml.Matrix4f;

/**
 * Cung cấp 3 phép biến đổi ma trận 3D cơ bản:
 *   - Phép tịnh tiến  (Translation)
 *   - Phép biến đổi tỷ lệ (Scale)
 *   - Phép biến dạng 3D  (Shear / Distortion)
 *
 * Mỗi ma trận được xây dựng thủ công từ đầu (column-major, như OpenGL yêu cầu).
 */
public final class Transform3D {

    private Transform3D() {}

    // =========================================================================
    //  Phép tịnh tiến  T(tx, ty, tz)
    //
    //  | 1  0  0  tx |
    //  | 0  1  0  ty |
    //  | 0  0  1  tz |
    //  | 0  0  0   1 |
    // =========================================================================
    public static Matrix4f translation(float tx, float ty, float tz) {
        Matrix4f m = new Matrix4f();
        // JOML dùng column-major: m.mXY = cột X dòng Y
        m.m00(1).m01(0).m02(0).m03(0);
        m.m10(0).m11(1).m12(0).m13(0);
        m.m20(0).m21(0).m22(1).m23(0);
        m.m30(tx).m31(ty).m32(tz).m33(1);
        return m;
    }

    // =========================================================================
    //  Phép biến đổi tỷ lệ  S(sx, sy, sz)
    //
    //  | sx  0   0  0 |
    //  |  0  sy  0  0 |
    //  |  0   0  sz 0 |
    //  |  0   0   0 1 |
    // =========================================================================
    public static Matrix4f scale(float sx, float sy, float sz) {
        Matrix4f m = new Matrix4f();
        m.m00(sx).m01(0).m02(0).m03(0);
        m.m10(0).m11(sy).m12(0).m13(0);
        m.m20(0).m21(0).m22(sz).m23(0);
        m.m30(0).m31(0).m32(0).m33(1);
        return m;
    }

    public static Matrix4f uniformScale(float s) { return scale(s, s, s); }

    // =========================================================================
    //  Phép biến dạng 3D (Shear)
    //
    //  Sh = | 1     shXY  shXZ  0 |
    //       | shYX  1     shYZ  0 |
    //       | shZX  shZY  1     0 |
    //       | 0     0     0     1 |
    //
    //  shXY: dịch X theo Y,  shXZ: dịch X theo Z
    //  shYX: dịch Y theo X,  shYZ: dịch Y theo Z
    //  shZX: dịch Z theo X,  shZY: dịch Z theo Y
    // =========================================================================
    public static Matrix4f shear(float shXY, float shXZ,
                                  float shYX, float shYZ,
                                  float shZX, float shZY) {
        Matrix4f m = new Matrix4f();
        // Column 0 (x basis)
        m.m00(1).m01(shYX).m02(shZX).m03(0);
        // Column 1 (y basis)
        m.m10(shXY).m11(1).m12(shZY).m13(0);
        // Column 2 (z basis)
        m.m20(shXZ).m21(shYZ).m22(1).m23(0);
        // Column 3 (translation stays identity)
        m.m30(0).m31(0).m32(0).m33(1);
        return m;
    }

    /** Tòa nhà nghiêng về phía trước theo tốc độ (shZY > 0 = lean forward). */
    public static Matrix4f shearZY(float sh) {
        return shear(0, 0, 0, 0, 0, sh);
    }

    /** Obstacle bị lệch trục X theo chiều Z (góc nhìn méo). */
    public static Matrix4f shearXZ(float sh) {
        return shear(0, sh, 0, 0, 0, 0);
    }

    /** Player tan chảy khi chết: trục Y dịch theo X. */
    public static Matrix4f shearYX(float sh) {
        return shear(0, 0, sh, 0, 0, 0);
    }

    // =========================================================================
    //  Kết hợp: model = T * R * S * Sh
    // =========================================================================
    public static Matrix4f compose(Matrix4f t, Matrix4f r, Matrix4f s, Matrix4f sh) {
        return new Matrix4f(t).mul(r).mul(s).mul(sh);
    }

    /**
     * Shortcut: tịnh tiến rồi scale đơn giản (không có shear).
     * Dùng phổ biến nhất trong render loop.
     */
    public static Matrix4f ts(float tx, float ty, float tz,
                               float sx, float sy, float sz) {
        return translation(tx, ty, tz).mul(scale(sx, sy, sz));
    }
}
