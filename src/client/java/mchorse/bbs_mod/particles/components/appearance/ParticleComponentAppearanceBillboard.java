package mchorse.bbs_mod.particles.components.appearance;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.math.molang.MolangException;
import mchorse.bbs_mod.math.molang.MolangParser;
import mchorse.bbs_mod.math.molang.expressions.MolangExpression;
import mchorse.bbs_mod.particles.components.IComponentParticleRender;
import mchorse.bbs_mod.particles.components.ParticleComponentBase;
import mchorse.bbs_mod.particles.emitter.Particle;
import mchorse.bbs_mod.particles.emitter.ParticleEmitter;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.interps.Lerps;
import mchorse.bbs_mod.utils.joml.Matrices;
import mchorse.bbs_mod.utils.joml.Vectors;
import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.core.BlockPos;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector4f;

public class ParticleComponentAppearanceBillboard extends ParticleComponentBase implements IComponentParticleRender
{
    /* Options */
    public MolangExpression sizeW = MolangParser.ZERO;
    public MolangExpression sizeH = MolangParser.ZERO;
    public CameraFacing facing = CameraFacing.LOOKAT_XYZ;
    public int textureWidth = 128;
    public int textureHeight = 128;
    public MolangExpression uvX = MolangParser.ZERO;
    public MolangExpression uvY = MolangParser.ZERO;
    public MolangExpression uvW = MolangParser.ZERO;
    public MolangExpression uvH = MolangParser.ZERO;

    public boolean flipbook = false;
    public float stepX;
    public float stepY;
    public float fps;
    public MolangExpression maxFrame = MolangParser.ZERO;
    public boolean stretchFPS = false;
    public boolean loop = false;

    /* Billboard direction sub-feature */
    public String directionMode = null; /* null = not set, "derive_from_velocity" or "custom" */
    public float speedThreshold = 0.01F;
    public MolangExpression[] customDirection = null; /* 3-element array: x, y, z */

    /* Full texture UV mode */
    public boolean fullTexture = false;

    /* Runtime properties */
    private float w;
    private float h;

    private float u1;
    private float v1;
    private float u2;
    private float v2;

    private int light;

    private Matrix4f transform = new Matrix4f();
    private Matrix4f rotation = new Matrix4f();
    private Matrix4f emitterMatrix = new Matrix4f();
    private Vector4f[] vertices = new Vector4f[] {
        new Vector4f(0, 0, 0, 1),
        new Vector4f(0, 0, 0, 1),
        new Vector4f(0, 0, 0, 1),
        new Vector4f(0, 0, 0, 1)
    };
    private Vector3f vector = new Vector3f();
    private Vector3f translation = new Vector3f();
    private Vector3f n = new Vector3f();
    private Vector3f direction = new Vector3f();
    private Vector3f cameraDirection = new Vector3f();
    private Vector3f projectedDirection = new Vector3f();
    private Vector3f rotatedNormal = new Vector3f();
    private Vector3f rotationDirection = new Vector3f();
    private final BlockPos.MutableBlockPos lightBlockPos = new BlockPos.MutableBlockPos();

    public ParticleComponentAppearanceBillboard()
    {}

