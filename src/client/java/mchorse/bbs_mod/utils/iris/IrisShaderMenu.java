package mchorse.bbs_mod.utils.iris;

import mchorse.bbs_mod.utils.iris.ShaderCurves;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.shaderpack.LanguageMap;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.option.ProfileSet;
import net.irisshaders.iris.shaderpack.option.menu.OptionMenuBooleanOptionElement;
import net.irisshaders.iris.shaderpack.option.menu.OptionMenuContainer;
import net.irisshaders.iris.shaderpack.option.menu.OptionMenuElement;
import net.irisshaders.iris.shaderpack.option.menu.OptionMenuElementScreen;
import net.irisshaders.iris.shaderpack.option.menu.OptionMenuLinkElement;
import net.irisshaders.iris.shaderpack.option.menu.OptionMenuOptionElement;
import net.irisshaders.iris.shaderpack.option.menu.OptionMenuProfileElement;
import net.irisshaders.iris.shaderpack.option.menu.OptionMenuStringOptionElement;
import net.irisshaders.iris.shaderpack.option.values.OptionValues;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads the Iris options menu into a neutral snapshot for the shader curve picker.
 * Only call after checking that Iris is loaded; UI classes do not link Iris types.
 * Adapted from qualet's BBS Curve Fixer (see docs/third-party/curvefixer.md).
 */
public final class IrisShaderMenu
{
    private IrisShaderMenu()
    {}

    /**
     * Raw merged shader-pack translations (en_us fallback overlaid with {@code language}), without the
     * breadcrumb path expansion {@code IrisUtils.getShadersLanguageMap} applies. Exposes the original
     * keys: {@code option.<id>}, {@code screen.<id>}, {@code value.<id>.<val>}, {@code prefix.<id>},
     * {@code suffix.<id>}, etc. — used by the refreshed picker to label cells with their short names.
     */
    public static Map<String, String> getShadersRawLanguageMap(String language)
    {
        if (Iris.getCurrentPack().isPresent())
        {
            ShaderPack shaderPack = Iris.getCurrentPack().get();
            LanguageMap languageMap = shaderPack.getLanguageMap();
            Map<String, String> map = new HashMap<>();
            Map<String, String> fallback = languageMap.getTranslations("en_us");
            Map<String, String> target = languageMap.getTranslations(language);

            if (fallback != null)
            {
                map.putAll(fallback);
            }

            if (target != null)
            {
                map.putAll(target);
            }

            return map;
        }

        return Collections.emptyMap();
    }

    /**
     * Snapshot the currently loaded shader pack's options menu into the neutral {@link ShaderMenu}
     * model and classify every option relative to {@code ShaderCurves.variableMap}.
     *
     * <p>Returns {@code null} when no pack is loaded — the picker is built lazily on open, and the
     * data is only valid while a pack is present ({@code ShaderCurves.reset()} clears it on swap).</p>
     */
    public static ShaderMenu buildShaderMenu()
    {
        Optional<ShaderPack> packOptional = Iris.getCurrentPack();

        if (packOptional.isEmpty())
        {
            return null;
        }

        OptionMenuContainer container = packOptional.get().getMenuContainer();
        Set<String> curvable = ShaderCurves.variableMap.keySet();
        Set<String> menuOptions = new HashSet<>();

        ShaderMenu.Screen mainScreen = buildScreen(ShaderMenu.MAIN_SCREEN, container.mainScreen, curvable, menuOptions);
        Map<String, ShaderMenu.Screen> subScreens = new LinkedHashMap<>();

        for (Map.Entry<String, OptionMenuElementScreen> entry : container.subScreens.entrySet())
        {
            subScreens.put(entry.getKey(), buildScreen(entry.getKey(), entry.getValue(), curvable, menuOptions));
        }

        Set<String> curvableInMenu = new HashSet<>();
        Set<String> nonCurvableInMenu = new HashSet<>();

        for (String optionId : menuOptions)
        {
            if (curvable.contains(optionId)) curvableInMenu.add(optionId);
            else nonCurvableInMenu.add(optionId);
        }

        Set<String> curvableNotInMenu = new HashSet<>();

        for (String optionId : curvable)
        {
            if (!menuOptions.contains(optionId)) curvableNotInMenu.add(optionId);
        }

        return new ShaderMenu(mainScreen, subScreens, curvableInMenu, nonCurvableInMenu, curvableNotInMenu);
    }

    private static ShaderMenu.Screen buildScreen(String id, OptionMenuElementScreen screen, Set<String> curvable, Set<String> menuOptions)
    {
        List<ShaderMenu.Cell> cells = new ArrayList<>();

        for (OptionMenuElement element : screen.elements)
        {
            cells.add(buildCell(element, curvable, menuOptions));
        }

        return new ShaderMenu.Screen(id, screen.getColumnCount(), cells);
    }

    private static ShaderMenu.Cell buildCell(OptionMenuElement element, Set<String> curvable, Set<String> menuOptions)
    {
        if (element instanceof OptionMenuLinkElement link)
        {
            return ShaderMenu.Cell.link(link.targetScreenId);
        }
        else if (element instanceof OptionMenuProfileElement profile)
        {
            ProfileSet profiles = profile.profiles;
            ProfileSet.ProfileResult result = profiles.scan(profile.options, profile.getPendingOptionValues());
            String name = result.current.map((p) -> p.name).orElse(null);

            return ShaderMenu.Cell.profile(name, profiles.size());
        }
        else if (element instanceof OptionMenuOptionElement option)
        {
            String optionId = option.optionId;
            boolean curve = curvable.contains(optionId);
            OptionValues values = option.getAppliedOptionValues();

            menuOptions.add(optionId);

            if (element instanceof OptionMenuBooleanOptionElement)
            {
                boolean current = values.getBooleanValueOrDefault(optionId);

                return ShaderMenu.Cell.option(optionId, option.slider, true, curve, Boolean.toString(current), Arrays.asList("false", "true"), current ? 1 : 0);
            }
            else if (element instanceof OptionMenuStringOptionElement stringElement)
            {
                String current = values.getStringValueOrDefault(optionId);
                List<String> allowed = new ArrayList<>(stringElement.option.getAllowedValues());

                return ShaderMenu.Cell.option(optionId, option.slider, false, curve, current, allowed, allowed.indexOf(current));
            }

            /* Unknown option subtype — still record its presence in the menu set. */
            return ShaderMenu.Cell.option(optionId, option.slider, false, curve, null, Collections.emptyList(), -1);
        }

        return ShaderMenu.Cell.empty();
    }
}
