package mchorse.bbs_mod.ui.forms.editors;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.model.ModelManager;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.forms.FormArchitect;
import mchorse.bbs_mod.forms.forms.BillboardForm;
import mchorse.bbs_mod.forms.forms.BodyPart;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.FramebufferForm;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.graphics.texture.TextureManager;
import mchorse.bbs_mod.resources.AssetProvider;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.framework.UIBaseMenu;
import mchorse.bbs_mod.utils.resources.FilteredLink;
import mchorse.bbs_mod.utils.resources.MultiLink;
import org.joml.Vector4f;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Executes the editor's face setup, snapshot undo and saved nested forms without GPU allocation. */
public final class FaceSetupParityTest
{
    public static void runAll() throws ReflectiveOperationException
    {
        Field registry = field(BBSMod.class, "forms");
        Field models = field(BBSModClient.class, "models");
        Field textures = field(BBSModClient.class, "textures");
        Object oldRegistry = registry.get(null);
        Object oldModels = models.get(null);
        Object oldTextures = textures.get(null);
        FormArchitect architect = new FormArchitect();
        architect.register(Link.bbs("model"), ModelForm.class);
        architect.register(Link.bbs("framebuffer"), FramebufferForm.class);
        architect.register(Link.bbs("billboard"), BillboardForm.class);
        registry.set(null, architect);
        models.set(null, new ModelManager(new AssetProvider())
        {
            @Override public ModelInstance getModel(String id) { return null; }
        });
        Texture skin = allocate(Texture.class);
        textures.set(null, new TextureManager(new AssetProvider())
        {
            @Override public Texture getTexture(Link link) { return skin; }
        });
        Method setup = UIFormEditor.class.getDeclaredMethod("setupFace", FramebufferForm.class, ModelForm.class, String.class, double.class);
        setup.setAccessible(true);
        UIBaseMenu menu = new UIBaseMenu() {};

        try
        {
            for (int scale : new int[] {0, 1, 2})
            {
                ModelForm parent = new ModelForm();
                /* startEdit assigns the undo root before registering its listeners. */
                parent.setId("form");
                FramebufferForm framebuffer = new FramebufferForm();
                BodyPart framePart = new BodyPart("face");
                framePart.setForm(framebuffer);
                parent.parts.addBodyPart(framePart);
                MultiLink original = new MultiLink();
                original.children.add(new FilteredLink(Link.assets("skin.png")));
                original.recalculateId();
                String originalId = original.toString();
                if (scale != 0) parent.texture.set(original);
                skin.width = 64 * scale;
                skin.height = 64 * scale;
                BaseType before = parent.toData();
                UIFormUndoHandler undo = new UIFormUndoHandler(menu.main);
                parent.preCallback(undo::handlePreValues);
                Host host = allocate(Host.class);
                host.form = parent;
                setup.invoke(host, framebuffer, parent, "eyes_fixture", 4D);
                undo.submitUndo(true);
                require(undo.getUndoManager().getTotalUndos() == 1, "Face setup was not one undo transaction");
                require(framebuffer.parts.getAllTyped().size() == 2, "Face setup did not append face and eyes");
                BillboardForm face = (BillboardForm) framebuffer.parts.getAllTyped().get(0).getForm();
                ModelForm eyes = (ModelForm) framebuffer.parts.getAllTyped().get(1).getForm();
                int expectedScale = Math.max(1, scale);
                require(face.crop.get().equals(new Vector4f(8F * expectedScale, 8F * expectedScale, 48F * expectedScale, 48F * expectedScale)),
                    "Face crop did not follow the skin resolution or default when no skin exists");
                require(!face.pickable.get() && face.resizeCrop.get() && eyes.model.get().equals("eyes_fixture"),
                    "Face rig properties were not initialized");
                require(framebuffer.parts.getAllTyped().get(1).transform.get().translate.y == -0.5F,
                    "Face setup ignored the eye offset");
                if (scale == 0)
                {
                    require(parent.texture.get() == null && face.texture.get() == null, "No-texture setup invented a skin");
                }
                else
                {
                    require(original.children.size() == 1 && original.toString().equals(originalId), "Face setup mutated a shared source texture");
                    require(parent.texture.get() instanceof MultiLink multi && multi.children.size() == 2,
                        "Face setup failed to add the erase filter");
                }
                BaseType after = parent.toData();
                Form restored = architect.fromData((MapType) after);
                require(restored != null && after.equals(restored.toData()), "Face rig lost data during save/reload");
                require(undo.getUndoManager().undo(parent) && before.equals(parent.toData()), "Undo left a partial face rig or texture edit");
                require(undo.getUndoManager().redo(parent) && after.equals(parent.toData()), "Redo lost a face child or crop");
                host.form = new ModelForm();
                BaseType current = parent.toData();
                setup.invoke(host, framebuffer, parent, "late_callback", 0D);
                require(current.equals(parent.toData()), "A stale setup callback edited the previous form");
            }
        }
        finally
        {
            registry.set(null, oldRegistry);
            models.set(null, oldModels);
            textures.set(null, oldTextures);
        }
        System.out.println("FaceSetupParityTest passed");
    }

    private static final class Host extends UIFormEditor
    {
        Host() { super(null); }
        @Override public void refreshFormList() {}
        @Override public boolean switchEditor(Form form) { return true; }
        @Override public void refillState() {}
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException
    {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static <T> T allocate(Class<T> type) throws ReflectiveOperationException
    {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Object allocator = field(unsafe, "theUnsafe").get(null);
        return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(allocator, type));
    }

    private static void require(boolean condition, String message)
    {
        if (!condition) throw new AssertionError(message);
    }
}