    @Override
    protected void toData(MapType data)
    {
        ListType size = new ListType();

        size.add(this.sizeW.toData());
        size.add(this.sizeH.toData());

        data.put("size", size);
        data.putString("facing_camera_mode", this.facing.id);

        /* Billboard direction sub-feature */
        if (this.directionMode != null)
        {
            MapType direction = new MapType();

            direction.putString("mode", this.directionMode);

            if ("derive_from_velocity".equals(this.directionMode))
            {
                direction.putFloat("min_speed_threshold", this.speedThreshold);
            }
            else if ("custom".equals(this.directionMode) && this.customDirection != null)
            {
                ListType customDir = new ListType();
                customDir.add(this.customDirection[0].toData());
                customDir.add(this.customDirection[1].toData());
                customDir.add(this.customDirection[2].toData());
                direction.put("custom_direction", customDir);
            }

            data.put("direction", direction);
        }

        /* UV data — omit if fullTexture mode */
        if (!this.fullTexture)
        {
            MapType uv = new MapType();

            uv.putInt("texture_width", this.textureWidth);
            uv.putInt("texture_height", this.textureHeight);

            if (!this.flipbook && !MolangExpression.isZero(this.uvX) || !MolangExpression.isZero(this.uvY))
            {
                ListType uvs = new ListType();

                uvs.add(this.uvX.toData());
                uvs.add(this.uvY.toData());

                uv.put("uv", uvs);
            }

            if (!this.flipbook && !MolangExpression.isZero(this.uvW) || !MolangExpression.isZero(this.uvH))
            {
                ListType uvs = new ListType();

                uvs.add(this.uvW.toData());
                uvs.add(this.uvH.toData());

                uv.put("uv_size", uvs);
            }

            /* Adding "flipbook" properties to "uv" */
            if (this.flipbook)
            {
                MapType flipbook = new MapType();

                if (!MolangExpression.isZero(this.uvX) || !MolangExpression.isZero(this.uvY))
                {
                    ListType base = new ListType();

                    base.add(this.uvX.toData());
                    base.add(this.uvY.toData());

                    flipbook.put("base_UV", base);
                }

                if (!MolangExpression.isZero(this.uvW) || !MolangExpression.isZero(this.uvH))
                {
                    ListType uvSize = new ListType();

                    uvSize.add(this.uvW.toData());
                    uvSize.add(this.uvH.toData());

                    flipbook.put("size_UV", uvSize);
                }

                if (this.stepX != 0 || this.stepY != 0)
                {
                    ListType step = new ListType();

                    step.addFloat(this.stepX);
                    step.addFloat(this.stepY);

                    flipbook.put("step_UV", step);
                }

                if (this.fps != 0) flipbook.putFloat("frames_per_second", this.fps);
                if (!MolangExpression.isZero(this.maxFrame)) flipbook.put("max_frame", this.maxFrame.toData());
                if (this.stretchFPS) flipbook.putBool("stretch_to_lifetime", true);
                if (this.loop) flipbook.putBool("loop", true);

                uv.put("flipbook", flipbook);
            }

            data.put("uv", uv);
        }
    }

    @Override
    public ParticleComponentBase fromData(BaseType data, MolangParser parser) throws MolangException
    {
        if (!data.isMap())
        {
            return super.fromData(data, parser);
        }

        MapType map = data.asMap();

        if (map.has("size", BaseType.TYPE_LIST))
        {
            ListType size = map.getList("size");

            if (size.size() >= 2)
            {
                this.sizeW = parser.parseDataSilently(size.get(0), MolangParser.ONE);
                this.sizeH = parser.parseDataSilently(size.get(1), MolangParser.ONE);
            }
        }

        if (map.has("facing_camera_mode"))
        {
            this.facing = CameraFacing.fromString(map.getString("facing_camera_mode"));
        }

        /* Parse direction sub-feature */
        if (map.has("direction", BaseType.TYPE_MAP))
        {
            MapType direction = map.getMap("direction");

            if (direction.has("mode"))
            {
                this.directionMode = direction.getString("mode");

                if ("derive_from_velocity".equals(this.directionMode) && direction.has("min_speed_threshold"))
                {
                    this.speedThreshold = direction.getFloat("min_speed_threshold");
                }
                else if ("custom".equals(this.directionMode) && direction.has("custom_direction", BaseType.TYPE_LIST))
                {
                    ListType customDir = direction.getList("custom_direction");

                    if (customDir.size() >= 3)
                    {
                        this.customDirection = new MolangExpression[]{
                            parser.parseDataSilently(customDir.get(0)),
                            parser.parseDataSilently(customDir.get(1), MolangParser.ONE),
                            parser.parseDataSilently(customDir.get(2))
                        };
                    }
                }
            }
        }

        /* Detect fullTexture mode: no "uv" key means full texture */
        if (map.has("uv", BaseType.TYPE_MAP))
        {
            this.parseUv(map.getMap("uv"), parser);
        }
        else if (!map.has("uv"))
        {
            this.fullTexture = true;
        }

        return super.fromData(map, parser);
    }

    private void parseUv(MapType data, MolangParser parser) throws MolangException
    {
        if (data.has("texture_width")) this.textureWidth = data.getInt("texture_width");
        if (data.has("texture_height")) this.textureHeight = data.getInt("texture_height");

        if (data.has("uv", BaseType.TYPE_LIST))
        {
            ListType uv = data.getList("uv");

            if (uv.size() >= 2)
            {
                this.uvX = parser.parseDataSilently(uv.get(0));
                this.uvY = parser.parseDataSilently(uv.get(1));
            }
        }

        if (data.has("uv_size", BaseType.TYPE_LIST))
        {
            ListType uv = data.getList("uv_size");

            if (uv.size() >= 2)
            {
                this.uvW = parser.parseDataSilently(uv.get(0), MolangParser.ONE);
                this.uvH = parser.parseDataSilently(uv.get(1), MolangParser.ONE);
            }
        }

        if (data.has("flipbook", BaseType.TYPE_MAP))
        {
            this.flipbook = true;

            this.parseFlipbook(data.getMap("flipbook"), parser);
        }
    }

