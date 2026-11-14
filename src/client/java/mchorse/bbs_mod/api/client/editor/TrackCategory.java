package mchorse.bbs_mod.api.client.editor;

import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.ui.utils.icons.Icons;

import java.util.Objects;

/** Addon-defined timeline category. */
public final class TrackCategory
{
    public static final TrackCategory REPLAY = builtin("replay", Icons.PLAYER);
    public static final TrackCategory FORM = builtin("form", Icons.BLOCK);
    public static final TrackCategory POSE = builtin("pose", Icons.POSE);
    public static final TrackCategory IK = builtin("ik", Icons.IK);
    public static final TrackCategory PHYSICS = builtin("physics", Icons.PHYSICS);

    public final String id;
    public final Icon icon;
    public final IKey label;
    public final IKey tooltip;

    public TrackCategory(String id, Icon icon, IKey label, IKey tooltip)
    {
        if (id == null || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("Expected namespaced category id: " + id);
        this.id = id;
        this.icon = Objects.requireNonNull(icon);
        this.label = Objects.requireNonNull(label);
        this.tooltip = Objects.requireNonNull(tooltip);
    }

    public static TrackCategory builtin(String id, Icon icon)
    {
        return new TrackCategory("bbs:" + id,
            icon,
            () -> L10n.lang("bbs.ui.film.replays.category." + id).get(),
            () -> L10n.lang("bbs.ui.film.replays.category." + id + ".tooltip").get());
    }
}
