package parity;

import java.util.ArrayList;
import java.util.List;

import javax.vecmath.Vector3f;

import software.bernie.geckolib3.geo.raw.pojo.ExtraInfo;
import software.bernie.geckolib3.geo.raw.pojo.ModelProperties;
import software.bernie.geckolib3.geo.render.built.GeoBone;
import software.bernie.geckolib3.geo.render.built.GeoCube;
import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.geo.render.built.GeoQuad;
import software.bernie.geckolib3.geo.render.built.GeoVertex;

/**
 * Canonical, order-sensitive textual dump of a built {@code GeoModel}.
 *
 * <p>
 * Two dumps are equal if and only if the object graphs are field-for-field identical in Java terms,
 * which is the M3 acceptance criterion: same {@code topLevelBones} order, same {@code childBones} order,
 * same {@code childCubes} order, same quad slots (including the {@code null} slots a legacy
 * per-face-UV cube can have), and the same f32/f64 bit patterns for every numeric field. Floats are
 * compared through {@link Float#floatToRawIntBits}, so a 1-ulp difference is a failure, not a tolerance
 * question.
 *
 * <p>
 * The dump is produced one model at a time and thrown away after the comparison, so the temporary line
 * lists stay bounded even for the 2.4 MiB {@code 08_sta} geometry.
 */
public final class GeoDump {

    private GeoDump() {}

    public static List<String> dump(GeoModel model) {
        List<String> lines = new ArrayList<>(4096);
        lines.add("model topLevelBones=" + model.topLevelBones.size());
        dumpProperties(model.properties, lines);
        lines.add(
            "model locators leftHand=" + names(model.leftHandBones)
                + " rightHand=" + names(model.rightHandBones)
                + " elytra=" + names(model.elytraBones)
                + " firstPersonHead=" + name(model.firstPersonHead)
                + " firstPersonViewLocator=" + name(model.firstPersonViewLocator));
        for (GeoBone bone : model.topLevelBones) {
            dumpBone(bone, "", lines);
        }
        return lines;
    }

    private static void dumpProperties(ModelProperties properties, List<String> lines) {
        if (properties == null) {
            lines.add("properties <null>");
            return;
        }
        lines.add(
            "properties identifier=" + str(properties.getIdentifier())
                + " textureWidth=" + dbl(properties.getTextureWidth())
                + " textureHeight=" + dbl(properties.getTextureHeight())
                + " visibleBoundsWidth=" + dbl(properties.getVisibleBoundsWidth())
                + " visibleBoundsHeight=" + dbl(properties.getVisibleBoundsHeight())
                + " visibleBoundsOffset=" + doubles(properties.getVisibleBoundsOffset())
                + " heightScale=" + dbl(properties.getHeightScale())
                + " widthScale=" + dbl(properties.getWidthScale())
                + " forceCulling=" + str(properties.getForceCulling())
                + " defaultTexture=" + str(properties.getDefaultTexture())
                + " renderLayersFirst=" + str(properties.getRenderLayersFirst())
                + " animationArmsDown=" + str(properties.getAnimationArmsDown())
                + " animationArmsOutFront=" + str(properties.getAnimationArmsOutFront())
                + " animationDontShowArmor=" + str(properties.getAnimationDontShowArmor())
                + " animationInvertedCrouch=" + str(properties.getAnimationInvertedCrouch())
                + " animationNoHeadBob=" + str(properties.getAnimationNoHeadBob())
                + " animationSingleArmAnimation=" + str(properties.getAnimationSingleArmAnimation())
                + " animationSingleLegAnimation=" + str(properties.getAnimationSingleLegAnimation())
                + " animationStationaryLegs=" + str(properties.getAnimationStationaryLegs())
                + " animationStatueOfLibertyArms=" + str(properties.getAnimationStatueOfLibertyArms())
                + " animationUpsideDown=" + str(properties.getAnimationUpsideDown())
                + " preserveModelPose=" + str(properties.getPreserveModelPose()));
        ExtraInfo extra = properties.getExtraInfo();
        if (extra == null) {
            lines.add("properties extraInfo=<null>");
        } else {
            lines.add(
                "properties extraInfo name=" + str(extra.getName())
                    + " tips=" + str(extra.getTips())
                    + " license=" + str(extra.getLicense())
                    + " authors=" + array(extra.getAuthors())
                    + " extraAnimationNames=" + array(extra.getExtraAnimationNames()));
        }
    }