    private void parseFlipbook(MapType flipbook, MolangParser parser) throws MolangException
    {
        if (flipbook.has("base_UV", BaseType.TYPE_LIST))
        {
            ListType uv = flipbook.getList("base_UV");

            if (uv.size() >= 2)
            {
                this.uvX = parser.parseDataSilently(uv.get(0));
                this.uvY = parser.parseDataSilently(uv.get(1));
            }
        }

        if (flipbook.has("size_UV", BaseType.TYPE_LIST))
        {
            ListType uv = flipbook.getList("size_UV");

            if (uv.size() >= 2)
            {
                this.uvW = parser.parseDataSilently(uv.get(0));
                this.uvH = parser.parseDataSilently(uv.get(1));
            }
        }

        if (flipbook.has("step_UV", BaseType.TYPE_LIST))
        {
            ListType uv = flipbook.getList("step_UV");

            if (uv.size() >= 2)
            {
                this.stepX = uv.getFloat(0);
                this.stepY = uv.getFloat(1);
            }
        }

        if (flipbook.has("frames_per_second")) this.fps = flipbook.getFloat("frames_per_second");
        if (flipbook.has("max_frame")) this.maxFrame = parser.parseDataSilently(flipbook.get("max_frame"));
        if (flipbook.has("stretch_to_lifetime")) this.stretchFPS = flipbook.getBool("stretch_to_lifetime");
        if (flipbook.has("loop")) this.loop = flipbook.getBool("loop");
    }

    @Override
    public void preRender(ParticleEmitter emitter, float transition)
    {}

