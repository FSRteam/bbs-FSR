package mchorse.bbs_mod.cubic.render.vanilla;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Subdivides and skins vanilla armor quads around a bending joint.
 *
 * <p>A vanilla armor cube normally has only the four corner vertices on each side. Merely
 * blending those corners produces one flat diagonal face, not a bend. Linear matrix skinning
 * also shrinks the cross-section at large angles. This consumer therefore splits vertical
 * faces into roughly one-pixel strips and interpolates a rigid rotation around the recovered
 * joint axis. The result follows an arc without collapsing inward.</p>
 */
public class SkinningVertexConsumer implements VertexConsumer
{
    private static final float PIXELS_PER_BLOCK = 16F;
    private static final float EPSILON = 0.00001F;
    private static final int MAX_SUBDIVISIONS = 16;

    private VertexConsumer target;
    private final Matrix4f upper = new Matrix4f();
    private final Matrix4f lower = new Matrix4f();
    private final Matrix4f relative = new Matrix4f();
    private final Matrix3f upperNormalMatrix = new Matrix3f();
    private final Matrix3f lowerNormalMatrix = new Matrix3f();
    private final Matrix3f relativeLinear = new Matrix3f();
    private final Matrix3f relativeRotationMatrix = new Matrix3f();
    private final Matrix3f pivotSolver = new Matrix3f();
    private final Matrix3f axisOuter = new Matrix3f();
    private final Quaternionf relativeRotation = new Quaternionf();
    private final Quaternionf partialRotation = new Quaternionf();
    private final Vector3f relativeScale = new Vector3f();
    private final Vector3f relativeTranslation = new Vector3f();
    private final Vector3f rotationAxis = new Vector3f(0F, 1F, 0F);
    private final Vector3f jointPivot = new Vector3f();
    private float axialTranslation;
    private boolean rigidRelative;
    private float bendStart;
    private float bendEnd;

    private final ArmorVertex[] quad = {
        new ArmorVertex(), new ArmorVertex(), new ArmorVertex(), new ArmorVertex()
    };
    private int quadVertexCount;

    private final Vector3f upperPosition = new Vector3f();
    private final Vector3f lowerPosition = new Vector3f();
    private final Vector3f outputPosition = new Vector3f();
    private final Vector3f upperNormal = new Vector3f();
    private final Vector3f lowerNormal = new Vector3f();

    public SkinningVertexConsumer setup(VertexConsumer target, Matrix4f upper, Matrix4f lower,
        float bendStart, float bendEnd)
    {
        this.target = target;
        this.upper.set(upper);
        this.lower.set(lower);
        this.bendStart = bendStart;
        this.bendEnd = bendEnd;
        this.quadVertexCount = 0;

        this.setupNormalMatrix(this.upperNormalMatrix, upper);
        this.setupNormalMatrix(this.lowerNormalMatrix, lower);
        this.setupRelativeTransform();

        return this;
    }

    public void clear()
    {
        this.flushPending();
        this.target = null;
    }

    private void setupRelativeTransform()
    {
        this.relative.set(this.upper).invert().mul(this.lower);
        this.relative.getScale(this.relativeScale);
        this.relative.getUnnormalizedRotation(this.relativeRotation).normalize();
        this.relative.getTranslation(this.relativeTranslation);
        this.relativeLinear.set(this.relative);
        this.relativeRotationMatrix.set(this.relativeRotation);

        if (this.relativeRotation.w < 0F)
        {
            this.relativeRotation.set(
                -this.relativeRotation.x,
                -this.relativeRotation.y,
                -this.relativeRotation.z,
                -this.relativeRotation.w
            );
        }

        float sinHalf = (float) Math.sqrt(Math.max(0F, 1F - this.relativeRotation.w * this.relativeRotation.w));

        this.rigidRelative = Math.abs(this.relativeScale.x - 1F) < 0.001F
            && Math.abs(this.relativeScale.y - 1F) < 0.001F
            && Math.abs(this.relativeScale.z - 1F) < 0.001F
            && this.relativeLinear.equals(this.relativeRotationMatrix, 0.001F)
            && sinHalf > EPSILON;

        if (!this.rigidRelative)
        {
            return;
        }

        this.rotationAxis.set(
            this.relativeRotation.x / sinHalf,
            this.relativeRotation.y / sinHalf,
            this.relativeRotation.z / sinHalf
        ).normalize();
        this.axialTranslation = this.rotationAxis.dot(this.relativeTranslation);
        this.recoverJointPivot();
    }

