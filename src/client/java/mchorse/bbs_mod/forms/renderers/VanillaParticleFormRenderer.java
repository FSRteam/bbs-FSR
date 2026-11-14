package mchorse.bbs_mod.forms.renderers;

import com.mojang.brigadier.StringReader;
import mchorse.bbs_mod.forms.ITickable;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.VanillaParticleForm;
import mchorse.bbs_mod.forms.forms.utils.ParticleSettings;
import mchorse.bbs_mod.particles.vanilla.VanillaParticleScene;
import mchorse.bbs_mod.particles.vanilla.VanillaParticlePreview;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.joml.Matrices;
import mchorse.bbs_mod.utils.joml.Vectors;
import net.minecraft.commands.arguments.ParticleArgument;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;

public class VanillaParticleFormRenderer extends FormRenderer<VanillaParticleForm> implements ITickable
{
    public static final Link PARTICLE_PREVIEW = new Link("minecraft", "textures/particle/flame.png");

    private Vector3d pos = new Vector3d();
    private Vector3f vel = new Vector3f();
    private Matrix3f rot = new Matrix3f();
    private Matrix4f renderMatrix = new Matrix4f();
    private Vector3d renderTranslation = new Vector3d();
    private Vector3f tempOffset = new Vector3f();
    private int tick;
    private VanillaParticleScene scene;
    private int previewTicks;
    private int worldRenderTicks;
    private long lastPreviewTick = Long.MIN_VALUE;

    private static final int RENDER_GRACE_TICKS = 4;
    private static final int MAX_CATCHUP = 10;

    public VanillaParticleFormRenderer(VanillaParticleForm form)
    {
        super(form);
    }

    @Override
    protected void renderInUI(UIContext context, int x1, int y1, int x2, int y2)
    {
        int size = Math.min(x2 - x1, y2 - y1) - 4;
        float x = (x1 + x2 - size) / 2F;
        float y = (y1 + y2 - size) / 2F;

        VanillaParticlePreview.render(context, this.form.settings.get().particle, x, y, size, Colors.WHITE);
    }

    @Override
    protected void render3D(FormRenderingContext context)
    {
        super.render3D(context);

        this.updatePlacement(context);

        if (context.modelRenderer && !context.isPicking())
        {
            this.previewTicks = RENDER_GRACE_TICKS;
            this.updatePreview(context.modelRendererTick);
            this.getScene().render(context.camera, context.getTransition());
        }
    }

    private void updatePlacement(FormRenderingContext context)
    {
        FormRenderSpace renderSpace = context.renderSpace == null
            ? FormRenderSpace.forType(context.type)
            : context.renderSpace;
        Matrix4f matrix = this.renderMatrix;

        if (renderSpace == FormRenderSpace.CAMERA_RELATIVE_WORLD)
        {
            matrix.set(context.stack.last().pose());
        }
        else if (renderSpace == FormRenderSpace.ENTITY_LOCAL && context.world != null)
        {
            matrix.set(context.world.last().pose());
        }
        else
        {
            matrix.set(context.camera.view).invert().mul(context.stack.last().pose());
        }

        Vector3f translation = matrix.getTranslation(Vectors.TEMP_3F);
        this.renderTranslation.set(translation.x, translation.y, translation.z);

        if (renderSpace != FormRenderSpace.ENTITY_LOCAL)
        {
            this.renderTranslation.add(context.camera.position.x, context.camera.position.y, context.camera.position.z);
        }

        if (renderSpace.isWorld())
        {
            this.worldRenderTicks = RENDER_GRACE_TICKS;
        }

        this.pos.set(this.renderTranslation);
        this.vel.set(0F, 0F, 1F);
        this.rot.set(matrix).transform(this.vel);
    }

    private VanillaParticleScene getScene()
    {
        if (this.scene == null)
        {
            this.scene = new VanillaParticleScene();
        }

        return this.scene;
    }

    private void updatePreview(long previewTick)
    {
        VanillaParticleScene scene = this.getScene();

        if (this.lastPreviewTick == Long.MIN_VALUE)
        {
            this.lastPreviewTick = previewTick;
        }

        long elapsed = previewTick - this.lastPreviewTick;
        this.lastPreviewTick = previewTick;

        if (elapsed > MAX_CATCHUP)
        {
            scene.clear();
            elapsed = 1;
        }

        for (long i = 0; i < elapsed; i++)
        {
            scene.tick();
            this.emitTick(scene::spawn);
        }
    }