    @Override
    public void render(ParticleEmitter emitter, VertexFormat format, Particle particle, BufferBuilder builder, Matrix4f matrix, int overlay, float transition)
    {
        this.calculateUVs(particle, emitter, transition);

        /* Render the particle */
        double px = Lerps.lerp(particle.prevPosition.x, particle.position.x, transition);
        double py = Lerps.lerp(particle.prevPosition.y, particle.position.y, transition);
        double pz = Lerps.lerp(particle.prevPosition.z, particle.position.z, transition);
        float angle = Lerps.lerp(particle.prevRotation, particle.rotation, transition);
        float scale = 1F;
        boolean staticSpace = particle.relativePosition && particle.relativeRotation;

        if (staticSpace)
        {
            this.vector.set((float) px, (float) py, (float) pz);
            emitter.rotation.transform(this.vector);

            px = this.vector.x;
            py = this.vector.y;
            pz = this.vector.z;

            px += emitter.lastGlobal.x;
            py += emitter.lastGlobal.y;
            pz += emitter.lastGlobal.z;
        }

        if (particle.textureScale)
        {
            scale = (staticSpace ? emitter.rotation : particle.matrix).getRow(0, Vectors.TEMP_3F).length();
        }

        /* Calculate yaw and pitch based on the facing mode */
        float entityYaw = emitter.cYaw;
        float entityPitch = emitter.cPitch;
        double entityX = emitter.cX;
        double entityY = emitter.cY;
        double entityZ = emitter.cZ;
        boolean lookAt = this.facing.isLookAt && !this.facing.isDirection;

        if (emitter.perspective == ParticleEmitter.PERSPECTIVE_THIRD_PERSON_FRONT)
        {
            this.w = -this.w;
        }
        else if (emitter.perspective == ParticleEmitter.PERSPECTIVE_GUI && !this.facing.isLookAt)
        {
            entityYaw = 180F - entityYaw;
            this.w = -this.w;
            this.h = -this.h;
        }

        if (lookAt)
        {
            double dX = entityX - px;
            double dY = entityY - py;
            double dZ = entityZ - pz;
            double hDist = Math.sqrt(dX * dX + dZ * dZ);

            entityYaw = 180 - (float) (Math.atan2(dZ, dX) * (180D / Math.PI)) - 90.0F;
            entityPitch = (float) (-(Math.atan2(dY, hDist) * (180D / Math.PI))) + 180;
        }

        if (this.facing.isDirection)
        {
            this.resolveDirection(particle);
        }

        double particleX = px;
        double particleY = py;
        double particleZ = pz;

        px -= emitter.cX;
        py -= emitter.cY;
        pz -= emitter.cZ;

        /* Calculate the geometry for billboards using cool matrix math */
        this.vertices[0].set(-this.w / 2, -this.h / 2, 0, 1);
        this.vertices[1].set(this.w / 2, -this.h / 2, 0, 1);
        this.vertices[2].set(this.w / 2, this.h / 2, 0, 1);
        this.vertices[3].set(-this.w / 2, this.h / 2, 0, 1);
        this.transform.identity();

        if (this.facing == CameraFacing.ROTATE_XYZ || this.facing == CameraFacing.LOOKAT_XYZ)
        {
            this.rotation.identity();
            this.rotation.rotateY(entityYaw / 180 * (float) Math.PI);
            this.transform.mul(this.rotation);
            this.rotation.identity();
            this.rotation.rotateX(entityPitch / 180 * (float) Math.PI);
            this.transform.mul(this.rotation);
        }
        else if (this.facing == CameraFacing.ROTATE_Y || this.facing == CameraFacing.LOOKAT_Y)
        {
            this.rotation.identity();
            this.rotation.rotateY(entityYaw / 180 * (float) Math.PI);
            this.transform.mul(this.rotation);
        }
        else if (this.facing == CameraFacing.DIRECTION_X)
        {
            this.rotation.identity();
            this.rotation.rotateY(this.getDirectionYaw() / 180 * (float) Math.PI);
            this.transform.mul(this.rotation);
            this.rotation.identity();
            this.rotation.rotateX(this.getDirectionPitch() / 180 * (float) Math.PI);
            this.transform.mul(this.rotation);
            this.rotation.identity();
            this.rotation.rotateY((float) Math.PI / 2);
            this.transform.mul(this.rotation);
        }
        else if (this.facing == CameraFacing.DIRECTION_Y)
        {
            this.rotation.identity();
            this.rotation.rotateY(this.getDirectionYaw() / 180 * (float) Math.PI);
            this.transform.mul(this.rotation);
            this.rotation.identity();
            this.rotation.rotateX((this.getDirectionPitch() + 90F) / 180 * (float) Math.PI);
            this.transform.mul(this.rotation);
        }
        else if (this.facing == CameraFacing.DIRECTION_Z)
        {
            this.rotation.identity();
            this.rotation.rotateY(this.getDirectionYaw() / 180 * (float) Math.PI);
            this.transform.mul(this.rotation);
            this.rotation.identity();
            this.rotation.rotateX(this.getDirectionPitch() / 180 * (float) Math.PI);
            this.transform.mul(this.rotation);
        }
        else if (this.facing == CameraFacing.LOOKAT_DIRECTION)
        {
            this.rotation.identity();
            this.rotation.rotateY(this.getDirectionYaw() / 180 * (float) Math.PI);
            this.transform.mul(this.rotation);
            this.rotation.identity();
            this.rotation.rotateX((this.getDirectionPitch() + 90F) / 180 * (float) Math.PI);
            this.transform.mul(this.rotation);

            this.cameraDirection.set((float) (entityX - particleX), (float) (entityY - particleY), (float) (entityZ - particleZ));
            this.rotatedNormal.set(0F, 0F, 1F);
            Matrices.EMPTY_3F.set(this.transform).transform(this.rotatedNormal);

            this.projectedDirection.set(this.direction).mul(this.cameraDirection.dot(this.direction));
            this.cameraDirection.sub(this.projectedDirection);

            if (this.cameraDirection.lengthSquared() >= 1.0e-30F)
            {
                this.cameraDirection.normalize();
                this.rotationDirection.set(this.cameraDirection).cross(this.rotatedNormal);

                this.rotation.identity();
                this.rotation.rotateY(-Math.copySign(this.cameraDirection.angle(this.rotatedNormal), this.rotationDirection.dot(this.direction)));
                this.transform.mul(this.rotation);
            }
        }
        else if (this.facing == CameraFacing.EMITTER_TRANSFORM_XY || this.facing == CameraFacing.EMITTER_TRANSFORM_XZ || this.facing == CameraFacing.EMITTER_TRANSFORM_YZ)
        {
            /* Emitter transform: billboard oriented in the specified plane of the emitter's local frame.
             * Snowstorm implementation: default billboard is in XY plane (facing +Z),
             * then rotated to the target plane:
             *   XY: no rotation (default)
             *   XZ: rotate -90 degrees around X axis
             *   YZ: rotate +90 degrees around Y axis
             * The emitter rotation is then applied on top. */
            this.rotation.identity();

            if (this.facing == CameraFacing.EMITTER_TRANSFORM_XZ)
            {
                this.rotation.rotateX((float) (-Math.PI / 2));
            }
            else if (this.facing == CameraFacing.EMITTER_TRANSFORM_YZ)
            {
                this.rotation.rotateY((float) (Math.PI / 2));
            }

            /* Apply emitter rotation to the base orientation */
            Matrix4f emitterMatrix = this.emitterMatrix.set(emitter.rotation);
            this.transform.mul(emitterMatrix).mul(this.rotation);
        }

        if (format != DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR)
        {
            this.n.set(0F, 0F, 1F);

            Matrices.EMPTY_3F.set(this.transform).transform(this.n);
            this.n.normalize();
        }

        this.rotation.identity();
        this.rotation.rotateZ(angle / 180 * (float) Math.PI);
        this.transform.mul(this.rotation);

        this.transform.scale(scale);
        this.transform.setTranslation(this.translation.set((float) px, (float) py, (float) pz));

        this.build(builder, format, matrix, particle, overlay);
    }