    /** Solve (I - R) * pivot = translation, fixing the free axis coordinate to the bend center. */
    private void recoverJointPivot()
    {
        Vector3f axis = this.rotationAxis;
        this.pivotSolver.identity().sub(this.relativeRotationMatrix);
        this.axisOuter.zero()
            .m00(axis.x * axis.x).m01(axis.x * axis.y).m02(axis.x * axis.z)
            .m10(axis.y * axis.x).m11(axis.y * axis.y).m12(axis.y * axis.z)
            .m20(axis.z * axis.x).m21(axis.z * axis.y).m22(axis.z * axis.z);

        this.pivotSolver.add(this.axisOuter);

        if (Math.abs(this.pivotSolver.determinant()) <= EPSILON)
        {
            this.jointPivot.set(0F, (this.bendStart + this.bendEnd) / (2F * PIXELS_PER_BLOCK), 0F);

            return;
        }

        Vector3f hint = this.jointPivot.set(0F, (this.bendStart + this.bendEnd) / (2F * PIXELS_PER_BLOCK), 0F);
        float alongAxis = axis.dot(hint);

        this.outputPosition.set(this.relativeTranslation)
            .fma(-this.axialTranslation, axis)
            .fma(alongAxis, axis);
        this.pivotSolver.invert().transform(this.outputPosition, this.jointPivot);
    }

    private void setupNormalMatrix(Matrix3f output, Matrix4f matrix)
    {
        output.set(matrix);

        if (Math.abs(output.determinant()) > EPSILON)
        {
            output.invert().transpose();
        }
        else
        {
            output.identity();
        }
    }

    private float getWeight(float y)
    {
        float pixels = y * PIXELS_PER_BLOCK;

        if (this.bendEnd <= this.bendStart)
        {
            return pixels >= this.bendStart ? 1F : 0F;
        }

        float weight = (pixels - this.bendStart) / (this.bendEnd - this.bendStart);

        return Math.max(0F, Math.min(1F, weight));
    }

    @Override
    public void addVertex(float x, float y, float z, int color, float u, float v,
        int overlay, int light, float nx, float ny, float nz)
    {
        this.quad[this.quadVertexCount++].set(x, y, z, color, u, v, overlay, light, nx, ny, nz);

        if (this.quadVertexCount == this.quad.length)
        {
            this.emitQuad();
            this.quadVertexCount = 0;
        }
    }

    private void emitQuad()
    {
        int start = this.findHorizontalEdge();

        if (start < 0)
        {
            for (ArmorVertex vertex : this.quad)
            {
                this.emit(vertex.x, vertex.y, vertex.z, vertex.color, vertex.u, vertex.v,
                    vertex.overlay, vertex.light, vertex.nx, vertex.ny, vertex.nz);
            }

            return;
        }

        ArmorVertex a = this.quad[start];
        ArmorVertex b = this.quad[(start + 1) & 3];
        ArmorVertex c = this.quad[(start + 2) & 3];
        ArmorVertex d = this.quad[(start + 3) & 3];
        int subdivisions = Math.min(MAX_SUBDIVISIONS,
            Math.max(1, (int) Math.ceil(Math.abs(a.y - d.y) * PIXELS_PER_BLOCK)));

        for (int i = 0; i < subdivisions; i++)
        {
            float t0 = (float) i / subdivisions;
            float t1 = (float) (i + 1) / subdivisions;

            this.emitInterpolated(a, d, t0);
            this.emitInterpolated(b, c, t0);
            this.emitInterpolated(b, c, t1);
            this.emitInterpolated(a, d, t1);
        }
    }

    private int findHorizontalEdge()
    {
        for (int i = 0; i < 4; i++)
        {
            ArmorVertex a = this.quad[i];
            ArmorVertex b = this.quad[(i + 1) & 3];
            ArmorVertex c = this.quad[(i + 2) & 3];
            ArmorVertex d = this.quad[(i + 3) & 3];

            if (Math.abs(a.y - b.y) <= EPSILON
                && Math.abs(c.y - d.y) <= EPSILON
                && Math.abs(a.y - c.y) > EPSILON)
            {
                return i;
            }
        }

        return -1;
    }

