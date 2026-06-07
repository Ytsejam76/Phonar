use batkit::{
    correlate::{matched_filter, normalize_abs},
    peak::find_peaks,
    probe::{apply_hann_window, linear_chirp, normalize_peak},
    range::delay_samples_to_distance_m,
};
use jni::{
    objects::{JFloatArray, JObject},
    sys::{jfloatArray, jint, JNI_ERR, JNI_VERSION_1_6},
    JNIEnv, JavaVM, NativeMethod,
};
use std::ffi::c_void;

const PROBE_DURATION_S: f32 = 0.020;
const START_HZ: f32 = 16_000.0;
const END_HZ: f32 = 22_000.0;

fn probe(sample_rate: f32) -> Vec<f32> {
    let mut samples = linear_chirp(sample_rate, PROBE_DURATION_S, START_HZ, END_HZ);
    apply_hann_window(&mut samples);
    normalize_peak(&mut samples);
    samples
}

fn to_java_array(env: &mut JNIEnv, values: &[f32]) -> jfloatArray {
    let Ok(result) = env.new_float_array(values.len() as i32) else {
        return std::ptr::null_mut();
    };

    if env.set_float_array_region(&result, 0, values).is_err() {
        return std::ptr::null_mut();
    }

    result.into_raw()
}

extern "system" fn generate_probe(
    mut env: JNIEnv,
    _object: JObject,
    sample_rate: jint,
) -> jfloatArray {
    if sample_rate <= 0 {
        return to_java_array(&mut env, &[]);
    }

    to_java_array(&mut env, &probe(sample_rate as f32))
}

extern "system" fn analyze(
    mut env: JNIEnv,
    _object: JObject,
    recording: JFloatArray,
    sample_rate: jint,
) -> jfloatArray {
    let fallback = [-1.0, 0.0];
    if sample_rate <= 0 {
        return to_java_array(&mut env, &fallback);
    }

    let Ok(len) = env.get_array_length(&recording) else {
        return to_java_array(&mut env, &fallback);
    };
    let mut samples = vec![0.0; len as usize];
    if env.get_float_array_region(&recording, 0, &mut samples).is_err() {
        return to_java_array(&mut env, &fallback);
    }

    let mut correlation = matched_filter(&samples, &probe(sample_rate as f32));
    normalize_abs(&mut correlation);
    let min_spacing = (sample_rate as usize / 1_000).max(1);
    let peaks = find_peaks(&correlation, 0, 0.18, min_spacing);

    let Some(direct) = peaks.iter().copied().max_by(|a, b| {
        a.value
            .total_cmp(&b.value)
            .then_with(|| b.index.cmp(&a.index))
    }) else {
        return to_java_array(&mut env, &fallback);
    };
    let echo_guard = sample_rate as usize / 2_000;
    let max_echo_delay = sample_rate as usize / 10;
    let echo_start = direct.index.saturating_add(echo_guard);
    let echo_end = direct
        .index
        .saturating_add(max_echo_delay)
        .min(correlation.len().saturating_sub(1));

    let echo = peaks
        .iter()
        .filter(|peak| peak.index >= echo_start && peak.index <= echo_end)
        .max_by(|a, b| a.value.total_cmp(&b.value));

    let Some(echo) = echo else {
        return to_java_array(&mut env, &fallback);
    };

    let delay = echo.index - direct.index;
    let result = [
        delay_samples_to_distance_m(delay, sample_rate as f32),
        echo.value.clamp(0.0, 1.0),
    ];
    to_java_array(&mut env, &result)
}

#[no_mangle]
pub extern "system" fn JNI_OnLoad(vm: JavaVM, _reserved: *mut c_void) -> jint {
    let Ok(mut env) = vm.get_env() else {
        return JNI_ERR;
    };

    let methods = [
        NativeMethod {
            name: "generateProbe".into(),
            sig: "(I)[F".into(),
            fn_ptr: generate_probe as *mut c_void,
        },
        NativeMethod {
            name: "analyze".into(),
            sig: "([FI)[F".into(),
            fn_ptr: analyze as *mut c_void,
        },
    ];

    match env.register_native_methods("com/ytsejam/phonar/BatKitNative", &methods) {
        Ok(()) => JNI_VERSION_1_6,
        Err(_) => JNI_ERR,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn probe_has_expected_length() {
        assert_eq!(probe(48_000.0).len(), 960);
    }
}
