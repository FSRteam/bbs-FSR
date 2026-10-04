package mchorse.bbs_mod.ui.film;

import mchorse.bbs_mod.data.DataToString;
import mchorse.bbs_mod.data.types.MapType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.HexFormat;

/** Source-level guards for render-state migrations that require an in-game visual smoke. */
public final class RenderRuntimeMigrationSourceTest
{
    /** preview2 requires a real second monitor; keep that smoke check opt-in. */
    private static final boolean ENABLE_PREVIEW2_TESTS = Boolean.getBoolean("bbs.test.preview2");
    private static final Path BBS_RENDERING = Path.of("src/client/java/mchorse/bbs_mod/client/BBSRendering.java");
    private static final Path GAME_RENDERER_MIXIN = Path.of("src/client/java/mchorse/bbs_mod/mixin/client/GameRendererMixin.java");
    private static final Path WINDOW_MIXIN = Path.of("src/client/java/mchorse/bbs_mod/mixin/client/WindowMixin.java");

    private static final Path FRAMEBUFFER_RENDERER = Path.of("src/client/java/mchorse/bbs_mod/forms/renderers/FramebufferFormRenderer.java");
    private static final Path FORM_FRAME_CACHE = Path.of("src/client/java/mchorse/bbs_mod/forms/renderers/utils/FormFrameCache.java");
    private static final Path ORBIT_CONTROLLER = Path.of("src/client/java/mchorse/bbs_mod/ui/film/controller/OrbitFilmCameraController.java");
    private static final Path LABEL_RENDERER = Path.of("src/client/java/mchorse/bbs_mod/forms/renderers/LabelFormRenderer.java");
    private static final Path MODEL_RENDERER = Path.of("src/client/java/mchorse/bbs_mod/forms/renderers/ModelFormRenderer.java");
    private static final Path EXTRUDED_RENDERER = Path.of("src/client/java/mchorse/bbs_mod/forms/renderers/ExtrudedFormRenderer.java");
    private static final Path BILLBOARD_RENDERER = Path.of("src/client/java/mchorse/bbs_mod/forms/renderers/BillboardFormRenderer.java");
    private static final Path KEYFRAME_GRAPH = Path.of("src/client/java/mchorse/bbs_mod/ui/framework/elements/input/keyframes/graphs/UIKeyframeGraph.java");
    private static final Path CLICKABLE = Path.of("src/client/java/mchorse/bbs_mod/ui/framework/elements/buttons/UIClickable.java");
    private static final Path DASHBOARD_PANEL = Path.of("src/client/java/mchorse/bbs_mod/ui/dashboard/panels/UIDataDashboardPanel.java");
    private static final Path BLOCK_RENDERER = Path.of("src/client/java/mchorse/bbs_mod/forms/renderers/BlockFormRenderer.java");
    private static final Path SODIUM_UTILS = Path.of("src/client/java/mchorse/bbs_mod/utils/sodium/SodiumUtils.java");
    private static final Path FILM_CONTROLLER = Path.of("src/client/java/mchorse/bbs_mod/film/BaseFilmController.java");
    private static final Path FILM_ENTITY_RENDERER = Path.of("src/client/java/mchorse/bbs_mod/film/FilmEntityRenderer.java");
    private static final Path FILM_MATRICES = Path.of("src/client/java/mchorse/bbs_mod/film/FilmMatrices.java");
    private static final Path TRANSLUCENT_QUEUE = Path.of("src/client/java/mchorse/bbs_mod/forms/FormTranslucentQueue.java");
    private static final Path VERTEX_CONSUMERS = Path.of("src/client/java/mchorse/bbs_mod/forms/CustomVertexConsumerProvider.java");
    private static final Path MODEL_INSTANCE = Path.of("src/client/java/mchorse/bbs_mod/cubic/ModelInstance.java");
    private static final Path MODEL_VAO_RENDERER = Path.of("src/client/java/mchorse/bbs_mod/cubic/render/vao/ModelVAORenderer.java");
    private static final Path KEYFRAME_EDITOR = Path.of("src/client/java/mchorse/bbs_mod/ui/framework/elements/input/keyframes/UIKeyframeEditor.java");
    private static final Path NESTED_EDIT = Path.of("src/client/java/mchorse/bbs_mod/ui/forms/UINestedEdit.java");
    private static final Path FORM_EDITOR = Path.of("src/client/java/mchorse/bbs_mod/ui/forms/editors/UIFormEditor.java");
    private static final Path FILM_PREVIEW = Path.of("src/client/java/mchorse/bbs_mod/ui/film/UIFilmPreview.java");
    private static final Path RENDER_LAYER_MIXIN = Path.of("src/client/java/mchorse/bbs_mod/mixin/client/RenderLayerMixin.java");
    private static final Path CLIENT_MIXINS = Path.of("src/client/resources/bbs.client.mixins.json");
    private static final Path ICONS = Path.of("src/client/resources/assets/bbs/assets/textures/icons.png");
    private static final String ICONS_SHA256 = "e8e8297585374629b14de90cef480c589e643b7de1570d1cb0df5899e38c1a41";

