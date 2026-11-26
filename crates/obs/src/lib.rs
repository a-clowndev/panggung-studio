use std::ffi::{CStr, CString};
use std::ptr;
use thiserror::Error;

pub use obs_sys as sys;

#[derive(Debug, Error)]
pub enum ObsError {
    #[error("OBS startup failed")]
    StartupFailed,
    #[error("OBS video reset failed with code {0}")]
    VideoResetFailed(i32),
    #[error("OBS audio reset failed")]
    AudioResetFailed,
    #[error("Null pointer returned for {0}")]
    NullPointer(&'static str),
    #[error("Invalid C string")]
    InvalidString,
}

pub type Result<T> = std::result::Result<T, ObsError>;

pub struct ObsContext;

impl ObsContext {
    pub fn init(locale: &str, data_path: &str, module_path: &str, config_path: &str) -> Result<Self> {
        let c_locale = CString::new(locale).map_err(|_| ObsError::InvalidString)?;
        let c_data = CString::new(data_path).map_err(|_| ObsError::InvalidString)?;
        let c_mod = CString::new(module_path).map_err(|_| ObsError::InvalidString)?;
        let c_cfg = CString::new(config_path).map_err(|_| ObsError::InvalidString)?;

        unsafe {
            sys::obs_android_set_data_path(c_data.as_ptr());
            sys::obs_android_set_module_path(c_mod.as_ptr());
            if !sys::obs_startup(c_locale.as_ptr(), c_cfg.as_ptr(), ptr::null_mut()) {
                return Err(ObsError::StartupFailed);
            }
        }
        Ok(Self)
    }

    pub fn version_string() -> String {
        unsafe {
            let p = sys::obs_get_version_string();
            if p.is_null() {
                "unknown".into()
            } else {
                CStr::from_ptr(p).to_string_lossy().into_owned()
            }
        }
    }

    pub fn reset_video(&self, width: u32, height: u32, fps_num: u32, fps_den: u32) -> Result<()> {
        let gfx = CString::new("libobs-gles").unwrap();
        let mut ovi = sys::obs_video_info {
            graphics_module: gfx.as_ptr(),
            fps_num,
            fps_den,
            base_width: width,
            base_height: height,
            output_width: width,
            output_height: height,
            output_format: sys::video_format::VIDEO_FORMAT_NV12,
            adapter: 0,
            gpu_conversion: true,
            colorspace: sys::video_colorspace::VIDEO_CS_709,
            range: sys::video_range_type::VIDEO_RANGE_PARTIAL,
            scale_type: sys::obs_scale_type::OBS_SCALE_BICUBIC,
        };
        let res = unsafe { sys::obs_reset_video(&mut ovi) };
        if res != sys::OBS_VIDEO_SUCCESS {
            return Err(ObsError::VideoResetFailed(res));
        }
        Ok(())
    }

    pub fn reset_audio(&self, sample_rate: u32) -> Result<()> {
        let oai = sys::obs_audio_info {
            samples_per_sec: sample_rate,
            speakers: sys::speaker_layout::SPEAKERS_STEREO,
        };
        if !unsafe { sys::obs_reset_audio(&oai) } {
            return Err(ObsError::AudioResetFailed);
        }
        Ok(())
    }

    pub fn load_all_modules(&self) {
        unsafe {
            sys::obs_load_core_modules();
            sys::obs_post_load_modules();
            sys::obs_log_loaded_modules();
        }
    }

    pub fn list_input_types(&self) -> Vec<String> {
        let mut out = Vec::new();
        let mut idx = 0;
        let mut id: *const std::ffi::c_char = ptr::null();
        while unsafe { sys::obs_enum_input_types(idx, &mut id) } {
            if !id.is_null() {
                out.push(unsafe { CStr::from_ptr(id).to_string_lossy().into_owned() });
            }
            idx += 1;
        }
        out
    }
}

impl Drop for ObsContext {
    fn drop(&mut self) {
        unsafe {
            if sys::obs_initialized() {
                sys::obs_shutdown();
            }
        }
    }
}

pub struct ObsData {
    ptr: *mut sys::obs_data_t,
}

impl ObsData {
    pub fn new() -> Result<Self> {
        let ptr = unsafe { sys::obs_data_create() };
        if ptr.is_null() {
            return Err(ObsError::NullPointer("obs_data_t"));
        }
        Ok(Self { ptr })
    }

    pub fn set_int(&mut self, key: &str, val: i64) -> Result<()> {
        let k = CString::new(key).map_err(|_| ObsError::InvalidString)?;
        unsafe { sys::obs_data_set_int(self.ptr, k.as_ptr(), val) };
        Ok(())
    }

    pub fn set_string(&mut self, key: &str, val: &str) -> Result<()> {
        let k = CString::new(key).map_err(|_| ObsError::InvalidString)?;
        let v = CString::new(val).map_err(|_| ObsError::InvalidString)?;
        unsafe { sys::obs_data_set_string(self.ptr, k.as_ptr(), v.as_ptr()) };
        Ok(())
    }

    pub fn as_ptr(&self) -> *mut sys::obs_data_t {
        self.ptr
    }
}

impl Drop for ObsData {
    fn drop(&mut self) {
        unsafe { sys::obs_data_release(self.ptr) };
    }
}
