package mchorse.bbs_mod.settings.values.ui;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.utils.MathUtils;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Supplier;

public class ValueEditorLayout extends BaseValue
{
    public enum FilmEditor
    {
        CAMERA("camera"),
        REPLAY("replay"),
        ACTION("action");

        public final String id;

        FilmEditor(String id)
        {
            this.id = id;
        }
    }

    private static class LayoutState
    {
        private EditorLayoutNode root;
        private final List<EditorLayoutNode.SplitterNode> splitters = new ArrayList<>();
        private final Supplier<EditorLayoutNode> defaultSupplier;

        public LayoutState(EditorLayoutNode root, Supplier<EditorLayoutNode> defaultSupplier)
        {
            this.defaultSupplier = defaultSupplier;
            this.setRoot(root);
        }

        public EditorLayoutNode getRoot()
        {
            return this.root;
        }

        public List<EditorLayoutNode.SplitterNode> getSplitters()
        {
            return this.splitters;
        }

        public void setRoot(EditorLayoutNode root)
        {
            this.root = root == null ? this.defaultSupplier.get() : root;
            this.splitters.clear();
            EditorLayoutNode.collectSplitters(this.root, this.splitters);
        }
    }

    private final LayoutState filmLayout = new LayoutState(EditorLayoutNode.defaultFilmLayout(), EditorLayoutNode::defaultFilmLayout);
    private final EnumMap<FilmEditor, LayoutState> filmEditorLayouts = new EnumMap<>(FilmEditor.class);
    private final EnumSet<FilmEditor> boundFilmEditors = EnumSet.noneOf(FilmEditor.class);
    private final LayoutState particleLayout = new LayoutState(EditorLayoutNode.defaultParticleLayout(), EditorLayoutNode::defaultParticleLayout);
    private float stateEditorSizeH = 0.7F;
    private float stateEditorSizeV = 0.25F;
    private int keyframeLabelWidth = 120;

    public ValueEditorLayout(String id)
    {
        super(id);
    }

    public EditorLayoutNode getFilmLayoutRoot()
    {
        return this.filmLayout.getRoot();
    }

    public EditorLayoutNode getFilmLayoutRoot(FilmEditor editor)
    {
        return this.getFilmLayoutState(editor, false).getRoot();
    }

    public void setFilmLayoutRoot(EditorLayoutNode root)
    {
        BaseValue.edit(this, (v) -> this.filmLayout.setRoot(root));
    }

    public void setFilmLayoutRoot(FilmEditor editor, EditorLayoutNode root)
    {
        if (editor == null)
        {
            this.setFilmLayoutRoot(root);

            return;
        }

        BaseValue.edit(this, (v) -> this.getFilmLayoutState(editor, true).setRoot(root));
    }

    public List<EditorLayoutNode.SplitterNode> getFilmSplitters()
    {
        return this.filmLayout.getSplitters();
    }

    public List<EditorLayoutNode.SplitterNode> getFilmSplitters(FilmEditor editor)
    {
        return this.getFilmLayoutState(editor, false).getSplitters();
    }

    public List<EditorLayoutNode.SplitterNode> getFilmSplittersForWrite()
    {
        return this.filmLayout.getSplitters();
    }

    public List<EditorLayoutNode.SplitterNode> getFilmSplittersForWrite(FilmEditor editor)
    {
        return this.getFilmLayoutState(editor, true).getSplitters();
    }

    public boolean isFilmLayoutBound(FilmEditor editor)
    {
        return editor != null && this.boundFilmEditors.contains(editor);
    }

    public void setFilmLayoutBound(FilmEditor editor, boolean bound)
    {
        if (editor == null)
        {
            return;
        }

        BaseValue.edit(this, (v) ->
        {
            if (bound)
            {
                this.boundFilmEditors.add(editor);
            }
            else
            {
                this.boundFilmEditors.remove(editor);
                this.filmEditorLayouts.remove(editor);
            }
        });
    }

    public EditorLayoutNode getParticleLayoutRoot()
    {
        return this.particleLayout.getRoot();
    }

    public void setParticleLayoutRoot(EditorLayoutNode root)
    {
        BaseValue.edit(this, (v) -> this.particleLayout.setRoot(root));
    }

    public List<EditorLayoutNode.SplitterNode> getParticleSplitters()
    {
        return this.particleLayout.getSplitters();
    }

    public List<EditorLayoutNode.SplitterNode> getParticleSplittersForWrite()
    {
        return this.particleLayout.getSplitters();
    }

