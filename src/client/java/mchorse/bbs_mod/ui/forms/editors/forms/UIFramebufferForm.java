package mchorse.bbs_mod.ui.forms.editors.forms;

import mchorse.bbs_mod.forms.forms.FramebufferForm;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.forms.editors.panels.UIFramebufferFormPanel;

public class UIFramebufferForm extends UIForm<FramebufferForm>
{
    public UIFramebufferForm()
    {
        super();

        this.defaultPanel = new UIFramebufferFormPanel(this);

        /* Every sibling panel names itself through UIKeys.FORMS_EDITORS_*_TITLE; this one was
         * the only hard-coded English, which left ru_ru users with an untranslated tab even
         * though bbs.ui.forms.editors.framebuffer.title already ships in all three languages. */
        this.registerPanel(this.defaultPanel, UIKeys.FORMS_EDITORS_FRAMEBUFFER_TITLE, FramebufferForm.ICON);
        this.registerDefaultPanels();
    }
}