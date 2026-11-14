package mchorse.bbs_mod.ui.film.replays;

import mchorse.bbs_mod.blocks.entities.ModelBlockEntity;
import mchorse.bbs_mod.blocks.entities.ModelProperties;
import mchorse.bbs_mod.camera.CameraPoseEvaluator;
import mchorse.bbs_mod.camera.data.Position;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.AnchorForm;
import mchorse.bbs_mod.forms.forms.BodyPart;
import mchorse.bbs_mod.forms.forms.StructureForm;
import mchorse.bbs_mod.utils.pose.Transform;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.Collections;
import java.util.Map;

/** Data assembly for replays created from world positions, cameras, model blocks and structures. */
public class ReplayFactory
{
    public static Replay atPosition(Film film, Vector3d position, float pitch, float yaw)
    {
        Replay replay = film.replays.addReplay();

        replay.category.set("");
        replay.keyframes.x.insert(0, position.x);
        replay.keyframes.y.insert(0, position.y);
        replay.keyframes.z.insert(0, position.z);
        replay.keyframes.pitch.insert(0, (double) pitch);
        replay.keyframes.yaw.insert(0, (double) yaw);
        replay.keyframes.headYaw.insert(0, (double) yaw);
        replay.keyframes.bodyYaw.insert(0, (double) yaw);

        return replay;
    }

    public static Replay fromCamera(Film film, int duration)
    {
        return fromCamera(film, Film.LEGACY_CAMERA_ID, duration, Collections.emptyMap());
    }

    /** Sample the selected FSR camera without publishing audio, subtitles or other playback effects. */
    public static Replay fromCamera(Film film, String cameraId, int duration, Map<String, IEntity> entities)
    {
        Replay replay = film.replays.addReplay();
        CameraPoseEvaluator evaluator = new CameraPoseEvaluator();

        replay.category.set("");

        try
        {
            for (int i = 0; i < duration; i++)
            {
                evaluator.beginFrame(i, film, i, 0F, false, entities);
                Position position = evaluator.evaluate(cameraId, null);
                double yaw = position.angle.yaw - 180D;

                replay.keyframes.x.insert(i, position.point.x);
                replay.keyframes.y.insert(i, position.point.y);
                replay.keyframes.z.insert(i, position.point.z);
                replay.keyframes.yaw.insert(i, yaw);
                replay.keyframes.headYaw.insert(i, yaw);
                replay.keyframes.bodyYaw.insert(i, yaw);
                replay.keyframes.pitch.insert(i, (double) position.angle.pitch);
            }
        }
        finally
        {
            evaluator.close();
        }

        return replay;
    }

    public static Replay fromModelBlock(Film film, ModelBlockEntity modelBlock)
    {
        return fromModelBlock(film, modelBlock.getBlockPos(), modelBlock.getProperties());
    }

    public static Replay fromModelBlock(Film film, BlockPos blockPos, ModelProperties properties)
    {
        Replay replay = film.replays.addReplay();
        Transform transform = properties.getTransform().copy();
        double x = blockPos.getX() + transform.translate.x + 0.5D;
        double y = blockPos.getY() + transform.translate.y;
        double z = blockPos.getZ() + transform.translate.z + 0.5D;

        replay.category.set("");
        transform.translate.set(0F, 0F, 0F);
        replay.shadow.set(properties.isShadow());
        replay.form.set(FormUtils.copy(properties.getForm()));
        replay.keyframes.x.insert(0, x);
        replay.keyframes.y.insert(0, y);
        replay.keyframes.z.insert(0, z);

        Vector3f rotation = transform.getEulerRotation(new Vector3f());

        if (!transform.isDefault())
        {
            if (rotation.x == 0F && rotation.z == 0F
                && transform.scale.x == 1F && transform.scale.y == 1F && transform.scale.z == 1F)
            {
                double yaw = -Math.toDegrees(rotation.y);

                replay.keyframes.yaw.insert(0, yaw);
                replay.keyframes.headYaw.insert(0, yaw);
                replay.keyframes.bodyYaw.insert(0, yaw);
            }
            else
            {
                AnchorForm form = new AnchorForm();
                BodyPart part = new BodyPart("");

                part.setForm(replay.form.get());
                form.transform.set(transform);
                form.parts.addBodyPart(part);
                replay.form.set(form);
            }
        }

        return replay;
    }

    public static Replay fromStructure(Film film, String id, BlockPos min, Vec3i size)
    {
        StructureForm form = new StructureForm();
        Replay replay = film.replays.addReplay();

        form.structure.set(id);
        form.name.set(id.substring(id.lastIndexOf('/') + 1));
        replay.category.set("");
        replay.form.set(form);
        replay.keyframes.x.insert(0, min.getX() + size.getX() / 2D);
        replay.keyframes.y.insert(0, (double) min.getY());
        replay.keyframes.z.insert(0, min.getZ() + size.getZ() / 2D);

        return replay;
    }
}
