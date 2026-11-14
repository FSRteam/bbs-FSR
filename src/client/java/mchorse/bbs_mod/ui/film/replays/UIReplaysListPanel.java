package mchorse.bbs_mod.ui.film.replays;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.ui.forms.editors.UIForms;
import mchorse.bbs_mod.ui.framework.elements.UISection;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.utils.UIDataUtils;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.utils.Direction;

import java.util.List;
import java.util.function.Consumer;

public class UIReplaysListPanel extends UIElement
{
    private static final int BAR_HEIGHT = 20;
    private static final int BAR_ICON_SIZE = 20;
    private static final int BAR_ICON_MARGIN = 2;

    private final UIFilmPanel filmPanel;

    public final UIElement content = new UIElement();
    public final UIElement bar = new UIElement();
    public final UIElement leftBar = new UIElement();
    public final UIIcon addReplay;
    public final UIIcon dupeReplay;
    public final UIIcon removeReplay;
    public final UIIcon presets;

    public final UIReplayList replays;
    public final UITextbox search;
    public final UIForms bodyParts;
    private Replay bodyPartsReplay;
    private final UISection bodyPartsSection = new UISection(L10n.lang("bbs.ui.film.replays.body_parts"));

    private final Area rightClickAnchorArea = new Area();

    public UIReplaysListPanel(UIFilmPanel panel, Consumer<List<Replay>> callback, Consumer<Form> formConsumer)
    {
        this(panel, callback, formConsumer, path -> {});
    }

    public UIReplaysListPanel(UIFilmPanel panel, Consumer<List<Replay>> callback, Consumer<Form> formConsumer, Consumer<String> partConsumer)
    {
        this.filmPanel = panel;
        this.bodyParts = new UIForms(list ->
        {
            if (!list.isEmpty()) partConsumer.accept(list.get(0).getPath());
        });
        this.bodyPartsSection.relative(this.content).x(5).y(1F, -5).w(1F, -10).anchorY(1F);
        this.bodyPartsSection.fields.add(this.bodyParts);
        this.replays = new UIReplayList(callback, formConsumer, panel);

        this.search = new UITextbox(1000, this.replays::filter);
        this.search.placeholder(UIKeys.GENERAL_SEARCH);
        this.search.relative(this.content).x(0).y(BAR_HEIGHT).w(1F).h(BAR_HEIGHT);
        this.addReplay = new UIIcon(Icons.ADD, (b) -> this.replays.addReplay());
        this.dupeReplay = new UIIcon(Icons.DUPE, (b) -> this.replays.dupeReplay());
        this.removeReplay = new UIIcon(Icons.REMOVE, (b) -> this.replays.removeReplay());
        this.presets = new UIIcon(Icons.MORE, (b) -> this.replays.openReplayPresets());
        this.presets.tooltip(UIKeys.GENERAL_PRESETS, Direction.LEFT);

        int leftW = BAR_ICON_SIZE * 3 + BAR_ICON_MARGIN * 2;

        this.bar.relative(this.content).x(0).y(0).w(1F).h(BAR_HEIGHT);
        this.leftBar.relative(this.bar).x(0).y(0).w(leftW).h(BAR_HEIGHT).row(BAR_ICON_MARGIN).height(BAR_HEIGHT);

        this.addReplay.w(BAR_ICON_SIZE);
        this.dupeReplay.w(BAR_ICON_SIZE);
        this.removeReplay.w(BAR_ICON_SIZE);

        this.presets.relative(this.bar).x(1F, -BAR_ICON_SIZE - BAR_ICON_MARGIN).y(0).w(BAR_ICON_SIZE).h(BAR_HEIGHT);

        this.leftBar.add(this.addReplay, this.dupeReplay, this.removeReplay);
        this.bar.add(this.leftBar, this.presets);

        this.replays.relative(this.content).x(0).y(0, BAR_HEIGHT * 2).w(1F).h(1F, -BAR_HEIGHT * 2);
        this.content.add(this.bar, this.search, this.bodyPartsSection, this.replays);

        this.refreshEditPanelOffset();
        this.add(this.content);
    }

    public void refreshEditPanelOffset()
    {
        int top = this.filmPanel.getEditPanelTopOffsetPx();
        this.content.relative(this).x(0).y(0, top).w(1F).h(1F, -top);
        this.resize();
    }

    public String setBodyPartsReplay(Replay replay, String path)
    {
        double scroll = this.bodyPartsReplay == replay ? this.bodyParts.scroll.getScroll() : 0;
        this.bodyPartsReplay = replay;
        if (replay == null || replay.form.get() == null)
        {
            this.bodyParts.clear();
            return "";
        }
        this.bodyParts.setForm(replay.form.get());
        String selected = this.bodyParts.setCurrentPath(path);
        this.bodyParts.scroll.setScroll(scroll);
        return selected;
    }

    @Override
    public void resize()
    {
        boolean visible = this.bodyPartsReplay != null;
        int maxHeight = Math.min(160, this.getFlex().getH() / 2);
        this.bodyParts.h(Math.max(1, Math.min(this.bodyParts.getList().size() * this.bodyParts.scroll.scrollItemSize, maxHeight)));
        this.bodyPartsSection.setVisible(visible);
        this.replays.hTo(visible ? this.bodyPartsSection.area : this.content.area, visible ? 0F : 1F);
        super.resize();
    }

    private void updateButtonsState()
    {
        boolean hasFilm = this.filmPanel.getData() != null;
        boolean hasSelection = this.replays.hasReplaySelection();

        this.addReplay.setEnabled(hasFilm);
        this.dupeReplay.setEnabled(hasSelection);
        this.removeReplay.setEnabled(hasSelection);
        this.presets.setEnabled(hasFilm);
    }

    @Override
    public void render(UIContext context)
    {
        int barBg = BBSSettings.baseSurface();

        this.updateButtonsState();
        context.batcher.box(this.bar.area.x, this.bar.area.y, this.bar.area.ex(), this.bar.area.ey(), barBg);
        super.render(context);
    }

}
