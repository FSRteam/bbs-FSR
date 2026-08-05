package mchorse.bbs_mod.ui.framework.elements.layout;

import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.ui.EditorLayoutNode;

import java.util.List;
import java.util.Set;

/**
 * Pluggable storage for a {@link UIDockLayout}. Decouples the docking component from any
 * particular settings value: the Film editor backs this with its per-editor film layout,
 * the Particle editor with its own particle layout, both living in {@code ValueEditorLayout}.
 */
public interface ILayoutSource
{
    /** The settings value to batch writes through via {@link BaseValue#edit}. */
    BaseValue value();

    EditorLayoutNode getRoot();

    void setRoot(EditorLayoutNode root);

    List<EditorLayoutNode.SplitterNode> getSplitters();

    List<EditorLayoutNode.SplitterNode> getSplittersForWrite();

    EditorLayoutNode getDefault();

    /** Panels the user hid; the dock's ensure pass must not re-add them. Returns a copy. */
    Set<String> getHiddenPanels();

    void setHiddenPanels(Set<String> hidden);
}
