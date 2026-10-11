package com.fox.ysmu.client.animation.molang;

import java.util.function.DoubleSupplier;

import net.minecraft.entity.Entity;

import com.fox.ysmu.client.animation.EntityQueryBindings;

import software.bernie.geckolib3.core.molang.LazyVariable;
import software.bernie.geckolib3.core.molang.MolangParser;

/**
 * The <b>lifetime</b> of the entity-scoped {@code query.*} / {@code ysm.*} values.
 *
 * <p>{@link EntityQueryBindings#apply} writes 61 names into the engine's one shared table
 * ({@code MolangParser.VARIABLES}, {@code MolangParser.java:53}), each as a supplier closing over the entity of that
 * draw. Inside a draw that is exactly right: {@code CustomPlayerModel#setLivingAnimations} publishes before
 * {@code super.setLivingAnimations} evaluates anything ({@code CustomPlayerModel.java:185} and {@code :208} then
 * {@code :210}), and the vehicle ({@code VehicleRenderer.java:485-487}) and projectile
 * ({@code ArrowProjectileRenderer.java:307-308}) renderers do the same. Between draws the same table still holds the
 * last entity's suppliers, so a reader with no entity of its own - {@code /ysm query}, the debug overlay, a pack's
 * {@code @sync} script - answered with whatever was drawn last: a vehicle or an arrow rather than the player it asked
 * about.
 *
 * <p>This class is the port's answer, and it is the answer {@link MolangFrameContext} already gives for the host
 * context it owns: a record published for the duration of a frame that answers the neutral default outside one
 * ("Outside a published frame ... the accessors answer 0/null <b>rather than a previous entity's values</b>",
 * {@code MolangFrameContext.java:38-41}). The 61 names never had that property. Two operations give it to them:</p>
 * <ul>
 * <li>{@link #beginFrame()} / {@link #endFrame()} - the render-tick pair ({@code EntityQueryFrameEvents}). The values
 * the table holds when the frame opens - the values {@code AnimationRegister}'s registrations left there, e.g.
 * {@code 20} for {@code ysm.food_level} ({@code AnimationRegister.java:399}) - are snapshotted once and put back when
 * the frame ends, so from the end of one frame until the first publish of the next the table holds no entity at all.
 * The snapshot is taken at the boundary rather than at the first publish because two of the three publishers are not
 * this class's to edit, and because the boundary <em>is</em> what the invariant is about.</li>
 * <li>{@link #publishForRead} - an entity-scoped publication for the duration of one read, for the readers that
 * <em>do</em> have an entity in hand: the debug overlay's local player, the entity an {@code @sync} script belongs to.
 * Upstream needs no counterpart for either operation, because every read there carries {@code IContext.entity()}
 * ({@code geckolib3/core/molang/context/IContext.java:23-24}): a name is always resolved against the entity the read
 * is about, so there is no "last published entity" to leak from.</li>
 * </ul>
 *
 * <p><b>What this deliberately does not cover.</b> The frame-data and camera names {@code AnimationRegister}
 * <em>also</em> binds per frame ({@code query.head_x_rotation} / {@code ysm.head_yaw} / {@code ysm.person_view} / the
 * world-clock trio, ...) keep their last frame's supplier, because they are not entity state and have no registered
 * default to fall back to. {@code v.*} is not touched at all: a pack's variable deliberately outlives a frame
 * ({@code AGENTS.md}, "Packs assume a roaming variable outlives a single frame"). Only
 * {@link EntityQueryBindings#PUBLISHED_NAMES} - the exact set that class documents as its own - is snapshotted and
 * restored.
 *
 * <p>Render thread only, like every other frame-scoped piece of this path ({@code MolangFrameContext} is the same
 * shape with a {@code ThreadLocal}); no synchronization is taken here.
 */
public final class EntityQueryScope {

    /**
     * The exact set {@link EntityQueryBindings#apply} writes, in the canonical form the table is keyed by.
     *
     * <p>Canonicalised here once rather than on every access: {@code apply} writes through
     * {@code parser.setValue} → {@code getVariable}, which lower-cases and folds {@code q.} into {@code query.}
     * ({@code MolangParser.java:248-251}, {@code :275-288}), so a snapshot keyed by any other spelling could miss an
     * entry the publication created.
     */
    private static final String[] NAMES = canonicalNames();

    /** The values the table held when the frame opened, one per {@link #NAMES} entry. */
    private static final double[] FRAME_VALUES = new double[NAMES.length];

    /**
     * One mutable constant per name, so putting a value back is a field write plus a reference store.
     * {@code LazyVariable#set(double)} would allocate a fresh lambda per name per frame
     * ({@code LazyVariable.java:31-33}); 61 names times two boundaries is not a cost this path should pay for a value
     * that is a constant by construction.
     */
    private static final Constant[] RESTORED = restoredSuppliers();

    /** Whether a frame is open (its snapshot is armed). */
    private static boolean frameOpen;

    private EntityQueryScope() {}

