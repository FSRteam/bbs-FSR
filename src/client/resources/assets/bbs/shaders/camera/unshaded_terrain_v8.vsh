#version 330 core

#import <sodium:include/fog.glsl>
#import <sodium:include/chunk_vertex.glsl>
#import <sodium:include/chunk_matrices.glsl>

out vec4 v_Color;
out vec2 v_TexCoord;

flat out uint v_Material;

#ifdef USE_FOG
out float v_FragDistance;
#endif

uniform int u_FogShape;
uniform vec3 u_RegionOffset;
uniform vec2 u_TexCoordShrink;
uniform sampler2D u_LightTex;

void main()
{
    _vert_init();

    uvec3 section = (uvec3(_draw_id) >> uvec3(5u, 0u, 2u)) & uvec3(7u, 3u, 7u);
    vec3 position = _vert_position + u_RegionOffset + vec3(section) * 16.0;

    gl_Position = u_ProjectionMatrix * u_ModelViewMatrix * vec4(position, 1.0);

#ifdef USE_FOG
    v_FragDistance = getFragDistance(u_FogShape, position);
#endif

    /* Iris stores separate AO in alpha; it is lighting, never block transparency. */
    v_Color = vec4(_vert_color.rgb * _vert_color.a, 1.0) * texture(u_LightTex, _vert_tex_light_coord);
    v_TexCoord = _vert_tex_diffuse_coord + _vert_tex_diffuse_coord_bias * u_TexCoordShrink;
    v_Material = _material_params;
}