    private void resolveDirection(Particle particle)
    {
        if ("custom".equals(this.directionMode) && this.customDirection != null)
        {
            this.direction.set((float) this.customDirection[0].get(), (float) this.customDirection[1].get(), (float) this.customDirection[2].get());
        }
        else if (particle.speed.lengthSquared() > this.speedThreshold * this.speedThreshold)
        {
            this.direction.set(particle.speed).normalize();
        }
        else
        {
            this.direction.set(1F, 0F, 0F);
        }

        float lengthSquared = this.direction.lengthSquared();

        if (lengthSquared < 0.0001F)
        {
            this.direction.set(1F, 0F, 0F);
        }
        else if (Math.abs(lengthSquared - 1F) > 0.0001F)
        {
            this.direction.normalize();
        }
    }

    private float getDirectionYaw()
    {
        double yaw = Math.atan2(-this.direction.x, this.direction.z);

        yaw = Math.toDegrees(yaw);

        if (yaw < -180)
        {
            yaw += 360;
        }
        else if (yaw > 180)
        {
            yaw -= 360;
        }

        return (float) -yaw;
    }

    private float getDirectionPitch()
    {
        double pitch = Math.atan2(this.direction.y, Math.sqrt(this.direction.x * this.direction.x + this.direction.z * this.direction.z));

        return (float) -Math.toDegrees(pitch);
    }

    private void build(BufferBuilder builder, VertexFormat format, Matrix4f matrix, Particle particle, int overlay)
    {
        float u1 = this.u1 / (float) this.textureWidth;
        float u2 = this.u2 / (float) this.textureWidth;
        float v1 = this.v1 / (float) this.textureHeight;
        float v2 = this.v2 / (float) this.textureHeight;

        for (Vector4f vertex : this.vertices)
        {
            this.transform.transform(vertex);
        }

        this.writeVertex(builder, format, matrix, this.vertices[0], u2, v2, overlay, particle);
        this.writeVertex(builder, format, matrix, this.vertices[1], u1, v2, overlay, particle);
        this.writeVertex(builder, format, matrix, this.vertices[2], u1, v1, overlay, particle);
        this.writeVertex(builder, format, matrix, this.vertices[2], u1, v1, overlay, particle);
        this.writeVertex(builder, format, matrix, this.vertices[3], u2, v1, overlay, particle);
        this.writeVertex(builder, format, matrix, this.vertices[0], u2, v2, overlay, particle);
    }

    private void writeVertex(BufferBuilder builder, VertexFormat format, Matrix4f matrix, Vector4f vertex, float u, float v, int overlay, Particle particle)
    {
        if (format == DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR)
        {
            /* DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR */
            builder.addVertex(matrix, vertex.x, vertex.y, vertex.z)
                .setUv(u, v)
                .setLight(this.light)
                .setColor(particle.r, particle.g, particle.b, particle.a);
        }
        else
        {
            /* DefaultVertexFormat.NEW_ENTITY */
            builder.addVertex(matrix, vertex.x, vertex.y, vertex.z)
                .setColor(particle.r, particle.g, particle.b, particle.a)
                .setUv(u, v)
                .setOverlay(overlay)
                .setLight(this.light)
                .setNormal(this.n.x, this.n.y, this.n.z);
        }
    }

