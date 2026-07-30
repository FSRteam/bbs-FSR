package mchorse.bbs_mod.ui.forms.editors.forms;

import mchorse.bbs_mod.forms.forms.sound.SoundSphereForm;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.forms.editors.panels.UISoundSphereFormPanel;
import mchorse.bbs_mod.ui.utils.icons.Icons;

public class UISoundSphereForm extends UIForm<SoundSphereForm>
{
    public UISoundSphereForm()
    {
        super();

        this.defaultPanel = new UISoundSphereFormPanel(this);

        this.registerPanel(this.defaultPanel, UIKeys.FORMS_EDITORS_SOUND_TITLE, Icons.SOUND);
        this.registerDefaultPanels();
    }
}
