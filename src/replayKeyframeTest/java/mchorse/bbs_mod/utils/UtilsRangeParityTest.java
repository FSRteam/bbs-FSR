package mchorse.bbs_mod.utils;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.graphics.InverseView;
import mchorse.bbs_mod.settings.values.core.ValueString;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.clips.ClipFactory;
import mchorse.bbs_mod.utils.clips.Clips;
import mchorse.bbs_mod.utils.clips.MissingClip;
import mchorse.bbs_mod.utils.iris.ShaderCurves;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.KeyframeLoop;
import mchorse.bbs_mod.utils.keyframes.KeyframeLoops;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;
import mchorse.bbs_mod.utils.profiler.BBSProfiler;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class UtilsRangeParityTest
{
    public static void main(String[] args)
    {
        runAll();
    }

    public static void runAll()
    {
        testUiMatrixScope();
        testProfilerFramesAndNestedTimers();
        testClipDurationAndFactory();
        testShaderCurves();
        testVersionParsing();
        testFiniteLoopSpans();
        System.out.println("UtilsRangeParityTest passed");
    }

    private static void testUiMatrixScope()
    {
        if (!RenderSystem.isOnRenderThread())
        {
            RenderSystem.initRenderThread();
        }

        Matrix4fStack stack = RenderSystem.getModelViewStack();
        Matrix4f previousApplied = new Matrix4f(RenderSystem.getModelViewMatrix());
        Matrix4f previousProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        Matrix3f previousInverse = new Matrix3f(InverseView.get());
        VertexSorting previousSorting = RenderSystem.getVertexSorting();
        Matrix4f uiApplied = new Matrix4f().translate(4F, 6F, 8F).rotateY(0.4F);
        Matrix4f callerTop = new Matrix4f().translate(-3F, 2F, 1F);
        Matrix4f projection = new Matrix4f().scaling(2F, 3F, 4F);
        Matrix3f inverse = new Matrix3f().rotationZ(0.3F);

        stack.pushMatrix();

        try
        {
            stack.set(callerTop);

            for (int pass = 0; pass < 4; pass++)
            {
                RenderSystem.getModelViewMatrix().set(uiApplied);
                RenderSystem.setProjectionMatrix(projection, VertexSorting.ORTHOGRAPHIC_Z);
                InverseView.set(inverse);
                MatrixStackUtils.cacheMatrices();

                check(stack.equals(new Matrix4f(), 0F), "UI 3D pass did not keep an identity stack top");
                check(RenderSystem.getModelViewMatrix().equals(new Matrix4f(), 0F), "UI 3D pass did not apply identity");

                // Vanilla layering phases change and reapply the stack during an embedded pass.
                stack.pushMatrix().scale(0.999F);
                RenderSystem.applyModelViewMatrix();
                stack.popMatrix();
                RenderSystem.applyModelViewMatrix();
                check(RenderSystem.getModelViewMatrix().equals(new Matrix4f(), 0F), "Layering phase leaked the UI matrix into 3D");
                RenderSystem.setProjectionMatrix(new Matrix4f(), VertexSorting.DISTANCE_TO_ORIGIN);
                InverseView.set(new Matrix3f());
                MatrixStackUtils.restoreMatrices();

                check(RenderSystem.getModelViewMatrix().equals(uiApplied, 0F), "Restore lost the UI applied matrix when it differed from the stack top");
                check(stack.equals(callerTop, 0F), "Restore changed the caller's stack top");
                check(RenderSystem.getProjectionMatrix().equals(projection, 0F), "Restore lost the UI projection");
                check(InverseView.get().equals(inverse, 0F), "Restore lost the inverse view");
            }
        }
        finally
        {
            stack.popMatrix();
            RenderSystem.getModelViewMatrix().set(previousApplied);
            RenderSystem.setProjectionMatrix(previousProjection, previousSorting);
            InverseView.set(previousInverse);
        }
    }

    private static void testProfilerFramesAndNestedTimers()
    {
        boolean enabled = BBSProfiler.enabled;

        try
        {
            BBSProfiler.enabled = true;
            BBSProfiler.frame();
            BBSProfiler.count(BBSProfiler.Section.KEYFRAME_FIND_SEGMENT, 3);
            BBSProfiler.begin(BBSProfiler.Timer.UI_TOTAL);
            BBSProfiler.begin(BBSProfiler.Timer.UI_TOTAL);
            BBSProfiler.end(BBSProfiler.Timer.UI_TOTAL);
            BBSProfiler.end(BBSProfiler.Timer.UI_TOTAL);
            BBSProfiler.frame();
            check(BBSProfiler.get(BBSProfiler.Section.KEYFRAME_FIND_SEGMENT) == 3L, "Profiler did not publish the previous frame's counters");
            check(BBSProfiler.getMs(BBSProfiler.Timer.UI_TOTAL) >= 0D, "Nested timer produced a negative duration");
            BBSProfiler.enabled = false;
            BBSProfiler.count(BBSProfiler.Section.KEYFRAME_FIND_SEGMENT, 9);
            BBSProfiler.begin(BBSProfiler.Timer.UI_TOTAL);
            BBSProfiler.end(BBSProfiler.Timer.UI_TOTAL);
            BBSProfiler.frame();
            check(BBSProfiler.get(BBSProfiler.Section.KEYFRAME_FIND_SEGMENT) == 0L, "Disabled profiler counted work or retained an old frame");
            check(BBSProfiler.getMs(BBSProfiler.Timer.UI_TOTAL) == 0D, "Disabled profiler retained an old timer");
        }
        finally
        {
            BBSProfiler.frame();
            BBSProfiler.enabled = enabled;
        }
    }

    private static void testClipDurationAndFactory()
    {
        ClipFactory factory = new ClipFactory();
        check(factory.fromData(new MapType()) == null, "A map without a type became an unknown clip");
        MapType unknown = new MapType();
        unknown.putString("type", "addon:temporarily_missing");
        unknown.putInt("tick", 3);
        unknown.putInt("duration", 7);
        Clip clip = factory.fromData(unknown);
        check(clip instanceof MissingClip && factory.toData(clip).equals(unknown), "Unknown clips lost their original type or data");
        Clips clips = new Clips("clips", factory);
        clips.addClip(clip);
        TraversalList storage = new TraversalList();
        storage.add(clip);

        try
        {
            Field field = Clips.class.getDeclaredField("clips");
            field.setAccessible(true);
            field.set(clips, storage);
        }
        catch (ReflectiveOperationException e)
        {
            throw new AssertionError(e);
        }

        check(clips.calculateDuration() == 10 && clips.calculateDuration() == 10 && storage.traversals == 1,
            "Repeated duration reads traversed the clips again");
        clip.duration.set(9);
        check(clips.calculateDuration() == 12 && storage.traversals == 2, "A clip value edit did not invalidate its duration");
        clip.tick.set(5);
        check(clips.calculateDuration() == 14, "A clip tick edit did not invalidate its duration");
        clips.remove(clip);
        check(clips.calculateDuration() == 0, "Structural removal retained the old duration");
        clips.addClip(clip);
        check(clips.calculateDuration() == 14, "Structural insertion retained an empty duration");
    }

    private static void testShaderCurves()
    {
        ValueBoolean enabled = BBSSettings.shaderCurvesEnabled;

        try
        {
            BBSSettings.shaderCurvesEnabled = new ValueBoolean("shader_curves", true);
            ShaderCurves.reset();
            String shader = "#version 150\n"
                + "#define GAIN 1.0 // [0.0 1.0]\n"
                + "#define ARRAY_SIZE 4 // [2 4 8]\n"
                + "#define MACRO_VALUE 2.0 // [1.0 2.0]\n"
                + "#define CONDITIONAL 1.0 // [0.0 1.0]\n"
                + "#define COMPUTED MACRO_VALUE * 2.0\n"
                + "# if CONDITIONAL > 0\n#endif\n"
                + "#if GAIN_DETAIL\n#endif\n"
                + "const float g = GAIN;\nvoid main() { float x = GAIN + MACRO_VALUE + CONDITIONAL; }\n";
            String transformed = ShaderCurves.processSource(shader);
            check(ShaderCurves.variableMap.containsKey("GAIN") && transformed.contains("uniform float bbs_GAIN;"),
                "A float option absent from sliders was not exposed as a curve");
            check(!ShaderCurves.variableMap.containsKey("ARRAY_SIZE"), "An integer option absent from sliders became a runtime uniform");
            check(!ShaderCurves.variableMap.containsKey("MACRO_VALUE") && !ShaderCurves.variableMap.containsKey("CONDITIONAL"),
                "A value-dependent preprocessor expression was changed to a runtime uniform");
            check(transformed.contains("float g = bbs_GAIN;") && !transformed.contains("const float g"),
                "A constant expression still depended on the new runtime uniform");
        }
        finally
        {
            BBSSettings.shaderCurvesEnabled = enabled;
            ShaderCurves.reset();
        }
    }

    private static void testVersionParsing()
    {
        check(FFMpegUtils.parseVersion("ffmpeg version 6.1-full_build-www.gyan.dev Copyright").orElseThrow().equals("6.1"), "Stable encoder version was not parsed");
        check(FFMpegUtils.parseVersion("ffmpeg version n4.4-154-g79c114e1b2-20210930 Copyright").orElseThrow().equals("4.4"), "Development encoder version was not parsed");
        check(FFMpegUtils.parseVersion("ffmpeg version git-master Copyright").orElseThrow().equals("git-master"), "An unnumbered encoder build was dropped");
        check(FFMpegUtils.parseVersion(null).isEmpty() && FFMpegUtils.parseVersion("another executable").isEmpty(), "Invalid encoder output reported a version");
        ValueString path = BBSSettings.videoEncoderPath;

        try
        {
            BBSSettings.videoEncoderPath = new ValueString("video_encoder_path", "bbs-missing-ffmpeg-parity-test-executable");
            check(FFMpegUtils.version().isEmpty(), "A process launch failure reported an encoder version");
        }
        finally
        {
            BBSSettings.videoEncoderPath = path;
        }
    }

    private static void testFiniteLoopSpans()
    {
        check(!new KeyframeLoop("overflow", -Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE).isValid(),
            "Finite endpoints with an infinite loop span were accepted");
        KeyframeChannel<Float> channel = new KeyframeChannel<>("large", KeyframeFactories.FLOAT);
        channel.insert(1E38F, 0F);
        channel.insert(2E38F, 1F);
        KeyframeLoop loop = KeyframeLoops.create(List.of(channel), 1E38F, 2E38F);
        check(loop != null && loop.isValid() && channel.getLoop(loop.id()) == loop,
            "Loop creation overflowed while its final span was representable");
    }

    private static class TraversalList extends ArrayList<Clip>
    {
        private int traversals;

        @Override
        public Iterator<Clip> iterator()
        {
            this.traversals++;

            return super.iterator();
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
