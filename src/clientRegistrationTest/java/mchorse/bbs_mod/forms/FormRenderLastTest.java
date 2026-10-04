package mchorse.bbs_mod.forms;

import com.mojang.blaze3d.vertex.PoseStack;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.renderers.FormRenderType;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import mchorse.bbs_mod.ui.framework.elements.utils.StencilMap;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Guards for the "render last" wiring. The feature is three wires the compiler cannot check:
 * the intercept in {@link FormUtilsClient#render} (a form set to render last must never reach
 * its renderer on the pass that skips it), the scope a pass opens around its form drawing, and
 * the suspension a framebuffer form applies so a part is not postponed out of its own buffer.
 *
 * <p>So this test does both halves: the execution half calls {@link FormRenderLast} directly and
 * pins what it must and must not intercept, the source half pins the call sites that cannot be
 * executed headless (they live in an initialiser or in a world pass). A source assertion alone
 * would still pass with the intercept placed after the renderer call, and an execution assertion
 * alone would not notice the scope never being opened — which is exactly the shape of the bug
 * this guards against.</p>
 *
 * <p>Not covered: what it looks like on screen. That needs a world pass, a form with
 * {@code render_last} on, and a semi-transparent neighbour — see the task report's pending
 * section for the reproduction.</p>
 */
public final class FormRenderLastTest
{
    private static final Path FORM_UTILS =
        Path.of("src/client/java/mchorse/bbs_mod/forms/FormUtilsClient.java");
    private static final Path BBS_RENDERING =
        Path.of("src/client/java/mchorse/bbs_mod/client/BBSRendering.java");
    private static final Path FRAMEBUFFER_FORM_RENDERER =
        Path.of("src/client/java/mchorse/bbs_mod/forms/renderers/FramebufferFormRenderer.java");
    private static final Path FORM =
        Path.of("src/main/java/mchorse/bbs_mod/forms/forms/Form.java");
    private static final Path GENERAL_FORM_PANEL =
        Path.of("src/client/java/mchorse/bbs_mod/ui/forms/editors/panels/UIGeneralFormPanel.java");
    private static final Path UI_KEYS =
        Path.of("src/client/java/mchorse/bbs_mod/ui/UIKeys.java");
    private static final Path NEO_EVENTS =
        Path.of("src/client/java/mchorse/bbs_mod/client/BBSClientNeoEvents.java");

    private static final String[] LOCALES = {"en_us", "ru_ru", "zh_cn"};
    private static final String LABEL_KEY = "bbs.ui.forms.editors.general.render_last";

    private FormRenderLastTest()
    {}

    public static void runAll()
    {
        deferralRequiresAnOpenScope();
        deferralSkipsPickingUiAndPreviewDraws();
        theRenderPathInterceptsBeforeItDraws();
        theWorldPassOpensAndClosesTheScope();
        theEntityPassScopeSpansTheEntityLoop();
        theFramebufferRendererSuspendsIt();
        theSwitchIsReachableFromTheFormEditor();
        theFieldIsOneSerializedPropertyAndNoDataKey();

        System.out.println("FormRenderLastTest: all checks passed");
    }

