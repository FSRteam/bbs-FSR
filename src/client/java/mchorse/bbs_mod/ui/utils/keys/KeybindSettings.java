package mchorse.bbs_mod.ui.utils.keys;

import mchorse.bbs_mod.settings.SettingsBuilder;
import mchorse.bbs_mod.settings.value.ValueKeyCombo;
import mchorse.bbs_mod.ui.Keys;
import mchorse.bbs_mod.ui.utils.icons.Icon;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class KeybindSettings
{
    private static final List<Class> classes = new ArrayList<>();

    /** Combos handed over one at a time rather than through a class of their own. */
    private static final List<KeyCombo> combos = new ArrayList<>();

    /** Icons for keybind categories, so an addon's own category is recognisable. */
    private static final Map<String, Icon> categoryIcons = new HashMap<>();

    public static void registerClasses()
    {
        classes.add(Keys.class);
    }

    /**
     * Adds a class whose {@code KeyCombo} fields become keybinds of their own.
     *
     * <p>A class rather than a combo at a time, because that is how BBS reads its own: by walking
     * the fields. Register before the keybind settings file is built.</p>
     */
    public static void register(Class clazz)
    {
        if (clazz != null)
        {
            classes.add(clazz);
        }
    }

    /** Removes a previously added class; returns whether it was there. */
    public static boolean unregister(Class clazz)
    {
        return clazz != null && classes.remove(clazz);
    }

    /** The classes whose key combos are read, in registration order. */
    public static List<Class> getRegisteredClasses()
    {
        return java.util.Collections.unmodifiableList(classes);
    }

    /**
     * Adds a single key combo, for an addon that would rather hand over its combos than a class
     * holding them. Register before the keybind settings file is built.
     */
    public static void register(KeyCombo combo)
    {
        if (combo != null)
        {
            combos.add(combo);
        }
    }

    /** The combos handed over through {@link #register(KeyCombo)}, in registration order. */
    public static List<KeyCombo> getRegisteredCombos()
    {
        return java.util.Collections.unmodifiableList(combos);
    }

    /**
     * Gives a keybind category an icon of its own.
     *
     * <p>Categories come from the combos' own {@code categoryKey}, so this is what an addon uses
     * to make its category look like part of BBS rather than a stray text row.</p>
     */
    public static void registerCategoryIcon(String category, Icon icon)
    {
        if (category != null && !category.isEmpty() && icon != null)
        {
            categoryIcons.put(category, icon);
        }
    }

    /** The icon registered for a category, or null when none was. */
    public static Icon getRegisteredCategoryIcon(String category)
    {
        return category == null ? null : categoryIcons.get(category);
    }

    public static void register(SettingsBuilder builder)
    {
        Map<String, List<KeyCombo>> byCategory = new HashMap<>();

        for (Class clazz : classes)
        {
            readKeyCombos(byCategory, clazz);
        }

        for (KeyCombo combo : KeybindSettings.combos)
        {
            byCategory.computeIfAbsent(combo.categoryKey, (k) -> new ArrayList<>()).add(combo);
        }

        List<String> keys = new ArrayList<>(byCategory.keySet());

        keys.sort(Comparator.comparing((a) -> a));

        for (String key : keys)
        {
            List<KeyCombo> comboList = byCategory.get(key);
            Icon icon = categoryIcons.get(key);

            if (icon == null)
            {
                builder.category(key);
            }
            else
            {
                builder.category(key, icon);
            }

            for (KeyCombo combo : comboList)
            {
                builder.register(new ValueKeyCombo(combo.id, combo));
            }
        }
    }

    private static void readKeyCombos(Map<String, List<KeyCombo>> combos, Class clazz)
    {
        for (Field field : clazz.getDeclaredFields())
        {
            if (field.getType() != KeyCombo.class)
            {
                continue;
            }

            try
            {
                KeyCombo combo = (KeyCombo) field.get(null);
                List<KeyCombo> comboList = combos.computeIfAbsent(combo.categoryKey, (k) -> new ArrayList<>());

                comboList.add(combo);
            }
            catch (Exception e)
            {}
        }
    }
}