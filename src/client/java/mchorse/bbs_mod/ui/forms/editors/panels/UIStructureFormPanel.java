package mchorse.bbs_mod.ui.forms.editors.panels;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.forms.forms.StructureForm;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.forms.editors.forms.UIForm;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.input.UIColor;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIListOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.utils.colors.Color;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class UIStructureFormPanel extends UIFormPanel<StructureForm>
{
    public UITextbox structureFile;
    public UIButton pickStructure;
    public UIColor color;

    public UIStructureFormPanel(UIForm editor)
    {
        super(editor);

        this.structureFile = new UITextbox(1000, (value) -> this.form.structureFile.set(value)).border();
        this.pickStructure = new UIButton(UIKeys.FORMS_EDITORS_STRUCTURE_PICK, (b) -> this.pickStructure());
        this.color = new UIColor((value) -> this.form.color.set(Color.rgba(value))).withAlpha();

        this.options.add(UI.label(UIKeys.FORMS_EDITORS_STRUCTURE_ID), this.structureFile, this.pickStructure, this.color);
    }

    private void pickStructure()
    {
        UIListOverlayPanel overlay = new UIListOverlayPanel(UIKeys.FORMS_EDITORS_STRUCTURE_PICK, this::setStructure);
        List<String> structures = this.collectStructures();

        overlay.addValues(structures);
        overlay.list.list.sort();
        overlay.setValue(this.form.structureFile.get());

        UIOverlay.addOverlay(this.getContext(), overlay, 280, 0.5F);
    }

    private List<String> collectStructures()
    {
        List<String> structures = new ArrayList<>();
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();

        if (server != null)
        {
            StructureTemplateManager manager = server.getStructureManager();

            manager.listTemplates().map(ResourceLocation::toString).forEach((id) -> this.addStructure(structures, id));
        }

        if (BBSMod.getProvider() != null)
        {
            for (String source : BBSMod.getProvider().getSourceKeys())
            {
                this.collectProviderStructures(structures, new Link(source, "structures"));
            }
        }

        return structures;
    }

    private void collectProviderStructures(List<String> structures, Link root)
    {
        try
        {
            Collection<Link> links = BBSMod.getProvider().getLinksFromPath(root);

            for (Link link : links)
            {
                if (link.path.toLowerCase().endsWith(".nbt"))
                {
                    String value = link.toString();

                    this.addStructure(structures, value);
                }
            }
        }
        catch (Exception e)
        {}
    }

    private void addStructure(List<String> structures, String structure)
    {
        if (!structures.contains(structure))
        {
            structures.add(structure);
        }
    }

    private void setStructure(String structure)
    {
        if (structure == null)
        {
            structure = "";
        }

        this.form.structureFile.set(structure);
        this.structureFile.setText(structure);
    }

    @Override
    public void startEdit(StructureForm form)
    {
        super.startEdit(form);

        this.structureFile.setText(form.structureFile.get());
        this.color.setColor(form.color.get().getARGBColor());
    }
}
