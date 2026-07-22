package mchorse.bbs_mod.plugin.client;

import mchorse.bbs_mod.BBSModClient;
import net.minecraft.client.Minecraft;

/** Physical-client projection refresh invoked reflectively by the common manager. */
public final class BBSPluginClientRefresh
{
    private BBSPluginClientRefresh() {}

    public static void refresh()
    {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft == null)
        {
            return;
        }

        minecraft.execute(() ->
        {
            if (BBSModClient.getModels() != null)
            {
                BBSModClient.getModels().reload();
            }

            if (BBSModClient.getTextures() != null)
            {
                BBSModClient.getTextures().delete();
            }

            if (BBSModClient.getSounds() != null)
            {
                BBSModClient.getSounds().deleteSounds();
            }

            BBSModClient.reloadLanguage(BBSModClient.getLanguageKey());
        });
    }
}