    private static void dumpBone(GeoBone bone, String parentPath, List<String> lines) {
        String path = parentPath.isEmpty() ? bone.getName() : parentPath + "/" + bone.getName();
        lines.add(
            "bone " + path
                + " parent=" + name(bone.parent)
                + " mirror=" + str(bone.mirror)
                + " inflate=" + dbl(bone.inflate)
                + " dontRender=" + str(bone.dontRender)
                + " reset=" + str(bone.reset)
                + " hidden=" + bone.isHidden()
                + " cubesHidden=" + bone.areCubesHidden
                + " hideChildrenToo=" + bone.hideChildBonesToo
                + " rotationPoint=" + bits(bone.rotationPointX, bone.rotationPointY, bone.rotationPointZ)
                + " rotation=" + bits(bone.getRotationX(), bone.getRotationY(), bone.getRotationZ())
                + " position=" + bits(bone.getPositionX(), bone.getPositionY(), bone.getPositionZ())
                + " scale=" + bits(bone.getScaleX(), bone.getScaleY(), bone.getScaleZ())
                + " renderColor=" + Integer.toHexString(bone.getRenderColor())
                + " renderTransparency=" + bone.getRenderTransparency()
                + " renderGlow=" + bone.getRenderGlow()
                + " textureOverride=" + (bone.textureOverride == null ? "<null>" : bone.textureOverride.toString()));
        for (int cubeIndex = 0; cubeIndex < bone.childCubes.size(); cubeIndex++) {
            dumpCube(bone.childCubes.get(cubeIndex), path, cubeIndex, lines);
        }
        for (GeoBone child : bone.childBones) {
            dumpBone(child, path, lines);
        }
    }

    private static void dumpCube(GeoCube cube, String bonePath, int cubeIndex, List<String> lines) {
        String prefix = "cube " + bonePath + "#" + cubeIndex;
        if (cube == null) {
            lines.add(prefix + " <null>");
            return;
        }
        lines.add(
            prefix + " size=" + bits(cube.size.x, cube.size.y, cube.size.z)
                + " pivot=" + (cube.pivot == null ? "<null>" : bits(cube.pivot.x, cube.pivot.y, cube.pivot.z))
                + " rotation=" + (cube.rotation == null ? "<null>" : bits(cube.rotation.x, cube.rotation.y, cube.rotation.z))
                + " inflate=" + Double.doubleToRawLongBits(cube.inflate)
                + " mirror=" + str(cube.mirror)
                + " mesh=" + cube.mesh
                + " hasNegSize=" + cube.hasNegSize
                + " quads=" + (cube.quads == null ? "<null>" : Integer.toString(cube.quads.length)));
        if (cube.quads == null) {
            return;
        }
        for (int quadIndex = 0; quadIndex < cube.quads.length; quadIndex++) {
            dumpQuad(cube.quads[quadIndex], prefix + " quad#" + quadIndex, lines);
        }
    }

    private static void dumpQuad(GeoQuad quad, String prefix, List<String> lines) {
        if (quad == null) {
            lines.add(prefix + " <null>");
            return;
        }
        StringBuilder line = new StringBuilder(256);
        line.append(prefix)
            .append(" direction=")
            .append(quad.direction == null ? "<null>" : quad.direction.name())
            .append(" uvRotation=")
            .append(quad.uvRotation)
            .append(" normal=")
            .append(quad.normal == null ? "<null>" : quad.normal.getX() + "," + quad.normal.getY() + "," + quad.normal.getZ())
            .append(" normalVector=")
            .append(quad.normalVector == null ? "<null>" : bits(quad.normalVector.x, quad.normalVector.y, quad.normalVector.z));
        if (quad.vertices != null) {
            for (int vertexIndex = 0; vertexIndex < quad.vertices.length; vertexIndex++) {
                GeoVertex vertex = quad.vertices[vertexIndex];
                line.append(" v")
                    .append(vertexIndex)
                    .append('=');
                if (vertex == null) {
                    line.append("<null>");
                } else {
                    Vector3f position = vertex.position;
                    line.append(position == null ? "<nullpos>" : bits(position.x, position.y, position.z))
                        .append('@')
                        .append(Integer.toHexString(Float.floatToRawIntBits(vertex.textureU)))
                        .append(',')
                        .append(Integer.toHexString(Float.floatToRawIntBits(vertex.textureV)));
                }
            }
        }
        lines.add(line.toString());
    }

    private static String names(List<GeoBone> bones) {
        if (bones == null) {
            return "<null>";
        }
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < bones.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(name(bones.get(i)));
        }
        return builder.append(']')
            .toString();
    }

    private static String name(GeoBone bone) {
        return bone == null ? "<null>" : bone.getName();
    }

    private static String bits(float x, float y, float z) {
        return "(" + Integer.toHexString(Float.floatToRawIntBits(x)) + ',' + Integer.toHexString(Float.floatToRawIntBits(y))
            + ',' + Integer.toHexString(Float.floatToRawIntBits(z)) + ')';
    }

    private static String dbl(Double value) {
        return value == null ? "<null>" : Long.toString(Double.doubleToRawLongBits(value));
    }

    private static String doubles(double[] values) {
        if (values == null) {
            return "<null>";
        }
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(Double.doubleToRawLongBits(values[i]));
        }
        return builder.append(']')
            .toString();
    }

    private static String str(Object value) {
        return value == null ? "<null>" : value.toString();
    }

    private static String array(String[] values) {
        if (values == null) {
            return "<null>";
        }
        return "[" + String.join(",", values) + "]";
    }
}
