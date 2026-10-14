package com.winlator.star.renderer.effects;

import com.winlator.star.renderer.material.ScreenMaterial;
import com.winlator.star.renderer.material.ShaderMaterial;

// =============================================================================
//  Debanding / dither - terminal post pass (GL EffectComposer port).
//
//  Adds sub-LSB triangular-PDF (TPDF) noise just before the final 8-bit
//  quantization so smooth gradients stop showing 8-bit banding (worst on AMOLED
//  darks). Mirrors the native Vulkan deband.frag exactly: interleaved gradient
//  noise (Jimenez 2014, public technique), two screen-locked samples summed for
//  a triangular PDF, float-only hash (no bitwise) = Adreno-compile-safe.
//
//  This is rendered as a DEDICATED TERMINAL slot by EffectComposer (it is NOT in
//  the `effects` list): the whole chain renders into an offscreen buffer and this
//  pass dithers that buffer straight to the screen, so the dither matches the
//  display quantizer after every other effect has produced final pixels.
//
//  GL ES 3.00; the source buffer is `screenTexture`, dither amplitude is the
//  `strength` uniform in LSBs (1.0 = +/-1/255), driven by the drawer slider
//  (slider/100). Drawer-only / session-live.
// =============================================================================

public class DebandEffect extends Effect {

    @Override
    protected ShaderMaterial createMaterial() {
        return new DebandMaterial();
    }

    private static class DebandMaterial extends ScreenMaterial {
        DebandMaterial() {
            super();
            setUniformNames("resolution", "screenTexture", "strength");
        }

        @Override
        protected String getVertexShader() {
            return String.join("\n", new CharSequence[]{
                "#version 300 es",
                "layout(location = 0) in vec2 position;",
                "void main() {",
                "    gl_Position = vec4(2.0 * position.x - 1.0, 2.0 * position.y - 1.0, 0.0, 1.0);",
                "}"
            });
        }

        @Override
        protected String getFragmentShader() {
            return String.join("\n", new CharSequence[]{
                "#version 300 es",
                "precision highp float;",
                "uniform sampler2D screenTexture;",
                "uniform vec2 resolution;",
                "uniform float strength;     // dither amplitude in LSBs",
                "out vec4 fragColor;",
                // Interleaved gradient noise - returns [0,1), float-only, Adreno-safe.
                "float ign(vec2 p) {",
                "    return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));",
                "}",
                "void main() {",
                "    vec2 uv = gl_FragCoord.xy / resolution;",
                "    vec3 col = texture(screenTexture, uv).rgb;",
                // Two decorrelated samples -> triangular PDF in (-1, 1).
                "    float n1 = ign(gl_FragCoord.xy);",
                "    float n2 = ign(gl_FragCoord.xy + vec2(11.0, 17.0));",
                "    float tri = (n1 + n2) - 1.0;",
                "    col += vec3(tri * (strength / 255.0));",
                "    fragColor = vec4(col, 1.0);",
                "}"
            });
        }
    }
}
