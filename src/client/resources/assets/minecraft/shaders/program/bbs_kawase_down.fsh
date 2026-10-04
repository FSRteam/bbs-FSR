#version 150

// Dual Kawase downsample (Bjorge, ARM 2015): the centre plus four diagonal bilinear taps half a
// source texel out, written into a target half the size. Offset spreads the taps for in-between strengths.

uniform sampler2D DiffuseSampler;
uniform float Offset;

in vec2 texCoord;
in vec2 oneTexel;

out vec4 fragColor;

void main() {
    vec2 h = oneTexel * 0.5 * Offset;

    vec3 sum = texture(DiffuseSampler, texCoord).rgb * 4.0;
    sum += texture(DiffuseSampler, texCoord - h).rgb;
    sum += texture(DiffuseSampler, texCoord + h).rgb;
    sum += texture(DiffuseSampler, texCoord + vec2(h.x, -h.y)).rgb;
    sum += texture(DiffuseSampler, texCoord + vec2(-h.x, h.y)).rgb;

    fragColor = vec4(sum / 8.0, 1.0);
}
