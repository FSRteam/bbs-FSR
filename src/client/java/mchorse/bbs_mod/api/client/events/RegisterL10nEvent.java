package mchorse.bbs_mod.api.client.events;

import mchorse.bbs_mod.l10n.L10n;

/**
 * Posted on the client while the language table is being filled in, before it is read.
 *
 * <p>An addon registers its own language files here rather than from its setup, because the load
 * happens right after this event: registering later would leave every one of the addon's labels
 * showing its raw key until the next language switch, or force the addon to reload the whole
 * table a second time.</p>
 */
public class RegisterL10nEvent
{
    public final L10n l10n;

    public RegisterL10nEvent(L10n l10n)
    {
        this.l10n = l10n;
    }
}