    private static final String[] MIGRATED_LANGUAGE_KEYS = {
        "bbs.config.workspace.keyframe_panel_width",
        "bbs.config.workspace.keyframe_panel_width-comment",
        "bbs.config.viewport.preview_icons_auto_hide",
        "bbs.config.viewport.preview_icons_auto_hide-comment",
        "bbs.config.timeline.stop_playback_on_scrub",
        "bbs.config.timeline.stop_playback_on_scrub-comment",
        "bbs.config.workspace.keep_frame_on_exit",
        "bbs.config.workspace.keep_frame_on_exit-comment",
        "bbs.config.camera.orbit_axis_ortho",
        "bbs.config.camera.orbit_axis_ortho-comment",
        "bbs.config.camera.orbit_gizmo",
        "bbs.config.camera.orbit_gizmo_scale",
        "bbs.config.camera.orbit_gizmo_scale-comment",
        "bbs.config.camera.orbit_gizmo-comment",
        "bbs.ui.bone_picker.click_bone",
        "bbs.ui.film.controller.keys.toggle_ortho",
        "bbs.ui.film.replay.bake_ik.bake",
        "bbs.ui.film.replay.bake_ik.chains",
        "bbs.ui.film.replay.bake_ik.disable",
        "bbs.ui.film.replay.bake_ik.disable-tooltip",
        "bbs.ui.film.replay.bake_ik.end",
        "bbs.ui.film.replay.bake_ik.range",
        "bbs.ui.film.replay.bake_ik.start",
        "bbs.ui.film.replay.bake_ik.step",
        "bbs.ui.film.replay.bake_ik.title",
        "bbs.ui.film.replay.context.bake_ik",
        "bbs.ui.forms.editors.model.ik.advanced",
        "bbs.ui.forms.editors.model.ik.chain_empty",
        "bbs.ui.forms.editors.model.ik.classic",
        "bbs.ui.forms.editors.model.ik.classic_fallback",
        "bbs.ui.forms.editors.model.ik.classic_tooltip",
        "bbs.ui.forms.editors.model.ik.cycle",
        "bbs.ui.forms.editors.model.ik.joint",
        "bbs.ui.forms.editors.model.ik.joint.limit",
        "bbs.ui.forms.editors.model.ik.joint.lock",
        "bbs.ui.forms.editors.model.ik.joint.max",
        "bbs.ui.forms.editors.model.ik.joint.min",
        "bbs.ui.forms.editors.model.ik.joint.stiffness",
        "bbs.ui.forms.editors.model.ik.pole_cycle",
        "bbs.ui.model_editor.weld.issue.source_bone",
        "bbs.ui.model_editor.weld.issue.source_cubes",
        "bbs.ui.model_editor.weld.issue.source_face",
        "bbs.ui.model_editor.weld.issue.target_bone",
        "bbs.ui.model_editor.weld.issue.target_cubes",
        "bbs.ui.model_editor.weld.issue.target_face",
        "bbs.ui.model_editor.weld.parent_share",
        "bbs.ui.model_editor.weld.twist",
        "bbs.ui.transforms.context.mode_euler",
        "bbs.ui.transforms.context.mode_quaternion",
        "bbs.ui.transforms.context.switch_view",
        "bbs.ui.transforms.keys.rotation_mode",
        "bbs.ui.transforms.rotation.mode_tooltip",
        "bbs.ui.transforms.rotation.quaternion",
        "bbs.ui.transforms.space.open",
        "bbs.ui.transforms.space.parent",
        "bbs.ui.transforms.space.title",
        "bbs.ui.transforms.space.tooltip",
        "bbs.ui.transforms.space.view",
        "bbs.ui.transforms.space.wip",
        "bbs.ui.transforms.space.world",
        "interpolations.step_tick"
    };

