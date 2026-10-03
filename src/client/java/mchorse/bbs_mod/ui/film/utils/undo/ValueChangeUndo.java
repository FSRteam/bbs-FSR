package mchorse.bbs_mod.ui.film.utils.undo;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.utils.DataPath;
import mchorse.bbs_mod.utils.undo.IUndo;

public class ValueChangeUndo extends FilmEditorUndo
{
    public DataPath name;
    public BaseType oldValue;
    public BaseType newValue;
    public MapType uiBefore;
    public MapType uiAfter;

    private boolean mergable = true;
    private boolean invalid;
    private BaseValue appliedValue;

    /**
     * The value the last {@link #undo(ValueGroup)}/{@link #redo(ValueGroup)} resolved and restored,
     * or {@code null} when neither has run. Film editor change notifications carry these rather than
     * the recorded paths: a path may address a value that only exists while the tree is live, and an
     * undo that resolved nothing must not pretend a value changed.
     */
    public BaseValue getAppliedValue()
    {
        return this.appliedValue;
    }

    public ValueChangeUndo(DataPath name, BaseType oldValue, BaseType newValue)
    {
        this.name = name;
        this.oldValue = oldValue;
        this.newValue = newValue;
    }

    public MapType getUIData(boolean redo)
    {
        return redo ? this.uiAfter : this.uiBefore;
    }

    public void cacheBefore(MapType uiData)
    {
        this.uiBefore = uiData;
    }

    public void cacheAfter(UIElement editor)
    {
        this.uiAfter = editor.getRoot() == null ? new MapType() : editor.getRoot().collectAllUndoData();
    }

    public DataPath getName()
    {
        return this.name;
    }

    @Override
    public IUndo<ValueGroup> noMerging()
    {
        this.mergable = false;

        return this;
    }

    @Override
    public boolean isMergeable(IUndo<ValueGroup> undo)
    {
        if (!this.mergable || this.invalid)
        {
            return false;
        }

        if (undo instanceof ValueChangeUndo)
        {
            ValueChangeUndo valueUndo = (ValueChangeUndo) undo;

            return !valueUndo.invalid && this.name.equals(valueUndo.getName());
        }

        return false;
    }

    @Override
    public void merge(IUndo<ValueGroup> undo)
    {
        if (this.invalid)
        {
            return;
        }

        if (undo instanceof ValueChangeUndo)
        {
            ValueChangeUndo prop = (ValueChangeUndo) undo;

            if (!prop.invalid)
            {
                this.newValue = prop.newValue;
            }
        }
    }

    private BaseValue resolveValue(ValueGroup context)
    {
        if (this.invalid)
        {
            return null;
        }

        BaseValue value = context.findRecursively(this.name);

        if (value == null || !value.getPath().equals(this.name))
        {
            this.invalid = true;
            this.mergable = false;

            return null;
        }

        return value;
    }

    @Override
    public void undo(ValueGroup context)
    {
        BaseValue value = this.appliedValue = this.resolveValue(context);

        if (value != null)
        {
            value.fromData(this.oldValue);
        }
    }

    @Override
    public void redo(ValueGroup context)
    {
        BaseValue value = this.appliedValue = this.resolveValue(context);

        if (value != null)
        {
            value.fromData(this.newValue);
        }
    }
}
