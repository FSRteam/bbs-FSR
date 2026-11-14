package mchorse.bbs_mod.ui.film.replays;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.camera.clips.CameraClipContext;
import mchorse.bbs_mod.camera.clips.misc.ImageClip;
import mchorse.bbs_mod.camera.clips.misc.ImageOverlay;
import mchorse.bbs_mod.camera.clips.misc.SubtitleClip;
import mchorse.bbs_mod.camera.clips.misc.VideoClientClip;
import mchorse.bbs_mod.camera.clips.modifiers.ShakeClip;
import mchorse.bbs_mod.camera.data.Placement;
import mchorse.bbs_mod.camera.data.Position;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.forms.forms.BodyPart;
import mchorse.bbs_mod.forms.forms.VideoForm;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.forms.utils.FormMaterial;
import mchorse.bbs_mod.forms.forms.utils.ValueMaterials;
import mchorse.bbs_mod.forms.renderers.utils.FormMaterialLevels;
import mchorse.bbs_mod.utils.pose.PoseTransform;
import mchorse.bbs_mod.utils.pose.Transform;
import mchorse.bbs_mod.utils.colors.Color;
import mchorse.bbs_mod.film.replays.FormProperties;
import mchorse.bbs_mod.film.replays.tracks.MaterialTracks;
import mchorse.bbs_mod.film.replays.tracks.TrackId;
import mchorse.bbs_mod.film.replays.tracks.TrackContext;
import mchorse.bbs_mod.film.replays.tracks.behaviours.MaterialPropTrack;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;
import mchorse.bbs_mod.forms.FormArchitect;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.iris.ShaderSunRotation;
import mchorse.bbs_mod.utils.clips.MissingClip;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import java.lang.reflect.Field;

/** Exercises migrated save formats and deterministic sampling without a GL context. */
public final class FS26FormClipParityTest
{
    public static void runAll()
    {
        testPlacementMigration();
        testSubtitlePlacementMigration();
        testShakeSampling();
        testAttachmentComponents();
        testVideoRoundTrip();
        testVideoPlacementUsesDrawnOverlay();
        testShaderSunRewrite();
        testBoneAppearance();
        testMaterialAppearance();
        testMaterialVisibility();
        testMaterialTracks();
        testMissingClipRoundTrip();
        ReplayFactoryTest.runAll();
        FormAppearanceContractTest.runAll();
    }

    private static void testMissingClipRoundTrip()
    {
        MapType raw = new MapType();
        MapType payload = new MapType();
        raw.putString("type", "absent:addon_clip");
        raw.putInt("tick", 18);
        raw.putInt("duration", 37);
        raw.putBool("enabled", true);
        payload.putString("custom", "keep this field");
        raw.put("payload", payload);
        MissingClip clip = new MissingClip(raw);
        check(!clip.enabled.get() && clip.tick.get() == 18, "Missing clip must remain inert at its original tick");
        check(raw.equals(clip.toData()), "Direct missing-clip serialization loses addon fields");
        check(raw.equals(clip.copy().toData()), "Copying an unavailable clip loses addon fields");

        MapType snapshot = (MapType) clip.toData();
        snapshot.getMap("payload").putString("custom", "changed copy");
        check(raw.equals(clip.toData()), "Missing clip snapshot shares mutable addon data");
        clip.fromData(snapshot);
        check(snapshot.equals(clip.toData()), "Drag/undo snapshot restore loses unknown addon fields");
        check(!clip.enabled.get(), "Restoring a placeholder must not enable execution");
    }

    private static void testPlacementMigration()
    {
        MapType old = new MapType();
        old.putFloat("scale", 2.5F);
        Placement placement = new Placement();
        placement.fromData(old);
        check(placement.scaleX == 2.5F && placement.scaleY == 2.5F, "Uniform placement was not migrated");

        placement.scaleX = 3F;
        placement.scaleY = 0.75F;
        placement.offsetX = 41F;
        Placement loaded = new Placement();
        loaded.fromData(placement.toData());
        check(placement.equals(loaded) && placement.hashCode() == loaded.hashCode(), "Independent placement axes were lost");
        loaded.scaleY = 1F;
        check(!placement.equals(loaded), "Placement equality ignores vertical scale");

        old.putFloat("x", 5F);
        old.putFloat("y", -4F);
        Placement legacy = Placement.fromLegacy(old, "scale", 1F);
        check(legacy.scaleX == 5F && legacy.scaleY == 5F && legacy.offsetX == 10F && legacy.offsetY == -8F,
            "Flat legacy overlay units changed");
    }