    private static final String[] NEW_SETTINGS_LANGUAGE_KEYS = {
        "bbs.config.workspace.keyframe_panel_width",
        "bbs.config.workspace.keyframe_panel_width-comment",
        "bbs.config.viewport.preview_icons_auto_hide",
        "bbs.config.viewport.preview_icons_auto_hide-comment",
        "bbs.config.timeline.stop_playback_on_scrub",
        "bbs.config.timeline.stop_playback_on_scrub-comment"
    };

    private static final String[] SLIDER_SOURCES = {
        "src/client/java/mchorse/bbs_mod/settings/ui/UIValueFactory.java",
        "src/client/java/mchorse/bbs_mod/ui/dashboard/UIDebugPanel.java",
        "src/client/java/mchorse/bbs_mod/ui/dashboard/textures/UITexturePainter.java",
        "src/client/java/mchorse/bbs_mod/ui/dashboard/textures/layers/UILayersPanel.java",
        "src/client/java/mchorse/bbs_mod/ui/film/UIFilmPlayerSettingsOverlayPanel.java",
        "src/client/java/mchorse/bbs_mod/ui/film/clips/UIAudioClip.java",
        "src/client/java/mchorse/bbs_mod/ui/film/clips/UIDragClip.java",
        "src/client/java/mchorse/bbs_mod/ui/film/controller/UIMotionPathContextMenu.java",
        "src/client/java/mchorse/bbs_mod/ui/forms/editors/panels/UIGeneralFormPanel.java",
        "src/client/java/mchorse/bbs_mod/ui/forms/editors/panels/UIModelConstraintsFormPanel.java",
        "src/client/java/mchorse/bbs_mod/ui/forms/editors/panels/UIModelIKFormPanel.java",
        "src/client/java/mchorse/bbs_mod/ui/forms/editors/panels/UIModelPhysicsFormPanel.java",
        "src/client/java/mchorse/bbs_mod/ui/forms/editors/utils/UIDebugOverlayContextMenu.java",
        "src/client/java/mchorse/bbs_mod/ui/framework/elements/input/UINumericInput.java",
        "src/client/java/mchorse/bbs_mod/ui/framework/elements/input/UISliderTrackpad.java",
        "src/client/java/mchorse/bbs_mod/ui/framework/elements/input/UITrackpad.java",
        "src/client/java/mchorse/bbs_mod/ui/framework/elements/input/keyframes/factories/UIIKKeyframeFactory.java",
        "src/client/java/mchorse/bbs_mod/ui/framework/elements/input/keyframes/factories/UIPhysicsKeyframeFactory.java",
        "src/client/java/mchorse/bbs_mod/ui/framework/elements/input/keyframes/factories/UIPoseTransformKeyframeFactory.java",
        "src/client/java/mchorse/bbs_mod/ui/framework/elements/input/keyframes/factories/UIWindKeyframeFactory.java",
        "src/client/java/mchorse/bbs_mod/ui/model_blocks/UIModelBlockEditorMenu.java",
        "src/client/java/mchorse/bbs_mod/ui/model_editor/UIModelEditorPanel.java",
        "src/client/java/mchorse/bbs_mod/ui/utils/pose/UIPoseEditor.java"
    };

    private RenderRuntimeMigrationSourceTest()
    {}

    public static void main(String[] args) throws Exception
    {
        runAll();
        System.out.println("RenderRuntimeMigrationSourceTest passed");
    }

