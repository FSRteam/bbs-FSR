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

import mchorse.bbs_mod.utils.joml.Matrices;
import org.joml.Matrix4f;

public class BodyPart extends ValueGroup
{
    private static final Matrix4f NO_BONE = new Matrix4f().rotateY((float) Math.PI);

    private Form form;

    public final ValueTransform transform = new ValueTransform("transform", new Transform());
    public final ValueString bone = new ValueString("bone", "");
    public final ValueBoolean useTarget = new ValueBoolean("useTarget", false);

    public final ValueBoolean inheritPosition = new ValueBoolean("inheritPosition", true);
    public final ValueBoolean inheritRotation = new ValueBoolean("inheritRotation", true);
    public final ValueBoolean inheritScale = new ValueBoolean("inheritScale", true);

    private IEntity entity = new StubEntity();

    public BodyPart(String id)
    {
        super(id);

        this.add(this.transform);
        this.add(this.bone);
        this.add(this.useTarget);
        this.add(this.inheritPosition);
        this.add(this.inheritRotation);
        this.add(this.inheritScale);
    }

    public Form getForm()
    {
        return this.form;
    }

    public boolean inheritsWholeBone()
    {
        return this.inheritPosition.get() && this.inheritRotation.get() && this.inheritScale.get();
    }

    /**
     * The attachment bone's matrix with the components the part doesn't inherit taken from
     * {@link #NO_BONE} instead. Returns the matrix itself when there is nothing to take out, and
     * never modifies the input: the bone matrices are a shared per-frame cache read by every part
     * hanging off that bone.
     */
    public Matrix4f filterBoneMatrix(Matrix4f matrix)
    {
        if (matrix == null || this.inheritsWholeBone())
        {
            return matrix;
        }

        return Matrices.compose(
            this.inheritPosition.get() ? matrix : NO_BONE,
            this.inheritRotation.get() ? matrix : NO_BONE,
            this.inheritScale.get() ? matrix : NO_BONE
        );
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

}
