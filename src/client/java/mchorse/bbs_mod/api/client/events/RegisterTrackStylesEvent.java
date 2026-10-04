package mchorse.bbs_mod.api.client.events;

import mchorse.bbs_mod.l10n.keys.IKey;

import mchorse.bbs_mod.ui.film.replays.UIReplaysEditor;
import mchorse.bbs_mod.ui.utils.icons.Icon;

/**
 * Posted on the client once BBS has given its own track properties their colours and icons.
 *
 * <p>Purely how a track looks on a timeline, keyed by the property's own name. A property with no
 * style still animates — it just wears the default blue and no icon.</p>
 *
 * <p>The tables behind this are the ones the timeline already reads
 * ({@code UIReplaysEditor.getColor/getIcon}), and a style set by the user in
 * {@code BBSSettings.trackStyles} still wins over one registered here — an addon supplies the
 * default, not an override of the user's own choice.</p>
 */
public class RegisterTrackStylesEvent
{
    public void registerLabel(String property, IKey label)
    {
        UIReplaysEditor.registerTrackLabel(property, label);
    }

    public void register(String property, Icon icon, int color, IKey label)
    {
        this.register(property, icon, color);
        this.registerLabel(property, label);
    }

    public void register(String property, Icon icon, int color)
    {
        UIReplaysEditor.registerTrackStyle(property, icon, color);
    }
}
