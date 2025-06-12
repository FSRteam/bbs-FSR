package mchorse.bbs_mod.ui.forms.editors.panels.widgets.states.properties;

import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.properties.TransformProperty;
import mchorse.bbs_mod.forms.triggers.StateTrigger;
import mchorse.bbs_mod.ui.framework.elements.input.UITransform;
import mchorse.bbs_mod.utils.Axis;
import mchorse.bbs_mod.utils.pose.Transform;

import java.util.function.Consumer;

public class UITransformPropertyEditor extends UIFormPropertyEditor<Transform, TransformProperty>
{
    public UITTransform transform;

    public UITransformPropertyEditor(ModelForm modelForm, StateTrigger trigger, String id, TransformProperty property)
    {
        super(modelForm, trigger, id, property);
    }

    @Override
    protected void fillData(TransformProperty property)
    {
        this.transform = new UITTransform(this::setValue);
        this.transform.setTransform(property.get());

        this.add(this.transform);
    }

    public static class UITTransform extends UITransform
    {
        private Consumer<Transform> callback;

        public UITTransform(Consumer<Transform> callback)
        {
            this.callback = callback;
        }

        public void setTransform(Transform transform)
        {
            this.tx.setValue(transform.translate.x);
            this.ty.setValue(transform.translate.y);
            this.tz.setValue(transform.translate.z);
            this.sx.setValue(transform.scale.x);
            this.sy.setValue(transform.scale.y);
            this.sz.setValue(transform.scale.z);
            this.rx.setValue(transform.rotate.x);
            this.ry.setValue(transform.rotate.y);
            this.rz.setValue(transform.rotate.z);
            this.r2x.setValue(transform.rotate2.x);
            this.r2y.setValue(transform.rotate2.y);
            this.r2z.setValue(transform.rotate2.z);
        }

        @Override
        public void setT(Axis axis, double x, double y, double z)
        {
            if (this.callback != null)
            {
                this.callback.accept(this.create());
            }
        }

        @Override
        public void setS(Axis axis, double x, double y, double z)
        {
            if (this.callback != null)
            {
                this.callback.accept(this.create());
            }
        }

        @Override
        public void setR(Axis axis, double x, double y, double z)
        {
            if (this.callback != null)
            {
                this.callback.accept(this.create());
            }
        }

        @Override
        public void setR2(Axis axis, double x, double y, double z)
        {
            if (this.callback != null)
            {
                this.callback.accept(this.create());
            }
        }

        private Transform create()
        {
            Transform t = new Transform();

            t.translate.set(this.tx.getValue(), this.ty.getValue(), this.tz.getValue());
            t.scale.set(this.sx.getValue(), this.sy.getValue(), this.sz.getValue());
            t.rotate.set(this.rx.getValue(), this.ry.getValue(), this.rz.getValue());
            t.rotate2.set(this.r2x.getValue(), this.r2y.getValue(), this.r2z.getValue());

            return t;
        }
    }
}