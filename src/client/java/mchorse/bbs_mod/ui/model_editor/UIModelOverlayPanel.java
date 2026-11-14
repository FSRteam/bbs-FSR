package mchorse.bbs_mod.ui.model_editor;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.cubic.model.config.ModelConfig;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.dashboard.panels.UIDataDashboardPanel;
import mchorse.bbs_mod.ui.dashboard.panels.overlay.UIDataOverlayPanel;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.utils.icons.Icons;

import java.util.function.Consumer;

/**
 * The model editor's data manager. Models are assets living in the assets folder, so this is a
 * picker that can also make a new model from scratch — but not duplicate, rename or remove one:
 * those are the folder's business.
 */
public class UIModelOverlayPanel extends UIDataOverlayPanel<ModelConfig>
{
    public UIModelOverlayPanel(IKey title, UIDataDashboardPanel<ModelConfig> panel, Consumer<String> callback)
    {
        super(title, panel, callback);

        /* Same icon the tabs and the landing screen use, so a model reads as a model everywhere. */
        this.namesList.setFileIcon(Icons.POSE);
    }

    @Override
    public boolean showActionButtons()
    {
        return false;
    }

    @Override
    public boolean canCreate()
    {
        return true;
    }

    /**
     * A new model is files on disk, not a document in memory: they're written, and the model is
     * opened the way a picked one is — it loads in the background like every model does.
     */
    @Override
    protected void addNewData(UIContext context, String name, MapType mapType)
    {
        if (name.trim().isEmpty())
        {
            context.notifyError(UIKeys.PANELS_MODALS_EMPTY);

            return;
        }

        this.panel.save();

        /* The manager refuses any folder already there — also one the list doesn't show, with no model in it. */
        if (this.namesList.hasInHierarchy(name) || !BBSModClient.getModels().createModel(name))
        {
            context.notifyError(UIKeys.MODEL_EDITOR_CREATE_TAKEN.format(name));

            return;
        }

        this.namesList.addFile(name);
        this.panel.pickData(name);
    }
}
