package org.example;

import static org.lwjgl.opengl.GL20.*;

public class ShaderProgram {

    public static int compile(String vertSrc, String fragSrc) {
        int vs = glCreateShader(GL_VERTEX_SHADER);
        glShaderSource(vs, vertSrc);
        glCompileShader(vs);
        if (glGetShaderi(vs, GL_COMPILE_STATUS) == 0)
            throw new RuntimeException("Vertex shader error:\n" + glGetShaderInfoLog(vs));

        int fs = glCreateShader(GL_FRAGMENT_SHADER);
        glShaderSource(fs, fragSrc);
        glCompileShader(fs);
        if (glGetShaderi(fs, GL_COMPILE_STATUS) == 0)
            throw new RuntimeException("Fragment shader error:\n" + glGetShaderInfoLog(fs));

        int prog = glCreateProgram();
        glAttachShader(prog, vs);
        glAttachShader(prog, fs);
        glLinkProgram(prog);
        if (glGetProgrami(prog, GL_LINK_STATUS) == 0)
            throw new RuntimeException("Shader link error:\n" + glGetProgramInfoLog(prog));

        glDeleteShader(vs);
        glDeleteShader(fs);
        return prog;
    }
}