    private static void testSubtitlePlacementMigration()
    {
        MapType legacy = new MapType();
        MapType transform = new MapType();
        Transform legacyTransform = new Transform();
        legacy.putFloat("windowX", 0.25F);
        legacy.putFloat("anchorY", 0.75F);
        legacy.putFloat("x", 13F);
        legacy.putFloat("y", -7F);
        legacy.putFloat("size", 3F);
        legacyTransform.translate.set(5F, -4F, 2F);
        legacyTransform.toData(transform);
        legacy.put("transform", transform);

        SubtitleClip clip = new SubtitleClip();
        clip.fromData(legacy);
        Placement placement = clip.placement.get();
        check(placement.windowX == 0.25F && placement.anchorY == 0.75F
            && placement.offsetX == 26F && placement.offsetY == -14F
            && placement.scaleX == 6F && placement.scaleY == 6F,
            "Legacy subtitle placement did not convert half-frame units");
        check(clip.transform.get().translate.equals(new Vector3f(10F, -8F, 2F)),
            "Legacy subtitle transform must double only its frame-plane translation");

        clip.font.set(Link.assets("fonts/test.ttf"));
        clip.fontSize.set(22);
        clip.lineHeight.set(0);
        clip.duration.set(20);
        SubtitleClip reopened = new SubtitleClip();
        reopened.fromData(clip.toData());
        check(reopened.placement.get().equals(placement)
            && reopened.transform.get().translate.equals(clip.transform.get().translate),
            "Reopening a migrated subtitle converted its units a second time");
        check(reopened.font.get().equals(clip.font.get()) && reopened.fontSize.get() == 22 && reopened.lineHeight.get() == 0,
            "Subtitle font or font-defined line spacing was lost on save");

        CameraClipContext context = new CameraClipContext();
        context.apply(reopened, new Position());
        check(SubtitleClip.getSubtitles(context).getFirst() == reopened.getSubtitle()
            && reopened.getOverlayBox() == reopened.getSubtitle().box
            && reopened.getSubtitle().placement == reopened.placement.get()
            && reopened.getSubtitle().font.equals(reopened.font.get()),
            "Subtitle evaluation detached placement, font or the renderer-owned bounds");
    }

    private static void testShakeSampling()
    {
        ShakeClip shake = new ShakeClip();
        shake.noise.set(true);
        shake.shake.set(2F);
        shake.shakeAmount.set(3F);
        shake.active.set(127);
        CameraClipContext context = new CameraClipContext();
        context.ticks = 73;
        context.transition = 0.375F;
        Position first = new Position();
        shake.applyClip(context, first);
        context.ticks = 7;
        shake.applyClip(context, new Position());
        context.ticks = 73;
        Position replayed = new Position();
        shake.applyClip(context, replayed);
        check(first.equals(replayed), "Shake changes after seeking away and back");
        check(first.point.x != first.point.y && first.point.y != first.point.z, "Noise axes share a signal");

        shake.noise.set(false);
        Position sine = new Position();
        shake.applyClip(context, sine);
        double expected = Math.sin((73F + 0.375F) / 2F) * 3F;
        check(Math.abs(sine.point.x - expected) < 1E-6, "Legacy sine shake changed");
        ShakeClip loaded = new ShakeClip();
        shake.noise.set(true);
        loaded.fromData(shake.toData());
        check(loaded.noise.get(), "Noise choice was lost on reload");
    }

    private static void testAttachmentComponents()
    {
        BodyPart part = new BodyPart("0");
        Matrix4f source = new Matrix4f().translate(3F, 7F, -4F).rotateY(0.4F).scale(2F, 3F, 4F);
        Matrix4f original = new Matrix4f(source);
        check(part.filterBoneMatrix(source) == source, "Default inheritance changed the cached matrix");
        part.inheritPosition.set(false);
        Vector3f translation = part.filterBoneMatrix(source).getTranslation(new Vector3f());
        check(translation.lengthSquared() < 1E-8, "Position inheritance cannot be disabled");
        part.inheritScale.set(false);
        Vector3f scale = part.filterBoneMatrix(source).getScale(new Vector3f());
        check(scale.distance(new Vector3f(1F)) < 1E-5, "Scale inheritance cannot be disabled");
        part.inheritRotation.set(false);
        check(part.filterBoneMatrix(source).equals(new Matrix4f().rotateY((float) Math.PI), 1E-5F),
            "Disabled inheritance does not use the unattached frame");
        check(source.equals(original), "Filtering mutated the shared bone cache");
        check(part.filterBoneMatrix(new Matrix4f().scale(0F)).isFinite(), "Collapsed bone scale produced NaN");
        BodyPart loaded = new BodyPart("0");
        loaded.fromData(part.toData());
        check(!loaded.inheritPosition.get() && !loaded.inheritRotation.get() && !loaded.inheritScale.get(),
            "Attachment choices did not survive saving");
    }

