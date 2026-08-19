package mchorse.bbs_mod.api.client.dashboard;

import mchorse.bbs_mod.ui.framework.elements.UIElement;

import java.util.Objects;

/** Plugin-owned UI content mounted inside a host-owned Dashboard panel. */
public interface BBSDashboardPanelContent
{
    UIElement root();

    default void onOpen() {}

    default void onClose() {}

    default void onAppear() {}

    default void onDisappear() {}

    default void onUpdate() {}

    default boolean needsBackground()
    {
        return true;
    }

    default boolean canToggleVisibility()
    {
        return true;
    }

    default boolean canPause()
    {
        return true;
    }

    default boolean canRefresh()
    {
        return true;
    }

    static BBSDashboardPanelContent of(UIElement root)
    {
        Objects.requireNonNull(root, "root");

        return () -> root;
    }
}
