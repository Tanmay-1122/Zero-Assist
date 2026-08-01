/// Returns true if the Linux kernel has the memory cgroup controller enabled.
///
/// Probes cgroup v2 (`/sys/fs/cgroup/memory.max`), then cgroup v1
/// (`/sys/fs/cgroup/memory/memory.limit_in_bytes`), then `/proc/cgroups`.
/// Any read error is treated as "absent" (conservative/safe direction).
#[cfg(target_os = "linux")]
pub fn linux_memcg_available() -> bool {
    use std::path::Path;

    if Path::new("/sys/fs/cgroup/memory.max").exists() {
        return true;
    }
    if Path::new("/sys/fs/cgroup/memory/memory.limit_in_bytes").exists() {
        return true;
    }
    if let Ok(content) = std::fs::read_to_string("/proc/cgroups") {
        for line in content.lines() {
            if line.starts_with('#') {
                continue;
            }
            let mut cols = line.split_whitespace();
            let name = cols.next().unwrap_or("");
            let _hierarchy = cols.next();
            let _num_cgroups = cols.next();
            let enabled = cols.next().unwrap_or("0");
            if name == "memory" && enabled == "1" {
                return true;
            }
        }
    }
    false
}

/// Non-Linux stub — always returns false.
#[cfg(not(target_os = "linux"))]
pub fn linux_memcg_available() -> bool {
    false
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn linux_memcg_available_returns_bool() {
        let _result: bool = linux_memcg_available();
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn linux_memcg_cgroup_v2_path_probe_does_not_panic() {
        let _ = std::path::Path::new("/sys/fs/cgroup/memory.max").exists();
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn linux_memcg_proc_cgroups_parses_without_panic() {
        if let Ok(content) = std::fs::read_to_string("/proc/cgroups") {
            let _found = content.lines().filter(|l| !l.starts_with('#')).any(|l| {
                let mut f = l.split_whitespace();
                let name = f.next().unwrap_or("");
                let _hier = f.next();
                let _num = f.next();
                let enabled = f.next().unwrap_or("0");
                name == "memory" && enabled == "1"
            });
        }
    }
}
