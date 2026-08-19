package mchorse.bbs_mod.api.client.dashboard;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.utils.icons.Icon;

import java.util.regex.Pattern;

/** Immutable host-owned description of one plugin Dashboard panel. */
public final class BBSDashboardPanelSpec
{
    private static final Pattern LOCAL_ID = Pattern.compile("[a-z0-9][a-z0-9_.-]{0,63}");

    private final String id;
    private final IKey title;
    private final Icon icon;

    private BBSDashboardPanelSpec(Builder builder)
    {
        this.id = builder.id == null ? null : builder.id.trim();
        this.title = builder.title;
        this.icon = builder.icon;
    }

    public static Builder builder(String id)
    {
        return new Builder(id);
    }

    public static boolean isValidId(String id)
    {
        return id != null && LOCAL_ID.matcher(id).matches();
    }

    public String id()
    {
        return this.id;
    }

    public IKey title()
    {
        return this.title;
    }

    public Icon icon()
    {
        return this.icon;
    }

    public static final class Builder
    {
        private final String id;
        private IKey title;
        private Icon icon;

        private Builder(String id)
        {
            this.id = id;
        }

        public Builder title(IKey title)
        {
            this.title = title;
            return this;
        }

        public Builder icon(Icon icon)
        {
            this.icon = icon;
            return this;
        }

        public BBSDashboardPanelSpec build()
        {
            return new BBSDashboardPanelSpec(this);
        }
    }
}
