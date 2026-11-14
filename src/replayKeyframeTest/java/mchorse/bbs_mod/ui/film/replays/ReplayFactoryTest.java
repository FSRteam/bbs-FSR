package mchorse.bbs_mod.ui.film.replays;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.blocks.entities.ModelProperties;
import mchorse.bbs_mod.camera.clips.CameraClip;
import mchorse.bbs_mod.camera.clips.CameraClipContext;
import mchorse.bbs_mod.camera.clips.CameraPoseClip;
import mchorse.bbs_mod.camera.clips.overwrite.IdleClip;
import mchorse.bbs_mod.camera.data.Position;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.camera.CameraTrack;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.FormArchitect;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.entities.StubEntity;
import mchorse.bbs_mod.forms.forms.AnchorForm;
import mchorse.bbs_mod.forms.forms.StructureForm;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.clips.ClipContext;
import mchorse.bbs_mod.utils.pose.Transform;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import org.joml.Vector3d;

import java.lang.reflect.Field;
import java.util.Map;

/** Replays keep their source placement and selected camera when constructed outside the list widget. */
public final class ReplayFactoryTest
{
    public static void runAll()
    {
        Film film = new Film();
        Vector3d point = new Vector3d(12.5D, -8D, 4.25D);
        Replay positioned = ReplayFactory.atPosition(film, point, 15F, 65F);
        check(positioned.keyframes.x.interpolate(0F) == point.x
            && positioned.keyframes.y.interpolate(0F) == point.y
            && positioned.keyframes.z.interpolate(0F) == point.z
            && positioned.keyframes.pitch.interpolate(0F) == 15D
            && positioned.keyframes.headYaw.interpolate(0F) == 65D,
            "Position construction changed the saved coordinate or angle space");
        Replay structure = ReplayFactory.fromStructure(film, "cuts/test", new BlockPos(-10, 5, 20), new Vec3i(5, 4, 9));
        check(structure.form.get() instanceof StructureForm form && form.structure.get().equals("cuts/test")
            && form.name.get().equals("test") && structure.keyframes.x.interpolate(0F) == -7.5D
            && structure.keyframes.y.interpolate(0F) == 5D && structure.keyframes.z.interpolate(0F) == 24.5D,
            "Structure construction lost its original footprint");
        check(!positioned.getId().isEmpty() && !positioned.getId().equals(structure.getId())
            && film.replays.getById(positioned.getId()) == positioned && !positioned.relative.get()
            && positioned.category.get().isEmpty(), "Factory bypassed replay identity or placement defaults");
        testModelBlock();
        testCamera();
    }

    private static void testModelBlock()
    {
        try
        {
            Field registry = BBSMod.class.getDeclaredField("forms");
            registry.setAccessible(true);
            Object previous = registry.get(null);
            FormArchitect architect = new FormArchitect();
            architect.register(Link.bbs("anchor"), AnchorForm.class, null);
            registry.set(null, architect);

            try
            {
                testRegisteredModelBlock();
            }
            finally
            {
                registry.set(null, previous);
            }
        }
        catch (ReflectiveOperationException exception)
        {
            throw new AssertionError("Could not install model-block form registry", exception);
        }
    }

    private static void testRegisteredModelBlock()
    {
        ModelProperties properties = new ModelProperties();
        AnchorForm source = new AnchorForm();
        source.name.set("source");
        properties.setForm(source);
        properties.setShadow(true);
        Transform transform = properties.getTransform();
        transform.translate.set(1F, 2F, 3F);
        transform.rotationMode = Transform.RotationMode.QUATERNION;
        transform.quat.rotationY(0.7F);
        Film film = new Film();
        Replay yawOnly = ReplayFactory.fromModelBlock(film, new BlockPos(10, 20, 30), properties);
        check(yawOnly.form.get() != source && yawOnly.form.get().name.get().equals("source") && yawOnly.shadow.get()
            && yawOnly.keyframes.x.interpolate(0F) == 11.5D && yawOnly.keyframes.y.interpolate(0F) == 22D
            && Math.abs(yawOnly.keyframes.yaw.interpolate(0F) + Math.toDegrees(0.7F)) < 1E-4D,
            "Model-block construction lost its form copy, translation, shadow or quaternion yaw");
        transform.scale.set(2F, 3F, 4F);
        Replay scaled = ReplayFactory.fromModelBlock(film, new BlockPos(10, 20, 30), properties);
        check(scaled.form.get() instanceof AnchorForm wrapper && wrapper.parts.getAllTyped().size() == 1
            && wrapper.parts.getAllTyped().getFirst().getForm() != source
            && wrapper.transform.get().translate.lengthSquared() == 0F
            && wrapper.transform.get().scale.equals(transform.scale)
            && wrapper.transform.get().rotationMode == Transform.RotationMode.QUATERNION
            && transform.translate.x == 1F,
            "Nontrivial model-block construction dropped its transform or mutated the source");
    }

    private static void testCamera()
    {
        Film film = new Film();
        IdleClip legacy = new IdleClip();
        legacy.position.get().point.x = 100D;
        legacy.duration.set(3);
        film.camera.addClip(legacy);
        CameraTrack selected = film.addCamera("Selected", new Position(10F, 20F, 30F, 210F, 15F));
        IEntity actor = new StubEntity();
        SampleClip sample = new SampleClip(actor);
        EffectClip effect = new EffectClip();
        sample.duration.set(3);
        effect.duration.set(3);
        selected.clips.addClip(sample);
        selected.clips.addClip(effect);
        Replay replay = ReplayFactory.fromCamera(film, selected.cameraId.get(), 3, Map.of("actor", actor));
        check(replay.keyframes.x.interpolate(0F) == 10D && replay.keyframes.x.interpolate(2F) == 12D
            && replay.keyframes.yaw.interpolate(2F) == 30D && replay.keyframes.pitch.interpolate(2F) == 15D
            && sample.calls == 3 && effect.calls == 0,
            "Camera conversion sampled another track, accumulated base poses or executed playback effects");
        Replay legacyReplay = ReplayFactory.fromCamera(film, 3);
        check(legacyReplay.keyframes.x.interpolate(2F) == 100D,
            "The public legacy-camera factory no longer samples film.camera");
    }

    private static final class SampleClip extends CameraClip implements CameraPoseClip
    {
        private final IEntity actor;
        private int calls;

        private SampleClip(IEntity actor)
        {
            this.actor = actor;
        }

        @Override
        public void applyClip(ClipContext context, Position position)
        {
            check(((CameraClipContext) context).entities.get("actor") == this.actor, "Camera conversion lost replay bindings");
            position.point.x += context.ticks;
            this.calls++;
        }

        @Override
        protected Clip create()
        {
            return new SampleClip(this.actor);
        }
    }

    private static final class EffectClip extends CameraClip
    {
        private int calls;

        @Override
        public void applyClip(ClipContext context, Position position)
        {
            this.calls++;
        }

        @Override
        protected Clip create()
        {
            return new EffectClip();
        }
    }

    private static void check(boolean condition, String message)
    {
        if (!condition) throw new AssertionError(message);
    }
}
