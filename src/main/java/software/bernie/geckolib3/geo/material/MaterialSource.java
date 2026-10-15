package software.bernie.geckolib3.geo.material;

import java.util.List;
import software.bernie.geckolib3.geo.render.built.GeoCube;

/** Host texture supplies a texture-specific CPU bake without coupling engine to host classes. */
public interface MaterialSource {
    boolean materialAvailable();
    List<BakedFace> materialFaces(GeoCube cube, boolean forceCulling);
}
