package mchorse.bbs_mod.ui.film.replays;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.api.client.events.FormRenderEvents;
import mchorse.bbs_mod.camera.data.Angle;
import mchorse.bbs_mod.camera.data.Point;
import mchorse.bbs_mod.camera.data.Position;
import mchorse.bbs_mod.camera.values.ValueAngle;
import mchorse.bbs_mod.camera.values.ValuePoint;
import mchorse.bbs_mod.camera.values.ValuePosition;
import mchorse.bbs_mod.forms.FormArchitect;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.renderers.FormRenderer;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import mchorse.bbs_mod.forms.renderers.utils.FormFrameCache;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCache;
import mchorse.bbs_mod.forms.renderers.utils.RenderFrame;
import mchorse.bbs_mod.forms.FormTranslucentQueue;
import mchorse.bbs_mod.graphics.Framebuffer;
import mchorse.bbs_mod.graphics.FramebufferManager;
import mchorse.bbs_mod.graphics.FramebufferPool;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.settings.values.core.ValueColor;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.settings.values.core.ValueRecentData;
import mchorse.bbs_mod.settings.values.core.ValueTransform;
import mchorse.bbs_mod.settings.values.misc.ValueVector3f;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import mchorse.bbs_mod.settings.values.numeric.ValueLong;
import mchorse.bbs_mod.data.types.IntType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.film.controller.FilmTargetPickerTest;
import mchorse.bbs_mod.ui.film.controller.FilmEditTargetParityTest;
import mchorse.bbs_mod.utils.colors.Color;
import mchorse.bbs_mod.utils.pose.Transform;
import mchorse.bbs_mod.utils.resources.PlayerSkinImage;
import mchorse.bbs_mod.utils.resources.PlayerSkins;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import sun.misc.Unsafe;

import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.List;
import java.util.ArrayList;

/** Executing contracts for defaults, render epochs, retained targets and unknown data. */
public final class FS26SupplementalContractTest
{
    public static void runAll()
    {
        try
        {
            defaults();
            FilmLiveSyncContractTest.runAll();
            renderEpoch();
            retainedFramebuffers();
            skinLayouts();
            pairedRenderEvents();
            FilmTargetPickerTest.runAll();
            FilmEditTargetParityTest.runAll();
            check(new FormArchitect().fromData(new MapType()) == null, "Empty form ids must remain null");
        }
        catch (ReflectiveOperationException exception)
        {
            throw new AssertionError(exception);
        }
    }

