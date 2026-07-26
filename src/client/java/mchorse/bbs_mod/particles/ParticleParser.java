package mchorse.bbs_mod.particles;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.math.Operation;
import mchorse.bbs_mod.math.molang.MolangException;
import mchorse.bbs_mod.plugin.manager.PluginParticleComponentClass;
import mchorse.bbs_mod.particles.components.ParticleComponentBase;
import mchorse.bbs_mod.particles.components.appearance.ParticleComponentAppearanceBillboard;
import mchorse.bbs_mod.particles.components.appearance.ParticleComponentAppearanceLighting;
import mchorse.bbs_mod.particles.components.appearance.ParticleComponentAppearanceTinting;
import mchorse.bbs_mod.particles.components.appearance.ParticleComponentCollisionAppearance;
import mchorse.bbs_mod.particles.components.appearance.ParticleComponentCollisionTinting;
import mchorse.bbs_mod.particles.components.events.ParticleComponentEmitterLifetimeEvents;
import mchorse.bbs_mod.particles.components.events.ParticleComponentParticleLifetimeEvents;
import mchorse.bbs_mod.particles.components.expiration.ParticleComponentExpireInBlocks;
import mchorse.bbs_mod.particles.components.expiration.ParticleComponentExpireNotInBlocks;
import mchorse.bbs_mod.particles.components.expiration.ParticleComponentKillPlane;
import mchorse.bbs_mod.particles.components.expiration.ParticleComponentParticleLifetime;
import mchorse.bbs_mod.particles.components.lifetime.ParticleComponentLifetimeExpression;
import mchorse.bbs_mod.particles.components.lifetime.ParticleComponentLifetimeLooping;
import mchorse.bbs_mod.particles.components.lifetime.ParticleComponentLifetimeOnce;
import mchorse.bbs_mod.particles.components.meta.ParticleComponentInitialization;
import mchorse.bbs_mod.particles.components.meta.ParticleComponentLocalSpace;
import mchorse.bbs_mod.particles.components.meta.ParticleComponentParticleInitialization;
import mchorse.bbs_mod.particles.components.motion.ParticleComponentInitialSpeed;
import mchorse.bbs_mod.particles.components.motion.ParticleComponentInitialSpin;
import mchorse.bbs_mod.particles.components.motion.ParticleComponentMotionCollision;
import mchorse.bbs_mod.particles.components.motion.ParticleComponentMotionDynamic;
import mchorse.bbs_mod.particles.components.motion.ParticleComponentMotionParametric;
import mchorse.bbs_mod.particles.components.rate.ParticleComponentRateInstant;
import mchorse.bbs_mod.particles.components.rate.ParticleComponentRateManual;
import mchorse.bbs_mod.particles.components.rate.ParticleComponentRateSteady;
import mchorse.bbs_mod.particles.components.shape.ParticleComponentShapeBox;
import mchorse.bbs_mod.particles.components.shape.ParticleComponentShapeCustom;
import mchorse.bbs_mod.particles.components.shape.ParticleComponentShapeDisc;
import mchorse.bbs_mod.particles.components.shape.ParticleComponentShapeEntityAABB;
import mchorse.bbs_mod.particles.components.shape.ParticleComponentShapePoint;
import mchorse.bbs_mod.particles.components.shape.ParticleComponentShapeSphere;
import mchorse.bbs_mod.particles.events.ParticleEventNode;
import mchorse.bbs_mod.resources.Link;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class ParticleParser
{
    private static final Logger LOGGER = LoggerFactory.getLogger("bbs-particles");
    public static final String PREFIX = "minecraft:";
    public static final String PREFIX_BLOCKBUSTER = "blockbuster:";

    public Map<String, Class<? extends ParticleComponentBase>> components = new HashMap<>();
    private final Map<String, PluginParticleComponentClass> registeredApi2ComponentClasses = new HashMap<>();
    private final Map<String, PluginParticleComponentClass> failedApi2ComponentClasses = new HashMap<>();

    public static boolean isEmpty(BaseType element)
    {
        if (element.isList())
        {
            return element.asList().isEmpty();
        }
        else if (element.isMap())
        {
            return element.asMap().isEmpty();
        }
        else if (element.isString())
        {
            return element.asString().isEmpty();
        }
        else if (element.isNumeric())
        {
            return Operation.equals(element.asNumeric().doubleValue(), 0);
        }

        return true;
    }

    public ParticleParser()
    {
        /* Meta components */
        this.components.put("emitter_local_space", ParticleComponentLocalSpace.class);
        this.components.put("emitter_initialization", ParticleComponentInitialization.class);
        this.components.put("particle_initialization", ParticleComponentParticleInitialization.class);

        /* Rate */
        this.components.put("emitter_rate_instant", ParticleComponentRateInstant.class);
        this.components.put("emitter_rate_steady", ParticleComponentRateSteady.class);
        this.components.put("emitter_rate_manual", ParticleComponentRateManual.class);

        /* Lifetime emitter */
        this.components.put("emitter_lifetime_looping", ParticleComponentLifetimeLooping.class);
        this.components.put("emitter_lifetime_once", ParticleComponentLifetimeOnce.class);
        this.components.put("emitter_lifetime_expression", ParticleComponentLifetimeExpression.class);

        /* Emitter events */
        this.components.put("emitter_lifetime_events", ParticleComponentEmitterLifetimeEvents.class);
        this.components.put("particle_lifetime_events", ParticleComponentParticleLifetimeEvents.class);

        /* Shapes */
        this.components.put("emitter_shape_disc", ParticleComponentShapeDisc.class);
        this.components.put("emitter_shape_box", ParticleComponentShapeBox.class);
        this.components.put("emitter_shape_entity_aabb", ParticleComponentShapeEntityAABB.class);
        this.components.put("emitter_shape_point", ParticleComponentShapePoint.class);
        this.components.put("emitter_shape_sphere", ParticleComponentShapeSphere.class);
        this.components.put("emitter_shape_custom", ParticleComponentShapeCustom.class);

        /* Lifetime particle */
        this.components.put("particle_lifetime_expression", ParticleComponentParticleLifetime.class);
        this.components.put("particle_expire_if_in_blocks", ParticleComponentExpireInBlocks.class);
        this.components.put("particle_expire_if_not_in_blocks", ParticleComponentExpireNotInBlocks.class);
        this.components.put("particle_kill_plane", ParticleComponentKillPlane.class);

        /* Appearance */
        this.components.put("particle_appearance_billboard", ParticleComponentAppearanceBillboard.class);
        this.components.put("particle_appearance_lighting", ParticleComponentAppearanceLighting.class);
        this.components.put("particle_appearance_tinting", ParticleComponentAppearanceTinting.class);

        /* Collision appearance (Blockbuster extension) */
        this.components.put("particle_collision_appearance", ParticleComponentCollisionAppearance.class);
        this.components.put("particle_collision_tinting", ParticleComponentCollisionTinting.class);

        /* Motion & Rotation */
        this.components.put("particle_initial_speed", ParticleComponentInitialSpeed.class);
        this.components.put("particle_initial_spin", ParticleComponentInitialSpin.class);
        this.components.put("particle_motion_collision", ParticleComponentMotionCollision.class);
        this.components.put("particle_motion_dynamic", ParticleComponentMotionDynamic.class);
        this.components.put("particle_motion_parametric", ParticleComponentMotionParametric.class);

        this.refreshApi2Components();
    }

    public synchronized void refreshApi2Components()
    {
        Map<String, PluginParticleComponentClass> addonComponents;

        try
        {
            addonComponents = BBSMod.getAddonParticleComponentClasses();
        }
        catch (Exception | LinkageError e)
        {
            LOGGER.warn("[bbs-particles] failed to query API 2.0 particle component registry", e);

            return;
        }

        /* Evict components whose owner unregistered them (plugin teardown/reload) so
         * the static component table never keeps a stale generation's Class alive. */
        Set<String> known = new HashSet<>(this.registeredApi2ComponentClasses.keySet());
        known.addAll(this.failedApi2ComponentClasses.keySet());

        for (String id : known)
        {
            if (!addonComponents.containsKey(id))
            {
                this.components.remove(id);
                this.registeredApi2ComponentClasses.remove(id);
                this.failedApi2ComponentClasses.remove(id);
                LOGGER.info("[bbs-particles] unregistered API 2.0 particle component '{}'", id);
            }
        }

        for (Map.Entry<String, PluginParticleComponentClass> entry : addonComponents.entrySet())
        {
            String id = entry.getKey();
            PluginParticleComponentClass descriptor = entry.getValue();
            String className = descriptor == null ? null : descriptor.className();

            if (id == null || id.isBlank() || className == null || className.isBlank())
            {
                LOGGER.warn("[bbs-particles] ignored API 2.0 particle component with blank id or class name");

                continue;
            }

            id = id.trim();
            className = className.trim();

            /* The registering generation's ClassLoader is part of the identity: a
             * reload can keep the same class name while replacing the loader, and
             * that must be re-resolved rather than treated as unchanged. */
            PluginParticleComponentClass normalized = new PluginParticleComponentClass(className, descriptor.classLoader());

            if (normalized.equals(this.registeredApi2ComponentClasses.get(id)))
            {
                continue;
            }

            boolean previouslyFailed = normalized.equals(this.failedApi2ComponentClasses.get(id));

            try
            {
                ClassLoader loader = normalized.classLoader();

                /* A null loader is the Addon v2 bridge, which is not classloader-isolated;
                 * resolve it exactly as before, via this class's own loader. */
                Class<?> rawClass = loader == null ? Class.forName(className) : Class.forName(className, true, loader);

                if (!ParticleComponentBase.class.isAssignableFrom(rawClass))
                {
                    if (!previouslyFailed)
                    {
                        LOGGER.warn("[bbs-particles] ignored API 2.0 particle component '{}' because '{}' is not a ParticleComponentBase",
                            id,
                            className);
                    }

                    this.failedApi2ComponentClasses.put(id, normalized);

                    continue;
                }

                this.components.put(id, rawClass.asSubclass(ParticleComponentBase.class));
                this.registeredApi2ComponentClasses.put(id, normalized);
                this.failedApi2ComponentClasses.remove(id);
                LOGGER.info("[bbs-particles] registered API 2.0 particle component '{}' ({})", id, className);
            }
            catch (Exception | LinkageError e)
            {
                this.failedApi2ComponentClasses.put(id, normalized);

                if (!previouslyFailed)
                {
                    LOGGER.warn("[bbs-particles] failed to load API 2.0 particle component '{}' ({})", id, className, e);
                }
            }
        }
    }

    public ParticleScheme fromData(MapType data) throws Exception
    {
        return this.fromData(new ParticleScheme(), data);
    }

    public ParticleScheme fromData(ParticleScheme scheme, MapType data) throws Exception
    {
        this.refreshApi2Components();

        if (!data.isMap())
        {
            throw new Exception("The root element of Bedrock particle should be an object!");
        }

        /* Skip format_version check to avoid breaking semi-compatible particles */
        MapType root = data.asMap();

        try
        {
            scheme.resetForParsing();
            this.parseEffect(scheme, this.getObject(root, "particle_effect", "No particle_effect was found..."));
        }
        catch (MolangException e)
        {
            throw new Exception("Couldn't parse some MoLang expression!", e);
        }

        scheme.setup();

        return scheme;
    }

    private void parseEffect(ParticleScheme scheme, MapType effect) throws Exception
    {
        this.parseDescription(scheme, this.getObject(effect, "description", "No particle_effect.description was found..."));

        if (effect.has("curves"))
        {
            BaseType curves = effect.get("curves");

            if (curves.isMap())
            {
                this.parseCurves(scheme, curves.asMap());
            }
        }

        if (effect.has("events"))
        {
            BaseType events = effect.get("events");

            if (events.isMap())
            {
                this.parseEvents(scheme, events.asMap());
            }
        }

        this.parseComponents(scheme, this.getObject(effect, "components", "No particle_effect.components was found..."));
    }

    /**
     * Parse description object (which contains ID of the particle, material type and texture)
     */
    private void parseDescription(ParticleScheme scheme, MapType description) throws Exception
    {
        if (description.has("identifier"))
        {
            scheme.identifier = description.getString("identifier");
        }

        MapType parameters = this.getObject(description, "basic_render_parameters", "No particle_effect.basic_render_parameters was found...");

        if (parameters.has("material"))
        {
            String materialStr = parameters.getString("material");
            ParticleMaterial parsed = ParticleMaterial.fromString(materialStr);

            if (parsed == ParticleMaterial.OPAQUE && !materialStr.equals("particles_opaque"))
            {
                scheme.customMaterialId = materialStr;
            }
            else
            {
                scheme.material = parsed;
            }
        }

        if (parameters.has("texture"))
        {
            String texture = parameters.getString("texture");

            if (!texture.equals("textures/particle/particles"))
            {
                scheme.texture = Link.create(texture);
            }
            else
            {
                scheme.texture = Link.create("assets:textures/default_particles.png");
            }

            if (scheme.texture.source.equals("b.a") || scheme.texture.source.equals("c.s"))
            {
                scheme.texture = Link.assets(scheme.texture.path);
            }
        }
    }

    /**
     * Parse curves object
     */
    private void parseCurves(ParticleScheme scheme, MapType curves) throws Exception
    {
        for (Map.Entry<String, BaseType> entry : curves)
        {
            BaseType data = entry.getValue();

            if (data.isMap())
            {
                ParticleCurve curve = new ParticleCurve();

                curve.fromData(data.asMap(), scheme.parser);
                scheme.curves.put(entry.getKey(), curve);
            }
        }
    }

    private void parseEvents(ParticleScheme scheme, MapType events) throws Exception
    {
        for (Map.Entry<String, BaseType> entry : events)
        {
            scheme.events.put(entry.getKey(), ParticleEventNode.fromData(entry.getValue(), scheme.parser));
        }
    }

    private void parseComponents(ParticleScheme scheme, MapType components) throws Exception
    {
        for (Map.Entry<String, BaseType> entry : components)
        {
            String key = this.stripKnownPrefix(entry.getKey());

            if (this.components.containsKey(key))
            {
                ParticleComponentBase component = null;

                try
                {
                    component = this.components.get(key).getConstructor().newInstance();
                }
                catch (Exception e)
                {
                    System.err.println("Failed to parse given component " + key + " in " + scheme.identifier + ": " + e.getMessage());
                    continue;
                }

                component.fromData(entry.getValue(), scheme.parser);
                scheme.addComponent(component);
            }
        }
    }

    private String stripKnownPrefix(String key)
    {
        if (key.startsWith(PREFIX))
        {
            return key.substring(PREFIX.length());
        }
        else if (key.startsWith(PREFIX_BLOCKBUSTER))
        {
            return key.substring(PREFIX_BLOCKBUSTER.length());
        }

        return key;
    }

    private MapType getObject(MapType map, String key, String message) throws Exception
    {
        /* Skip format_version check to avoid breaking semi-compatible particles */
        if (!map.has(key, BaseType.TYPE_MAP))
        {
            throw new Exception(message);
        }

        return map.get(key).asMap();
    }

    /**
     * Turn given bedrock scheme into JSON
     */
    public MapType toData(ParticleScheme scheme)
    {
        this.refreshApi2Components();

        MapType data = new MapType();
        MapType effect = new MapType();

        data.putString("format_version", "1.10.0");
        data.put("particle_effect", effect);

        this.addDescription(effect, scheme);
        this.addCurves(effect, scheme);
        this.addEvents(effect, scheme);
        this.addComponents(effect, scheme);

        return data;
    }

    private void addDescription(MapType effect, ParticleScheme scheme)
    {
        MapType desc = new MapType();
        MapType render = new MapType();

        effect.put("description", desc);

        desc.putString("identifier", scheme.identifier);
        desc.put("basic_render_parameters", render);

        render.putString("material", scheme.customMaterialId != null ? scheme.customMaterialId : scheme.material.id);
        render.putString("texture", "textures/particle/particles");

        if (scheme.texture != null && !scheme.texture.equals(ParticleScheme.DEFAULT_TEXTURE))
        {
            render.putString("texture", scheme.texture.toString());
        }
    }

    private void addCurves(MapType effect, ParticleScheme scheme)
    {
        MapType curves = new MapType();

        effect.put("curves", curves);

        for (Map.Entry<String, ParticleCurve> entry : scheme.curves.entrySet())
        {
            curves.put(entry.getKey(), entry.getValue().toData());
        }
    }

    private void addEvents(MapType effect, ParticleScheme scheme)
    {
        if (scheme.events.isEmpty())
        {
            return;
        }

        MapType events = new MapType(false);

        for (Map.Entry<String, ParticleEventNode> entry : scheme.events.entrySet())
        {
            if (entry.getKey() == null || entry.getKey().trim().isEmpty() || entry.getValue() == null)
            {
                continue;
            }

            events.put(entry.getKey(), entry.getValue().toData());
        }

        if (!events.isEmpty())
        {
            effect.put("events", events);
        }
    }

    private Set<String> blockbusterKeys = new HashSet<>(Arrays.asList(
        "particle_collision_appearance",
        "particle_collision_tinting"
    ));

    private void addComponents(MapType effect, ParticleScheme scheme)
    {
        MapType components = new MapType();

        effect.put("components", components);

        main:
        for (ParticleComponentBase component : scheme.components)
        {
            BaseType element = component.toData();

            if (isEmpty(element) && !component.canBeEmpty())
            {
                continue;
            }

            for (Map.Entry<String, Class<? extends ParticleComponentBase>> entry : this.components.entrySet())
            {
                if (entry.getValue().equals(component.getClass()))
                {
                    String prefix = this.blockbusterKeys.contains(entry.getKey()) ? PREFIX_BLOCKBUSTER : PREFIX;
                    components.put(prefix + entry.getKey(), element);

                    continue main;
                }
            }

            System.err.println("Component for class \"" + component.getClass().getSimpleName() + "\" couldn't be saved!");
        }
    }
}
