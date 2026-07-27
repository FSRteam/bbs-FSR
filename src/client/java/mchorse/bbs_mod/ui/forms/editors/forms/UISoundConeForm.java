package mchorse.bbs_mod.ui.forms.editors.forms;

import mchorse.bbs_mod.forms.forms.sound.SoundConeForm;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.forms.editors.panels.UISoundConeFormPanel;
import mchorse.bbs_mod.ui.utils.icons.Icons;

public class UISoundConeForm extends UIForm<SoundConeForm>
{
    public UISoundConeForm()
    {
        super();

        this.defaultPanel = new UISoundConeFormPanel(this);

        this.registerPanel(this.defaultPanel, UIKeys.FORMS_EDITORS_SOUND_CONE_TITLE, Icons.SOUND);
        this.registerDefaultPanels();
    }
}