    @Override
    public void tick(IEntity entity)
    {
        if (this.previewTicks > 0)
        {
            this.previewTicks -= 1;
            return;
        }

        if (this.scene != null)
        {
            this.scene = null;
            this.lastPreviewTick = Long.MIN_VALUE;
        }

        Level world = entity.level();
        if (world == null)
        {
            return;
        }

        if (this.worldRenderTicks > 0)
        {
            this.worldRenderTicks -= 1;
        }
        else
        {
            this.updateFromEntity(entity);
        }

        this.emitTick((effect, x, y, z, velocityX, velocityY, velocityZ) ->
            world.addParticle(effect, true, x, y, z, velocityX, velocityY, velocityZ));
    }

    private void updateFromEntity(IEntity entity)
    {
        Matrix4f matrix = new Matrix4f().rotateY(MathUtils.toRad(-entity.getBodyYaw()));

        matrix.mul(this.createTransform().createMatrix());

        Vector3f translation = matrix.getTranslation(new Vector3f());

        this.pos.set(entity.getX() + translation.x, entity.getY() + translation.y, entity.getZ() + translation.z);
        this.rot.set(matrix);
        this.vel.set(0F, 0F, 1F);
        this.rot.transform(this.vel);
    }

    private void emitTick(ParticleSink sink)
    {
        if (this.form.paused.get())
        {
            return;
        }

        if (this.tick <= 0)
        {
            this.emit(sink);
            this.tick = this.form.frequency.get();
        }

        this.tick -= 1;
    }

    private void emit(ParticleSink sink)
    {
        Matrix3f m = Matrices.TEMP_3F;
        Vector3f v = Vectors.TEMP_3F;
        Vector3f temp3f = this.tempOffset;
        float velocity = this.form.velocity.get();
        int count = this.form.count.get();
        ParticleOptions effect = this.getEffect();
        for (int i = 0; i < count; i++)
        {
            float velocityX = this.vel.x * velocity;
            float velocityY = this.vel.y * velocity;
            float velocityZ = this.vel.z * velocity;
            float sh = MathUtils.toRad(this.form.scatteringYaw.get()) * (float) (Math.random() - 0.5D);
            float sv = MathUtils.toRad(this.form.scatteringPitch.get()) * (float) (Math.random() - 0.5D);

            m.identity().rotateY(sh).rotateX(sv).transform(v.set(velocityX, velocityY, velocityZ));
            temp3f.set(
                (Math.random() * 2F - 1F) * this.form.offsetX.get(),
                (Math.random() * 2F - 1F) * this.form.offsetY.get(),
                (Math.random() * 2F - 1F) * this.form.offsetZ.get()
            );

            if (this.form.local.get())
            {
                this.rot.transform(temp3f);
            }

            sink.spawn(effect, this.pos.x + temp3f.x, this.pos.y + temp3f.y, this.pos.z + temp3f.z,
                v.x, v.y, v.z);
        }
    }

    private ParticleOptions getEffect()
    {
        return createEffect(this.form.settings.get(), Minecraft.getInstance().level);
    }

    private static ParticleOptions createEffect(ParticleSettings settings, Level world)
    {
        String args = settings.arguments == null ? "" : settings.arguments.trim();

        try
        {
            ParticleType<?> type = BuiltInRegistries.PARTICLE_TYPE.get(settings.particle);
            boolean bareId = !args.isEmpty() && args.charAt(0) != '{';

            if (bareId && type == ParticleTypes.BLOCK)
            {
                return new BlockParticleOption(ParticleTypes.BLOCK, BuiltInRegistries.BLOCK.get(ResourceLocation.parse(args)).defaultBlockState());
            }

            if (bareId && type == ParticleTypes.ITEM)
            {
                return new ItemParticleOption(ParticleTypes.ITEM, new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(args))));
            }

            return ParticleArgument.readParticle(new StringReader(settings.particle + args), world.registryAccess());
        }
        catch (Exception ignored)
        {
            return ParticleTypes.FLAME;
        }
    }

    private interface ParticleSink
    {
        void spawn(ParticleOptions effect, double x, double y, double z, double velocityX, double velocityY, double velocityZ);
    }
}
