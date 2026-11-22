//! Shared JNI helpers: array copying, panics-to-Java-exceptions and the "guard" every entry uses.

use std::panic::{catch_unwind, AssertUnwindSafe};

use jni::objects::{JByteArray, JFloatArray, JIntArray, ReleaseMode};
use jni::JNIEnv;

/// Copies a Java `byte[]` into Rust memory.
pub fn read_bytes(env: &mut JNIEnv, array: &JByteArray) -> Result<Vec<u8>, String> {
    env.convert_byte_array(array)
        .map_err(|error| format!("failed to read byte[]: {error}"))
}

/// Creates a new Java `byte[]` from Rust memory (one copy, unavoidable: the Java side owns the result).
pub fn new_bytes<'local>(env: &mut JNIEnv<'local>, data: &[u8]) -> Result<JByteArray<'local>, String> {
    env.byte_array_from_slice(data)
        .map_err(|error| format!("failed to create byte[]: {error}"))
}

/// Copies a Java `int[]` into Rust memory.
pub fn read_ints(env: &mut JNIEnv, array: &JIntArray) -> Result<Vec<i32>, String> {
    let length = env
        .get_array_length(array)
        .map_err(|error| format!("failed to read int[] length: {error}"))? as usize;
    let mut values = vec![0i32; length];
    env.get_int_array_region(array, 0, &mut values)
        .map_err(|error| format!("failed to read int[]: {error}"))?;
    Ok(values)
}

/// Copies a Java `float[]` into Rust memory.
pub fn read_floats(env: &mut JNIEnv, array: &JFloatArray) -> Result<Vec<f32>, String> {
    let length = env
        .get_array_length(array)
        .map_err(|error| format!("failed to read float[] length: {error}"))? as usize;
    let mut values = vec![0f32; length];
    env.get_float_array_region(array, 0, &mut values)
        .map_err(|error| format!("failed to read float[]: {error}"))?;
    Ok(values)
}

/// Runs `body` against a borrowed view of a Java `byte[]` without copying it.
///
/// `convert_byte_array` copies the whole array into Rust memory, which for the codec path is a
/// measurable second pass over every payload. The critical-array API lends the Java heap memory instead;
/// the closure must not call back into Java while the view is alive (the borrow checker enforces that).
pub fn with_bytes<R>(
    env: &mut JNIEnv,
    array: &JByteArray,
    body: impl FnOnce(&[u8]) -> R,
) -> Result<R, String> {
    let elements = unsafe { env.get_array_elements_critical(array, ReleaseMode::NoCopyBack) }
        .map_err(|error| format!("failed to access byte[]: {error}"))?;
    // JNI's `jbyte` is `i8`; the payloads are byte strings, so the view is reinterpreted as `u8`. The
    // view lives only for this call and nothing is written through it.
    let pointer = elements.as_ptr().cast::<u8>();
    let slice = unsafe { std::slice::from_raw_parts(pointer, elements.len()) };
    Ok(body(slice))
}

/// Runs `body` against a mutable view of a Java `int[]` without copying it.
///
/// The renderer's output array can be several megabytes per frame, so it is written in place through
/// the JNI critical-array API. The closure must not call back into Java while the view is alive; the
/// JNI spec forbids it and the borrow checker enforces it structurally here.
pub fn with_ints_mut<R>(
    env: &mut JNIEnv,
    array: &JIntArray,
    body: impl FnOnce(&mut [i32]) -> R,
) -> Result<R, String> {
    let mut elements = unsafe { env.get_array_elements_critical(array, ReleaseMode::CopyBack) }
        .map_err(|error| format!("failed to access int[]: {error}"))?;
    Ok(body(&mut elements))
}

/// Throws an `IllegalStateException`; the Java facade catches it and disables the native layer.
pub fn throw(env: &mut JNIEnv, message: &str) {
    let _ = env.throw_new("java/lang/IllegalStateException", message);
}

fn panic_message(payload: &(dyn std::any::Any + Send)) -> &str {
    if let Some(message) = payload.downcast_ref::<&'static str>() {
        message
    } else if let Some(message) = payload.downcast_ref::<String>() {
        message.as_str()
    } else {
        "unknown panic payload"
    }
}

/// Runs a JNI entry body, converting an `Err` or a panic into a thrown Java exception.
///
/// `on_error` is what the entry returns after throwing: `null` for object results, the module's error
/// sentinel for primitives, because a returned primitive cannot carry the exception.
pub fn guard<'local, T>(
    env: &mut JNIEnv<'local>,
    on_error: T,
    body: impl FnOnce(&mut JNIEnv<'local>) -> Result<T, String>,
) -> T {
    match catch_unwind(AssertUnwindSafe(|| body(env))) {
        Ok(Ok(value)) => value,
        Ok(Err(message)) => {
            throw(env, &format!("native layer: {message}"));
            on_error
        }
        Err(payload) => {
            let message = format!("native layer panicked: {}", panic_message(&*payload));
            throw(env, &message);
            on_error
        }
    }
}
