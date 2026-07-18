package mchorse.bbs_mod.particles;

import com.mojang.blaze3d.vertex.PoseStack;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.IntType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.forms.renderers.FormRenderSpace;
import mchorse.bbs_mod.forms.renderers.FormRenderType;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import mchorse.bbs_mod.math.molang.expressions.MolangExpression;
import mchorse.bbs_mod.particles.components.appearance.ParticleComponentCollisionAppearance;
import mchorse.bbs_mod.particles.components.events.ParticleComponentEmitterLifetimeEvents;
import mchorse.bbs_mod.particles.emitter.Particle;
import mchorse.bbs_mod.particles.emitter.ParticleEmitter;
import mchorse.bbs_mod.particles.events.ParticleCollisionEvents;
import mchorse.bbs_mod.particles.events.ParticleEventNode;
import mchorse.bbs_mod.particles.events.ParticleEventTimeline;
import mchorse.bbs_mod.particles.events.ParticleEventTriggerList;
import mchorse.bbs_mod.particles.events.ParticleLoopingDistanceEvents;
import mchorse.bbs_mod.resources.Link;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.neoforged.fml.loading.LoadingModList;
import org.joml.Vector3d;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Dependency-light regressions for particle event and render-space contracts. */
public final class ParticleRuntimeConsistencyTest
{
    private ParticleRuntimeConsistencyTest()
    {}

    public static void main(String[] args)
    {
        bootstrapStandaloneMinecraftRuntime();

        testRootPlacementRebasesDistance();
        testCollisionCrossingRearms();
        testTimelineZeroFiresOnce();
        testTriggerRawSiblingSurvivesEdit();
        testCollisionRawSiblingSurvivesEdit();
        testLoopingRawSiblingSurvivesEdit();
        testBundledDefaultTexture();
        testExplicitRenderSpaces();
        testOuterWorldDepthSnapshot();

        System.out.println("ParticleRuntimeConsistencyTest: all tests passed");
    }

    private static void bootstrapStandaloneMinecraftRuntime()
    {
        SharedConstants.tryDetectVersion();

        if (LoadingModList.get() == null)
        {
            LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        }

        Bootstrap.bootStrap();
    }

    private static void testRootPlacementRebasesDistance()
    {
        ParticleEmitter emitter = new ParticleEmitter();

        emitter.setRootPosition(new Vector3d(1_000_000D, 80D, -1_000_000D));
        emitter.updateEventTravelDistance();
        check(close(emitter.eventTravelDistance, 0D), "first world placement counted as travel");

        emitter.setRootPosition(new Vector3d(1_000_003D, 84D, -1_000_000D));
        emitter.updateEventTravelDistance();
        check(close(emitter.eventTravelDistance, 5D), "real emitter travel was not accumulated");
    }

    private static void testCollisionCrossingRearms()
    {
        Particle particle = new Particle(0, 0F);

        check(particle.updateCollisionEventCrossing(true), "first collision did not trigger");
        check(!particle.updateCollisionEventCrossing(true), "continuous contact triggered twice");
        check(!particle.updateCollisionEventCrossing(false), "clearing contact reported a collision");
        check(particle.updateCollisionEventCrossing(true), "independent collision did not rearm");
    }

    private static void testTimelineZeroFiresOnce()
    {
        AtomicInteger fired = new AtomicInteger();
        ParticleScheme scheme = new ParticleScheme();
        ParticleEmitter emitter = new ParticleEmitter();
        ParticleComponentEmitterLifetimeEvents events = new ParticleComponentEmitterLifetimeEvents();
        ParticleEventNode node = new ParticleEventNode();
        ParticleEventTimeline.Entry zero = events.timeline.add("0");

        zero.events.add("zero");
        node.expression = new MolangExpression(scheme.parser)
        {
            @Override
            public double get()
            {
                return fired.incrementAndGet();
            }
        };
        scheme.events.put("zero", node);
        emitter.scheme = scheme;
        emitter.playing = true;
        emitter.age = 0;

        events.update(emitter);
        events.update(emitter);

        check(fired.get() == 1, "timeline key 0 did not dispatch exactly once");
    }

    private static void testTriggerRawSiblingSurvivesEdit()
    {
        ListType input = new ListType();
        MapType vendor = vendorSibling();
        ParticleEventTriggerList triggers = new ParticleEventTriggerList();

        input.addString("known");
        input.add(vendor);
        input.addString("known_2");
        triggers.fromData(input);
        triggers.setFromCSV("renamed");

        BaseType output = triggers.toData();

        check(output != null && output.isList(), "mixed trigger list lost its list shape");
        check(containsVendorSibling(output.asList()), "editing a trigger dropped its raw sibling");
    }

    private static void testCollisionRawSiblingSurvivesEdit()
    {
        ListType input = new ListType();
        MapType known = new MapType(false);
        ParticleCollisionEvents events = new ParticleCollisionEvents();

        known.putString("event", "known");
        known.putInt("min_speed", 2);
        input.add(known);
        input.add(vendorSibling());
        events.fromData(input);
        events.entries.get(0).event = "renamed";
        events.markEdited();

        BaseType output = events.toData();

        check(output != null && output.isList(), "edited collision events lost their list shape");
        check(containsVendorSibling(output.asList()), "editing a collision entry dropped its raw sibling");
    }

