#version 150

#moj_import <fog.glsl>

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform int PassMode;

in float vertexDistance;
in vec4 vertexColor;
in vec4 lightMapColor;
in vec4 overlayColor;
in vec2 texCoord0;
in vec4 normal;

out vec4 fragColor;

void main()
{
    vec4 color = texture(Sampler0, texCoord0);

    if (color.a < 0.1)
    {
        discard;
    }

    float texAlpha = color.a;

    color *= vertexColor * ColorModulator;

    if (PassMode == 1 && color.a < 0.999)
    {
        discard;
    }

    if (PassMode == 2 && color.a >= 0.999)
    {
        discard;
    }

    /* Uniform Color Pose fades use the texture's own alpha for the partition.
     * That keeps skin overlays/shading blended over the model's opaque texels
     * instead of blending against whatever happens to be behind the model. */
    if (PassMode == 3 && texAlpha < 0.999)
    {
        discard;
    }

    if (PassMode == 4 && texAlpha >= 0.999)
    {
        discard;
    }
    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
    color *= lightMapColor;

    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
