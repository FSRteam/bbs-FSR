package mchorse.bbs_mod.forms.forms;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.entities.StubEntity;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.settings.values.core.ValueString;
import mchorse.bbs_mod.settings.values.core.ValueTransform;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.utils.pose.Transform;

import java.util.Set;

public class BodyPart extends ValueGroup
{
    /**
     * The attachment switches a build newer than this one writes.
     *
     * <p>Upstream lets a part ride only some components of its bone's frame; this build always
     * takes the bone whole, so it has no child to claim the three flags. Dropping them would make
     * a sight that follows a hand without turning with it — or a form that keeps its size on a
     * stretched bone — come back as an ordinary attachment, and the authoring choice would be
     * gone rather than merely ignored. Kept as raw data; see
     * {@link ValueGroup#preservedUnknownKeys()}.</p>
     */
    private static final Set<String> PRESERVED_UNKNOWN_KEYS = Set.of("inheritPosition", "inheritRotation", "inheritScale");

    private Form form;

    public final ValueTransform transform = new ValueTransform("transform", new Transform());
    public final ValueString bone = new ValueString("bone", "");
    public final ValueBoolean useTarget = new ValueBoolean("useTarget", false);

    private IEntity entity = new StubEntity();

    public BodyPart(String id)
    {
        super(id);

        this.add(this.transform);
        this.add(this.bone);
        this.add(this.useTarget);
    }

    public Form getForm()
    {
        return this.form;
    }

    public IEntity getEntity()
    {
        return this.entity;
    }

    public BodyPartManager getManager()
    {
        return this.parent instanceof BodyPartManager parts ? parts : null;
    }

    /**
     * Resolve the entity used by a nested form and propagate the target world to the part's
     * neutral entity. The part keeps its own animation clock when it does not mirror the target,
     * but still belongs to the target level for collision-aware model physics.
     */
    public IEntity getRenderEntity(IEntity target)
    {
        if (this.useTarget.get())
        {
            return target;
        }

        this.syncWorld(target);

        return this.entity;
    }

    private void syncWorld(IEntity target)
    {
        if (target != null && target != this.entity)
        {
            this.entity.setWorld(target.getWorld());
        }
    }

    public void setForm(Form form)
    {
        this.preNotify();
        this.setInternalForm(form);
        this.postNotify();
    }

    private void setInternalForm(Form form)
    {
        if (this.form != null)
        {
            this.remove(this.form);
        }

        this.form = form;

        if (this.form != null)
        {
            form.setId("form");
            this.add(this.form);
        }
    }

    public void update(IEntity target)
    {
        this.syncWorld(target);

        if (this.form != null)
        {
            this.form.update(this.getRenderEntity(target));
        }

        this.entity.update();
    }

    public BodyPart copy()
    {
        BodyPart part = new BodyPart(this.id);

        part.fromData(this.toData());

        return part;
    }

    @Override
    public void fromData(BaseType data)
    {
        super.fromData(data);

        if (data.isMap())
        {
            MapType map = data.asMap();
            Form form = map.has("form") ? FormUtils.fromData(map.getMap("form")) : null;

            this.setInternalForm(form);
        }
    }

    @Override
    protected Set<String> preservedUnknownKeys()
    {
        return PRESERVED_UNKNOWN_KEYS;
    }
}
