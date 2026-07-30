package mchorse.bbs_mod.cubic.render.vao;

import mchorse.bbs_mod.client.BBSRendering;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import org.lwjgl.opengl.GL30;

public class ModelVAO implements IModelVAO
{
    private int vao;
    private int vao2;
    private int count;
    private int vertexBuffer;
    private int normalBuffer;
    private int tangentsBuffer;
    private int texCoordBuffer;
    private int midTexCoordBuffer;

    public ModelVAO(ModelVAOData data)
    {
        int currentVAO = GL30.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);

        this.upload(data);

        GL30.glBindVertexArray(currentVAO);
    }

    public void delete()
    {
        GL30.glDeleteVertexArrays(this.vao);
        GL30.glDeleteVertexArrays(this.vao2);
        GL30.glDeleteBuffers(this.vertexBuffer);
        GL30.glDeleteBuffers(this.normalBuffer);
        GL30.glDeleteBuffers(this.tangentsBuffer);
        GL30.glDeleteBuffers(this.texCoordBuffer);
        GL30.glDeleteBuffers(this.midTexCoordBuffer);
    }

    public void upload(ModelVAOData data)
    {
        this.vao = GL30.glGenVertexArrays();
        this.vao2 = GL30.glGenVertexArrays();

        GL30.glBindVertexArray(this.vao);

        this.vertexBuffer = GL30.glGenBuffers();
        this.normalBuffer = GL30.glGenBuffers();
        this.tangentsBuffer = GL30.glGenBuffers();
        this.texCoordBuffer = GL30.glGenBuffers();
        this.midTexCoordBuffer = GL30.glGenBuffers();

        GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, this.vertexBuffer);
        GL30.glBufferData(GL30.GL_ARRAY_BUFFER, data.vertices(), GL30.GL_STATIC_DRAW);
        GL30.glVertexAttribPointer(Attributes.POSITION, 3, GL30.GL_FLOAT, false, 0, 0);

        GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, this.normalBuffer);
        GL30.glBufferData(GL30.GL_ARRAY_BUFFER, data.normals(), GL30.GL_STATIC_DRAW);
        GL30.glVertexAttribPointer(Attributes.NORMAL, 3, GL30.GL_FLOAT, false, 0, 0);

        GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, this.texCoordBuffer);
        GL30.glBufferData(GL30.GL_ARRAY_BUFFER, data.texCoords(), GL30.GL_STATIC_DRAW);
        GL30.glVertexAttribPointer(Attributes.TEXTURE_UV, 2, GL30.GL_FLOAT, false, 0, 0);

        GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, this.tangentsBuffer);
        GL30.glBufferData(GL30.GL_ARRAY_BUFFER, data.tangents(), GL30.GL_STATIC_DRAW);
        GL30.glVertexAttribPointer(Attributes.TANGENTS, 4, GL30.GL_FLOAT, false, 0, 0);

        GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, this.midTexCoordBuffer);
        GL30.glBufferData(GL30.GL_ARRAY_BUFFER, data.texCoords(), GL30.GL_STATIC_DRAW);
        GL30.glVertexAttribPointer(Attributes.MID_TEXTURE_UV, 2, GL30.GL_FLOAT, false, 0, 0);

        GL30.glEnableVertexAttribArray(Attributes.POSITION);
        GL30.glEnableVertexAttribArray(Attributes.TEXTURE_UV);
        GL30.glEnableVertexAttribArray(Attributes.NORMAL);

        GL30.glDisableVertexAttribArray(Attributes.COLOR);
        GL30.glDisableVertexAttribArray(Attributes.OVERLAY_UV);
        GL30.glDisableVertexAttribArray(Attributes.LIGHTMAP_UV);
        GL30.glDisableVertexAttribArray(Attributes.TANGENTS);
        GL30.glDisableVertexAttribArray(Attributes.MID_TEXTURE_UV);

        /* DefaultVertexFormat.POSITION_TEXTURE_LIGHT_COLOR */
        GL30.glBindVertexArray(this.vao2);

        GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, this.vertexBuffer);
        GL30.glVertexAttribPointer(0, 3, GL30.GL_FLOAT, false, 0, 0);

        GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, this.texCoordBuffer);
        GL30.glVertexAttribPointer(1, 2, GL30.GL_FLOAT, false, 0, 0);

        GL30.glEnableVertexAttribArray(0);
        GL30.glEnableVertexAttribArray(1);
        GL30.glDisableVertexAttribArray(2);
        GL30.glDisableVertexAttribArray(3);

        this.count = data.vertices().length / 3;
    }

    @Override
    public void render(VertexFormat format, float r, float g, float b, float a, int light, int overlay)
    {
        boolean hasShaders = isShadersEnabled();
        boolean compact = format == DefaultVertexFormat.POSITION_TEX
            || !hasShaders && format != DefaultVertexFormat.NEW_ENTITY;
        int vao = compact ? this.vao2 : this.vao;

        GL30.glBindVertexArray(vao);

        if (vao == this.vao)
        {
            GL30.glVertexAttrib4f(Attributes.COLOR, r, g, b, a);
            GL30.glVertexAttribI2i(Attributes.OVERLAY_UV, overlay & '\uffff', overlay >> 16 & '\uffff');
            GL30.glVertexAttribI2i(Attributes.LIGHTMAP_UV, light & '\uffff', light >> 16 & '\uffff');
        }
        else
        {
            GL30.glVertexAttribI2i(2, light & '\uffff', light >> 16 & '\uffff');
            GL30.glVertexAttrib4f(3, r, g, b, a);
        }

        if (hasShaders && !compact) GL30.glEnableVertexAttribArray(Attributes.MID_TEXTURE_UV);
        else GL30.glDisableVertexAttribArray(Attributes.MID_TEXTURE_UV);

        if (hasShaders && !compact) GL30.glEnableVertexAttribArray(Attributes.TANGENTS);
        else GL30.glDisableVertexAttribArray(Attributes.TANGENTS);

        GL30.glDrawArrays(GL30.GL_TRIANGLES, 0, this.count);
        GL30.glBindVertexArray(0);
    }

    public static boolean isShadersEnabled()
    {
        return BBSRendering.isIrisShadersEnabled();
    }
}