    private static void defaults()
    {
        ValueFloat number = new ValueFloat("number", 2F);
        int[] before = {0};
        int[] after = {0};
        number.preCallback((value, flag) -> before[0]++);
        number.postCallback((value, flag) -> after[0]++);
        number.set(7F);
        number.reset();
        check(number.get() == 2F && number.isDefault() && before[0] == 2 && after[0] == 2,
            "Reset must restore the declaration and use ordinary edit notifications");

        Transform declaration = new Transform();
        declaration.translate.set(1F, 2F, 3F);
        ValueTransform transform = new ValueTransform("transform", declaration);
        declaration.translate.x = 99F;
        check(!transform.isDefault(), "Mutable value edits must not mutate the captured default");
        transform.reset();
        check(transform.get().translate.x == 1F && transform.isDefault(), "Transform default was not deep copied");
        transform.get().translate.x = 8F;
        transform.reset();
        check(transform.get().translate.x == 1F, "A reset result must not alias its stored default");

        ValueColor color = new ValueColor("color", new Color(0.2F, 0.4F, 0.6F, 0.8F));
        color.get().a = 0F;
        color.reset();
        check(color.get().a == 0.8F && color.isDefault(), "Mutable color defaults lost alpha or share storage");

        Point initialPoint = new Point(1D, 2D, 3D);
        ValuePoint point = new ValuePoint("point", initialPoint);
        initialPoint.set(9D, 8D, 7D);
        check(!point.isDefault(), "Point edits must not mutate their captured default");
        point.reset();
        check(point.get().equals(new Point(1D, 2D, 3D)) && point.get() == initialPoint,
            "Point reset must restore coordinates through the original live object");
        point.get().x = 20D;
        point.reset();
        check(point.get().x == 1D && point.isDefault(), "Point reset aliased its stored default");

        Angle initialAngle = new Angle(10F, 20F, 30F, 80F);
        ValueAngle angle = new ValueAngle("angle", initialAngle);
        initialAngle.set(1F, 2F, 3F, 4F);
        check(!angle.isDefault(), "Angle edits must not mutate their captured default");
        angle.reset();
        check(angle.get().equals(new Angle(10F, 20F, 30F, 80F)) && angle.get() == initialAngle,
            "Angle reset must restore yaw, pitch, roll and FOV through the original live object");
        angle.get().fov = 25F;
        angle.reset();
        check(angle.get().fov == 80F && angle.isDefault(), "Angle reset aliased its stored default");

        Position initialPosition = new Position(1F, 2F, 3F, 10F, 20F, 30F, 80F);
        Position expectedPosition = initialPosition.copy();
        ValuePosition position = new ValuePosition("position", initialPosition);
        position.set(new Position(9F, 8F, 7F, 60F, 50F, 40F, 90F));
        position.reset();
        check(position.get() == initialPosition && position.get().equals(expectedPosition) && position.isDefault(),
            "Position group reset failed to restore its actual camera pose");
        check(position.getPoint().get() == initialPosition.point && position.getAngle().get() == initialPosition.angle,
            "Reset detached the position delegates from the camera pose");
        position.getPoint().set(new Point(4D, 5D, 6D));
        position.getAngle().set(new Angle(7F, 8F, 9F, 100F));
        check(position.get().point.x == 4D && position.get().angle.fov == 100F,
            "Camera delegates no longer edit the position after reset");
        position.reset();
        check(position.get().equals(expectedPosition), "Repeated position resets changed the declared pose");

        Vector3f vectorDeclaration = new Vector3f(1F, 2F, 3F);
        ValueVector3f vector = new ValueVector3f("vector", vectorDeclaration);
        vectorDeclaration.x = 8F;
        vector.reset();
        check(vector.get().equals(new Vector3f(1F, 2F, 3F)) && vector.isDefault(),
            "Vector settings must retain independent declaration defaults");

        ValueInt integer = new ValueInt("integer", 0);
        ValueLong longValue = new ValueLong("long", 0L);
        integer.setNumber(2.6D);
        longValue.setNumber(-2.6D);
        check(integer.get() == 3 && longValue.get() == -3L,
            "Shared numeric input must round integer settings rather than truncate them");
        ValueInt rgb = new ValueInt("rgb", 0).color();
        ValueInt argb = new ValueInt("argb", 0).colorAlpha();
        rgb.fromData(new IntType(0x80112233));
        argb.fromData(new IntType(0x80112233));
        check(rgb.get() == 0x112233 && argb.get() == 0x80112233,
            "Legacy RGB input retained alpha or ARGB input lost it");

        ValueGroup group = new ValueGroup("group");
        group.add(integer);
        group.add(position);
        check(group.getBasic("integer") == integer && group.getBasic("position") == null
            && group.getBasic("missing") == null, "Basic lookup must return only the directly addressed scalar");
        ValueRecentData recent = new ValueRecentData("recent").limit(0);
        for (int i = 0; i < 25; i++)
        {
            recent.touch("film", "film-" + i);
        }
        check(recent.get("film").size() == 25, "A zero recent-file limit must keep unbounded history");
    }

