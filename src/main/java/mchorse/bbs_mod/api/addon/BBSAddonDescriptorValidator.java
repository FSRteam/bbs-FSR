package mchorse.bbs_mod.api.addon;

import mchorse.bbs_mod.api.BBSApiVersion;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public final class BBSAddonDescriptorValidator
{
    private static final String ID_PATTERN = "[a-z0-9_.-]+";

    private BBSAddonDescriptorValidator() {}

    public static List<String> validate(BBSAddonDescriptor descriptor, Predicate<String> modLoaded)
    {
        List<String> issues = new ArrayList<>();

        if (descriptor == null)
        {
            issues.add("descriptor is null");
            return issues;
        }

        validateId("addonId", descriptor.addonId(), issues);

        if (!BBSApiVersion.isSupported(descriptor.apiVersion()))
        {
            issues.add("unsupported apiVersion '" + descriptor.apiVersion() + "'");
        }

        for (String namespace : descriptor.namespaces())
        {
            validateId("namespace", namespace, issues);
        }

        Predicate<String> loaded = modLoaded == null ? (id) -> true : modLoaded;

        for (String modId : descriptor.requiredMods())
        {
            validateId("requiredMod", modId, issues);

            if (!loaded.test(modId))
            {
                issues.add("required mod '" + modId + "' is not loaded");
            }
        }

        return issues;
    }

    private static void validateId(String field, String value, List<String> issues)
    {
        if (value == null || value.isBlank())
        {
            issues.add(field + " is blank");
        }
        else if (!value.matches(ID_PATTERN))
        {
            issues.add(field + " '" + value + "' must match " + ID_PATTERN);
        }
    }
}
