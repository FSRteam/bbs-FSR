package mchorse.bbs_mod.forms.forms;

import mchorse.bbs_mod.settings.values.core.ValueColor;
import mchorse.bbs_mod.settings.values.core.ValueString;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import mchorse.bbs_mod.utils.colors.Color;

public class StructureForm extends Form
{
    public final ValueString structureFile = new ValueString("structure_file", "");
    public final ValueColor color = new ValueColor("color", Color.white());
    public final ValueString biomeId = new ValueString("biome_id", "");
    public final ValueBoolean emitLight = new ValueBoolean("emit_light", false);
    public final ValueInt lightIntensity = new ValueInt("light_intensity", 15);
    public final ValueBoolean tintBlockEntities = new ValueBoolean("tint_block_entities", false);
    public final ValueFloat pivotX = new ValueFloat("pivot_x", 0F);
    public final ValueFloat pivotY = new ValueFloat("pivot_y", 0F);
    public final ValueFloat pivotZ = new ValueFloat("pivot_z", 0F);

    public StructureForm()
    {
        this.add(this.structureFile);
        this.add(this.color);
        this.add(this.biomeId);

        this.emitLight.invisible();
        this.lightIntensity.invisible();
        this.tintBlockEntities.invisible();
        this.pivotX.invisible();
        this.pivotY.invisible();
        this.pivotZ.invisible();

        this.add(this.emitLight);
        this.add(this.lightIntensity);
        this.add(this.tintBlockEntities);
        this.add(this.pivotX);
        this.add(this.pivotY);
        this.add(this.pivotZ);
    }

    @Override
    protected String getDefaultDisplayName()
    {
        String path = this.structureFile.get();

        if (path == null || path.isEmpty())
        {
            return super.getDefaultDisplayName();
        }

        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String name = slash >= 0 ? path.substring(slash + 1) : path;

        if (name.toLowerCase().endsWith(".nbt"))
        {
            name = name.substring(0, name.length() - 4);
        }

        return name.isEmpty() ? super.getDefaultDisplayName() : name;
    }

    @Override
    public String getTrackName(String property)
    {
        int slash = property.lastIndexOf('/');
        String prefix = slash == -1 ? "" : property.substring(0, slash + 1);
        String last = slash == -1 ? property : property.substring(slash + 1);

        if ("structure_file".equals(last))
        {
            last = "structure";
        }
        else if ("biome_id".equals(last))
        {
            last = "biome";
        }

        return super.getTrackName(prefix + last);
    }
}