    private static void renderEpoch()
    {
        boolean enabled = BBSSettings.framePoseCache.get();
        BBSSettings.framePoseCache.set(true);
        try
        {
            Form form = new Form() {};
            ProbeRenderer renderer = new ProbeRenderer(form);
            form.setRenderer(renderer);
            Object firstOwner = new Object();
            Object secondOwner = new Object();
            Matrix4f placement = new Matrix4f().translate(1F, 2F, 3F);
            RenderFrame.nextFrame();
            MatrixCache first = RenderFrame.collect(form, null, firstOwner, placement, true, true, 0.25F);
            MatrixCache reused = RenderFrame.collect(form, null, firstOwner, new Matrix4f(placement), true, true, 0.25F);
            check(first == reused && renderer.evaluations == 1, "Same pose and semantic placement should share evaluation");
            RenderFrame.collect(form, null, secondOwner, placement, true, true, 0.25F);
            check(renderer.evaluations == 2, "Independent simulation owners must not share cached matrices");
            RenderFrame.collect(form, null, secondOwner, placement.translate(1F, 0F, 0F), true, true, 0.25F);
            check(renderer.evaluations == 3, "Moved semantic placement reused old matrices");
            form.lighting.set(0.5F);
            RenderFrame.collect(form, null, secondOwner, placement, true, true, 0.25F);
            check(renderer.evaluations == 4, "Value notifications must invalidate the pose");
            FormFrameCache explicit = new FormFrameCache();
            explicit.collect(form, null, 0F);
            RenderFrame.invalidate();
            explicit.collect(form, null, 0F);
            check(renderer.evaluations == 6, "An explicit pass cache survived an out-of-band re-pose");
            BBSSettings.framePoseCache.set(false);
            RenderFrame.collect(form, null, 0F);
            RenderFrame.collect(form, null, 0F);
            check(renderer.evaluations == 8, "Disabled pose cache must evaluate each read");
        }
        finally
        {
            BBSSettings.framePoseCache.set(enabled);
            RenderFrame.invalidate();
        }
    }

    private static void retainedFramebuffers() throws ReflectiveOperationException
    {
        FramebufferManager manager = new FramebufferManager();
        check(manager.getFormFramebuffers() == manager.pool && manager.getFormFramebuffers() == manager.getFormFramebuffers(),
            "The public form pool accessor and compatibility field must retain the same owner");
        FakePool pool = new FakePool();
        Framebuffer first = pool.get(64, 64);
        Framebuffer sibling = pool.get(64, 64);
        check(first != sibling, "An active target was handed to a sibling before its deferred draw");
        pool.release(first);
        check(pool.get(64, 64) == first, "A released target of the same size should be reused");
        pool.release(first);
        pool.release(first);
        Framebuffer reused = pool.get(64, 64);
        check(pool.get(64, 64) != reused, "Duplicate releases must not allow concurrent ownership");
        pool.delete();
        check(pool.created.stream().allMatch(buffer -> buffer.deleted == 1), "Pool deletion must release active and idle targets once");

        FakePool bounded = new FakePool();
        for (int i = 0; i < 12; i++)
        {
            bounded.release(bounded.get(32 + i, 32));
        }
        check(bounded.created.stream().filter(buffer -> buffer.deleted == 0).count() == 8,
            "Idle buffers must stay within the count bound");
        FakeBuffer oversized = (FakeBuffer) bounded.get(8192, 8192);
        bounded.release(oversized);
        check(oversized.deleted == 1, "Oversized idle allocations must be evicted by the byte bound");
        bounded.delete();

        int[] released = {0};
        FormTranslucentQueue.VertexBufferCommand command = new FormTranslucentQueue.VertexBufferCommand(null,
            () -> null, FormTranslucentQueue.PASS_TRANSLUCENT, false, null, new Matrix4f(), null,
            new Vector3f(), null, false, null, null, false, () -> released[0]++);
        command.release();
        command.release();
        check(released[0] == 1, "Deferred target cleanup must also be safe on an aborted draw");
    }

