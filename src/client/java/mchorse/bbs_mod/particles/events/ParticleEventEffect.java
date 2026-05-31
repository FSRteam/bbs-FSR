package mchorse.bbs_mod.particles.events;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.math.molang.MolangParser;
import mchorse.bbs_mod.math.molang.expressions.MolangExpression;

import java.util.LinkedHashMap;
import java.util.Map;

public class ParticleEventEffect
{
    public String effect = "";
    public String type = "emitter";
    public MolangExpression preEffectExpression;

    private final Map<String, BaseType> extra = new LinkedHashMap<>();

    public void fromData(BaseType data, MolangParser parser)
    {
        this.extra.clear();

        if (data == null || !data.isMap())
        {
            return;
        }

        MapType map = data.asMap();

        if (map.has("effect")) this.effect = map.getString("effect");
        if (map.has("type")) this.type = map.getString("type", "emitter");
        if (map.has("pre_effect_expression")) this.preEffectExpression = parser.parseDataSilently(map.get("pre_effect_expression"));

        for (Map.Entry<String, BaseType> entry : map)
        {
            if (!entry.getKey().equals("effect") && !entry.getKey().equals("type") && !entry.getKey().equals("pre_effect_expression"))
            {
                this.extra.put(entry.getKey(), entry.getValue().copy());
            }
        }
    }

    public BaseType toData()
    {
        MapType map = new MapType(false);

        for (Map.Entry<String, BaseType> entry : this.extra.entrySet())
        {
            map.put(entry.getKey(), entry.getValue().copy());
        }

        if (this.effect != null && !this.effect.isEmpty()) map.putString("effect", this.effect);
        if (this.type != null && !this.type.isEmpty()) map.putString("type", this.type);
        if (this.preEffectExpression != null) map.put("pre_effect_expression", this.preEffectExpression.toData());

        return map;
    }
}
