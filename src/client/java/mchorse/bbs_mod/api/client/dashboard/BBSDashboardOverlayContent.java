package mchorse.bbs_mod.api.client.dashboard;

import mchorse.bbs_mod.ui.framework.elements.UIElement;

import java.util.Objects;

/** Addon-owned content mounted in the host-owned Dashboard guide layer. */
public interface BBSDashboardOverlayContent
{
    UIElement root();

    default void onMounted() {}

    default void onUnmounted() {}

    default void onDashboardOpen() {}

    default void onDashboardClose() {}

    default void onShown() {}

    default void onHidden() {}

    static BBSDashboardOverlayContent of(UIElement root)
    {
        Objects.requireNonNull(root, "root");

        return () -> root;
    }
}