    /**
     * The execution half. Runs the real {@link FormRenderLast} against a bare form and a bare
     * context, so the assertions are about the queue's contract, not about the JVM's ability to
     * open a window.
     */
    private static void deferralRequiresAnOpenScope()
    {
        FormRenderLast.release();

        TestForm last = new TestForm();
        TestForm ordinary = new TestForm();
        FormRenderingContext context = context();

        last.renderLast.set(true);

        require(captured() == 0, "a fresh scope started with something already captured");
        require(!FormRenderLast.postpone(last, context),
            "a form was postponed although no pass ever opened a scope");
        require(!FormRenderLast.postpone(null, context), "a null form was postponed");
        require(!FormRenderLast.postpone(ordinary, context),
            "a form without render_last was postponed although no scope was open");

        require(FormRenderLast.open(), "the first open() reported that it did not open the scope");
        require(FormRenderLast.isActive(), "the pass was not active inside its own scope");

        require(!FormRenderLast.postpone(ordinary, context), "a form without render_last was postponed");
        require(captured() == 0, "a form without render_last was captured for the replay");

        require(FormRenderLast.postpone(last, context),
            "a render-last form drew on the pass that was supposed to skip it");
        require(captured() == 1, "the skipped form was not captured for the replay: " + captured());

        require(!FormRenderLast.open(), "a nested open() reported that it opened a scope");
        FormRenderLast.close(false);
        require(FormRenderLast.isActive(), "a nested close() ended the outer scope");
        require(captured() == 1, "a nested close() drained the outer scope's capture");

        boolean wasActive = FormRenderLast.suspend();

        require(wasActive, "suspend() did not report the scope it deactivated");
        require(!FormRenderLast.isActive(), "suspend() left the pass active");
        require(!FormRenderLast.postpone(last, context), "a suspended pass postponed a form");

        FormRenderLast.restore(true);
        require(FormRenderLast.isActive(), "restore() did not put the scope back");

        FormRenderLast.release();
        require(!FormRenderLast.isActive(), "release() left the pass active");
        require(captured() == 0, "release() kept the captured frame");
        require(!FormRenderLast.postpone(last, context), "a released pass postponed a form");

        /* close() on an empty scope drains nothing and touches no GL state — that is what lets a
         * failing assertion clean up after itself without the cleanup drawing. */
        require(FormRenderLast.open(), "the scope could not be reopened after release()");
        FormRenderLast.close(true);
        require(!FormRenderLast.isActive(), "close() did not end the scope it opened");
        require(captured() == 0, "close() left a capture behind");
    }

    private static void deferralSkipsPickingUiAndPreviewDraws()
    {
        FormRenderLast.release();

        TestForm last = new TestForm();
        FormRenderingContext picking = context();
        FormRenderingContext ui = context();
        FormRenderingContext preview = context();

        last.renderLast.set(true);
        picking.stencilMap(new StencilMap());
        ui.inUI();
        preview.modelRenderer(42L);

        boolean opened = FormRenderLast.open();

        try
        {
            require(!FormRenderLast.postpone(last, picking),
                "a picking pass postponed a form: the stencil needs every form where it stands");
            require(!FormRenderLast.postpone(last, ui),
                "an in-world UI preview was postponed out of the UI pass");
            require(!FormRenderLast.postpone(last, preview),
                "a model-renderer preview was postponed out of its viewport");
            require(captured() == 0, "a preview was captured for the world replay: " + captured());
        }
        finally
        {
            /* release(), not close(): close() would draw whatever the assertions above just
             * caught, and that draw fails headless — masking the real failure with a GL state
             * complaint. release() leaves the same clean queue without touching a renderer. */
            FormRenderLast.release();
        }

        require(opened, "the scope did not open for the checks above");
        require(!FormRenderLast.isActive(), "the scope outlived its pass");
    }

    private static void theRenderPathInterceptsBeforeItDraws()
    {
        String body = methodBody(read(FORM_UTILS),
            "public static void render(Form form, FormRenderingContext context)");
        String flat = compact(body);

        int intercept = flat.indexOf("if(FormRenderLast.postpone(form,context)){return;}");
        int draw = flat.indexOf("FormRendererrenderer=getRenderer(form);");

        require(intercept >= 0,
            "FormUtilsClient.render no longer asks FormRenderLast to postpone the form");
        require(draw > intercept,
            "FormUtilsClient.render draws the form before it asks FormRenderLast, so a render-last form still draws in its own turn");
    }

    private static void theWorldPassOpensAndClosesTheScope()
    {
        String source = read(BBS_RENDERING);
        String body = methodBody(source,
            "public static void renderCoolStuff(IBbsWorldRenderContext worldRenderContext)");
        String flat = compact(body);

        int open = flat.indexOf("booleanrenderLast=FormRenderLast.open();");
        int films = flat.indexOf("BBSModClient.getFilms().render(worldRenderContext);");
        int close = flat.indexOf("FormRenderLast.close(renderLast);");
        int endBatch = flat.indexOf("worldRenderContext.consumers().endBatch();");

        require(open >= 0, "the world pass no longer opens a render-last scope");
        require(close > films,
            "the world pass does not close its render-last scope after drawing its forms");
        require(endBatch > close,
            "the postponed forms replay after the batch ended and the pass's matrices were put back");

        require(code(source).indexOf("finally { FormRenderLast.close(renderLast);") >= 0,
            "the scope is not closed on a finally, so an exception mid-pass leaves the forms captured for the next frame");

        require(occurrences(flatten(source), "FormRenderLast.release();") == 2,
            "the failed-view paths no longer drop a failed pass's captured forms alongside FormTranslucentQueue.abort()");
    }