    public void setParticleSplitterRatio(int index, float ratio)
    {
        if (index < 0 || index >= this.particleLayout.getSplitters().size())
        {
            return;
        }
        int i = index;
        BaseValue.edit(this, (v) -> this.particleLayout.getSplitters().get(i).setRatio(MathUtils.clamp(ratio, 0.05F, 0.95F)));
    }

    public void setFilmSplitterRatio(int index, float ratio)
    {
        if (index < 0 || index >= this.filmLayout.getSplitters().size())
        {
            return;
        }
        int i = index;
        BaseValue.edit(this, (v) -> this.filmLayout.getSplitters().get(i).setRatio(MathUtils.clamp(ratio, 0.05F, 0.95F)));
    }

    public float getFilmMainRatio()
    {
        EditorLayoutNode root = this.filmLayout.getRoot();

        if (root instanceof EditorLayoutNode.SplitterNode)
        {
            return ((EditorLayoutNode.SplitterNode) root).getRatio();
        }
        return 0.66F;
    }

    public float getFilmSmallRatio()
    {
        EditorLayoutNode root = this.filmLayout.getRoot();

        if (root instanceof EditorLayoutNode.SplitterNode)
        {
            EditorLayoutNode second = ((EditorLayoutNode.SplitterNode) root).getSecond();
            if (second instanceof EditorLayoutNode.SplitterNode)
            {
                return ((EditorLayoutNode.SplitterNode) second).getRatio();
            }
        }
        return 0.5F;
    }

    public void setFilmRatios(float mainRatio, float smallRatio)
    {
        BaseValue.edit(this, (v) ->
        {
            EditorLayoutNode rootNode = this.filmLayout.getRoot();

            if (rootNode instanceof EditorLayoutNode.SplitterNode)
            {
                EditorLayoutNode.SplitterNode root = (EditorLayoutNode.SplitterNode) rootNode;
                root.setRatio(MathUtils.clamp(mainRatio, 0.05F, 0.95F));
                EditorLayoutNode second = root.getSecond();
                if (second instanceof EditorLayoutNode.SplitterNode)
                {
                    ((EditorLayoutNode.SplitterNode) second).setRatio(MathUtils.clamp(smallRatio, 0.05F, 0.95F));
                }
            }
        });
    }

    public void setFilmMainRatio(float mainRatio)
    {
        BaseValue.edit(this, (v) ->
        {
            EditorLayoutNode root = this.filmLayout.getRoot();

            if (root instanceof EditorLayoutNode.SplitterNode)
            {
                ((EditorLayoutNode.SplitterNode) root).setRatio(MathUtils.clamp(mainRatio, 0.05F, 0.95F));
            }
        });
    }

    public void setFilmSmallRatio(float smallRatio)
    {
        BaseValue.edit(this, (v) ->
        {
            EditorLayoutNode root = this.filmLayout.getRoot();

            if (root instanceof EditorLayoutNode.SplitterNode)
            {
                EditorLayoutNode second = ((EditorLayoutNode.SplitterNode) root).getSecond();
                if (second instanceof EditorLayoutNode.SplitterNode)
                {
                    ((EditorLayoutNode.SplitterNode) second).setRatio(MathUtils.clamp(smallRatio, 0.05F, 0.95F));
                }
            }
        });
    }

    public void setStateEditorSizeH(float stateEditorSizeH)
    {
        BaseValue.edit(this, (v) -> this.stateEditorSizeH = stateEditorSizeH);
    }

    public void setStateEditorSizeV(float stateEditorSizeV)
    {
        BaseValue.edit(this, (v) -> this.stateEditorSizeV = stateEditorSizeV);
    }

    public float getStateEditorSizeH()
    {
        return MathUtils.clamp(this.stateEditorSizeH, 0.1F, 0.9F);
    }

    public float getStateEditorSizeV()
    {
        return MathUtils.clamp(this.stateEditorSizeV, 0.1F, 0.9F);
    }

    public int getKeyframeLabelWidth()
    {
        return MathUtils.clamp(this.keyframeLabelWidth, 40, 400);
    }

    public void setKeyframeLabelWidth(int keyframeLabelWidth)
    {
        BaseValue.edit(this, (v) -> this.keyframeLabelWidth = MathUtils.clamp(keyframeLabelWidth, 40, 400));
    }