    @Override
    public void renderUI(Particle particle, BufferBuilder builder, Matrix4f matrix, float transition)
    {
        this.calculateUVs(particle, null, transition);

        this.w = this.h = 0.5F;
        float angle = Lerps.lerp(particle.prevRotation, particle.rotation, transition);

        /* Calculate the geometry for billboards using cool matrix math */
        this.vertices[0].set(-this.w / 2, -this.h / 2, 0, 1);
        this.vertices[1].set(this.w / 2, -this.h / 2, 0, 1);
        this.vertices[2].set(this.w / 2, this.h / 2, 0, 1);
        this.vertices[3].set(-this.w / 2, this.h / 2, 0, 1);
        this.transform.identity();
        this.transform.scale(2.5F);

        this.rotation.identity();
        this.rotation.rotateZ(angle / 180 * (float) Math.PI);
        this.transform.mul(this.rotation);

        this.buildUI(builder, matrix, particle);
    }

    private void buildUI(BufferBuilder builder, Matrix4f matrix, Particle particle)
    {
        float u1 = this.u1 / (float) this.textureWidth;
        float u2 = this.u2 / (float) this.textureWidth;
        float v1 = this.v1 / (float) this.textureHeight;
        float v2 = this.v2 / (float) this.textureHeight;

        for (Vector4f vertex : this.vertices)
        {
            this.transform.transform(vertex);
        }

        this.writeVertexUI(builder, matrix, this.vertices[2], u2, v2, particle);
        this.writeVertexUI(builder, matrix, this.vertices[1], u2, v1, particle);
        this.writeVertexUI(builder, matrix, this.vertices[0], u1, v1, particle);
        this.writeVertexUI(builder, matrix, this.vertices[0], u1, v1, particle);
        this.writeVertexUI(builder, matrix, this.vertices[3], u1, v2, particle);
        this.writeVertexUI(builder, matrix, this.vertices[2], u2, v2, particle);
    }

    private void writeVertexUI(BufferBuilder builder, Matrix4f matrix, Vector4f vertex, float u, float v, Particle particle)
    {
        builder.addVertex(matrix, vertex.x, vertex.y, 0F)
            .setUv(u, v)
            .setColor(particle.r, particle.g, particle.b, particle.a);
    }

    public void calculateUVs(Particle particle, ParticleEmitter emitter, float transition)
    {
        /* Update particle's UVs and size */
        this.w = (float) this.sizeW.get() * 2.25F;
        this.h = (float) this.sizeH.get() * 2.25F;

        if (this.fullTexture)
        {
            this.u1 = 0;
            this.v1 = 0;
            this.u2 = this.textureWidth;
            this.v2 = this.textureHeight;
        }
        else
        {
            float u = (float) this.uvX.get();
            float v = (float) this.uvY.get();
            float w = (float) this.uvW.get();
            float h = (float) this.uvH.get();

            if (this.flipbook)
            {
                int index = (int) (particle.getAge(transition) * this.fps);
                int max = (int) this.maxFrame.get();

                if (this.stretchFPS)
                {
                    float lifetime = particle.lifetime <= 0 ? 0 : (particle.age + transition) / particle.lifetime;

                    index = MathUtils.clamp((int) (lifetime * max), 0, max - 1);
                }

                if (this.loop && max != 0)
                {
                    index = index % max;
                }

                if (index > max)
                {
                    index = max;
                }

                u += this.stepX * index;
                v += this.stepY * index;
            }

            this.u1 = u;
            this.v1 = v;
            this.u2 = u + w;
            this.v2 = v + h;
        }

        if (emitter == null || emitter.lit || emitter.world == null)
        {
            this.light = LightTexture.pack(15, 15);
        }
        else
        {
            Vector3d pos = particle.getGlobalPosition(emitter);

            this.light = LevelRenderer.getLightColor(emitter.world, this.lightBlockPos.set(pos.x, pos.y, pos.z));
        }
    }

    @Override
    public void postRender(ParticleEmitter emitter, float transition)
    {}
}