    public static void runAll() throws Exception
    {
        Path root = findProjectRoot();
        String filmEntityRenderer = compact(Files.readString(root.resolve(FILM_ENTITY_RENDERER)));
        String framebuffer = compact(Files.readString(root.resolve(FRAMEBUFFER_RENDERER)));
        String relativeRender = section(filmEntityRenderer, "stack.pushPose(); try", "if (UIBaseMenu.shouldRenderAxes() && context.anchorGizmo)");

        check(relativeRender.contains("stack.last().pose().rotate(context.camera.rotation());")
                && relativeRender.contains("stack.last().normal().rotate(context.camera.rotation());")
                && !relativeRender.contains("stack.last().pose().identity();"),
            "relative replay must compose the camera rotation without erasing the active Iris pass stack");

        check(framebuffer.contains("int viewportX;")
                && framebuffer.contains("int viewportY;")
                && framebuffer.contains("int prevCullFace = GL30.glGetInteger(GL11.GL_CULL_FACE_MODE);")
                && framebuffer.contains("VertexSorting vertexSorting = RenderSystem.getVertexSorting();"),
            "framebuffer forms no longer snapshot the complete borrowed viewport/cull/projection state");
        check(framebuffer.contains("GL30.glViewport(viewportX, viewportY, width, height);")
                && framebuffer.contains("RenderSystem.setProjectionMatrix(projectionMatrix, vertexSorting);")
                && framebuffer.contains("GL30.glCullFace(prevCullFace);")
                && occurrences(framebuffer, "RenderSystem.applyModelViewMatrix();") >= 2,
            "framebuffer forms no longer restore the exact caller render state");
        check(framebuffer.contains("finally { FormTranslucentQueue.restore(queueWasActive);")
                && framebuffer.contains("finally { depth -= 1;")
                && framebuffer.contains("finally { context.stack.popPose();"),
            "framebuffer child failures can leak queue, Iris depth, or PoseStack ownership");

        checkFormFrameCacheWiring(root);
        checkTranslucentRenderWiring(root);
        checkSmallUiFixes(root);
        checkCrashGuards(root);
        checkResources(root);
        checkSliderWiring(root);

        if (ENABLE_PREVIEW2_TESTS)
        {
            checkPreview2StateIsolation(root);
            check(Files.exists(root.resolve(BBS_RENDERING)),
                "preview2 visual smoke requires the multiview renderer source");
        }
    }

    private static void checkPreview2StateIsolation(Path root) throws IOException
    {
        String rendering = compact(Files.readString(root.resolve(BBS_RENDERING)));
        String gameRenderer = compact(Files.readString(root.resolve(GAME_RENDERER_MIXIN)));
        String window = compact(Files.readString(root.resolve(WINDOW_MIXIN)));
        check(rendering.contains("if (secondaryViewEnabled) { renderSecondaryView(); }")
                && !rendering.contains("saveSodiumCameraPos")
                && !rendering.contains("saveSodiumProjection"),
            "preview2 still renders after the primary pass or rolls back Sodium's shared terrain lists");
        check(window.contains("BBSRendering.canReplaceFramebuffer() && !BBSRendering.isApplyingSecondaryCamera()")
                && occurrences(window, "!BBSRendering.isApplyingSecondaryCamera()") >= 6,
            "preview2 still inherits export-size Window dimensions during its world pass");
        check(gameRenderer.contains("if (BBSRendering.isApplyingSecondaryCamera())")
                && gameRenderer.contains("return projection;"),
            "preview2 projection path is not isolated from the primary ortho conversion");
    }