    private static void testVideoRoundTrip()
    {
        try
        {
            Field registry = BBSMod.class.getDeclaredField("forms");
            registry.setAccessible(true);
            Object previous = registry.get(null);
            FormArchitect architect = new FormArchitect();
            architect.register(Link.bbs("video"), VideoForm.class, null);
            registry.set(null, architect);

            try
            {
                testRegisteredVideoRoundTrip();
            }
            finally
            {
                registry.set(null, previous);
            }
        }
        catch (ReflectiveOperationException exception)
        {
            throw new AssertionError("Could not install video form registry", exception);
        }
    }

    private static void testVideoPlacementUsesDrawnOverlay()
    {
        VideoClientClip clip = new VideoClientClip();
        clip.audio.set(Link.assets("video/example.mp4"));
        clip.duration.set(20);
        CameraClipContext context = new CameraClipContext();
        context.setup(5, 0F);
        check(context.apply(clip, new Position()), "Video clip did not publish its active overlay");
        ImageOverlay rendered = ImageClip.getImages(context).get(0);
        check(rendered == clip.getOverlay() && rendered.box == clip.getOverlayBox(),
            "Video placement gizmo reads a different overlay from the rendered picture");
        rendered.box.set(10F, 20F, 320F, 180F, 1920F);
        check(clip.getOverlayBox().x == 10F && clip.getOverlayBox().w == 320F,
            "Video placement lost the bounds measured by the renderer");
    }

    private static void testRegisteredVideoRoundTrip()
    {
        VideoForm form = new VideoForm();
        form.video.set(Link.assets("video/example.mp4"));
        form.videoOffset.set(-1.25F);
        form.loop.set(false);
        VideoForm loaded = new VideoForm();
        loaded.fromData(form.toData());
        check(form.video.get().equals(loaded.video.get()) && loaded.videoOffset.get() == -1.25F && !loaded.loop.get(),
            "Video form playback settings were lost");
    }

