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
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class UIPoseEditorPanel extends UIElement
{
    private static final List<String> TRANSFORM_PROPERTIES = Arrays.asList(
        "translate.x", "translate.y", "translate.z",
        "scale.x", "scale.y", "scale.z",
        "rotate.x", "rotate.y", "rotate.z",
        "rotate2.x", "rotate2.y", "rotate2.z"
    );

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

        List<UIKeyframeSheet> sheets = new ArrayList<>();

        for (String bone : bones)
        {
            boolean first = true;

            for (String property : TRANSFORM_PROPERTIES)
            {
                String path = bone + "." + property;
                KeyframeChannel channel = this.replay.properties.properties.get(path);

                if (channel == null)
                {
                    channel = new KeyframeChannel(path, KeyframeFactories.FLOAT);
                    this.replay.properties.properties.put(path, channel);
                    this.replay.properties.add(channel);
                }

                int color = this.getColorForBone(bone);
                UIKeyframeSheet sheet = new UIKeyframeSheet(path, IKey.raw(path), color, first, channel, null);

                sheet.icon(UIReplaysEditor.getIcon(property));
                sheets.add(sheet);

                first = false;
            }
        }

        if (!sheets.isEmpty())
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

            for (UIKeyframeSheet sheet : sheets)
            {
                this.keyframeEditor.view.addSheet(sheet);
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
}
