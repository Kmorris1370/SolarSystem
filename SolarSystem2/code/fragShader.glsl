#version 430
in vec2 tc;
out vec4 color;

uniform vec4 tintColor = vec4(1.0, 1.0, 1.0, 1.0);
uniform sampler2D s;
uniform bool useTexture = true;

void main(void)
{
    if (useTexture)
        color = texture(s, tc) * tintColor;
    else
        color = tintColor;
}