    /**
     * A publication that ends with {@link Publication#close()}. Narrower than {@link AutoCloseable} so a caller needs
     * no {@code catch}: nothing on this path can fail with a checked exception.
     */
    public interface Publication extends AutoCloseable {

        @Override
        void close();
    }

    /** The publication a read that has no entity to publish gets. */
    public static final Publication NOTHING = () -> {};

    /**
     * Snapshots the un-published values and opens the frame. Called from the render tick's START phase
     * ({@code EntityQueryFrameEvents#onRenderTick}).
     *
     * <p>An already-open frame is left alone. If a previous frame's {@link #endFrame()} never ran - the render path
     * threw - the snapshot those values came from is still the one that has to be put back, and re-snapshotting here
     * would capture the half-finished frame's values instead and make the poisoning permanent.</p>
     */
    public static void beginFrame() {
        if (frameOpen) {
            return;
        }
        for (int i = 0; i < NAMES.length; i++) {
            LazyVariable variable = MolangParser.VARIABLES.get(NAMES[i]);
            // A name this client never registered (a unit JVM) has no value to keep, and 0 is what the parser's own
            // default answers for it (MolangParser.newVariable, MolangParser.java:290-292).
            FRAME_VALUES[i] = variable == null ? 0.0D : variable.get();
        }
        frameOpen = true;
    }

    /**
     * Puts the 61 names back to the values {@link #beginFrame()} captured and closes the frame. Called from the render
     * tick's END phase, i.e. after every draw of that frame has published and evaluated.
     */
    public static void endFrame() {
        if (!frameOpen) {
            return;
        }
        frameOpen = false;
        restore(FRAME_VALUES);
    }

    /** Whether the render tick currently has a frame open (diagnostics and tests). */
    public static boolean isFrameOpen() {
        return frameOpen;
    }

    /**
     * Publishes {@code entity}'s {@link EntityQueryBindings#PUBLISHED_NAMES} for the duration of one read, and puts
     * the table back when the returned publication is closed.
     *
     * <p>This is the read half: a reader outside a frame that knows which entity its read is about announces it here
     * instead of trusting whatever the last draw left behind. Closing puts back what the table held, so an off-frame
     * read leaves no entity behind either - the leak and its repair are both scoped to the read.</p>
     *
     * <p>The partial tick is 0, because there is no frame to interpolate within. The two bindings that read it are
     * {@code ysm.input_vertical} (the body yaw it interpolates) and {@code query.distance_from_camera} (the camera
     * position it interpolates); with 0 both answer from the entity's <em>current</em> position and yaw, which is the
     * honest answer for a read that happens between frames.</p>
     *
     * <p>A read that happens while a frame is open (the debug overlay, which draws after every draw) restores the
     * values the frame's own table held, not the frame entity's suppliers. That is safe for the one caller of that
     * shape: every draw that reads these names publishes its own values before it evaluates anything, and the overlay
     * is the last reader of the frame.</p>
     *
     * @return the publication to close; {@link #NOTHING} when there is nothing to publish, so a caller may always use
     *         try-with-resources
     */
    public static Publication publishForRead(MolangParser parser, Entity entity) {
        if (parser == null || entity == null) {
            return NOTHING;
        }
        double[] saved = new double[NAMES.length];
        for (int i = 0; i < NAMES.length; i++) {
            LazyVariable variable = MolangParser.VARIABLES.get(NAMES[i]);
            saved[i] = variable == null ? 0.0D : variable.get();
        }
        EntityQueryBindings.apply(parser, entity, 0.0F);
        return () -> restore(saved);
    }

    /**
     * Puts the 61 names back to {@code values} without allocating: each name's own reusable
     * {@link Constant} is pointed at the value and installed on the existing {@link LazyVariable}, which keeps the
     * object identity a parsed expression cached at parse time (see
     * {@code EntityQueryBindingsTest#rebindingPreservesParsedVariableIdentityAndRestoresTheNextFramesSupplier}).
     */
    private static void restore(double[] values) {
        for (int i = 0; i < NAMES.length; i++) {
            LazyVariable variable = MolangParser.VARIABLES.get(NAMES[i]);
            if (variable == null) {
                // Still absent after a publication would be a bug, but a restore must never be the thing that creates
                // a name: a caller that had no entry keeps having none.
                continue;
            }
            Constant constant = RESTORED[i];
            constant.value = values[i];
            variable.set(constant);
        }
    }

    private static String[] canonicalNames() {
        String[] names = new String[EntityQueryBindings.PUBLISHED_NAMES.length];
        for (int i = 0; i < names.length; i++) {
            names[i] = MolangParser.canonicalVariableName(EntityQueryBindings.PUBLISHED_NAMES[i]);
        }
        return names;
    }

    private static Constant[] restoredSuppliers() {
        Constant[] constants = new Constant[NAMES.length];
        for (int i = 0; i < constants.length; i++) {
            constants[i] = new Constant();
        }
        return constants;
    }

    /** A constant supplier whose value can be rewritten, so a restore allocates nothing. */
    private static final class Constant implements DoubleSupplier {

        private double value;

        @Override
        public double getAsDouble() {
            return value;
        }
    }
}
