package mchorse.bbs_mod.client.compat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Source-level guard for multi-texture model batching and BOBJ pose reuse. */
public final class ModelRenderPerformanceSourceTest
{
    private ModelRenderPerformanceSourceTest()
    {}

    public static void runAll()
    {
        String vaoRenderer = read("src/client/java/mchorse/bbs_mod/cubic/render/vao/ModelVAORenderer.java");
        String cubicRenderer = read("src/client/java/mchorse/bbs_mod/cubic/render/CubicVAORenderer.java");
        String modelInstance = read("src/client/java/mchorse/bbs_mod/cubic/ModelInstance.java");
        String bobjVao = read("src/client/java/mchorse/bbs_mod/cubic/render/vao/BOBJModelVAO.java");

        check(vaoRenderer.contains("public static record BatchDraw")
                && vaoRenderer.contains("public static void renderBatch")
                && vaoRenderer.contains("uploadDrawUniforms(shader"),
            "Model VAO batches no longer keep per-draw matrix uploads explicit");
        check(cubicRenderer.contains("private final Map<String, Texture> textureCache")
                && cubicRenderer.contains("this.renderImmediateBatches()")
                && cubicRenderer.contains("ModelVAORenderer.renderBatch"),
            "Cubic multi-texture rendering no longer resolves and submits texture batches");
        check(modelInstance.contains("hasBOBJArmatureChanged(armatureMatrices)")
                && modelInstance.contains("vao.updateMesh(stencilMap, armatureMatrices, armatureChanged)")
                && modelInstance.contains("deferredArmature"),
            "BOBJ rendering no longer shares the model-level pose check and deferred snapshot");
        check(bobjVao.contains("updateMesh(StencilMap stencilMap, Matrix4f[] matrices, boolean armatureChanged)"),
            "BOBJ VAO lost the caller-provided armature change decision");

        check(count(modelInstance, "vao.snapshotArmature()") <= 2,
            "BOBJ delayed rendering is allocating one armature snapshot per mesh");
    }

    private static int count(String source, String value)
    {
        int count = 0;
        int offset = 0;

        while ((offset = source.indexOf(value, offset)) >= 0)
        {
            count++;
            offset += value.length();
        }

        return count;
    }

    private static String read(String path)
    {
        try
        {
            return Files.readString(Path.of(path));
        }
        catch (IOException e)
        {
            throw new AssertionError("could not read " + path, e);
        }
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
