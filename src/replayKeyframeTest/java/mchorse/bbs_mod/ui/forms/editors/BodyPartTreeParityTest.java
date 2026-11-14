package mchorse.bbs_mod.ui.forms.editors;

import mchorse.bbs_mod.forms.forms.BodyPart;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.ui.framework.UIBaseMenu;
import mchorse.bbs_mod.ui.framework.UIContext;

/** Sibling insertion is measured around whole subtrees and retains stable part addresses. */
public final class BodyPartTreeParityTest
{
    public static void runAll()
    {
        Form root = named("Root");
        Form a = named("First");
        Form b = named("Second");
        BodyPart first = part(root, "first", a);
        BodyPart second = part(root, "second", b);
        BodyPart child = part(a, "child", named("Child"));
        BodyPart last = part(b, "last", named("Last"));
        Tree tree = new Tree();
        UIBaseMenu menu = new UIBaseMenu() {};
        menu.main.add(tree);
        tree.area.set(0, 0, 200, 180);
        tree.setForm(root);
        tree.reorder((moving, index) ->
        {
            moving.getManager().moveBodyPart(moving, index);
            tree.setForm(root);
            tree.setCurrentForm(moving.getForm());
        });
        require(tree.getList().get(2).part == child && tree.getList().get(4).part == last,
            "Parts are not flattened in tree order");
        require(!tree.getList().get(1).last && tree.getList().get(2).lines == 1,
            "Tree guide failed to continue through a sibling's subtree");
        require(tree.getList().get(3).last && tree.getList().get(4).lines == 0,
            "Last subtree retained an ancestor guide");
        require(tree.indent(tree.getList().get(2)) == UIForms.INDENT * 2
                && tree.indentStep() == UIForms.INDENT
                && tree.elementToString(menu.context, 2, tree.getList().get(2)).equals("Child"),
            "Body-part rows did not use the shared tree indentation contract");
        require(!new UIForms.FormEntry(root, first, 1).equals(new UIForms.FormEntry(root, second, 1)),
            "Equal-valued parts alias each other's selection");
        String firstPath = tree.getList().get(1).getPath();
        String childPath = tree.getList().get(2).getPath();
        require(!tree.canDrag(0), "Root form can be reordered");
        require(tree.dropInsertion(1, 10, 78) == 3, "Drop targets a slot inside a sibling subtree");
        require(tree.dropInsertion(1, 10, 110) == 5, "Cannot insert after the last sibling's subtree");
        UIContext context = menu.context;
        context.mouseX = 10;
        context.mouseY = 30;
        context.mouseButton = 0;
        tree.subMouseClicked(context);
        context.mouseY = 110;
        context.mouseButton = 1;
        tree.subMouseReleased(context);
        require(root.parts.getAllTyped().get(0) == first && tree.getDraggingIndex() == 1,
            "An unrelated release ended or committed a body-part drag");
        context.mouseButton = 0;
        tree.subMouseReleased(context);
        require(root.parts.getAllTyped().get(1) == first && a.parts.getAllTyped().get(0) == child,
            "Sibling insertion separated a subtree or chose the wrong final index");
        require(tree.getCurrentFirst().part == first, "Drop lost the selected part's identity");
        require(tree.setCurrentPath(childPath).equals(childPath), "Reorder changed a child's stable track address");
        require(tree.setCurrentPath(firstPath).equals(firstPath), "Reorder changed the part address");
        tree.setForm(null);
        require(tree.getList().size() == 1 && tree.getList().get(0).toString().equals("-"),
            "Clearing a form breaks the root row");
        System.out.println("BodyPartTreeParityTest passed");
    }

    private static Form named(String name)
    {
        Form form = new Form() {};
        form.name.set(name);
        return form;
    }

    private static BodyPart part(Form owner, String id, Form form)
    {
        BodyPart part = new BodyPart(id);
        part.setForm(form);
        owner.parts.addBodyPart(part);
        return part;
    }

    private static final class Tree extends UIForms
    {
        Tree() { super(null); }
    }

    private static void require(boolean condition, String message)
    {
        if (!condition) throw new AssertionError(message);
    }
}