    /**
     * The entity pass's scope: opened on the last stage before vanilla's entity loop and closed
     * at AFTER_ENTITIES, so the forms the loop draws (actors, morphed players, model blocks) take
     * the switch too. Without it {@code render_last} only affects film forms, and the checkbox
     * silently does nothing on an entity.
     *
     * <p>NeoForge 1.21.1 has no {@code BEFORE_ENTITIES} stage — the event's stage list runs
     * AFTER_SKY, the terrain layers, AFTER_ENTITIES — so the hook is AFTER_CUTOUT_BLOCKS, which
     * is both the last stage before the loop and late enough that the solid layer's Iris film
     * pass (which opens a scope of its own) is already over. The assertions below pin that choice
     * too: a later stage would swallow the films' scope, and a fabricated BEFORE_ENTITIES would
     * not compile.</p>
     */
    private static void theEntityPassScopeSpansTheEntityLoop()
    {
        String source = read(NEO_EVENTS);
        String openBranch = branch(source, "Stage.AFTER_CUTOUT_BLOCKS", "Stage.AFTER_ENTITIES");
        String entityBranch = branch(source, "Stage.AFTER_ENTITIES", "Stage.AFTER_BLOCK_ENTITIES");

        require(flatten(openBranch).contains("BBSRendering.beginEntityPass();"),
            "the stage before the entity loop no longer opens a render-last scope");
        require(!flatten(source).contains("Stage.BEFORE_ENTITIES"),
            "the hook names a stage NeoForge 1.21.1 does not have");
        require(flatten(entityBranch).contains("BBSRendering.endEntityPass();"),
            "the entity pass no longer closes its render-last scope");

        /* The close has to be the first statement of the finally, not a statement after the
         * try/finally: placed after it, both the stack-null return and a throw inside the try
         * skip it, and an open scope owns the next frame's render-last forms. */
        require(code(entityBranch).contains("finally { BBSRendering.endEntityPass(); }"),
            "the entity pass closes its scope somewhere a return or a throw can reach past, which leaves the scope open and defers every later form");

        /* The failure net at AFTER_LEVEL, next to the one the model-block queue already has. */
        require(code(source).contains("ModelBlockRenderLastQueue.release(); FormRenderLast.release();"),
            "a frame whose render threw before AFTER_ENTITIES leaves the entity pass's scope open for the next frame");

        /* The two halves in BBSRendering: the open only opens, and the close only closes what
         * this open opened — so a pass that found a scope already open never ends someone else's. */
        String rendering = compact(read(BBS_RENDERING));

        require(rendering.contains("entityPassRenderLast=FormRenderLast.open();"),
            "beginEntityPass no longer records whether it was the call that opened the scope");
        require(rendering.contains("FormRenderLast.close(entityPassRenderLast);entityPassRenderLast=false;"),
            "endEntityPass no longer closes only the scope its own beginEntityPass opened");

        /* The model-block queue is a separate feature: closing the form scope must not touch it. */
        require(!compact(openBranch).contains("ModelBlockRenderLastQueue.release()")
                && !compact(openBranch).contains("ModelBlockRenderLastQueue.flush("),
            "the form entity-pass scope now drains the model-block render-last queue as well");
    }

    private static void theFramebufferRendererSuspendsIt()
    {
        String flat = compact(read(FRAMEBUFFER_FORM_RENDERER));

        require(flat.contains("booleanrenderLastWasActive=FormRenderLast.suspend();"),
            "a framebuffer form no longer suspends render-last around its off-screen parts");
        require(flat.contains("FormTranslucentQueue.restore(queueWasActive);FormRenderLast.restore(renderLastWasActive);"),
            "a framebuffer form restores render-last somewhere other than the scope that suspended it");
    }

