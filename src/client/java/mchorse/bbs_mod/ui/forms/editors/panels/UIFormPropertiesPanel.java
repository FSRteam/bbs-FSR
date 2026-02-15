package mchorse.bbs_mod.ui.forms.editors.panels;

import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.film.replays.FormProperties;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.renderers.ModelFormRenderer;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.forms.editors.forms.UIForm;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIListOverlayPanel;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class UIFormPropertiesPanel extends UIFormPanel<ModelForm>
{
    public UIKeyframes keyframes;
    public UIIcon add;

    public UIFormPropertiesPanel(UIForm editor)
    {
        super(editor);

        this.keyframes = new UIKeyframes((k) -> {});
        this.keyframes.relative(this).w(1F).h(1F);

        this.add = new UIIcon(Icons.ADD, (b) -> this.addProperty());
        this.add.tooltip(IKey.raw("Add property"));
        this.add.relative(this).x(1F, -20).y(1F, -20).anchor(1F, 1F);

        this.add(this.keyframes, this.add);
    }

    private void addProperty()
    {
        ModelInstance model = ModelFormRenderer.getModel(this.form);

        if (model == null)
        {
            return;
        }

        List<String> bones = new ArrayList<>(model.model.getAllGroupKeys());
        Collections.sort(bones);

        UIListOverlayPanel panel = new UIListOverlayPanel(IKey.raw("Select Bone"), (bone) ->
        {
            this.pickProperty(bone);
        });

        panel.addValues(bones);

        UIOverlay.addOverlay(this.getContext(), panel);
    }

    private void pickProperty(String bone)
    {
        UIListOverlayPanel panel = new UIListOverlayPanel(IKey.raw("Select Property"), (property) ->
        {
            String path = bone + "." + property;
            
            if (!this.form.properties.properties.containsKey(path))
            {
                KeyframeChannel channel = new KeyframeChannel(path, KeyframeFactories.FLOAT);
                
                this.form.properties.properties.put(path, channel);
                this.form.properties.add(channel);
            }
            
            this.fillData();
        });

        String[] props = {
            "translate.x", "translate.y", "translate.z",
            "scale.x", "scale.y", "scale.z",
            "rotate.x", "rotate.y", "rotate.z",
            "rotate2.x", "rotate2.y", "rotate2.z"
        };

        panel.addValues(Arrays.asList(props));

        UIOverlay.addOverlay(this.getContext(), panel);
    }

    @Override
    public void startEdit(ModelForm form)
    {
        super.startEdit(form);

        this.fillData();
    }

    private void fillData()
    {
        this.keyframes.removeAllSheets();

        FormProperties properties = this.form.properties;
        List<String> keys = new ArrayList<>(properties.properties.keySet());

        Collections.sort(keys);

        for (String key : keys)
        {
            KeyframeChannel channel = properties.properties.get(key);
            int color = this.getColorForBone(this.getBoneName(key));

            this.keyframes.addSheet(new UIKeyframeSheet(key, IKey.raw(key), color, false, channel, null));
        }
    }

    private String getBoneName(String key)
    {
        int index = key.indexOf(".");

        return index == -1 ? key : key.substring(0, index);
    }

    private int getColorForBone(String bone)
    {
        int rgb = bone.hashCode() & 0xFFFFFF;
        int r = (int) ((rgb >> 16 & 0xFF) * 0.8F);
        int g = (int) ((rgb >> 8 & 0xFF) * 0.8F);
        int b = (int) ((rgb & 0xFF) * 0.8F);
        return (r << 16) | (g << 8) | b | 0xFF000000;
    }
}
