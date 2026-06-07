use batkit::{
    correlate::{matched_filter, normalize_abs},
    peak::find_peaks,
    probe::{apply_hann_window, linear_chirp, normalize_peak, sine_ping},
    range::{delay_samples_to_distance_m, distance_m_to_delay_samples},
};
use jni::{
    objects::{JFloatArray, JObject},
    sys::{jfloatArray, jint, JNI_ERR, JNI_VERSION_1_6},
    JNIEnv, JavaVM, NativeMethod,
};
use std::ffi::c_void;

fn probe_from_args(
    sample_rate: f32,
    kind: jint,
    duration_ms: f32,
    primary_hz: f32,
    secondary_hz: f32,
    decay: f32,
) -> Vec<f32> {
    let duration_s = duration_ms / 1000.0;
    let mut samples = match kind {
        0 => linear_chirp(sample_rate, duration_s, primary_hz, secondary_hz),
        1 => sine_ping(sample_rate, duration_s, primary_hz, decay),
        _ => return Vec::new(),
    };
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

fn locate_echo(correlation: &[f32], sample_rate: f32) -> Option<(usize, usize, f32)> {
    let max = correlation
        .iter()
        .copied()
        .map(f32::abs)
        .fold(0.0, f32::max);
    let threshold = max * 0.15;
    let min_spacing = (sample_rate as usize / 1_000).max(1);
    let direct_window_end = (sample_rate as usize / 4).min(correlation.len().saturating_sub(1));

    let direct = find_peaks(correlation, 0, threshold, min_spacing)
        .into_iter()
        .find(|peak| peak.index <= direct_window_end)?;

    let echo_guard = (sample_rate as usize / 1_000).max(1);
    let echo_start = direct.index.saturating_add(echo_guard);
    let echo_end = direct
        .index
        .saturating_add(distance_m_to_delay_samples(4.0, sample_rate))
        .min(correlation.len().saturating_sub(1));

    let echo = find_peaks(correlation, echo_start, threshold, min_spacing)
        .into_iter()
        .find(|peak| peak.index <= echo_end)?;

    let delay = echo.index - direct.index;
    let distance = delay_samples_to_distance_m(delay, sample_rate);
    Some((direct.index, echo.index, distance))
}

extern "system" fn generate_probe(
    mut env: JNIEnv,
    _object: JObject,
    sample_rate: jint,
    probe_kind: jint,
    duration_ms: f32,
    primary_hz: f32,
    secondary_hz: f32,
    decay: f32,
) -> jfloatArray {
    if sample_rate <= 0 {
        return to_java_array(&mut env, &[]);
    }

    to_java_array(
        &mut env,
        &probe_from_args(
            sample_rate as f32,
            probe_kind,
            duration_ms,
            primary_hz,
            secondary_hz,
            decay,
        ),
    )
}

extern "system" fn analyze(
    mut env: JNIEnv,
    _object: JObject,
    recording: JFloatArray,
    sample_rate: jint,
    probe_kind: jint,
    duration_ms: f32,
    primary_hz: f32,
    secondary_hz: f32,
    decay: f32,
) -> jfloatArray {
    let fallback = [
        -1.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0,
        0.0, 0.0,
    ];
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

    let probe = probe_from_args(
        sample_rate as f32,
        probe_kind,
        duration_ms,
        primary_hz,
        secondary_hz,
        decay,
    );
    if probe.is_empty() {
        return to_java_array(&mut env, &fallback);
    }

    let mut correlation = matched_filter(&samples, &probe);
    normalize_abs(&mut correlation);
    let Some((direct_index, echo_index, distance)) = locate_echo(&correlation, sample_rate as f32)
    else {
        return to_java_array(&mut env, &fallback);
    };
    let mut result = Vec::with_capacity(18);
    result.push(distance);
    let direct_value = correlation[direct_index].clamp(0.0, 1.0);
    let echo_value = correlation[echo_index].clamp(0.0, 1.0);
    result.push(echo_value);
    result.push(direct_value);
    result.push(echo_value);

    let bins = 14usize;
    if correlation.len() >= 2 {
        let last = correlation.len() - 1;
        for i in 0..bins {
            let index = if bins == 1 {
                0
            } else {
                i * last / (bins - 1)
            };
            result.push(correlation[index].clamp(0.0, 1.0));
        }
    } else {
        result.extend(std::iter::repeat_n(0.0, bins));
    }

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
            sig: "(IIFFFF)[F".into(),
            fn_ptr: generate_probe as *mut c_void,
        },
        NativeMethod {
            name: "analyze".into(),
            sig: "([FIIFFFF)[F".into(),
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
    use batkit::{probe::apply_hann_window, range::distance_m_to_delay_samples};

    fn synthetic_recording(probe: &[f32], direct_gain: f32, echoes: &[(f32, f32)]) -> Vec<f32> {
        let max_echo_delay = echoes
            .iter()
            .map(|(distance_m, _)| distance_m_to_delay_samples(*distance_m, 48_000.0))
            .max()
            .unwrap_or(0);
        let mut rx = vec![0.0; probe.len() + max_echo_delay + 1024];

        for (i, &x) in probe.iter().enumerate() {
            rx[i] += direct_gain * x;
        }

        for &(distance_m, gain) in echoes {
            let delay = distance_m_to_delay_samples(distance_m, 48_000.0);
            for (i, &x) in probe.iter().enumerate() {
                rx[delay + i] += gain * x;
            }
        }

        rx
    }

    #[test]
    fn probe_has_expected_length() {
        assert_eq!(
            probe_from_args(48_000.0, 0, 20.0, 16_000.0, 22_000.0, 6.0).len(),
            960
        );
    }

    #[test]
    fn prefers_near_echo_over_stronger_late_clutter() {
        let mut probe = linear_chirp(48_000.0, 0.040, 16_000.0, 22_000.0);
        apply_hann_window(&mut probe);
        normalize_peak(&mut probe);

        let rx = synthetic_recording(&probe, 1.0, &[(0.50, 0.22), (59.0, 0.95)]);
        let mut correlation = matched_filter(&rx, &probe);
        normalize_abs(&mut correlation);

        let (_, _, distance) =
            locate_echo(&correlation, 48_000.0).expect("near echo should still be found");

        assert!((distance - 0.50).abs() < 0.10);
    }
}