    private static void checkCrashGuards(Path root) throws IOException
    {
        String graph = compact(Files.readString(root.resolve(KEYFRAME_GRAPH)));
        String film = compact(Files.readString(root.resolve(FILM_CONTROLLER)));
        String clickable = compact(Files.readString(root.resolve(CLICKABLE)));
        String dashboard = compact(Files.readString(root.resolve(DASHBOARD_PANEL)));
        String block = compact(Files.readString(root.resolve(BLOCK_RENDERER)));
        String sodium = compact(Files.readString(root.resolve(SODIUM_UTILS)));

        check(graph.contains("MeshData mesh = builder.build();")
                && graph.contains("if (mesh != null)")
                && graph.contains("BufferUploader.drawWithShader(mesh);"),
            "empty keyframe graph buffers still use buildOrThrow");
        check(clickable.contains("if (context == null)")
                && clickable.contains("this.click(mouseButton);"),
            "programmatic keybind clicks still require a mouse context");
        check(dashboard.contains("if (this.data == null || this.data.getId() == null || repository == null)"),
            "Ctrl+S can still save a missing dashboard document");
        check(block.contains("Color.white()")
                && block.contains("consumers.setSubstitute(null);"),
            "block and fluid forms can still start from black or replace vanilla vertex colors");
        check(sodium.contains("Class.forName(\"net.caffeinemc.mods.sodium.client.SodiumClientMod\")")
                && sodium.contains("getMethod(\"options\")"),
            "Sodium camera compatibility still links directly to one version's options accessor");
        check(film.contains("this.applyReplayItemUse(replay, ticks, entity)")
                && film.contains("ItemUseEffects.tick(replay, actorEntity, ticks)")
                && film.contains("LivePlayerItemUse.apply(player, use, offUse);")
                && film.contains("ItemUseEffects.tick(replay, new MCEntity(player), replayTick)"),
            "replay item use/eating effects are not published for editor and actor playback");
    }

    private static void checkFormFrameCacheWiring(Path root) throws IOException
    {
        String cache = compact(Files.readString(root.resolve(FORM_FRAME_CACHE)));
        String film = compact(Files.readString(root.resolve(FILM_ENTITY_RENDERER)))
            + " " + compact(Files.readString(root.resolve(FILM_MATRICES)));
        String orbit = compact(Files.readString(root.resolve(ORBIT_CONTROLLER)));

        check(cache.contains("entry.entity == entity")
                && cache.contains("entry.simulationOwner == simulationOwner")
                && cache.contains("entry.allowWorldTargetOverrides == allowWorldTargetOverrides")
                && cache.contains("entry.allowWorldCollisions == allowWorldCollisions")
                && cache.contains("Float.compare(entry.transition, transition) == 0")
                && cache.contains("matricesEqual(entry.semanticBase, semanticBase)"),
            "FormFrameCache no longer keys every pose-affecting input");
        check(film.contains("FormFrameCache gizmoFrame")
                && occurrences(film, "FormFrameCache.collect(") >= 4,
            "film axes/preview/anchor paths no longer share the explicit frame cache");
        check(orbit.contains("FormFrameCache frame = new FormFrameCache();")
                && orbit.contains("FormFrameCache.collect("),
            "orbit focus no longer reuses the anchor pose evaluation");
    }

