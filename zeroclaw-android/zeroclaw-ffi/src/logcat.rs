/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

//! Android logcat integration.
//!
//! On Android, native `stderr` (used by `eprintln!` and the default
//! `tracing_subscriber` writer) is discarded by the runtime and never
//! reaches `adb logcat`. This module redirects fd 2 to a pipe whose read
//! end is pumped by a background thread that forwards every line to
//! logcat via `__android_log_write`, making Rust-side diagnostics visible
//! under the `ZeroAssistRust` tag.

use std::sync::atomic::{AtomicBool, Ordering};

#[cfg(target_os = "android")]
#[link(name = "log")]
unsafe extern "C" {
    fn __android_log_write(prio: i32, tag: *const std::ffi::c_char, msg: *const std::ffi::c_char) -> i32;
}

/// `ANDROID_LOG_WARN` priority so lines are visible in default logcat filters.
#[cfg(target_os = "android")]
const ANDROID_LOG_WARN: i32 = 5;

/// Forwards a single line to logcat under the `ZeroAssistRust` tag.
#[cfg(target_os = "android")]
fn logcat_line(prio: i32, line: &str) {
    let tag = b"ZeroAssistRust\0";
    let mut msg = line.as_bytes().to_vec();
    msg.push(0);
    unsafe {
        __android_log_write(prio, tag.as_ptr().cast(), msg.as_ptr().cast());
    }
}

/// Redirects process stderr (fd 2) into logcat.
///
/// Idempotent: subsequent calls are no-ops. On non-Android targets this
/// does nothing (e.g. desktop test binaries keep normal stderr).
pub(crate) fn redirect_stderr_to_logcat() {
    static REDIRECTED: AtomicBool = AtomicBool::new(false);
    if REDIRECTED.swap(true, Ordering::SeqCst) {
        return;
    }

    #[cfg(target_os = "android")]
    {
        use std::os::fd::FromRawFd;
        let mut fds = [0i32; 2];
        if unsafe { libc::pipe(fds.as_mut_ptr()) } != 0 {
            return;
        }
        let (read_fd, write_fd) = (fds[0], fds[1]);
        unsafe {
            libc::dup2(write_fd, 2);
            libc::close(write_fd);
        }
        std::thread::spawn(move || {
            let file = unsafe { std::fs::File::from_raw_fd(read_fd) };
            let mut reader = std::io::BufReader::new(file);
            let mut line = String::new();
            loop {
                line.clear();
                match std::io::BufRead::read_line(&mut reader, &mut line) {
                    Ok(0) => break,
                    Ok(_) => logcat_line(ANDROID_LOG_WARN, line.trim_end()),
                    Err(_) => break,
                }
            }
        });
    }
}
