package mchorse.bbs_mod.forms.renderers;

import mchorse.bbs_mod.camera.Camera;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.film.replays.FormProperties;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.ui.framework.elements.utils.StencilMap;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.MatrixStackUtils;
import mchorse.bbs_mod.utils.interps.Lerps;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

public class FormRenderingContext
{
    public FormRenderType type;
    public FormRenderSpace renderSpace;
    public IEntity entity;
    public Object simulationOwner;
    public PoseStack stack;
    public PoseStack world;
    public boolean allowWorldTargetOverrides;
    public boolean allowWorldCollisions;
    public int light;
    public int overlay;
    public float transition;
    public final Camera camera = new Camera();
    public StencilMap stencilMap;
    public boolean ui;
    public int color;
    public boolean modelRenderer;
    public FormProperties timelineProperties;
    public float timelineTick;
    public boolean timelinePlaying;

    private final Quaternionf cameraRotation = new Quaternionf();

    public FormRenderingContext()
    {}

    public FormRenderingContext set(FormRenderType type, IEntity entity, PoseStack stack, int light, int overlay, float transition)
    {
        this.type = type == null ? FormRenderType.ENTITY : type;
        this.renderSpace = FormRenderSpace.forType(this.type);
        this.entity = entity;
        this.simulationOwner = entity;
        this.stack = stack;
        this.world = new PoseStack();
        this.allowWorldTargetOverrides = this.type.hasWorldHost();
        this.allowWorldCollisions = this.type.hasWorldHost();
        this.light = light;
        this.overlay = overlay;
        this.transition = transition;
        this.stencilMap = null;
        this.ui = false;
        this.color = 0xffffffff;
        this.modelRenderer = false;
        this.timelineProperties = null;
        this.timelineTick = Float.NaN;
        this.timelinePlaying = false;

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
        this.renderSpace = FormRenderSpace.UI_LOCAL;
        this.localSimulation();

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
        this.renderSpace = FormRenderSpace.UI_LOCAL;
        this.localSimulation();

        return this;
    }

    public FormRenderingContext renderSpace(FormRenderSpace renderSpace)
    {
        this.renderSpace = renderSpace == null ? FormRenderSpace.forType(this.type) : renderSpace;

        return this;
    }

    public FormRenderingContext cameraRelativeWorld()
    {
        return this.renderSpace(FormRenderSpace.CAMERA_RELATIVE_WORLD);
    }

    /**
     * Set an absolute semantic transform from a camera-relative render target.
     * The target can already include film-anchor/bone transforms; adding the
     * camera origin converts the exact matrix shown on screen back to world
     * space without leaking the view rotation into simulation history.
     */
    public FormRenderingContext semanticWorldFromCameraRelative(Matrix4f target, double cameraX, double cameraY, double cameraZ)
    {
        PoseStack semanticWorld = new PoseStack();

        semanticWorld.translate(cameraX, cameraY, cameraZ);

        if (target != null)
        {
            MatrixStackUtils.multiply(semanticWorld, target);
        }

        this.world = semanticWorld;

        return this;
    }

    public FormRenderingContext semanticWorld(PoseStack world)
    {
        if (world != null)
        {
            this.world = world;
        }

        return this;
    }

    /** Use stable model-local simulation and reject all absolute-world inputs. */
    public FormRenderingContext localSimulation()
    {
        this.allowWorldTargetOverrides = false;
        this.allowWorldCollisions = false;

        return this;
    }

    public FormRenderingContext entityLocal(PoseStack world)
    {
        this.renderSpace = FormRenderSpace.ENTITY_LOCAL;

        if (world != null)
        {
            this.world = world;
        }

        return this;
    }

    public FormRenderingContext simulationOwner(Object owner)
    {
        this.simulationOwner = owner == null ? this.entity : owner;

        return this;
    }

    /** Attach the Film timeline state after its form properties were evaluated. */
    public FormRenderingContext timeline(FormProperties properties, float tick, boolean playing)
    {
        this.timelineProperties = properties;
        this.timelineTick = tick;
        this.timelinePlaying = playing;

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

    /**
     * Whether this render call belongs to the world translucent stage. The
     * deferred queue is global for a frame, therefore render type alone is
     * insufficient: editor, framebuffer and stencil previews can run while a
     * world queue is still active.
     */
    public boolean canDeferWorldTranslucency()
    {
        return BBSRendering.isRenderingWorld()
            && this.renderSpace != null
            && this.renderSpace.isWorld()
            && !this.isPicking()
            && !this.ui
            && !this.modelRenderer;
    }

    public int getPickingIndex()
    {
        return this.stencilMap == null ? -1 : this.stencilMap.objectIndex;
    }
}
