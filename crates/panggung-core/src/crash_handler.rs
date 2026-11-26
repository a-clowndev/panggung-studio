use std::fs::OpenOptions;
use std::io::Write;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;

static CRASH_LOG_DIR: Mutex<Option<String>> = Mutex::new(None);
static CRASH_HANDLER_INSTALLED: AtomicBool = AtomicBool::new(false);

extern "C" fn crash_signal_handler(sig: libc::c_int) {
    let dir = CRASH_LOG_DIR
        .try_lock()
        .ok()
        .and_then(|g| g.clone())
        .unwrap_or_else(|| "/data/local/tmp".into());
    let path = format!("{dir}/last_crash.log");
    if let Ok(mut f) = OpenOptions::new().create(true).write(true).truncate(true).open(&path) {
        let ts = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs())
            .unwrap_or(0);
        let _ = writeln!(f, "Panggung Studio Native Crash Report");
        let _ = writeln!(f, "timestamp={ts}");
        let _ = writeln!(f, "signal={sig}");
        let _ = writeln!(f, "obs_version={}", obs::ObsContext::version_string());
    }
    // Re-raise default handler so Android tombstone is still generated
    unsafe {
        libc::signal(sig, libc::SIG_DFL);
        libc::raise(sig);
    }
}

pub fn install_crash_handler(config_dir: &str) {
    *CRASH_LOG_DIR.lock().unwrap() = Some(config_dir.to_string());
    if CRASH_HANDLER_INSTALLED.swap(true, Ordering::SeqCst) {
        return;
    }
    unsafe {
        libc::signal(libc::SIGSEGV, crash_signal_handler as libc::sighandler_t);
        libc::signal(libc::SIGABRT, crash_signal_handler as libc::sighandler_t);
        libc::signal(libc::SIGBUS, crash_signal_handler as libc::sighandler_t);
        libc::signal(libc::SIGFPE, crash_signal_handler as libc::sighandler_t);
    }
}

pub fn read_and_clear_last_crash(config_dir: &str) -> Option<String> {
    let path = format!("{config_dir}/last_crash.log");
    let content = std::fs::read_to_string(&path).ok()?;
    let _ = std::fs::remove_file(&path);
    Some(content)
}