    private static void testShaderSunRewrite()
    {
        String source = "#version 150\nvec3 sun = vec3(-sin(a), cos(a) * sunRotationData);\n";
        String transformed = ShaderSunRotation.processSource(source);
        check(transformed.contains("bbs_rotateSunVector(vec3(-sin(a)") && transformed.contains("uniform float bbs_sunHorizontalRotation"),
            "Reconstructed shader sun vectors do not rotate");
        check(transformed.equals(ShaderSunRotation.processSource(transformed)), "Sun shader rewrite is not idempotent");
        check(ShaderSunRotation.processSource("#version 150\nvoid main() {}\n").equals("#version 150\nvoid main() {}\n"),
            "Unrelated shader source changed");
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    private static void testBoneAppearance()
    {
        PoseTransform source = new PoseTransform();
        source.visible = false;
        source.overlay.set(1F, 0.25F, 0F, 0.5F);
        source.lighting = 0.35F;
        source.glintMode = PoseTransform.GLINT_EDGE;
        PoseTransform loaded = new PoseTransform();
        loaded.fromData(source.toData());
        check(source.equals(loaded), "Bone appearance did not survive a save");
        PoseTransform copy = (PoseTransform) source.copy();
        source.overlay.a = 0.9F;
        check(Math.abs(copy.overlay.a - 0.5F) < 1E-6F, "Pose copies share overlay state");

        PoseTransform blend = new PoseTransform();
        blend.lerp(loaded, 0.5F);
        check(blend.visible && Math.abs(blend.lighting - 0.175F) < 1E-6F,
            "Bone glow is not continuous or visibility switched before its key");
        blend.lerp(loaded, 1F);
        check(!blend.visible && Math.abs(blend.overlay.a - loaded.overlay.a) < 1E-6F
            && Math.abs(blend.overlay.g - loaded.overlay.g) < 1E-6F, "Bone appearance missed the endpoint");
        blend.identity();
        check(blend.visible && blend.overlay.a == 0F && blend.glintMode == 0F, "Bone appearance reset is not neutral");
        loaded.fromData(new MapType());
        check(loaded.visible && loaded.overlay.a == 0F, "Legacy poses gained hidden or colored bones");
    }

    private static void testMaterialAppearance()
    {
        ModelForm form = new ModelForm();
        check(FormMaterialLevels.materialVisible(form, "skin") && FormMaterialLevels.materialGlow(form, "skin") == 0F,
            "Untouched materials no longer render neutrally");
        FormMaterial skin = form.materials.getOrCreate("skin");
        skin.color.set(new Color(0.5F, 0.75F, 1F, 0.25F));
        skin.overlayColor.set(new Color(1F, 0F, 0F, 0.6F));
        skin.lighting.set(0.4F);
        skin.visible.set(false);
        skin.culling.set(FormMaterial.CULLING_OFF);
        skin.metallic.set(0.75F);
        int hash = form.materials.appearanceHash();
        skin.color.get().r = 0.3F;
        check(hash != form.materials.appearanceHash(), "Mutable material tint did not invalidate CPU geometry");
        ValueMaterials loaded = new ValueMaterials("materials");
        loaded.fromData(form.materials.toData());
        FormMaterial restored = loaded.getMaterial("skin");
        check(restored != null && !restored.visible.get() && restored.metallic.get() == 0.75F
            && restored.overlayColor.get().equals(skin.overlayColor.get()), "Dynamic material data was lost");
        check(!FormMaterialLevels.materialVisible(form, "skin")
            && Math.abs(FormMaterialLevels.materialGlow(form, "skin") - 0.6F) < 1E-6F,
            "Static material settings did not reach render appearance");
        form.materialVisibilityOverrides.put("skin", true);
        form.materialLightingOverrides.put("skin", 0.2F);
        check(FormMaterialLevels.materialVisible(form, "skin")
            && Math.abs(FormMaterialLevels.materialGlow(form, "skin") - 0.8F) < 1E-6F,
            "Animated material values did not override static appearance");
    }

    private static void testMaterialTracks()
    {
        MaterialTracks.Address nested = new MaterialTracks.Address("bp_a/bp_b", "Skin.001/ik_targets", TrackId.MATERIAL_PROP_OVERLAY);
        check(nested.equals(MaterialTracks.parse(nested.key())), "Material track lost its dotted name or nested owner");
        check(MaterialTracks.parse("pose.bones.materials.skin.color") == null, "Material track hijacked a bone name");
        check(MaterialTracks.parse("materials.skin.unknown") == null, "Unknown material properties were guessed");
        ModelForm form = new ModelForm();
        form.materials.getOrCreate("Skin.001").lighting.set(0.8F);
        FormProperties properties = new FormProperties("properties");
        MaterialTracks.Address address = new MaterialTracks.Address("", "Skin.001", TrackId.MATERIAL_PROP_LIGHTING);
        KeyframeChannel<Float> channel = properties.getOrCreate(form, address.key());
        channel.insert(0F, 0.2F);
        properties.applyProperties(form, 0F, 0.5F);
        check(Math.abs(form.materialLightingOverrides.get("Skin.001") - 0.5F) < 1E-6F,
            "Material state blend did not start from its static setting");
        channel.removeAll();
        properties.applyProperties(form, 0F);
        check(!form.materialLightingOverrides.containsKey("Skin.001"), "An emptied material channel left stale render state");
    }

    private static void testMaterialVisibility()
    {
        ModelForm form = new ModelForm();
        String material = "Material.001";
        check(FormMaterialLevels.materialVisible(form, material), "Untouched materials must remain visible");
        form.materials.getOrCreate(material).visible.set(false);
        check(!FormMaterialLevels.materialVisible(form, material)
            && FormMaterialLevels.materialVisible(form, "Other"), "Static visibility must only hide its own material");

        ValueMaterials restored = new ValueMaterials("materials");
        restored.fromData(form.materials.toData());
        ValueMaterials copied = new ValueMaterials("materials");
        copied.copy(restored);
        check(!copied.getMaterial(material).visible.get(), "Material copies lost visibility");
        MapType old = new MapType();
        old.put(material, new MapType());
        restored.fromData(old);
        check(restored.getMaterial(material).visible.get(), "Old material maps must default to visible");

        TrackId id = TrackId.materialProp("", material, TrackId.MATERIAL_PROP_VISIBLE);
        check(id.equals(TrackId.parse(id.toKey())), "Visibility track lost a dotted material name");
        MaterialPropTrack track = new MaterialPropTrack();
        check(track.factory(id) == KeyframeFactories.BOOLEAN, "Visibility must use stepped boolean keys");
        KeyframeChannel<Boolean> channel = new KeyframeChannel<>(id.toKey(), KeyframeFactories.BOOLEAN);
        channel.insert(0, true);
        channel.insert(10, false);
        track.apply(TrackContext.of(form), id, channel, 5F, 1F);
        check(FormMaterialLevels.materialVisible(form, material), "Visibility animation must override a static hidden material");
        track.apply(TrackContext.of(form), id, channel, 10F, 1F);
        check(!FormMaterialLevels.materialVisible(form, material), "Visibility must switch at the next key");
        form.materials.getMaterial(material).visible.set(true);
        KeyframeChannel<Boolean> empty = new KeyframeChannel<>(id.toKey(), KeyframeFactories.BOOLEAN);
        track.apply(TrackContext.of(form), id, empty, 0F, 1F);
        check(!form.materialVisibilityOverrides.containsKey(material)
            && FormMaterialLevels.materialVisible(form, material), "Empty visibility channels must restore the static setting");
    }
}