    private void emitInterpolated(ArmorVertex a, ArmorVertex b, float t)
    {
        this.emit(
            lerp(a.x, b.x, t), lerp(a.y, b.y, t), lerp(a.z, b.z, t), a.color,
            lerp(a.u, b.u, t), lerp(a.v, b.v, t), a.overlay, a.light,
            lerp(a.nx, b.nx, t), lerp(a.ny, b.ny, t), lerp(a.nz, b.nz, t)
        );
    }

    private void emit(float x, float y, float z, int color, float u, float v,
        int overlay, int light, float nx, float ny, float nz)
    {
        float weight = this.getWeight(y);

        if (weight <= 0F)
        {
            this.upper.transformPosition(x, y, z, this.outputPosition);
            this.upperNormalMatrix.transform(this.upperNormal.set(nx, ny, nz)).normalize();
        }
        else if (weight >= 1F)
        {
            this.lower.transformPosition(x, y, z, this.outputPosition);
            this.lowerNormalMatrix.transform(this.upperNormal.set(nx, ny, nz)).normalize();
        }
        else if (this.rigidRelative)
        {
            this.partialRotation.identity().slerp(this.relativeRotation, weight).normalize();
            this.outputPosition.set(x, y, z).sub(this.jointPivot);
            this.partialRotation.transform(this.outputPosition);
            this.outputPosition.add(this.jointPivot).fma(weight * this.axialTranslation, this.rotationAxis);
            this.upper.transformPosition(this.outputPosition);

            this.partialRotation.transform(this.upperNormal.set(nx, ny, nz));
            this.upperNormalMatrix.transform(this.upperNormal).normalize();
        }
        else
        {
            /* Scaled/sheared joints are uncommon; preserve their exact endpoints and use
             * linear skinning because a single rigid rotation cannot represent them. */
            this.upper.transformPosition(x, y, z, this.upperPosition);
            this.lower.transformPosition(x, y, z, this.lowerPosition);
            this.outputPosition.set(this.upperPosition).lerp(this.lowerPosition, weight);

            this.upperNormalMatrix.transform(this.upperNormal.set(nx, ny, nz));
            this.lowerNormalMatrix.transform(this.lowerNormal.set(nx, ny, nz));
            this.upperNormal.lerp(this.lowerNormal, weight).normalize();
        }

        this.target.addVertex(
            this.outputPosition.x, this.outputPosition.y, this.outputPosition.z,
            color, u, v, overlay, light,
            this.upperNormal.x, this.upperNormal.y, this.upperNormal.z
        );
    }

    private void flushPending()
    {
        for (int i = 0; i < this.quadVertexCount; i++)
        {
            ArmorVertex vertex = this.quad[i];

            this.emit(vertex.x, vertex.y, vertex.z, vertex.color, vertex.u, vertex.v,
                vertex.overlay, vertex.light, vertex.nx, vertex.ny, vertex.nz);
        }

        this.quadVertexCount = 0;
    }

    private static float lerp(float a, float b, float t)
    {
        return a + (b - a) * t;
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z)
    {
        this.flushPending();

        return this.target.addVertex(x, y, z);
    }

    @Override
    public VertexConsumer setColor(int red, int green, int blue, int alpha)
    {
        return this.target.setColor(red, green, blue, alpha);
    }

    @Override
    public VertexConsumer setUv(float u, float v)
    {
        return this.target.setUv(u, v);
    }

    @Override
    public VertexConsumer setUv1(int u, int v)
    {
        return this.target.setUv1(u, v);
    }

    @Override
    public VertexConsumer setUv2(int u, int v)
    {
        return this.target.setUv2(u, v);
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z)
    {
        return this.target.setNormal(x, y, z);
    }

    private static class ArmorVertex
    {
        public float x;
        public float y;
        public float z;
        public int color;
        public float u;
        public float v;
        public int overlay;
        public int light;
        public float nx;
        public float ny;
        public float nz;

        public void set(float x, float y, float z, int color, float u, float v,
            int overlay, int light, float nx, float ny, float nz)
        {
            this.x = x;
            this.y = y;
            this.z = z;
            this.color = color;
            this.u = u;
            this.v = v;
            this.overlay = overlay;
            this.light = light;
            this.nx = nx;
            this.ny = ny;
            this.nz = nz;
        }
    }
}
