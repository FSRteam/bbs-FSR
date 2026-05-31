package mchorse.bbs_mod.particles.events;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.math.molang.MolangParser;
import mchorse.bbs_mod.math.molang.expressions.MolangExpression;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ParticleEventNode
{
    public final List<ParticleEventNode> sequence = new ArrayList<>();
    public final List<ParticleEventNode> randomize = new ArrayList<>();

    public float weight = 1F;
    public ParticleEventEffect particleEffect;
    public String soundEvent = "";
    public MolangExpression expression;
    public String log = "";

    private final Map<String, BaseType> extra = new LinkedHashMap<>();
    private final Map<String, BaseType> soundExtra = new LinkedHashMap<>();
    private BaseType raw;

    public static ParticleEventNode fromData(BaseType data, MolangParser parser)
    {
        ParticleEventNode node = new ParticleEventNode();

        node.read(data, parser);

        return node;
    }

    public void read(BaseType data, MolangParser parser)
    {
        this.sequence.clear();
        this.randomize.clear();
        this.extra.clear();
        this.soundExtra.clear();
        this.raw = null;

        if (data == null)
        {
            return;
        }

        if (!data.isMap())
        {
            this.raw = data.copy();

            return;
        }

        MapType map = data.asMap();

        if (map.has("weight") && map.get("weight").isNumeric()) this.weight = map.get("weight").asNumeric().floatValue();
        if (map.has("sequence") && map.get("sequence").isList())
        {
            for (BaseType element : map.get("sequence").asList())
            {
                this.sequence.add(ParticleEventNode.fromData(element, parser));
            }
        }
        if (map.has("randomize") && map.get("randomize").isList())
        {
            for (BaseType element : map.get("randomize").asList())
            {
                this.randomize.add(ParticleEventNode.fromData(element, parser));
            }
        }
        if (map.has("particle_effect"))
        {
            this.particleEffect = new ParticleEventEffect();
            this.particleEffect.fromData(map.get("particle_effect"), parser);
        }
        if (map.has("sound_effect") && map.get("sound_effect").isMap())
        {
            MapType sound = map.get("sound_effect").asMap();

            if (sound.has("event_name")) this.soundEvent = sound.getString("event_name");

            for (Map.Entry<String, BaseType> entry : sound)
            {
                if (!entry.getKey().equals("event_name"))
                {
                    this.soundExtra.put(entry.getKey(), entry.getValue().copy());
                }
            }
        }
        if (map.has("expression")) this.expression = parser.parseDataSilently(map.get("expression"));
        if (map.has("log")) this.log = map.getString("log");

        for (Map.Entry<String, BaseType> entry : map)
        {
            String key = entry.getKey();

            if (!key.equals("weight") && !key.equals("sequence") && !key.equals("randomize") && !key.equals("particle_effect") && !key.equals("sound_effect") && !key.equals("expression") && !key.equals("log"))
            {
                this.extra.put(key, entry.getValue().copy());
            }
        }
    }

    public BaseType toData()
    {
        if (this.raw != null)
        {
            return this.raw.copy();
        }

        MapType map = new MapType(false);

        for (Map.Entry<String, BaseType> entry : this.extra.entrySet())
        {
            map.put(entry.getKey(), entry.getValue().copy());
        }

        if (this.weight != 1F) map.putFloat("weight", this.weight);

        if (!this.sequence.isEmpty())
        {
            mchorse.bbs_mod.data.types.ListType list = new mchorse.bbs_mod.data.types.ListType();

            for (ParticleEventNode node : this.sequence)
            {
                list.add(node.toData());
            }

            map.put("sequence", list);
        }

        if (!this.randomize.isEmpty())
        {
            mchorse.bbs_mod.data.types.ListType list = new mchorse.bbs_mod.data.types.ListType();

            for (ParticleEventNode node : this.randomize)
            {
                list.add(node.toData());
            }

            map.put("randomize", list);
        }

        if (this.particleEffect != null) map.put("particle_effect", this.particleEffect.toData());

        if ((this.soundEvent != null && !this.soundEvent.isEmpty()) || !this.soundExtra.isEmpty())
        {
            MapType sound = new MapType(false);

            for (Map.Entry<String, BaseType> entry : this.soundExtra.entrySet())
            {
                sound.put(entry.getKey(), entry.getValue().copy());
            }

            if (this.soundEvent != null && !this.soundEvent.isEmpty()) sound.putString("event_name", this.soundEvent);

            map.put("sound_effect", sound);
        }

        if (this.expression != null) map.put("expression", this.expression.toData());
        if (this.log != null && !this.log.isEmpty()) map.putString("log", this.log);

        return map;
    }

    public boolean isEmpty()
    {
        return this.raw == null
            && this.sequence.isEmpty()
            && this.randomize.isEmpty()
            && this.particleEffect == null
            && (this.soundEvent == null || this.soundEvent.isEmpty())
            && this.expression == null
            && (this.log == null || this.log.isEmpty())
            && this.extra.isEmpty()
            && this.soundExtra.isEmpty();
    }

    public void clearKnownPayload()
    {
        this.raw = null;
        this.sequence.clear();
        this.randomize.clear();
        this.particleEffect = null;
        this.soundEvent = "";
        this.expression = null;
        this.log = "";
    }
}
