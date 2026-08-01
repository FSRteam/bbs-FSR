package mchorse.bbs_mod.film;

import io.netty.util.collection.IntObjectMap;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.film.replays.FormProperties;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.ui.framework.elements.utils.StencilMap;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.client.rendering.context.IBbsWorldRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.MultiBufferSource;
import com.mojang.blaze3d.vertex.PoseStack;

public class FilmControllerContext
{
    public final static FilmControllerContext instance = new FilmControllerContext();

    public IntObjectMap<IEntity> entities;
    public IEntity entity;
    public Replay replay;
    public Camera camera;
    public PoseStack stack;
    public MultiBufferSource.BufferSource consumers;
    public StencilMap map;

    public float transition;
    public int color;
    public float shadowRadius;

    public String bone;
    public boolean local;

    public String bone2;
    public boolean local2;

    public boolean anchorGizmo;
    public boolean anchorLocal;

    public String nameTag = "";
    public boolean relative;
    public FormProperties timelineProperties;
    public float timelineTick;
    public boolean timelinePlaying;
    /** Skip the visible form render but still capture gizmo placement. */
    public boolean gizmoOnly;

    private FilmControllerContext()
    {}

    private void reset()
    {
        this.map = null;
        this.shadowRadius = 0F;
        this.color = Colors.WHITE;
        this.bone = null;
        this.local = false;
        this.anchorGizmo = false;
        this.anchorLocal = false;
        this.nameTag = "";
        this.relative = false;
        this.timelineProperties = null;
        this.timelineTick = Float.NaN;
        this.timelinePlaying = false;
        this.gizmoOnly = false;
    }

    public FilmControllerContext setup(IntObjectMap<IEntity> entities, IEntity entity, Replay replay, IBbsWorldRenderContext context)
    {
        this.reset();

        this.entities = entities;
        this.entity = entity;
        this.replay = replay;
        this.camera = context.camera();
        this.stack = context.matrixStack();
        this.consumers = context.consumers();
        this.transition = context.tickDelta();

        return this;
    }

    public FilmControllerContext setup(IntObjectMap<IEntity> entities, IEntity entity, Replay replay, Camera camera, PoseStack stack, MultiBufferSource.BufferSource consumers, float transition)
    {
        this.reset();

        this.entities = entities;
        this.entity = entity;
        this.replay = replay;
        this.camera = camera;
        this.stack = stack;
        this.consumers = consumers;
        this.transition = transition;

        return this;
    }

    public FilmControllerContext transition(float transition)
    {
        this.transition = transition;

        return this;
    }

    public FilmControllerContext stencil(StencilMap map)
    {
        this.map = map;

        return this;
    }

    public FilmControllerContext shadow(boolean shadow, float shadowRadius)
    {
        this.shadowRadius = shadow ? shadowRadius : 0F;

        return this;
    }

    public FilmControllerContext shadow(float shadowRadius)
    {
        this.shadowRadius = shadowRadius;

        return this;
    }

    public FilmControllerContext color(int overlayColor)
    {
        this.color = overlayColor;

        return this;
    }

    public FilmControllerContext bone(String bone, boolean local)
    {
        this.bone = bone;
        this.local = local;

        return this;
    }

    public FilmControllerContext bone2(String bone, boolean local)
    {
        this.bone2 = bone;
        this.local2 = local;

        return this;
    }

    public FilmControllerContext anchorGizmo(boolean anchorGizmo, boolean anchorLocal)
    {
        this.anchorGizmo = anchorGizmo;
        this.anchorLocal = anchorLocal;

        return this;
    }

    public FilmControllerContext nameTag(String nameTag)
    {
        this.nameTag = nameTag;

        return this;
    }

    public FilmControllerContext relative(boolean relative)
    {
        this.relative = relative;

        return this;
    }

    public FilmControllerContext timeline(FormProperties properties, float tick, boolean playing)
    {
        this.timelineProperties = properties;
        this.timelineTick = tick;
        this.timelinePlaying = playing;

        return this;
    }

    public FilmControllerContext gizmoOnly(boolean gizmoOnly)
    {
        this.gizmoOnly = gizmoOnly;

        return this;
    }
}
