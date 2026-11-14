package mchorse.bbs_mod.ui.model_editor;

import mchorse.bbs_mod.ui.dashboard.panels.landing.UILandingScreen;

/** Compatibility entry point for callers before landing screens acquired a shared owner. */
@Deprecated
public class UIModelSelectionScreen extends UILandingScreen
{
    public UIModelSelectionScreen(UIModelEditorPanel panel)
    {
        super(panel);
    }
}