    private static void checkTranslucentRenderWiring(Path root) throws IOException
    {
        String label = compact(Files.readString(root.resolve(LABEL_RENDERER)));
        String model = compact(Files.readString(root.resolve(MODEL_RENDERER)));
        String extruded = compact(Files.readString(root.resolve(EXTRUDED_RENDERER)));
        String billboard = compact(Files.readString(root.resolve(BILLBOARD_RENDERER)));
        String framebuffer = compact(Files.readString(root.resolve(FRAMEBUFFER_RENDERER)));
        String queue = compact(Files.readString(root.resolve(TRANSLUCENT_QUEUE)));
        String consumers = compact(Files.readString(root.resolve(VERTEX_CONSUMERS)));
        String irisUtils = compact(Files.readString(root.resolve(Path.of("src/client/java/mchorse/bbs_mod/utils/iris/IrisUtils.java"))));
        String rendering = compact(Files.readString(root.resolve(Path.of("src/client/java/mchorse/bbs_mod/client/BBSRendering.java"))));
        String modelInstance = compact(Files.readString(root.resolve(MODEL_INSTANCE)));
        String modelVaoRenderer = compact(Files.readString(root.resolve(MODEL_VAO_RENDERER)));
        String modelShader = compact(Files.readString(root.resolve(Path.of("src/main/resources/assets/bbs/shaders/core/model.fsh"))));
        String mixin = compact(Files.readString(root.resolve(RENDER_LAYER_MIXIN)));
        String mixins = compact(Files.readString(root.resolve(CLIENT_MIXINS)));

        check(label.contains("FormTranslucentQueue.beginGroup(origin, false);")
                && label.contains("finally { CustomVertexConsumerProvider.clearRunnables();")
                && label.contains("FormTranslucentQueue.endGroup();"),
            "label background and text no longer share one sorted translucent group");
        check(mixins.contains("\"BackgroundRendererMixin\"")
                && mixins.contains("\"RenderLayerMixin\""),
            "orthographic fog or deferred RenderType mixin is not registered");
        check(mixin.contains("CustomVertexConsumerProvider.drawLayer((RenderType) (Object) this, meshData)")
                && mixin.contains("info.cancel();"),
            "RenderLayerMixin no longer routes deferred buffers into the FSR queue");
        check(occurrences(model, "FormTranslucentQueue.setSortOrigin(") >= 2
                && occurrences(model, "consumers.endBatch();") >= 3
                && model.contains("boolean cutout = irisWorld && textureObject != null && textureObject.hasTranslucency()")
                && model.contains("queueWasActive = FormTranslucentQueue.suspend();"),
            "model armor/items or Iris rendering bypasses the migrated translucency policy");
        check(extruded.contains("boolean cutout = defer && irisWorld && textureObject != null && textureObject.hasTranslucency()")
                && extruded.contains("queueWasActive = FormTranslucentQueue.suspend();")
                && extruded.contains("FormTranslucentQueue.restore(queueWasActive);"),
            "extruded forms no longer use the Iris immediate/cutout fallback");
        check(queue.contains("public RenderLayerCommand( RenderType layer, VertexBuffer buffer, Matrix4f modelView, Vector3f origin, Runnable prepare, boolean extendedLayout )")
                && queue.contains("super(origin, true, true);")
                && consumers.contains("new Vector3f(origin),")
                && consumers.contains("captureLayerPreparation(layer),")
                && !consumers.contains("textLayer,")
                /* The deferred draw must pin Iris' vertex-array layout to the one the mesh
                 * was captured with, or a vanilla-format mesh drawn under a raised
                 * renderWithExtendedVertexFormat flag tears into a triangle fan. */
                && queue.contains("boolean previousLayout = BBSRendering.applyIrisVertexLayout(this.extendedLayout);")
                && queue.contains("buffer.drawWithShader(modelView, RenderSystem.getProjectionMatrix(), RenderSystem.getShader());")
                && queue.contains("BBSRendering.restoreIrisVertexLayout(previousLayout);")
                && queue.contains("private static void setupModelSamplers()")
                && occurrences(queue, "setupModelSamplers();") >= 3
                && queue.contains("private static void teardownModelSamplers()")
                && occurrences(queue, "teardownModelSamplers();") >= 3
                && queue.contains("game.gameRenderer.lightTexture().turnOnLightLayer();")
                && queue.contains("game.gameRenderer.overlayTexture().setupOverlayColor();")
                && queue.contains("game.gameRenderer.lightTexture().turnOffLightLayer();")
                && queue.contains("game.gameRenderer.overlayTexture().teardownOverlayColor();")
                && queue.contains("float[] previousShaderColor = RenderSystem.getShaderColor().clone();")
                && queue.contains("RenderSystem.setShaderColor(1F, 1F, 1F, 1F);")
                && consumers.contains("boolean extendedLayout = BBSRendering.captureIrisVertexLayout();")
                && consumers.contains("BBSRendering.beginIrisBufferUpload(source)")
                && consumers.contains("BBSRendering.endIrisBufferUpload(previousLayout)")
                && irisUtils.contains("IrisExtendedBufferBuilder")
                && irisUtils.contains("beginBufferUpload(BufferBuilder builder)")
                && irisUtils.contains("!extended.iris$extending()")
                && rendering.contains("beginIrisBufferUpload(com.mojang.blaze3d.vertex.BufferBuilder builder)"),
            "deferred vanilla render layers stopped writing depth unconditionally, so item and block forms pile their faces");
        check(queue.contains("this(vao, BBSShaders::getModel, PASS_TRANSLUCENT, true, texture, modelView, normalMat,")
                && queue.contains("this(vao, BBSShaders::getModel, PASS_TRANSLUCENT, true, armatureSnapshot, uploadCount,")
                && modelInstance.contains("PASS_TEX_OPAQUE")
                && modelInstance.contains("PASS_TEX_TRANSLUCENT")
                && modelShader.contains("float texAlpha = color.a")
                && modelShader.contains("PassMode == 3")
                && modelShader.contains("PassMode == 4")
                && queue.contains("!BBSRendering.isIrisWorldForms()")
                && rendering.contains("public static boolean isIrisWorldForms()")
                && modelVaoRenderer.contains("Uniform colorModulator = shader.getUniform(\"ColorModulator\");")
                && modelVaoRenderer.contains("colorModulator.set(1F, 1F, 1F, 1F);"),
            "split-pass solid geometry stopped writing depth in the deferred replay, so fully translucent textures self-blend");
        check(modelInstance.contains("drawWithStableModelColor(mesh, shader, bbsModelShader)")
                && modelInstance.contains("drawWithStableModelColor(buffer, shader, modelView, bbsModelShader)")
                && modelInstance.contains("RenderSystem.setShaderColor(1F, 1F, 1F, 1F);"),
            "CPU model buffers can inherit a stale global ColorModulator");
        check(queue.contains("PASS_TRANSLUCENT, false")
                && queue.contains("boolean closeBuffer")
                && billboard.contains("VertexBufferCommand(buffer, () -> capturedShader, texture,")
                && framebuffer.contains("VertexBufferCommand(buffer, () -> capturedShader, texture,")
                && label.contains("VertexBufferCommand(buffer, GameRenderer::getPositionColorShader, null,"),
            "flat billboard/framebuffer/label forms no longer use the depth-write-free deferred replay");
    }

