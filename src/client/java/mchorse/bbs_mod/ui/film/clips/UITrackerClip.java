package mchorse.bbs_mod.ui.film.clips;

import io.netty.util.collection.IntObjectMap;
import mchorse.bbs_mod.camera.clips.misc.TrackerClientClip;
import mchorse.bbs_mod.camera.clips.misc.TrackerFrame;
import mchorse.bbs_mod.camera.data.Angle;
import mchorse.bbs_mod.camera.data.Point;
import mchorse.bbs_mod.camera.data.Position;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.film.IUIClipsDelegate;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.clips.modules.UIPointModule;
import mchorse.bbs_mod.ui.film.clips.widgets.UIBitToggle;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories.UIAnchorKeyframeFactory;
import org.joml.Vector3d;

public class UITrackerClip extends UIClip<TrackerClientClip>
{
    public UIButton selector;
    public UIButton group;

    public UIPointModule point;
    public UIPointModule angle;
    public UITrackpad fov;
    public UIToggle lookAt;
    public UIToggle relative;
    public UIBitToggle active;

    public UITrackerClip(TrackerClientClip clip, IUIClipsDelegate editor)
    {
        super(clip, editor);
    }

    public void registerUI()
    {
        super.registerUI();

        this.selector = new UIButton(UIKeys.CAMERA_PANELS_TARGET_TITLE, (b) ->
        {
            UIFilmPanel panel = this.getParent(UIFilmPanel.class);

            if (panel != null)
            {
                UIAnchorKeyframeFactory.displayActors(this.getContext(), panel.getController().getEntities(), this.clip.selector.get(), (i) -> this.clip.selector.set(i));
            }
        });
        this.selector.tooltip(UIKeys.CAMERA_PANELS_TARGET_TOOLTIP);
        this.group = new UIButton(UIKeys.GENERIC_KEYFRAMES_ANCHOR_PICK_ATTACHMENT, (b) ->
        {
            UIAnchorKeyframeFactory.displayAttachments(this.getParent(UIFilmPanel.class), this.clip.selector.get(), this.clip.group.get(), (attachment) -> this.clip.group.set(attachment));
        });

        this.point = new UIPointModule(this.editor, UIKeys.CAMERA_PANELS_OFFSET).contextMenu();
        this.angle = new UIPointModule(this.editor, UIKeys.CAMERA_PANELS_ANGLE).contextMenu();
        this.fov = new UITrackpad((v) -> this.clip.fov.set(v.floatValue()));
        this.fov.tooltip(UIKeys.CAMERA_PANELS_FOV);
        this.lookAt = new UIToggle(UIKeys.CAMERA_PANELS_LOOK_AT, b -> this.clip.lookAt.set(b.getValue()));
        this.relative = new UIToggle(UIKeys.CAMERA_PANELS_RELATIVE, b -> this.clip.relative.set(b.getValue()));
        this.active = new UIBitToggle((value) -> this.clip.active.set(value)).all();
    }

    @Override
    protected void registerPanels()
    {
        super.registerPanels();

        this.panels.add(this.section(UIKeys.CAMERA_PANELS_TARGET, this.selector, this.group));

        this.panels.add(this.point);
        this.panels.add(this.angle);
        this.panels.add(this.fov);
        this.panels.add(this.lookAt);
        this.panels.add(this.relative);
        this.panels.add(this.active);
    }

    @Override
    public void editClip(Position position)
    {
        this.applyCameraPosition(position);

        super.editClip(position);
    }

    private void applyCameraPosition(Position position)
    {
        TrackerFrame frame = this.resolveFrame(position);

        if (frame == null)
        {
            return;
        }

        Point offset = this.clip.offset.get();
        Point angle = this.clip.angle.get();
        boolean lookAt = this.clip.lookAt.get();

        if (!lookAt && this.isActive(0, 1, 2))
        {
            Vector3d current = frame.position(offset);
            Point solved = frame.solveOffset(
                this.pick(0, position.point.x, current.x),
                this.pick(1, position.point.y, current.y),
                this.pick(2, position.point.z, current.z)
            );

            if (solved != null)
            {
                this.clip.offset.set(solved);
            }
        }

        if (this.isActive(3, 4, 5))
        {
            Angle current = lookAt ? frame.lookAtAngles(offset, angle) : frame.angles(angle);
            float yaw = this.pick(3, position.angle.yaw, current.yaw);
            float pitch = this.pick(4, position.angle.pitch, current.pitch);
            float roll = this.pick(5, position.angle.roll, current.roll);

            this.clip.angle.set(lookAt
                ? frame.solveLookAtAngles(offset, yaw, pitch, roll)
                : frame.solveAngles(yaw, pitch, roll));
        }

        if (this.clip.isActive(6) && position.angle.fov != this.clip.fov.get())
        {
            this.clip.fov.set(position.angle.fov);
        }
    }

    private TrackerFrame resolveFrame(Position position)
    {
        UIFilmPanel panel = this.getParent(UIFilmPanel.class);
        UIContext context = this.getContext();
        int selector = this.clip.selector.get();

        if (panel == null || context == null || selector < 0)
        {
            return null;
        }

        IntObjectMap<IEntity> entities = panel.getController().getEntities();
        TrackerFrame frame = TrackerFrame.resolve(
            entities,
            entities.get(selector),
            this.clip.group.get(),
            position.point.x,
            position.point.y,
            position.point.z,
            context.getTransition()
        );

        if (frame != null && this.clip.relative.get())
        {
            if (!this.clip.isEvaluated())
            {
                return null;
            }

            frame.relative(this.clip.getUnderneath(), this.clip.position);
        }

        return frame;
    }

    private boolean isActive(int... bits)
    {
        for (int bit : bits)
        {
            if (this.clip.isActive(bit))
            {
                return true;
            }
        }

        return false;
    }

    private float pick(int bit, float camera, float current)
    {
        return this.clip.isActive(bit) ? camera : current;
    }

    private double pick(int bit, double camera, double current)
    {
        return this.clip.isActive(bit) ? camera : current;
    }

    @Override
    public void fillData()
    {
        super.fillData();

        this.point.fill(this.clip.offset);
        this.angle.fill(this.clip.angle);
        this.fov.setValue(this.clip.fov.get());
        this.lookAt.setValue(this.clip.lookAt.get());
        this.relative.setValue(this.clip.relative.get());
        this.active.setValue(this.clip.active.get());
    }
}
