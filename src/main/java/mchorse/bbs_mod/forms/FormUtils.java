package mchorse.bbs_mod.forms;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.forms.forms.BodyPart;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.properties.IFormProperty;
import mchorse.bbs_mod.utils.CollectionUtils;
import mchorse.bbs_mod.utils.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public class FormUtils
{
    public static final String PATH_SEPARATOR = "/";

    private static final List<String> path = new ArrayList<>();

    public static Form fromData(BaseType data)
    {
        if (data instanceof MapType map)
        {
            return fromData(map);
        }

        return null;
    }

    public static Form fromData(MapType data)
    {
        try
        {
            return data == null ? null : BBSMod.getForms().fromData(data);
        }
        catch (Exception e)
        {}

        return null;
    }

    public static MapType toData(Form form)
    {
        return form == null ? null : BBSMod.getForms().toData(form);
    }

    public static Form copy(Form form)
    {
        return form == null ? null : form.copy();
    }

    public static Form getRoot(Form form)
    {
        while (form.getParent() != null)
        {
            form = form.getParent();
        }

        return form;
    }

    public static Form getForm(Form form, String path)
    {
        String[] split = path.split(PATH_SEPARATOR);

        for (String s : split)
        {
            try
            {
                int index = Integer.parseInt(s);
                BodyPart safe = CollectionUtils.getSafe(form.parts.getAll(), index);

                if (safe != null)
                {
                    form = safe.getForm();
                }
                else
                {
                    break;
                }
            }
            catch (Exception e)
            {
                break;
            }
        }

        return form;
    }

    public static String getPath(Form form)
    {
        if (form.getParent() == null)
        {
            return "";
        }

        path.clear();

        while (form != null)
        {
            Form parent = form.getParent();

            if (parent != null)
            {
                int i = 0;

                for (BodyPart part : parent.parts.getAll())
                {
                    if (part.getForm() == form)
                    {
                        path.add(String.valueOf(i));
                    }

                    i += 1;
                }
            }

            form = parent;
        }

        Collections.reverse(path);

        return String.join(PATH_SEPARATOR, path);
    }

    /* Form properties utils */

    public static String getPropertyPath(IFormProperty property)
    {
        path.clear();
        path.add(property.getKey());

        Form form = property.getForm();

        while (form != null)
        {
            Form parent = form.getParent();

            if (parent != null)
            {
                int i = 0;

                for (BodyPart part : parent.parts.getAll())
                {
                    if (part.getForm() == form)
                    {
                        path.add(String.valueOf(i));
                    }

                    i += 1;
                }
            }

            form = parent;
        }

        Collections.reverse(path);

        return String.join(PATH_SEPARATOR, path);
    }

    public static List<String> collectPropertyPaths(Form form)
    {
        List<String> properties = new ArrayList<>();

        collectPropertyPaths(form, properties, "");

        /* There is no need to animate body part anchor properties */
        Iterator<String> it = properties.iterator();

        while (it.hasNext())
        {
            if (it.next().endsWith("/anchor"))
            {
                it.remove();
            }
        }

        return properties;
    }

    public static void collectPropertyPaths(Form form, List<String> properties, String prefix)
    {
        if (form == null || !form.animatable.get())
        {
            return;
        }

        for (IFormProperty property : form.getProperties().values())
        {
            if (property.canCreateChannel())
            {
                properties.add(StringUtils.combinePaths(prefix, property.getKey()));
            }
        }

        List<BodyPart> all = form.parts.getAll();

        for (int i = 0; i < all.size(); i++)
        {
            String newPrefix = StringUtils.combinePaths(prefix, String.valueOf(i));

            collectPropertyPaths(all.get(i).getForm(), properties, newPrefix);
        }
    }

    public static IFormProperty getProperty(Form form, String path)
    {
        if (form == null)
        {
            return null;
        }

        if (!path.contains(PATH_SEPARATOR))
        {
            return form.getProperties().get(path);
        }

        String[] segments = path.split(PATH_SEPARATOR);

        for (int i = 0; i < segments.length; i++)
        {
            String segment = segments[i];
            IFormProperty property = form.getProperties().get(segment);

            if (property == null)
            {
                try
                {
                    int index = Integer.parseInt(segment);

                    if (CollectionUtils.inRange(form.parts.getAll(), index))
                    {
                        form = form.parts.getAll().get(index).getForm();

                        if (form == null)
                        {
                            return null;
                        }
                    }
                    else
                    {
                        return null;
                    }
                }
                catch (Exception e)
                {}
            }
            else
            {
                return property;
            }
        }

        return null;
    }
}