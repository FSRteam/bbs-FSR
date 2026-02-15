package mchorse.bbs_mod.ui.film.poses;

import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.renderers.ModelFormRenderer;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.replays.UIReplaysEditor;
import mchorse.bbs_mod.ui.film.utils.keyframes.UIFilmKeyframes;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeEditor;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeElement;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeGroup;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

public class UIPoseEditorPanel extends UIElement
{
    private static final List<String> TRANSFORM_GROUPS = Arrays.asList("translate", "scale", "rotate", "rotate2");

    public UIKeyframeEditor keyframeEditor;

    private final UIFilmPanel panel;
    
    private Replay replay;

    public UIPoseEditorPanel(UIFilmPanel panel)
    {
        this.panel = panel;
    }

    public void setReplay(Replay replay)
    {
        this.replay = replay;
        this.updateChannelsList();
    }

    private void updateChannelsList()
    {
        UIKeyframes lastEditor = null;

        if (this.keyframeEditor != null)
        {
            lastEditor = this.keyframeEditor.view;

            this.keyframeEditor.removeFromParent();
            this.keyframeEditor = null;
        }

        if (this.replay == null || !(this.replay.form.get() instanceof ModelForm form))
        {
            return;
        }

        ModelInstance model = ModelFormRenderer.getModel(form);

        if (model == null)
        {
            return;
        }

        List<String> bones = new ArrayList<>(model.model.getAllGroupKeys());
        Collections.sort(bones);

        List<UIKeyframeElement> elements = new ArrayList<>();

        for (String bone : bones)
        {
            UIKeyframeGroup boneGroup = new UIKeyframeGroup(IKey.raw(bone));
            boneGroup.color = this.getColorForBone(bone);

            for (String group : TRANSFORM_GROUPS)
            {
                String path = bone + "." + group;
                KeyframeChannel channel = this.replay.properties.properties.get(path);

                if (channel == null)
                {
                    channel = new KeyframeChannel(path, group.equals("scale") ? KeyframeFactories.VECTOR3F_SCALE : KeyframeFactories.VECTOR3F);
                    this.replay.properties.properties.put(path, channel);
                    this.replay.properties.add(channel);
                }

                if (channel.isEmpty())
                {
                    this.migrateAxisChannels(bone, group, channel);
                }

                int color = this.getColorForBone(bone);
                UIKeyframeSheet sheet = new UIKeyframeSheet(path, IKey.raw(group), color, false, channel, null);

                sheet.icon(this.getIconForGroup(group));
                boneGroup.add(sheet);
            }

            if (!boneGroup.children.isEmpty())
            {
                elements.add(boneGroup);
            }
        }

        if (!elements.isEmpty())
        {
            this.keyframeEditor = new UIKeyframeEditor((consumer) -> new UIFilmKeyframes(this.panel.cameraEditor, consumer).absolute()).target(this.panel.editArea);
            this.keyframeEditor.full(this);
            this.keyframeEditor.setUndoId("pose_keyframe_editor");

            if (lastEditor != null)
            {
                this.keyframeEditor.view.copyViewport(lastEditor);
            }

            this.keyframeEditor.view.duration(() -> this.panel.getData() == null ? 0 : this.panel.getData().camera.calculateDuration());
            this.keyframeEditor.view.backgroundRenderer((context) ->
            {
                if (this.panel.getData() == null)
                {
                    return;
                }

                UIKeyframes view = this.keyframeEditor.view;

                UIReplaysEditor.renderBackground(context, view, this.panel.getData().camera, 0);
            });

            for (UIKeyframeElement element : elements)
            {
                this.keyframeEditor.view.addElement(element);
            }

            this.add(this.keyframeEditor);
        }

        this.resize();

        if (this.keyframeEditor != null && lastEditor == null)
        {
            this.keyframeEditor.view.resetView();
        }
    }

    private int getColorForBone(String bone)
    {
        int rgb = bone.hashCode() & 0xFFFFFF;
        int r = (int) (((rgb >> 16) & 0xFF) * 0.8F);
        int g = (int) (((rgb >> 8) & 0xFF) * 0.8F);
        int b = (int) ((rgb & 0xFF) * 0.8F);
        return (r << 16) | (g << 8) | b;
    }

    private void migrateAxisChannels(String bone, String group, KeyframeChannel<Vector3f> target)
    {
        KeyframeChannel<Float> x = this.replay.properties.properties.get(bone + "." + group + ".x");
        KeyframeChannel<Float> y = this.replay.properties.properties.get(bone + "." + group + ".y");
        KeyframeChannel<Float> z = this.replay.properties.properties.get(bone + "." + group + ".z");

        if (x == null && y == null && z == null)
        {
            return;
        }

        if ((x == null || x.isEmpty()) && (y == null || y.isEmpty()) && (z == null || z.isEmpty()))
        {
            return;
        }

        Vector3f defaults = group.equals("scale") ? new Vector3f(1F, 1F, 1F) : new Vector3f();
        TreeSet<Float> ticks = new TreeSet<>();

        if (x != null) for (mchorse.bbs_mod.utils.keyframes.Keyframe<Float> kf : x.getKeyframes()) ticks.add(kf.getTick());
        if (y != null) for (mchorse.bbs_mod.utils.keyframes.Keyframe<Float> kf : y.getKeyframes()) ticks.add(kf.getTick());
        if (z != null) for (mchorse.bbs_mod.utils.keyframes.Keyframe<Float> kf : z.getKeyframes()) ticks.add(kf.getTick());

        for (Float tick : ticks)
        {
            float vx = x == null ? defaults.x : x.interpolate(tick, defaults.x);
            float vy = y == null ? defaults.y : y.interpolate(tick, defaults.y);
            float vz = z == null ? defaults.z : z.interpolate(tick, defaults.z);

            target.insert(tick, new Vector3f(vx, vy, vz));
        }
    }

    private mchorse.bbs_mod.ui.utils.icons.Icon getIconForGroup(String group)
    {
        return switch (group)
        {
            case "translate" -> Icons.ALL_DIRECTIONS;
            case "scale" -> Icons.SCALE;
            case "rotate", "rotate2" -> Icons.REFRESH;
            default -> null;
        };
    }
}