    private static void checkResources(Path root) throws IOException, NoSuchAlgorithmException
    {
        String settings = Files.readString(root.resolve(Path.of("src/main/java/mchorse/bbs_mod/BBSSettings.java")));
        String viewport = section(settings, "builder.category(\"viewport\"", "builder.category(\"timeline\"");
        String timeline = section(settings, "builder.category(\"timeline\"", "builder.category(\"workspace\"");
        String workspace = section(settings, "builder.category(\"workspace\"", "builder.category(\"recording\"");

        check(viewport.contains("editorPreviewIconsAutoHide")
                && timeline.contains("editorStopPlaybackOnScrub")
                && timeline.contains("editorRestartOnSeek")
                && workspace.contains("editorKeyframePanelWidth")
                && !workspace.contains("editorStopPlaybackOnScrub")
                && !workspace.contains("editorPreviewIconsAutoHide")
                && !workspace.contains("editorRestartOnSeek"),
            "post-sync settings are registered under the wrong category");

        Path stringsDir = root.resolve("src/client/resources/assets/bbs/assets/strings");
        List<Path> localeFiles;

        try (var files = Files.list(stringsDir))
        {
            localeFiles = files.filter(path -> path.getFileName().toString().endsWith(".json")).toList();
        }

        for (Path localePath : localeFiles)
        {
            String locale = localePath.getFileName().toString();
            MapType strings = DataToString.mapFromString(Files.readString(localePath));

            check(strings != null, "failed to parse migrated locale: " + locale);

            for (String key : NEW_SETTINGS_LANGUAGE_KEYS)
            {
                check(strings.has(key) && !strings.getString(key).isEmpty(),
                    locale + " is missing new settings language key: " + key);
            }
        }

        for (String locale : new String[] {"en_us", "ru_ru", "zh_cn"})
        {
            Path path = stringsDir.resolve(locale + ".json");
            MapType strings = DataToString.mapFromString(Files.readString(path));

            for (String key : MIGRATED_LANGUAGE_KEYS)
            {
                check(strings.has(key), locale + " is missing migrated language key: " + key);
            }
        }

        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve(ICONS)));

        check(ICONS_SHA256.equals(HexFormat.of().formatHex(digest)),
            "the migrated transform-space icon atlas changed unexpectedly");
    }

    private static void checkSmallUiFixes(Path root) throws IOException
    {
        String keyframeEditor = compact(Files.readString(root.resolve(KEYFRAME_EDITOR)));
        String nestedEdit = compact(Files.readString(root.resolve(NESTED_EDIT)));
        String formEditor = compact(Files.readString(root.resolve(FORM_EDITOR)));
        String filmPreview = compact(Files.readString(root.resolve(FILM_PREVIEW)));
        String replacement = section(
            keyframeEditor,
            "private void replaceEditor(",
            "public void setTimelineVisible(boolean visible)"
        );
        int mounted = replacement.indexOf("this.add(replacement);");
        int targetResize = replacement.indexOf("this.target.resize();");
        int editorResize = replacement.indexOf("this.resize();");

        check(mounted >= 0 && targetResize > mounted && editorResize > targetResize,
            "keyframe replacement no longer resizes its target before the editor recursively resizes the mounted panel");
        check(nestedEdit.contains("this.h(UIConstants.CONTROL_HEIGHT).row(UIConstants.MARGIN);"),
            "nested form pick/edit buttons lost their standard spacing");
        check(formEditor.contains("if (entry.part == part)")
                && formEditor.contains("this.formsList.setCurrentScroll(added);")
                && formEditor.contains("this.pickForm(added);"),
            "new body attachments are not revealed and selected after the form list refresh");
        String cameraContext = section(filmPreview, "this.perspective.context", "this.recordReplay =");

        check(formEditor.contains("this.listSection = new UIElement();")
                && formEditor.contains("this.bodyPartEditor.relative(this.forms).w(1F).y(1F).h(0).anchorY(1F);")
                && formEditor.contains("private void resizeSidebar()")
                && formEditor.contains("this.listSection.h(1F, -height);")
                && formEditor.contains("if (entry.part == part)")
                && formEditor.contains("this.formsList.setCurrentScroll(added);" )
                && formEditor.contains("this.pickForm(added);"),
            "body-part settings are not pinned to the sidebar bottom after adding an attachment");
        check(!cameraContext.contains("controller.populateCameraModeMenu(menu);")
                && cameraContext.contains("controller::teleportOrbitPivotToReplay")
                && filmPreview.contains("controller::toggleOrbitAttachment")
                && filmPreview.contains("controller.orbit::toggleOrtho"),
            "camera mode button context menu must contain only the three orbit-camera actions");
    }

    private static void checkSliderWiring(Path root) throws IOException
    {
        for (String source : SLIDER_SOURCES)
        {
            String text = Files.readString(root.resolve(source));

            check(text.contains("UISliderTrackpad") || text.contains("slider(true)"),
                "slider migration is disconnected in " + source);
        }
    }

    private static Path findProjectRoot()
    {
        Path current = Path.of("").toAbsolutePath();

        while (current != null)
        {
            if (Files.isRegularFile(current.resolve(FILM_CONTROLLER)))
            {
                return current;
            }

            Path nested = current.resolve("new");

            if (Files.isRegularFile(nested.resolve(FILM_CONTROLLER)))
            {
                return nested;
            }

            current = current.getParent();
        }

        throw new AssertionError("could not locate the new project source tree");
    }

    private static String compact(String source)
    {
        return source.replaceAll("\\s+", " ").trim();
    }

    private static String section(String source, String start, String end)
    {
        int startIndex = source.indexOf(start);
        int endIndex = source.indexOf(end, startIndex);

        check(startIndex >= 0 && endIndex > startIndex, "missing production source section");

        return source.substring(startIndex, endIndex);
    }

    private static int occurrences(String source, String marker)
    {
        int count = 0;
        int index = 0;

        while ((index = source.indexOf(marker, index)) >= 0)
        {
            count += 1;
            index += marker.length();
        }

        return count;
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
