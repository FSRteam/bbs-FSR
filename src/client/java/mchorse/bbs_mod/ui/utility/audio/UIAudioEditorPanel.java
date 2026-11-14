package mchorse.bbs_mod.ui.utility.audio;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.audio.ColorCode;
import mchorse.bbs_mod.audio.SoundManager;
import mchorse.bbs_mod.audio.SoundPlayer;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.Keys;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.dashboard.panels.UIEditorDashboardPanel;
import mchorse.bbs_mod.ui.dashboard.panels.landing.UILandingScreen;
import mchorse.bbs_mod.ui.dashboard.panels.tabs.IUITabsHost;
import mchorse.bbs_mod.ui.dashboard.panels.tabs.UITabList;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.ui.framework.elements.overlay.UISoundOverlayPanel;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.ui.utils.icons.Icon;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class UIAudioEditorPanel extends UIEditorDashboardPanel implements IUITabsHost
{
    public UIIcon pickAudio;
    public UIIcon plause;
    public UIIcon saveColors;
    public UIAudioEditor audioEditor;
    public final UITabList tabs;
    private final Map<String, List<ColorCode>> edits = new HashMap<>();

    public UIAudioEditorPanel(UIDashboard dashboard)
    {
        super(dashboard);

        this.onClose(this::onPanelClosed);
        this.onAppear(this::onPanelAppeared);
        this.onDisappear(this::onPanelDisappeared);

        this.pickAudio = new UIIcon(Icons.MORE, (b) -> UIOverlay.addOverlay(this.getContext(), new UISoundOverlayPanel(this::openAudio)));
        this.plause = new UIIcon(() ->
        {
            SoundPlayer player = this.audioEditor.getPlayer();

            if (player == null)
            {
                return Icons.STOP;
            }

            return player.isPlaying() ? Icons.PAUSE : Icons.PLAY;
        }, (b) -> this.audioEditor.togglePlayback());
        this.saveColors = new UIIcon(Icons.SAVED, (b) -> this.saveColors());
        this.audioEditor = new UIAudioEditor();
        this.audioEditor.full(this.editor);
        this.pickAudio.tooltip(UIKeys.PANELS_KEYS_OPEN_DATA_MANAGER);
        this.saveColors.tooltip(UIKeys.GENERAL_SAVE);

        this.actions().action(this.plause).common(this.pickAudio).common(this.saveColors);
        this.editor.add(this.audioEditor);
        this.tabs = this.documentTabs;
        this.tabs.setBar(this.topBar.enableTabs(this.tabs));
        this.mountLanding();

        this.openAudio(null);

        this.keys().register(Keys.PLAUSE, this.audioEditor::togglePlayback);
        this.keys().register(Keys.SAVE, this::saveColors);
        this.keys().register(Keys.OPEN_DATA_MANAGER, this.pickAudio::clickItself);
        this.keys().register(Keys.OPEN_NEW_TAB, this.tabs::addTab);
    }

    @Override
    public IKey getTitle()
    {
        return UIKeys.AUDIO_TITLE;
    }

    @Override
    public String getRecentType()
    {
        return "audio";
    }

    @Override
    protected String getLandingId()
    {
        return this.getOpenId();
    }

    @Override
    public File getDataFolder()
    {
        return BBSMod.getAudioFolder();
    }

    @Override
    public void openDataManager()
    {
        this.pickAudio.clickItself();
    }

    @Override
    public void pickData(String id)
    {
        this.tabs.pick(id);
    }

    @Override
    public void requestNames()
    {
        this.landing.fillNames(UISoundOverlayPanel.getSoundEvents());
    }

    private void openAudio(Link link)
    {
        this.tabs.pick(link == null ? null : link.toString());
    }

    @Override
    protected void showTab(String id)
    {
        Link link = id == null ? null : Link.create(id);

        this.audioEditor.setup(link);
        this.saveColors.setEnabled(this.audioEditor.isEditing());

        Link opened = this.audioEditor.getAudio();

        if (opened != null)
        {
            List<ColorCode> colors = this.edits.get(id);

            if (colors != null)
            {
                this.audioEditor.getColorCodes().clear();
                this.audioEditor.getColorCodes().addAll(colors);
            }

            BBSSettings.recentData.touch("audio", opened.toString());
        }

        this.audioEditor.setVisible(opened != null);
        this.syncLanding();
        this.tabs.setOpenId(opened == null ? null : opened.toString());
    }

    @Override
    public String getOpenId()
    {
        Link link = this.audioEditor.getAudio();

        return link == null ? null : link.toString();
    }

    @Override
    public void saveOpen()
    {
        String id = this.getOpenId();

        if (id != null)
        {
            this.edits.put(id, new ArrayList<>(this.audioEditor.getColorCodes()));
        }
    }

    @Override
    public IKey getNewTabLabel()
    {
        return UIKeys.PANELS_TABS_NEW_TAB;
    }

    @Override
    public Icon getTabIcon(String id)
    {
        return id == null ? Icons.SEARCH : Icons.SOUND;
    }

    private void onPanelDisappeared()
    {
        if (this.audioEditor.getPlayer() != null)
        {
            this.audioEditor.getPlayer().pause();
        }
    }

    private void onPanelClosed()
    {
        this.saveOpen();
        this.audioEditor.delete();
    }

    private void onPanelAppeared()
    {
        if (this.audioEditor.getPlayer() == null && this.tabs.getCurrentId() != null)
        {
            this.openTab(this.tabs.getCurrentId());
        }
    }

    private void saveColors()
    {
        Link audio = this.audioEditor.getAudio();

        if (audio == null)
        {
            return;
        }

        SoundManager sounds = BBSModClient.getSounds();

        sounds.saveColorCodes(new Link(audio.source, audio.path + ".json"), this.audioEditor.getColorCodes());
        sounds.deleteSound(audio);
    }
}
