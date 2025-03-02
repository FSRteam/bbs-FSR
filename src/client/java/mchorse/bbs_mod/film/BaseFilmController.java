package mchorse.bbs_mod.film;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.client.renderer.ModelBlockEntityRenderer;
import mchorse.bbs_mod.entity.ActorEntity;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.entities.MCEntity;
import mchorse.bbs_mod.forms.entities.StubEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.properties.AnchorProperty;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import mchorse.bbs_mod.graphics.Draw;
import mchorse.bbs_mod.ui.framework.UIBaseMenu;
import mchorse.bbs_mod.utils.CollectionUtils;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.MatrixStackUtils;
import mchorse.bbs_mod.utils.StringUtils;
import mchorse.bbs_mod.utils.interps.Lerps;
import mchorse.bbs_mod.utils.joml.Matrices;
import mchorse.bbs_mod.utils.joml.Vectors;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.LightType;
import net.minecraft.world.World;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public abstract class BaseFilmController
{
    public final Film film;

    protected List<IEntity> entities = new ArrayList<>();

    public int exception = -1;

    /* Rendering helpers */

    public static void renderEntity(FilmControllerContext context)
    {
        List<IEntity> entities = context.entities;
        IEntity entity = context.entity;
        Camera camera = context.camera;
        MatrixStack stack = context.stack;
        float transition = context.transition;

        Form form = entity.getForm();

        if (form == null)
        {
            return;
        }

        Vector3d position = Vectors.TEMP_3D.set(
            Lerps.lerp(entity.getPrevX(), entity.getX(), transition),
            Lerps.lerp(entity.getPrevY(), entity.getY(), transition),
            Lerps.lerp(entity.getPrevZ(), entity.getZ(), transition)
        );

        AnchorProperty.Anchor value = form.anchor.get();
        Matrix4f target = null;
        double cx = camera.getPos().x;
        double cy = camera.getPos().y;
        double cz = camera.getPos().z;
        Matrix4f defaultMatrix = getMatrixForRenderWithRotation(entity, cx, cy, cz, transition);
        float opacity = 1F;

        boolean same = value.previousActor == -2
            || (value.actor == value.previousActor && Objects.equals(value.attachment, value.previousAttachment));

        if (same)
        {
            Matrix4f matrix = getEntityMatrix(entities, cx, cy, cz, value.actor, value.attachment, defaultMatrix, transition);

            if (matrix != defaultMatrix)
            {
                target = matrix;
                opacity = 0F;
            }
        }
        else if (value.x <= 0F && value.previousActor >= -1)
        {
            Matrix4f matrix = getEntityMatrix(entities, cx, cy, cz, value.previousActor, value.previousAttachment, defaultMatrix, transition);

            if (matrix != defaultMatrix)
            {
                target = matrix;
                opacity = 0F;
            }
        }
        else
        {
            Matrix4f matrix = getEntityMatrix(entities, cx, cy, cz, value.actor, value.attachment, defaultMatrix, transition);
            Matrix4f lastMatrix = getEntityMatrix(entities, cx, cy, cz, value.previousActor, value.previousAttachment, defaultMatrix, transition);

            if (matrix != lastMatrix)
            {
                float factor = value.x;

                target = factor >= 1F ? matrix : Matrices.lerp(lastMatrix, matrix, factor);

                if (value.actor == -1 && value.previousActor >= 0) opacity = factor;
                else if (value.actor >= 0 && value.previousActor == -1) opacity = 1F - factor;
                else opacity = 0F;
            }
        }

        if (target != null)
        {
            Vector3f v = target.getTranslation(new Vector3f());
            Vector3f v2 = defaultMatrix.getTranslation(new Vector3f());

            position.x += v.x - v2.x;
            position.y += v.y - v2.y;
            position.z += v.z - v2.z;
        }

        BlockPos pos = BlockPos.ofFloored(position.x, position.y + 0.5D, position.z);
        int sky = entity.getWorld().getLightLevel(LightType.SKY, pos);
        int torch = entity.getWorld().getLightLevel(LightType.BLOCK, pos);
        int light = LightmapTextureManager.pack(torch, sky);
        int overlay = OverlayTexture.packUv(OverlayTexture.getU(0F), OverlayTexture.getV(entity.getHurtTimer() > 0));

        FormRenderingContext formContext = new FormRenderingContext()
            .set(entity, stack, light, overlay, transition)
            .camera(camera)
            .stencilMap(context.map)
            .color(context.color);

        stack.push();
        MatrixStackUtils.multiply(stack, target == null ? defaultMatrix : target);
        FormUtilsClient.render(form, formContext);

        if (context.bone != null && UIBaseMenu.renderAxes)
        {
            Form root = FormUtils.getRoot(form);
            MatrixStack tempStack = new MatrixStack();
            Map<String, Matrix4f> map = new HashMap<>();

            FormUtilsClient.getRenderer(root).collectMatrices(entity, context.local ? null : context.bone, tempStack, map, "", transition);

            Matrix4f matrix = map.get(context.bone);

            if (matrix != null)
            {
                stack.push();
                MatrixStackUtils.multiply(stack, matrix);
                Draw.coolerAxes(stack, 0.25F, 0.01F, 0.26F, 0.02F);
                RenderSystem.enableDepthTest();
                stack.pop();
            }
        }

        stack.pop();

        if (context.map == null && opacity > 0F && context.shadowRadius > 0F)
        {
            stack.push();
            stack.translate(position.x - cx, position.y - cy, position.z - cz);

            ModelBlockEntityRenderer.renderShadow(context.consumers, stack, transition, position.x, position.y, position.z, 0F, 0F, 0F, context.shadowRadius, opacity);

            stack.pop();
        }

        if (!context.nameTag.isEmpty() && context.map == null)
        {
            stack.push();
            stack.translate(position.x - cx, position.y - cy, position.z - cz);

            renderNameTag(entity, Text.literal(StringUtils.processColoredText(context.nameTag)), stack, context.consumers, light);

            stack.pop();
        }

        RenderSystem.enableDepthTest();
    }

    private static void renderNameTag(IEntity entity, Text text, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light)
    {
        boolean sneaking = !entity.isSneaking();
        float hitboxH = (float) entity.getPickingHitbox().h + 0.5F;

        matrices.push();
        matrices.translate(0F, hitboxH, 0F);
        matrices.multiply(MinecraftClient.getInstance().getEntityRenderDispatcher().getRotation());
        matrices.scale(-0.025F, -0.025F, 0.025F);

        Matrix4f matrix4f = matrices.peek().getPositionMatrix();
        TextRenderer textRenderer = MinecraftClient.getInstance().textRenderer;

        float opacity = MinecraftClient.getInstance().options.getTextBackgroundOpacity(0.25F);
        int background = (int) (opacity * 255F) << 24;
        float h = (float) (-textRenderer.getWidth(text) / 2);

        textRenderer.draw(text, h, 0, 0x20ffffff, false, matrix4f, vertexConsumers, sneaking ? TextRenderer.TextLayerType.SEE_THROUGH : TextRenderer.TextLayerType.NORMAL, background, light);

        if (sneaking)
        {
            textRenderer.draw(text, h, 0, -1, false, matrix4f, vertexConsumers, TextRenderer.TextLayerType.NORMAL, 0, light);
        }

        matrices.pop();
    }

    public static Matrix4f getEntityMatrix(List<IEntity> entities, double cameraX, double cameraY, double cameraZ, int actor, String attachment, Matrix4f defaultMatrix, float transition)
    {
        IEntity entity = CollectionUtils.getSafe(entities, actor);

        if (entity != null)
        {
            Matrix4f basic = new Matrix4f(getMatrixForRenderWithRotation(entity, cameraX, cameraY, cameraZ, transition));

            Map<String, Matrix4f> map = new HashMap<>();
            MatrixStack stack = new MatrixStack();

            Form form = entity.getForm();

            if (form != null)
            {
                FormUtilsClient.getRenderer(form).collectMatrices(entity, null, stack, map, "", transition);

                Matrix4f matrix = map.get(attachment);

                if (matrix != null)
                {
                    basic.mul(matrix);
                }
            }

            return basic;
        }

        return defaultMatrix;
    }

    public static Matrix4f getMatrixForRenderWithRotation(IEntity entity, double cameraX, double cameraY, double cameraZ, float tickDelta)
    {
        double x = Lerps.lerp(entity.getPrevX(), entity.getX(), tickDelta) - cameraX;
        double y = Lerps.lerp(entity.getPrevY(), entity.getY(), tickDelta) - cameraY;
        double z = Lerps.lerp(entity.getPrevZ(), entity.getZ(), tickDelta) - cameraZ;

        Matrix4f matrix = new Matrix4f();

        float bodyYaw = Lerps.lerp(entity.getPrevBodyYaw(), entity.getBodyYaw(), tickDelta);

        matrix.translate((float) x, (float) y, (float) z);
        matrix.rotateY(MathUtils.toRad(-bodyYaw));

        return matrix;
    }

    /* Film controller */

    public BaseFilmController(Film film)
    {
        this.film = film;
    }

    public List<IEntity> getEntities()
    {
        return this.entities;
    }

    public void createEntities()
    {
        this.entities.clear();

        if (this.film == null)
        {
            return;
        }

        for (Replay replay : this.film.replays.getList())
        {
            World world = MinecraftClient.getInstance().world;
            IEntity entity = new StubEntity(world);

            entity.setForm(FormUtils.copy(replay.form.get()));
            replay.applyFrame(0, entity);
            entity.setPrevX(entity.getX());
            entity.setPrevY(entity.getY());
            entity.setPrevZ(entity.getZ());

            entity.setPrevYaw(entity.getYaw());
            entity.setPrevHeadYaw(entity.getHeadYaw());
            entity.setPrevPitch(entity.getPitch());
            entity.setPrevBodyYaw(entity.getBodyYaw());

            this.entities.add(entity);
        }
    }

    public abstract Map<String, Integer> getActors();

    public abstract int getTick();

    public boolean hasFinished()
    {
        return false;
    }

    public void update()
    {
        this.updateEntities(this.getTick());
    }

    protected void updateEntities(int ticks)
    {
        for (int i = 0; i < this.entities.size(); i++)
        {
            IEntity entity = this.entities.get(i);
            List<Replay> replays = this.film.replays.getList();
            Replay replay = CollectionUtils.getSafe(replays, i);

            if (!this.canUpdate(i, replay, entity))
            {
                continue;
            }

            if (replay != null)
            {
                ticks = replay.getTick(ticks);

                this.updateEntityAndForm(entity, ticks);
                this.applyReplay(replay, ticks, entity);

                Map<String, Integer> actors = this.getActors();

                if (actors != null)
                {
                    Integer entityId = actors.get(replay.getId());

                    if (entityId != null)
                    {
                        Entity anEntity = MinecraftClient.getInstance().world.getEntityById(entityId);

                        if (anEntity instanceof ActorEntity actor)
                        {
                            /* Force synchronize entity angles */
                            actor.setYaw(replay.keyframes.yaw.interpolate(ticks).floatValue());
                            actor.setHeadYaw(replay.keyframes.headYaw.interpolate(ticks).floatValue());
                            actor.setBodyYaw(replay.keyframes.bodyYaw.interpolate(ticks).floatValue());
                            actor.setPitch(replay.keyframes.pitch.interpolate(ticks).floatValue());
                            replay.applyClientActions(ticks, new MCEntity(anEntity), this.film);
                        }
                    }
                }
            }
        }
    }

    protected void updateEntityAndForm(IEntity entity, int tick)
    {
        entity.update();

        if (entity.getForm() != null)
        {
            entity.getForm().update(entity);
        }
    }

    protected void applyReplay(Replay replay, int ticks, IEntity entity)
    {
        replay.applyFrame(ticks, entity, null);
        replay.applyClientActions(ticks, entity, this.film);
    }

    public void startRenderFrame(float transition)
    {
        for (int i = 0; i < this.entities.size(); i++)
        {
            Replay replay = this.film.replays.getList().get(i);
            IEntity entity = this.entities.get(i);

            if (!this.canUpdate(i, replay, entity))
            {
                continue;
            }

            int tick = replay.getTick(this.getTick());

            /* Apply property */
            replay.applyProperties(tick + transition, entity.getForm());

            Map<String, Integer> actors = this.getActors();

            if (actors != null)
            {
                Integer entityId = actors.get(replay.getId());

                if (entityId != null)
                {
                    Entity anEntity = MinecraftClient.getInstance().world.getEntityById(entityId);

                    if (anEntity instanceof ActorEntity actor)
                    {
                        replay.applyProperties(tick + transition, actor.getForm());
                    }
                }
            }
        }
    }

    protected boolean canUpdate(int i, Replay replay, IEntity entity)
    {
        return i != this.exception;
    }

    public void render(WorldRenderContext context)
    {
        RenderSystem.enableDepthTest();

        for (int i = 0; i < this.entities.size(); i++)
        {
            Replay replay = this.film.replays.getList().get(i);
            IEntity entity = this.entities.get(i);

            if (!this.canUpdate(i, replay, entity))
            {
                continue;
            }

            this.renderEntity(context, replay, entity);
        }
    }

    protected void renderEntity(WorldRenderContext context, Replay replay, IEntity entity)
    {
        if (!replay.actor.get())
        {
            renderEntity(getFilmControllerContext(context, replay, entity));
        }
    }

    protected FilmControllerContext getFilmControllerContext(WorldRenderContext context, Replay replay, IEntity entity)
    {
        return FilmControllerContext.instance
            .setup(this.entities, entity, context)
            .shadow(replay.shadow.get(), replay.shadowSize.get())
            .nameTag(replay.nameTag.get());
    }

    public void shutdown()
    {}
}