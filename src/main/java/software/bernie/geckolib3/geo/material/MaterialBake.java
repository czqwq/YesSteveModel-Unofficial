package software.bernie.geckolib3.geo.material;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import software.bernie.geckolib3.geo.render.built.GeoQuad;

/** CPU port of native baked_model.cc:390-515,555-569,685-734. No GL or shared-geometry edits. */
public final class MaterialBake {
    public static final int CURRENT_RAW_UV_VERSION = 29;
    private MaterialBake() {}

    public static List<BakedFace> bake(GeoQuad[] quads, BufferedImage image, boolean forceCulling) {
        return bake(quads, image, CURRENT_RAW_UV_VERSION, forceCulling, false);
    }

    public static List<BakedFace> bake(GeoQuad[] quads, BufferedImage image, int version,
        boolean forceCulling, boolean forceTranslucent) {
        java.util.Objects.requireNonNull(quads, "quads");
        java.util.Objects.requireNonNull(image, "image");
        if (version < 0 || version > 65535) throw new IllegalArgumentException("UV version outside uint16");
        List<GeoQuad> source = new ArrayList<>();
        for (GeoQuad quad : quads) {
            if (quad == null) continue;
            if (quad.vertices.length != 4) throw new IllegalArgumentException("material face must have four vertices");
            source.add(quad);
        }
        List<float[]> uvs = new ArrayList<>();
        List<BakedFace.AlphaKind> kinds = new ArrayList<>();
        int solids = 0;
        for (GeoQuad quad : source) {
            float[] uv = new float[8];
            for (int i = 0; i < 4; i++) {
                uv[i * 2] = quad.vertices[i].textureU;
                uv[i * 2 + 1] = quad.vertices[i].textureV;
            }
            BakedFace.AlphaKind kind = inspect(uv, image, version);
            uvs.add(uv);
            kinds.add(kind);
            if (kind == BakedFace.AlphaKind.SOLID) solids++;
        }
        boolean cull = forceCulling && source.size() == 6 || inverted(source);
        List<BakedFace> output = new ArrayList<>();
        for (int i = 0; i < source.size(); i++) {
            BakedFace.AlphaKind kind = kinds.get(i);
            if (kind == BakedFace.AlphaKind.EMPTY && !forceTranslucent) continue;
            boolean translucent = forceTranslucent || kind != BakedFace.AlphaKind.SOLID;
            MaterialPartition partition = translucent
                ? (cull ? MaterialPartition.TRANSLUCENT_CULLING : MaterialPartition.TRANSLUCENT)
                : (cull || solids == 6 ? MaterialPartition.CUTOUT : MaterialPartition.CUTOUT_NO_CULLING);
            output.add(new BakedFace(source.get(i), uvs.get(i), kind, partition));
        }
        return Collections.unmodifiableList(output);
    }

    private static boolean inverted(List<GeoQuad> quads) {
        if (quads.size() != 6) return false;
        float[][] centers = new float[6][3];
        float[] center = new float[3];
        for (int i = 0; i < 6; i++) {
            for (int v = 0; v < 4; v++) {
                centers[i][0] += quads.get(i).vertices[v].position.x * 0.25f;
                centers[i][1] += quads.get(i).vertices[v].position.y * 0.25f;
                centers[i][2] += quads.get(i).vertices[v].position.z * 0.25f;
            }
            for (int a = 0; a < 3; a++) center[a] += centers[i][a] / 6;
        }
        for (int i = 0; i < 6; i++) {
            GeoQuad quad = quads.get(i);
            float x = centers[i][0] - center[0], y = centers[i][1] - center[1], z = centers[i][2] - center[2];
            float nx = quad.normalVector == null ? quad.normal.getX() : quad.normalVector.x;
            float ny = quad.normalVector == null ? quad.normal.getY() : quad.normalVector.y;
            float nz = quad.normalVector == null ? quad.normal.getZ() : quad.normalVector.z;
            if (x * x + y * y + z * z > 1e-12f && nx * x + ny * y + nz * z < 0) return true;
        }
        return false;
    }

    private static int quantize(float uv, int extent, float threshold) {
        if (!Float.isFinite(uv)) throw new IllegalArgumentException("nonfinite UV");
        float scaled = Math.max(0, Math.min(1, uv)) * extent;
        int base = (int) scaled;
        if (base < extent && scaled - base >= threshold) base++;
        return Math.min(base, extent);
    }

    private static BakedFace.AlphaKind inspect(float[] uv, BufferedImage image, int version) {
        int processing = version >= 29 ? 29 : version >= 28 ? 28 : version >= 17 ? 17 : version >= 6 ? 6 : 0;
        int width = image.getWidth(), height = image.getHeight();
        int minU = width, maxU = 0, minV = height, maxV = 0;
        for (int i = 0; i < 4; i++) {
            int u, v;
            if (processing == 0 || processing == 29) {
                u = quantize(uv[i * 2], width, 0.6f);
                v = quantize(uv[i * 2 + 1], height, processing == 29 ? 0.4f : 0.6f);
                uv[i * 2] = (float) u / width;
                uv[i * 2 + 1] = (float) v / height;
            } else {
                if (!Float.isFinite(uv[i * 2]) || !Float.isFinite(uv[i * 2 + 1])) {
                    throw new IllegalArgumentException("nonfinite UV");
                }
                u = Math.round(Math.max(0, Math.min(1, uv[i * 2])) * width);
                v = Math.round(Math.max(0, Math.min(1, uv[i * 2 + 1])) * height);
            }
            minU = Math.min(minU, u); maxU = Math.max(maxU, u);
            minV = Math.min(minV, v); maxV = Math.max(maxV, v);
        }
        float uOffset = 0, vOffset = 0;
        if (processing == 6) {
            if (minU == maxU && maxU < width) maxU++;
            if (minV == maxV && maxV < height) maxV++;
        } else {
            if (minU == maxU) {
                if (maxU < width) maxU++;
                else if (minU > 0) { minU--; uOffset = -1f / width; }
            }
            if (minV == maxV) {
                if (processing == 0 && maxV < height) maxV++;
                else if (minV > 0) { minV--; vOffset = -1f / height; }
                else if (maxV < height) maxV++;
            }
        }
        if (processing == 17 || processing == 6) {
            for (int i = 0; i < 4; i++) { uv[i * 2] += uOffset; uv[i * 2 + 1] += vOffset; }
        } else {
            uv[0] += uOffset; uv[6] += uOffset;
            uv[5] += vOffset; uv[7] += vOffset;
        }
        boolean empty = true, transparent = false;
        for (int y = minV; y < maxV; y++) {
            for (int x = minU; x < maxU; x++) {
                int alpha = image.getRGB(x, y) >>> 24;
                if (alpha != 0) {
                    empty = false;
                    if (alpha != 255) return BakedFace.AlphaKind.TRANSLUCENT;
                } else transparent = true;
            }
        }
        return empty ? BakedFace.AlphaKind.EMPTY
            : transparent ? BakedFace.AlphaKind.CUTOUT : BakedFace.AlphaKind.SOLID;
    }
}
