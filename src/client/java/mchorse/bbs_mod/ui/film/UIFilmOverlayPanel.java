package mchorse.bbs_mod.ui.film;

import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.dashboard.panels.UIDataDashboardPanel;
import mchorse.bbs_mod.ui.dashboard.panels.overlay.UIDataOverlayPanel;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIListOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIPromptOverlayPanel;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.DataPath;
import mchorse.bbs_mod.utils.repos.IRepository;

import java.util.Comparator;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

public class UIFilmOverlayPanel extends UIDataOverlayPanel<Film>
{
    public UIFilmOverlayPanel(IKey title, UIDataDashboardPanel<Film> panel, Consumer<String> callback)
    {
        super(title, panel, callback);

        UIIcon backups = new UIIcon(Icons.REFRESH, (b) -> this.openBackups())
        {
            @Override
            public boolean isEnabled()
            {
                DataPath selected = UIFilmOverlayPanel.this.namesList.getCurrentFirst();

                return super.isEnabled() && selected != null && !selected.folder;
            }
        };

        backups.tooltip(UIKeys.FILM_BACKUPS_TITLE);
        this.icons.add(backups);
    }

    /** Restore a previously saved version of the selected film as a new film. */
    private void openBackups()
    {
        DataPath selected = this.namesList.getCurrentFirst();
        UIContext context = this.getContext();

        if (selected == null || selected.folder)
        {
            return;
        }

        String id = selected.toString();
        IRepository<Film> repository = this.panel.getRepository();

        repository.requestBackups(id, (keys) ->
        {
            if (keys.isEmpty())
            {
                context.notifyError(UIKeys.FILM_BACKUPS_EMPTY);

                return;
            }

            Map<String, String> versions = new LinkedHashMap<>();
            String name = id.substring(id.lastIndexOf('/') + 1);

            keys.stream().sorted(Comparator.reverseOrder()).forEach((key) ->
            {
                String date = key.substring(key.lastIndexOf('/') + name.length() + 2);
                String[] parts = date.split("_");
                String label = parts.length == 4 || parts.length == 5
                    ? parts[0] + "-" + parts[1] + "-" + parts[2] + " " + parts[3] + ":" + (parts.length == 5 ? parts[4] : "00 – " + parts[3] + ":59")
                    : date;

                versions.put(label, key);
            });

            UIListOverlayPanel list = new UIListOverlayPanel(UIKeys.FILM_BACKUPS_TITLE, null);

            list.addValues(versions.keySet());
            list.list.label(UIKeys.GENERAL_SEARCH);
            list.callback((selection) ->
            {
                String key = versions.get(selection.get(0));

                repository.load(key, (backup) ->
                {
                    if (backup == null)
                    {
                        context.notifyError(UIKeys.FILM_BACKUPS_ERROR);

                        return;
                    }

                    list.close();

                    /* Read before saving the open film: saving can replace the latest backup. */
                    String folder = id.substring(0, id.lastIndexOf('/') + 1);
                    UIPromptOverlayPanel prompt = new UIPromptOverlayPanel(
                        UIKeys.FILM_BACKUPS_RESTORE, UIKeys.FILM_BACKUPS_DESCRIPTION, (value) ->
                    {
                        String target = folder + value.trim();

                        if (value.trim().isEmpty())
                        {
                            context.notifyError(UIKeys.PANELS_MODALS_EMPTY);

                            return;
                        }

                        repository.requestKeys((names) ->
                        {
                            if (this.backupExists(names, target) || this.namesList.hasInHierarchy(target))
                            {
                                context.notifyError(UIKeys.FILM_BACKUPS_EXISTS);

                                return;
                            }

                            this.addNewData(target, backup.toData().asMap());

                            Film film = this.panel.getData();

                            if (film != null)
                            {
                                film.stampCreationTimeNow();
                            }

                            this.panel.save();
                            this.panel.requestNames();
                        });
                    });

                    prompt.text.filename();
                    prompt.text.setText(name + "_restored_" + key.substring(key.lastIndexOf('/') + name.length() + 2));
                    UIOverlay.addOverlay(context, prompt);
                });
            });
            UIOverlay.addOverlay(context, list);
        });
    }

    /** Whether the target name is taken by a film or swallowed by an existing folder. */
    private boolean backupExists(Collection<String> names, String target)
    {
        return names.contains(target) || names.stream().anyMatch((entry) -> entry.startsWith(target + "/"));
    }

    @Override
    protected void dupeData(String name)
    {
        super.dupeData(name);

        Film film = this.panel.getData();

        if (film != null)
        {
            film.stampCreationTimeNow();
            this.panel.save();
        }
    }
}
