package parity;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import com.fox.ysmu.nativebridge.NativeGeometry;

import software.bernie.geckolib3.geo.render.built.GeoModel;

/**
 * Reflective bridge to the M3 facade methods that may not exist yet in {@code build/classes/java/main}.
 *
 * <p>
 * The geometry milestone adds {@code NativeGeometry.info(long)} and the
 * {@code NativeGeometryReader.read(byte[])} materialiser while this harness is being written. Reflecting
 * on them instead of compiling against them keeps the harness buildable against the current classes and
 * lets the same binary pick the API up once the Lead's {@code compileJava} has produced it — the report
 * then switches from PENDING to a real comparison without a harness edit. {@code bake}/{@code blob} are
 * already declared on the facade and are called directly.
 */
final class NativeGeometryApi {

    private static final String READER_CLASS = "com.fox.ysmu.nativebridge.NativeGeometryReader";

    private static Method infoMethod;
    private static boolean infoResolved;
    private static String infoProblem = "not resolved";

    private static Method readerMethod;
    private static boolean readerResolved;
    private static String readerProblem = "not resolved";

    private NativeGeometryApi() {}

    /** Whether {@code NativeGeometry.info(long)} exists in the loaded facade. */
    static synchronized boolean hasInfo() {
        resolveInfo();
        return infoMethod != null;
    }

    static synchronized String infoProblem() {
        resolveInfo();
        return infoProblem;
    }

    /**
     * {@code NativeGeometry.info(handle)}: {@code [boneCount, cubeCount, quadCount, flags]}.
     *
     * @return the array, or {@code null} when the method is missing or declined the handle
     */
    static synchronized int[] info(long handle) {
        resolveInfo();
        if (infoMethod == null) {
            return null;
        }
        try {
            Object value = infoMethod.invoke(null, handle);
            return value instanceof int[] ? (int[]) value : null;
        } catch (Throwable failure) {
            infoProblem = "NativeGeometry.info threw: " + unwrap(failure);
            infoMethod = null;
            return null;
        }
    }

    /** Describes whether the {@code NativeGeometryReader} materialiser is available. */
    static synchronized String readerAvailability() {
        resolveReader();
        return readerProblem;
    }

    /** Materialises a native blob into the engine's object graph through the M3 reader. */
    static synchronized GeoModel read(byte[] blob) {
        resolveReader();
        if (readerMethod == null) {
            throw new IllegalStateException("NativeGeometryReader unavailable: " + readerProblem);
        }
        try {
            Object value = readerMethod.invoke(null, (Object) blob);
            if (value instanceof GeoModel) {
                return (GeoModel) value;
            }
            throw new IllegalStateException(
                "NativeGeometryReader." + readerMethod.getName() + " returned "
                    + (value == null ? "null" : value.getClass()
                        .getName()));
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            throw new IllegalStateException("NativeGeometryReader threw: " + cause, cause);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("NativeGeometryReader not accessible: " + failure, failure);
        }
    }

    private static void resolveInfo() {
        if (infoResolved) {
            return;
        }
        infoResolved = true;
        try {
            Method method = NativeGeometry.class.getMethod("info", long.class);
            method.setAccessible(true);
            if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() != int[].class) {
                infoProblem = "NativeGeometry.info has an unexpected signature: " + method;
                return;
            }
            infoMethod = method;
            infoProblem = "available";
        } catch (NoSuchMethodException missing) {
            infoProblem = "NativeGeometry.info(long) is not in build/classes/java/main yet (M3 facade change not compiled)";
        } catch (Throwable failure) {
            infoProblem = "NativeGeometry.info lookup failed: " + failure;
        }
    }

    private static void resolveReader() {
        if (readerResolved) {
            return;
        }
        readerResolved = true;
        Class<?> reader;
        try {
            reader = Class.forName(READER_CLASS);
        } catch (ClassNotFoundException missing) {
            readerProblem = READER_CLASS + " is not in build/classes/java/main yet (M3 reader not compiled)";
            return;
        }
        // Contract name first; the other spellings keep the harness useful if the owner names it differently.
        for (String name : new String[] { "read", "materialize", "materialise", "fromBlob", "toGeoModel", "readBlob" }) {
            try {
                Method method = reader.getMethod(name, byte[].class);
                method.setAccessible(true);
                if (!Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                Class<?> returnType = method.getReturnType();
                if (!GeoModel.class.isAssignableFrom(returnType)) {
                    continue;
                }
                readerMethod = method;
                readerProblem = "available: " + READER_CLASS + "." + name + "(byte[])";
                return;
            } catch (NoSuchMethodException ignored) {
                // Try the next spelling.
            }
        }
        StringBuilder methods = new StringBuilder();
        for (Method method : reader.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers()) && method.getParameterCount() == 1
                && method.getParameterTypes()[0] == byte[].class) {
                if (methods.length() > 0) {
                    methods.append(", ");
                }
                methods.append(method.getReturnType()
                    .getSimpleName())
                    .append(' ')
                    .append(method.getName())
                    .append("(byte[])");
            }
        }
        readerProblem = READER_CLASS + " has no static GeoModel method taking byte[] (found: "
            + (methods.length() == 0 ? "none" : methods) + ")";
    }

    private static String unwrap(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.toString();
    }
}
