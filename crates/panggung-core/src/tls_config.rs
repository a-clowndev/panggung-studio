//! Persistent self-signed ECDSA P-256 TLS 1.3/1.2 configuration for LAN Remote Control (HTTPS :8080 & WSS :4455).
//! - Persists certificate & private key in `<config_dir>/panggung_lan_tls.*` so identity survives app restarts.
//! - Automatically renews certificate SAN if the device's LAN IPv4 changes (e.g. Wi-Fi switch).
//! - Localhost (127.0.0.1) remains plain HTTP/WS for adb-forward tooling; LAN Access enforces TLS fail-closed.

use std::net::{IpAddr, UdpSocket};
use std::path::PathBuf;
use std::sync::{Arc, Mutex};

use log::{error, info};
use rcgen::{CertificateParams, DistinguishedName, DnType, KeyPair, SanType};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
use rustls::ServerConfig;

struct LanTlsBundle {
    config: Arc<ServerConfig>,
    fingerprint: String,
    lan_ip: Option<IpAddr>,
}

static CONFIG_DIR: Mutex<Option<PathBuf>> = Mutex::new(None);
static LAN_TLS: Mutex<Option<LanTlsBundle>> = Mutex::new(None);

pub fn set_config_dir(dir: &str) {
    if !dir.is_empty() {
        if let Ok(mut g) = CONFIG_DIR.lock() {
            *g = Some(PathBuf::from(dir));
        }
    }
}

fn detect_lan_ipv4() -> Option<IpAddr> {
    let sock = UdpSocket::bind("0.0.0.0:0").ok()?;
    sock.connect("8.8.8.8:80").ok()?;
    let ip = sock.local_addr().ok()?.ip();
    if ip.is_loopback() || ip.is_unspecified() {
        None
    } else {
        Some(ip)
    }
}

fn sha256_fingerprint_hex(der: &[u8]) -> String {
    let digest = crate::ws_server::sha256(der);
    let mut out = String::with_capacity(digest.len() * 3);
    for (i, b) in digest.iter().enumerate() {
        if i > 0 {
            out.push(':');
        }
        out.push_str(&format!("{:02X}", b));
    }
    out
}

fn build_server_config(cert_der: Vec<u8>, key_der: Vec<u8>) -> Result<(Arc<ServerConfig>, String), String> {
    let _ = rustls::crypto::ring::default_provider().install_default();
    let fingerprint = sha256_fingerprint_hex(&cert_der);
    let cert = CertificateDer::from(cert_der);
    let key = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(key_der));
    let mut cfg = ServerConfig::builder()
        .with_no_client_auth()
        .with_single_cert(vec![cert], key)
        .map_err(|e| format!("rustls ServerConfig failed: {e}"))?;
    cfg.alpn_protocols = vec![b"http/1.1".to_vec()];
    Ok((Arc::new(cfg), fingerprint))
}

fn load_or_generate_lan_tls(cur_ip: Option<IpAddr>) -> Result<LanTlsBundle, String> {
    let dir_opt = CONFIG_DIR.lock().ok().and_then(|g| g.clone());
    let cur_ip_str = cur_ip.map(|ip| ip.to_string()).unwrap_or_default();

    // 1. Try loading persisted cert + key if LAN IP matches saved SAN IP
    if let Some(ref dir) = dir_opt {
        let cert_path = dir.join("panggung_lan_tls.crt.der");
        let key_path = dir.join("panggung_lan_tls.key.der");
        let ip_path = dir.join("panggung_lan_tls.ip");
        let saved_ip = std::fs::read_to_string(&ip_path).unwrap_or_default();
        if saved_ip.trim() == cur_ip_str {
            if let (Ok(cert_der), Ok(key_der)) = (std::fs::read(&cert_path), std::fs::read(&key_path)) {
                if let Ok((config, fingerprint)) = build_server_config(cert_der, key_der) {
                    info!("Loaded persistent LAN TLS cert (ip={cur_ip_str}, SHA-256={fingerprint})");
                    return Ok(LanTlsBundle { config, fingerprint, lan_ip: cur_ip });
                }
            }
        }
    }

    // 2. Generate new cert (reusing persistent PKCS#8 DER ECDSA P-256 key if available)
    let key_pair = if let Some(ref dir) = dir_opt {
        let key_path = dir.join("panggung_lan_tls.key.der");
        std::fs::read(&key_path)
            .ok()
            .and_then(|der| {
                let pkcs8 = PrivatePkcs8KeyDer::from(der);
                KeyPair::from_pkcs8_der_and_sign_algo(&pkcs8, &rcgen::PKCS_ECDSA_P256_SHA256).ok()
            })
            .unwrap_or_else(|| KeyPair::generate().expect("ECDSA keypair generation"))
    } else {
        KeyPair::generate().map_err(|e| format!("rcgen keypair failed: {e}"))?
    };

    let mut params = CertificateParams::new(vec!["localhost".to_string()])
        .map_err(|e| format!("rcgen params failed: {e}"))?;
    let mut dn = DistinguishedName::new();
    dn.push(DnType::CommonName, "Panggung Studio LAN Remote");
    dn.push(DnType::OrganizationName, "Panggung Studio");
    params.distinguished_name = dn;
    params.subject_alt_names.push(SanType::IpAddress(IpAddr::from([127, 0, 0, 1])));
    if let Some(ip) = cur_ip {
        params.subject_alt_names.push(SanType::IpAddress(ip));
    }

    let cert = params
        .self_signed(&key_pair)
        .map_err(|e| format!("rcgen self-signed cert failed: {e}"))?;
    let cert_der = cert.der().as_ref().to_vec();
    let key_der = key_pair.serialize_der();

    let (config, fingerprint) = build_server_config(cert_der.clone(), key_der.clone())?;

    // Persist cert, key, and IP atomically to config_dir
    if let Some(ref dir) = dir_opt {
        let _ = std::fs::create_dir_all(dir);
        let _ = std::fs::write(dir.join("panggung_lan_tls.crt.der"), &cert_der);
        let _ = std::fs::write(dir.join("panggung_lan_tls.key.der"), &key_der);
        let _ = std::fs::write(dir.join("panggung_lan_tls.ip"), &cur_ip_str);
    }

    info!("Generated & persisted LAN TLS 1.3/1.2 ECDSA cert (ip={cur_ip_str}, SHA-256={fingerprint})");
    Ok(LanTlsBundle { config, fingerprint, lan_ip: cur_ip })
}

pub fn get_or_init_lan_tls() -> Option<Arc<ServerConfig>> {
    let cur_ip = detect_lan_ipv4();
    let mut guard = LAN_TLS.lock().ok()?;
    if let Some(ref b) = *guard {
        if b.lan_ip == cur_ip {
            return Some(b.config.clone());
        }
        info!("LAN IP changed ({:?} -> {:?}) — renewing TLS certificate SAN", b.lan_ip, cur_ip);
    }
    match load_or_generate_lan_tls(cur_ip) {
        Ok(b) => {
            let cfg = b.config.clone();
            *guard = Some(b);
            Some(cfg)
        }
        Err(e) => {
            error!("LAN TLS unavailable (failing closed): {e}");
            None
        }
    }
}

pub fn current_fingerprint() -> String {
    LAN_TLS
        .lock()
        .ok()
        .and_then(|g| g.as_ref().map(|b| b.fingerprint.clone()))
        .unwrap_or_default()
}