    private static void testLoopingRawSiblingSurvivesEdit()
    {
        ListType input = new ListType();
        MapType known = new MapType(false);
        ParticleLoopingDistanceEvents events = new ParticleLoopingDistanceEvents();

        known.putInt("distance", 1);
        known.putString("effects", "known");
        input.add(known);
        input.add(vendorSibling());
        events.fromData(input);
        events.entries.get(0).setDistance("2");
        events.markEdited();

        BaseType output = events.toData();

        check(output != null && output.isList(), "edited looping events lost their list shape");
        check(containsVendorSibling(output.asList()), "editing a looping entry dropped its raw sibling");
    }

    private static void testBundledDefaultTexture()
    {
        Link expected = Link.assets("textures/default_particles.png");
        ParticleComponentCollisionAppearance collision = new ParticleComponentCollisionAppearance();
        Path texture = Path.of("src/client/resources/assets/bbs/assets").resolve(expected.path);

        check(ParticleScheme.DEFAULT_TEXTURE.equals(expected), "default particle texture no longer targets the bundled atlas");
        check(collision.texture.equals(expected), "default collision appearance can fall through to a missing texture");
        check(Files.isRegularFile(texture), "bundled default particle texture is missing");

        try
        {
            byte[] png = Files.readAllBytes(texture);

            check(png.length >= 24
                    && (png[0] & 0xff) == 0x89
                    && png[1] == 'P'
                    && png[2] == 'N'
                    && png[3] == 'G'
                    && png[4] == 0x0d
                    && png[5] == 0x0a
                    && png[6] == 0x1a
                    && png[7] == 0x0a,
                "bundled default particle texture is not a PNG");
            check(ByteBuffer.wrap(png, 16, 8).getInt() > 0 && ByteBuffer.wrap(png, 20, 4).getInt() > 0,
                "bundled default particle texture has invalid dimensions");
        }
        catch (IOException e)
        {
            throw new AssertionError("could not inspect bundled default particle texture", e);
        }

        MapType root = new MapType(false);
        MapType effect = new MapType(false);
        MapType description = new MapType(false);
        MapType parameters = new MapType(false);

        root.put("particle_effect", effect);
        effect.put("description", description);
        effect.put("components", new MapType(false));
        description.put("basic_render_parameters", parameters);
        parameters.putString("material", "particles_alpha");
        parameters.putString("texture", "textures/particle/particles");

        try
        {
            ParticleScheme parsed = ParticleScheme.PARSER.fromData(root);
            MapType encoded = ParticleScheme.PARSER.toData(parsed);
            String encodedTexture = encoded.getMap("particle_effect")
                .getMap("description")
                .getMap("basic_render_parameters")
                .getString("texture");

            check(parsed.texture.equals(expected), "legacy vanilla particle atlas no longer resolves to the bundled default");
            check(encodedTexture.equals("textures/particle/particles"), "default atlas no longer serializes to the legacy vanilla identifier");
        }
        catch (Exception e)
        {
            throw new AssertionError("could not round-trip the legacy default particle texture", e);
        }
    }

    private static void testExplicitRenderSpaces()
    {
        FormRenderingContext entity = new FormRenderingContext()
            .set(FormRenderType.ENTITY, null, new PoseStack(), 0, 0, 0F);
        FormRenderingContext block = new FormRenderingContext()
            .set(FormRenderType.MODEL_BLOCK, null, new PoseStack(), 0, 0, 0F);
        FormRenderingContext preview = new FormRenderingContext()
            .set(FormRenderType.PREVIEW, null, new PoseStack(), 0, 0, 0F);

        check(entity.renderSpace == FormRenderSpace.ENTITY_LOCAL, "entity space was inferred from global state");
        check(block.renderSpace == FormRenderSpace.ENTITY_LOCAL, "model-block space was inferred from global state");
        check(preview.renderSpace == FormRenderSpace.UI_LOCAL, "preview space was not UI-local");
        entity.cameraRelativeWorld();
        check(entity.renderSpace == FormRenderSpace.CAMERA_RELATIVE_WORLD, "world replay space was not explicit");
    }

    private static void testOuterWorldDepthSnapshot()
    {
        Path source = Path.of("src/client/java/mchorse/bbs_mod/client/BBSRendering.java");

        try
        {
            String code = Files.readString(source);

            check(code.contains("boolean oldDepthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);"),
                "world composite does not snapshot the actual GL depth state");
            check(code.contains("if (oldDepthTest)")
                    && code.contains("RenderSystem.enableDepthTest();")
                    && code.contains("RenderSystem.disableDepthTest();"),
                "world composite does not restore both depth-state branches");
        }
        catch (IOException e)
        {
            throw new AssertionError("could not inspect world render-state guard", e);
        }
    }

    private static MapType vendorSibling()
    {
        MapType vendor = new MapType(false);

        vendor.put("vendor_payload", new IntType(42));

        return vendor;
    }

    private static boolean containsVendorSibling(ListType list)
    {
        for (BaseType element : list)
        {
            if (element.isMap() && element.asMap().getInt("vendor_payload", -1) == 42)
            {
                return true;
            }
        }

        return false;
    }

    private static boolean close(double a, double b)
    {
        return Math.abs(a - b) < 1.0E-9D;
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
