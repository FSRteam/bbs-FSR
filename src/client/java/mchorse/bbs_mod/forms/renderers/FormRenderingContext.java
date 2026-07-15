package mchorse.bbs_mod.forms.renderers;

import mchorse.bbs_mod.camera.Camera;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.ui.framework.elements.utils.StencilMap;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.interps.Lerps;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import org.joml.Quaternionf;

public class FormRenderingContext
{
    public FormRenderType type;
    public IEntity entity;
    public Object simulationOwner;
    public PoseStack stack;
    public PoseStack world;
    public int light;
    public int overlay;
    public float transition;
    public final Camera camera = new Camera();
    public StencilMap stencilMap;
    public boolean ui;
    public int color;
    public boolean modelRenderer;

    private final Quaternionf cameraRotation = new Quaternionf();

    public FormRenderingContext()
    {}

    public FormRenderingContext set(FormRenderType type, IEntity entity, PoseStack stack, int light, int overlay, float transition)
    {
        this.type = type == null ? FormRenderType.ENTITY : type;
        this.entity = entity;
        this.simulationOwner = entity;
        this.stack = stack;
        this.world = new PoseStack();
        this.light = light;
        this.overlay = overlay;
        this.transition = transition;
        this.stencilMap = null;
        this.ui = false;
        this.color = 0xffffffff;
        this.modelRenderer = false;

        if (entity != null && (this.type == FormRenderType.ENTITY || this.type == FormRenderType.MODEL_BLOCK))
        {
            double x = Lerps.lerp(entity.getPrevX(), entity.getX(), transition);
            double y = Lerps.lerp(entity.getPrevY(), entity.getY(), transition);
            double z = Lerps.lerp(entity.getPrevZ(), entity.getZ(), transition);
            float bodyYaw = Lerps.lerp(entity.getPrevBodyYaw(), entity.getBodyYaw(), transition);

            this.world.translate(x, y, z);
            this.world.mulPose(Axis.YP.rotationDegrees(-bodyYaw));
        }

        return this;
    }

    public FormRenderingContext camera(Camera camera)
    {
        this.camera.copy(camera);
        this.camera.updateView();

        return this;
    }

    public FormRenderingContext camera(net.minecraft.client.Camera camera)
    {
        this.camera.position.set(camera.getPosition().x, camera.getPosition().y, camera.getPosition().z);
        this.camera.rotation.set(MathUtils.toRad(-camera.getXRot()), MathUtils.toRad(camera.getYRot()), 0F);
        this.camera.fov = MathUtils.toRad(Minecraft.getInstance().options.fov().get());
        this.camera.view.identity().rotate(camera.rotation().conjugate(this.cameraRotation));

        return this;
    }

    public FormRenderingContext stencilMap(StencilMap stencilMap)
    {
        this.stencilMap = stencilMap;

        return this;
    }

    public FormRenderingContext inUI()
    {
        this.ui = true;

        return this;
    }

    public FormRenderingContext color(int color)
    {
        this.color = color;

        return this;
    }

    public FormRenderingContext modelRenderer()
    {
        this.modelRenderer = true;

        return this;
    }

    public FormRenderingContext simulationOwner(Object owner)
    {
        this.simulationOwner = owner == null ? this.entity : owner;

        return this;
    }

    public float getTransition()
    {
        return this.transition;
    }

    public boolean isPicking()
    {
        return this.stencilMap != null;
    }

    public int getPickingIndex()
    {
        return this.stencilMap == null ? -1 : this.stencilMap.objectIndex;
    }
}
