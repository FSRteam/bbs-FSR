package mchorse.bbs_mod.particles.components.expiration;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.math.Operation;
import mchorse.bbs_mod.math.molang.MolangException;
import mchorse.bbs_mod.math.molang.MolangParser;
import mchorse.bbs_mod.particles.components.IComponentParticleUpdate;
import mchorse.bbs_mod.particles.components.ParticleComponentBase;
import mchorse.bbs_mod.particles.emitter.Particle;
import mchorse.bbs_mod.particles.emitter.ParticleEmitter;

public class ParticleComponentKillPlane extends ParticleComponentBase implements IComponentParticleUpdate
{
    public float a;
    public float b;
    public float c;
    public float d;

    @Override
    public BaseType toData()
    {
        ListType list = new ListType();

        if (Operation.equals(this.a, 0) && Operation.equals(this.b, 0) && Operation.equals(this.c, 0) && Operation.equals(this.d, 0))
        {
            return list;
        }

        list.addFloat(this.a);
        list.addFloat(this.b);
        list.addFloat(this.c);
        list.addFloat(this.d);

        return list;
    }

    @Override
    public ParticleComponentBase fromData(BaseType data, MolangParser parser) throws MolangException
    {
        if (!data.isList())
        {
            return super.fromData(data, parser);
        }

        ListType list = data.asList();

        if (list.size() >= 4)
        {
            this.a = list.getFloat(0);
            this.b = list.getFloat(1);
            this.c = list.getFloat(2);
            this.d = list.getFloat(3);
        }

        return super.fromData(data, parser);
    }

    @Override
    public void update(ParticleEmitter emitter, Particle particle)
    {
        if (particle.isDead())
        {
            return;
        }

        double prevX = particle.prevPosition.x;
        double prevY = particle.prevPosition.y;
        double prevZ = particle.prevPosition.z;
        double x = particle.position.x;
        double y = particle.position.y;
        double z = particle.position.z;

        if (!particle.relativePosition)
        {
            prevX -= emitter.lastGlobal.x;
            prevY -= emitter.lastGlobal.y;
            prevZ -= emitter.lastGlobal.z;
            x -= emitter.lastGlobal.x;
            y -= emitter.lastGlobal.y;
            z -= emitter.lastGlobal.z;
        }

        double prev = this.a * prevX + this.b * prevY + this.c * prevZ + this.d;
        double now = this.a * x + this.b * y + this.c * z + this.d;

        if ((prev > 0 && now < 0) || (prev < 0 && now > 0))
        {
            particle.setDead();
        }
    }

    @Override
    public int getSortingIndex()
    {
        return 100;
    }
}