    @Override
    public BaseType toData()
    {
        MapType data = new MapType();
        MapType filmEditorLayouts = new MapType();
        MapType filmEditorBindings = new MapType();

        data.put("film_layout", this.filmLayout.getRoot().toData());
        data.put("particle_layout", this.particleLayout.getRoot().toData());

        for (FilmEditor editor : FilmEditor.values())
        {
            LayoutState state = this.filmEditorLayouts.get(editor);

            if (state != null)
            {
                filmEditorLayouts.put(editor.id, state.getRoot().toData());
            }

            if (this.boundFilmEditors.contains(editor))
            {
                filmEditorBindings.putBool(editor.id, true);
            }
        }

        if (!filmEditorLayouts.isEmpty())
        {
            data.put("film_editor_layouts", filmEditorLayouts);
        }

        if (!filmEditorBindings.isEmpty())
        {
            data.put("film_editor_layout_bindings", filmEditorBindings);
        }

        data.putFloat("state_editor_size_h", this.stateEditorSizeH);
        data.putFloat("state_editor_size_v", this.stateEditorSizeV);
        data.putInt("keyframe_label_width", this.keyframeLabelWidth);
        return data;
    }

    @Override
    public void fromData(BaseType data)
    {
        this.resetFilmLayouts();
        this.particleLayout.setRoot(EditorLayoutNode.defaultParticleLayout());

        if (data.isMap())
        {
            MapType map = data.asMap();

            if (map.has("film_layout"))
            {
                EditorLayoutNode filmLayoutRoot = EditorLayoutNode.fromData(map.get("film_layout"));

                if (filmLayoutRoot == null)
                {
                    filmLayoutRoot = EditorLayoutNode.defaultFilmLayout();
                }

                this.filmLayout.setRoot(filmLayoutRoot);
            }
            else
            {
                float mainV = map.getFloat("main_size_v", 0.66F);
                float editorV = map.getFloat("editor_size_v", 0.5F);
                EditorLayoutNode filmLayoutRoot = EditorLayoutNode.defaultFilmLayout();

                if (filmLayoutRoot instanceof EditorLayoutNode.SplitterNode)
                {
                    EditorLayoutNode.SplitterNode root = (EditorLayoutNode.SplitterNode) filmLayoutRoot;
                    root.setRatio(MathUtils.clamp(mainV, 0.05F, 0.95F));
                    EditorLayoutNode second = root.getSecond();
                    if (second instanceof EditorLayoutNode.SplitterNode)
                    {
                        ((EditorLayoutNode.SplitterNode) second).setRatio(MathUtils.clamp(editorV, 0.05F, 0.95F));
                    }
                }

                this.filmLayout.setRoot(filmLayoutRoot);
            }

            if (map.has("particle_layout"))
            {
                this.particleLayout.setRoot(EditorLayoutNode.fromData(map.get("particle_layout")));
            }

            MapType editorLayouts = map.getMap("film_editor_layouts");

            for (FilmEditor editor : FilmEditor.values())
            {
                if (!editorLayouts.has(editor.id))
                {
                    continue;
                }

                EditorLayoutNode root = EditorLayoutNode.fromData(editorLayouts.get(editor.id));

                if (root != null)
                {
                    this.filmEditorLayouts.put(editor, new LayoutState(root, EditorLayoutNode::defaultFilmLayout));
                }
            }

            MapType editorBindings = map.getMap("film_editor_layout_bindings");

            for (FilmEditor editor : FilmEditor.values())
            {
                if (editorBindings.getBool(editor.id))
                {
                    this.boundFilmEditors.add(editor);
                }
            }

            this.stateEditorSizeH = map.getFloat("state_editor_size_h", 0.7F);
            this.stateEditorSizeV = map.getFloat("state_editor_size_v", 0.25F);
            this.keyframeLabelWidth = map.getInt("keyframe_label_width", 120);
        }
    }

    private LayoutState getFilmLayoutState(FilmEditor editor, boolean forWrite)
    {
        if (editor == null)
        {
            return this.filmLayout;
        }

        LayoutState state = this.filmEditorLayouts.get(editor);

        if (state == null)
        {
            state = new LayoutState(copyFilmLayoutRoot(this.filmLayout.getRoot()), EditorLayoutNode::defaultFilmLayout);
            this.filmEditorLayouts.put(editor, state);
        }

        return state;
    }

    private void resetFilmLayouts()
    {
        this.filmLayout.setRoot(EditorLayoutNode.defaultFilmLayout());
        this.filmEditorLayouts.clear();
        this.boundFilmEditors.clear();
    }

    private static EditorLayoutNode copyFilmLayoutRoot(EditorLayoutNode root)
    {
        EditorLayoutNode copy = EditorLayoutNode.fromData((root == null ? EditorLayoutNode.defaultFilmLayout() : root).toData());

        return copy == null ? EditorLayoutNode.defaultFilmLayout() : copy;
    }
}
