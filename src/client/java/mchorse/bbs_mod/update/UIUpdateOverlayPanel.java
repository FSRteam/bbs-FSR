package mchorse.bbs_mod.update;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.film.home.MarkdownBody;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.UIUtils;

/**
 * Update popup shown after a release check finds something: the update
 * variant renders the release's Markdown notes with install/download/skip
 * actions, the revoked variant warns that the running build was recalled.
 * Mandatory releases hide the skip button so the popup keeps coming back.
 */
public class UIUpdateOverlayPanel extends UIOverlayPanel
{
    private final FSRUpdates.Release release;
    private final FSRUpdates.Release latest;
    private final boolean revoked;

    public UIUpdateOverlayPanel(FSRUpdates.Release release, FSRUpdates.Release latest, boolean revoked)
    {
        super(IKey.constant(revoked
            ? L10n.lang("bbs.updates.title_revoked").format(release.version).get()
            : L10n.lang("bbs.updates.title_update").format(release.version).get()));

        this.release = release;
        this.latest = latest != null ? latest : release;
        this.revoked = revoked;

        /* Header: channel chip + release date */
        String header = this.release.channel.equals("preview")
            ? L10n.lang("bbs.updates.channel_preview").get()
            : L10n.lang("bbs.updates.channel_stable").get();

        if (!this.release.date.isEmpty())
        {
            header += " · " + this.release.date;
        }

        UILabel label = UI.label(IKey.constant(header)).color(BBSSettings.mutedTextColor());

        label.relative(this.content).xy(8, 26).w(1F, -16).h(12);
        this.content.add(label);

        String body = this.revoked
            ? L10n.lang("bbs.updates.revoked_body").format(this.release.revokedReason.isEmpty() ? L10n.lang("bbs.updates.revoked_no_reason").get() : this.release.revokedReason).get()
            : this.release.notes;

        MarkdownBody bodyView = new MarkdownBody(body.isEmpty() ? L10n.lang("bbs.updates.no_notes").get() : body);

        bodyView.relative(this.content).xy(6, 42).w(1F, -12).h(1F, -72);
        this.content.add(bodyView);

        int x = 8;

        if (!this.revoked && UpdateInstaller.canAutoInstall(this.release))
        {
            UIButton install = new UIButton(L10n.lang("bbs.updates.install"), (b) ->
            {
                this.close();
                UpdateInstaller.install(b.getContext(), this.release);
            });

            install.relative(this.content).x(x).y(1F, -26).wh(88, 20);
            this.content.add(install);
            x += 96;
        }

        String url = !this.latest.url.isEmpty() ? this.latest.url : this.release.url;

        if (!url.isEmpty())
        {
            UIButton open = new UIButton(L10n.lang("bbs.updates.open_download"), (b) ->
            {
                UIUtils.openWebLink(url);
            });

            open.relative(this.content).x(x).y(1F, -26).wh(88, 20);
            this.content.add(open);
            x += 96;
        }

        if (!this.revoked && !this.release.mandatory)
        {
            UIButton skip = new UIButton(L10n.lang("bbs.updates.skip"), (b) ->
            {
                /* Skipping means "remind me again at the next version". */
                BBSSettings.updateSkippedVersion.set(this.release.version);
                this.close();
            });

            skip.relative(this.content).x(x).y(1F, -26).wh(88, 20);
            this.content.add(skip);
        }

        UIButton close = new UIButton(L10n.lang("bbs.updates.close"), (b) -> this.close());

        close.relative(this.content).x(1F, -6).y(1F, -26).anchor(1F, 0F).wh(60, 20);
        this.content.add(close);
    }
}