    private static void skinLayouts()
    {
        BufferedImage old = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        old.setRGB(4, 20, 0xff123456);
        BufferedImage converted = PlayerSkinImage.normalize(old);
        check(converted.getHeight() == 64 && converted.getRGB(4, 20) == 0xff123456 && converted.getRGB(0, 0) == 0xff000000,
            "Legacy base-layer pixels must become opaque");
        check(converted.getRGB(23, 52) == 0xff123456, "Legacy left limb must mirror its matching right limb");
        BufferedImage modern = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        modern.setRGB(12, 12, 0x60123456);
        BufferedImage copy = PlayerSkinImage.normalize(modern);
        check(copy.getRGB(12, 12) == modern.getRGB(12, 12), "Modern skin alpha must survive normalization");
        copy.setRGB(12, 12, 0);
        check(modern.getRGB(12, 12) != 0, "Normalized skins must not alias the downloaded image");
        check("Notch".equals(PlayerSkins.nickname(PlayerSkins.link("Notch.png")))
            && PlayerSkins.isNickname("A_b9") && !PlayerSkins.isNickname("../bad"), "Player texture link grammar changed");
    }

    private static void pairedRenderEvents() throws ReflectiveOperationException
    {
        Form form = new Form() {};
        form.setRenderer(new ProbeRenderer(form));
        int[] after = {0};
        FormRenderEvents.Render before = (target, context) ->
        {
            if (target == form) throw new AssertionError("expected before-render failure");
        };
        FormRenderEvents.Render finish = (target, context) ->
        {
            if (target == form) after[0]++;
        };
        FormRenderEvents.BEFORE.register(before);
        FormRenderEvents.AFTER.register(finish);
        Field stack = FormUtilsClient.class.getDeclaredField("currentForm");
        stack.setAccessible(true);
        int depth = ((ArrayDeque<?>) stack.get(null)).size();
        try
        {
            try
            {
                FormUtilsClient.render(form, new FormRenderingContext());
                throw new AssertionError("Expected render failure was swallowed");
            }
            catch (AssertionError expected)
            {
                check("expected before-render failure".equals(expected.getMessage()), "Unexpected rendering failure");
            }
            check(after[0] == 1 && ((ArrayDeque<?>) stack.get(null)).size() == depth,
                "Render failure must pair AFTER and restore the current-form stack");
        }
        finally
        {
            FormRenderEvents.BEFORE.unregister(before);
            FormRenderEvents.AFTER.unregister(finish);
        }
    }

    private static final class ProbeRenderer extends FormRenderer<Form>
    {
        int evaluations;

        ProbeRenderer(Form form)
        {
            super(form);
        }

        @Override
        public MatrixCache collectMatrices(IEntity entity, Object owner, Matrix4f base, boolean targets, boolean collisions, float transition)
        {
            this.evaluations++;
            return new MatrixCache();
        }

        @Override
        public void renderInUI(UIContext context, int a, int b, int c, int d)
        {}
    }

    private static final class FakePool extends FramebufferPool
    {
        final List<FakeBuffer> created = new ArrayList<>();

        @Override
        protected Framebuffer create(int width, int height)
        {
            try
            {
                Field field = Unsafe.class.getDeclaredField("theUnsafe");
                field.setAccessible(true);
                Unsafe unsafe = (Unsafe) field.get(null);
                FakeBuffer buffer = (FakeBuffer) unsafe.allocateInstance(FakeBuffer.class);
                buffer.texture = (Texture) unsafe.allocateInstance(Texture.class);
                buffer.texture.width = width;
                buffer.texture.height = height;
                this.created.add(buffer);
                return buffer;
            }
            catch (ReflectiveOperationException exception)
            {
                throw new AssertionError(exception);
            }
        }
    }

    private static final class FakeBuffer extends Framebuffer
    {
        Texture texture;
        int deleted;

        @Override
        public Texture getMainTexture()
        {
            return this.texture;
        }

        @Override
        public void delete()
        {
            this.deleted++;
        }
    }

    private static void check(boolean condition, String message)
    {
        if (!condition) throw new AssertionError(message);
    }
}