    private static void theSwitchIsReachableFromTheFormEditor()
    {
        String panel = compact(read(GENERAL_FORM_PANEL));
        String keys = compact(read(UI_KEYS));

        require(panel.contains("this.renderLast=newUIToggle(UIKeys.FORMS_EDITORS_GENERAL_RENDER_LAST,"
                + "(b)->this.form.renderLast.set(b.getValue()));"),
            "the form editor has no toggle bound to the form's render_last property");
        require(panel.contains("this.renderLast.setValue(form.renderLast.get());"),
            "the editor never loads the form's stored value into the toggle");
        require(panel.contains("this.lighting,this.shaderShadow,this.additiveColor,this.renderLast,"),
            "the toggle exists but is not placed in the form editor's display section");
        require(keys.contains("FORMS_EDITORS_GENERAL_RENDER_LAST=L10n.lang(\"" + LABEL_KEY + "\");"),
            "the toggle's label key is not declared");

        for (String locale : LOCALES)
        {
            String bundle = read(Path.of("src/client/resources/assets/bbs/assets/strings/" + locale + ".json"));

            require(bundle.contains("\"" + LABEL_KEY + "\""),
                "the render-last label is missing from the " + locale + " language file");
        }
    }

    private static void theFieldIsOneSerializedPropertyAndNoDataKey()
    {
        String source = read(FORM);

        require(source.contains("public final ValueBoolean renderLast = new ValueBoolean(\"render_last\", false);"),
            "Form no longer declares render_last as a ValueBoolean defaulting to false");
        require(source.contains("this.add(this.renderLast);"),
            "Form declares render_last but never adds it to its value tree, so it is not persisted");
        require(source.contains("this.renderLast.invisible();"),
            "render_last is not hidden from the automatic value list, so the editor shows it twice");

        require(occurrences(source, "\"render_last\"") == 1,
            "render_last appears more than once in Form: a hand-written fromData/toData key means the save format was touched");
    }

    /** Reads the form's frame off the capture list — the only observable the queue exposes. */
    private static int captured()
    {
        try
        {
            Field field = FormRenderLast.class.getDeclaredField("postponed");

            field.setAccessible(true);

            return ((List<?>) field.get(null)).size();
        }
        catch (ReflectiveOperationException e)
        {
            throw new AssertionError("could not read FormRenderLast's capture list", e);
        }
    }

    private static FormRenderingContext context()
    {
        return new FormRenderingContext().set(FormRenderType.ENTITY, null, new PoseStack(), 0, 0, 0F);
    }

    /** The text between the branch for {@code stage} and the branch that follows it. */
    private static String branch(String source, String stage, String next)
    {
        int start = source.indexOf(stage);

        require(start >= 0, "could not locate the branch for " + stage);

        int end = source.indexOf(next, start);

        require(end > start, "could not locate the branch after " + stage);

        return source.substring(start, end);
    }

    private static String methodBody(String source, String signature)
    {
        int start = source.indexOf(signature);

        require(start >= 0, "could not locate: " + signature);

        int open = source.indexOf('{', start);

        require(open > start, "could not locate the body of: " + signature);

        return source.substring(start, Math.min(source.length(), open + 4000));
    }

    private static String read(Path relative)
    {
        Path current = Path.of("").toAbsolutePath().normalize();

        while (current != null)
        {
            Path candidate = current.resolve(relative);

            if (Files.isRegularFile(candidate))
            {
                try
                {
                    return Files.readString(candidate);
                }
                catch (IOException e)
                {
                    throw new AssertionError("could not read " + candidate, e);
                }
            }

            current = current.getParent();
        }

        throw new AssertionError("could not locate " + relative);
    }

    /** Everything on one line, spaces kept — for assertions whose literals contain spaces. */
    private static String flatten(String source)
    {
        return source.replaceAll("\\s+", " ").trim();
    }

    /** Everything on one line, spaces gone — for assertions about the shape of a statement. */
    private static String compact(String source)
    {
        return source.replaceAll("\\s+", "");
    }

    /**
     * Comments removed and everything on one line — for assertions about two statements being
     * adjacent, which a comment between them would otherwise break.
     */
    private static String code(String source)
    {
        return source
            .replaceAll("(?s)/\\*.*?\\*/", " ")
            .replaceAll("(?m)//.*$", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }

    private static int occurrences(String source, String needle)
    {
        int count = 0;

        for (int at = source.indexOf(needle); at >= 0; at = source.indexOf(needle, at + needle.length()))
        {
            count++;
        }

        return count;
    }

    private static void require(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    /** A bare form: the queue only reads {@code renderLast} off it before the replay. */
    private static final class TestForm extends Form
    {}
}
