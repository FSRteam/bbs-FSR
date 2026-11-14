package mchorse.bbs_mod.ui.film.utils.shader;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.camera.clips.misc.CurveClip;
import mchorse.bbs_mod.ui.film.clips.UICurveClip;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.utils.iris.IrisShaderMenu;
import mchorse.bbs_mod.utils.iris.ShaderMenu;
import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Entry point of the refreshed shader-curve editor. Decides whether the "add curve" action opens the
 * BBS-styled {@link UIShaderOptionPicker} (a mirror of the Iris shader-options menu) or falls back to
 * BBS's stock flat list.
 *
 * <p><b>Iris isolation:</b> this class references no {@code net.irisshaders.*} type directly; it only
 * reaches Iris through {@link IrisShaderMenu} <i>after</i> the {@code isModLoaded("iris")} gate. That
 * keeps {@link IrisShaderMenu} (and Iris itself) unloaded when Iris is absent, so the picker falls back
 * to the stock list instead of crashing.</p>
 */
public final class ShaderCurvePicker
{
    private ShaderCurvePicker()
    {}

    /**
     * @return {@code true} if the refreshed picker was shown (caller should not also open the flat list);
     *         {@code false} to let BBS's stock {@code offerCurveKeys} flat list run.
     */
    public static boolean open(UIContext context, List<String> existing, Consumer<String> callback)
    {
        if (!ModList.get().isLoaded("iris"))
        {
            return false;
        }

        ShaderMenu menu;
        Map<String, String> languageMap;

        try
        {
            menu = IrisShaderMenu.buildShaderMenu();

            if (menu == null)
            {
                return false;
            }

            languageMap = IrisShaderMenu.getShadersRawLanguageMap(BBSModClient.getLanguageKey());
        }
        catch (LinkageError e)
        {
            /* Iris build whose option-menu API differs from the one we compiled against: stock list. */
            return false;
        }

        List<String> addedChannels = new ArrayList<>(existing);
        Consumer<String> onAddOptionId = (id) ->
        {
            String channel = CurveClip.SHADER_CURVES_PREFIX + id;

            callback.accept(channel);
            addedChannels.add(channel);
        };
        Runnable openLegacy = () ->
        {
            UICurveClip.offerCurveKeyList(context, addedChannels, callback);
        };

        UIShaderOptionPicker picker = new UIShaderOptionPicker(menu, languageMap, collectAddedOptionIds(existing), onAddOptionId, openLegacy);

        UIOverlay.addOverlay(context, picker, picker.preferredWidth(), picker.preferredHeight());

        return true;
    }

    /**
     * Bare option ids (prefix stripped) of the curve channels already present, so the picker can mark
     * those cells as animated (a highlighted outline).
     */
    private static Set<String> collectAddedOptionIds(List<String> existing)
    {
        Set<String> added = new HashSet<>();

        for (String id : existing)
        {
            if (id.startsWith(CurveClip.SHADER_CURVES_PREFIX))
            {
                added.add(id.substring(CurveClip.SHADER_CURVES_PREFIX.length()));
            }
        }

        return added;
    }
}
