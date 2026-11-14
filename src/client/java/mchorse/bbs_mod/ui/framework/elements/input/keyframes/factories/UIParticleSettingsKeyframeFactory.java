package mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories;

import mchorse.bbs_mod.forms.forms.utils.ParticleSettings;
import mchorse.bbs_mod.ui.forms.editors.utils.UIParticleSettings;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import net.minecraft.resources.ResourceLocation;

public class UIParticleSettingsKeyframeFactory extends UIKeyframeFactory<ParticleSettings>
{
    private UIParticleSettings settings;

    public UIParticleSettingsKeyframeFactory(Keyframe<ParticleSettings> keyframe, UIKeyframes editor)
    {
        super(keyframe, editor);

        this.settings = new UIParticleSettingsEditor(this);
        this.settings.setSettings(this.getDisplayValue());

        this.scroll.add(this.settings);
    }

    @Override
    public void update()
    {
        super.update();
        this.settings.setSettings(this.getDisplayValue());
    }

    public static class UIParticleSettingsEditor extends UIParticleSettings
    {
        private final UIParticleSettingsKeyframeFactory editor;

        public UIParticleSettingsEditor(UIParticleSettingsKeyframeFactory editor)
        {
            super();

            this.editor = editor;
        }

        @Override
        protected void setParticle(ResourceLocation id)
        {
            ParticleSettings value = this.editor.keyframe.getFactory().copy(this.editor.getDisplayValue());
            value.particle = id;
            this.editor.setValue(value);
        }

        @Override
        protected void setArguments(String args)
        {
            ParticleSettings value = this.editor.keyframe.getFactory().copy(this.editor.getDisplayValue());
            value.arguments = args;
            this.editor.setValue(value);
        }
    }
}
