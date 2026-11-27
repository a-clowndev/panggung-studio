//! Self-signed ECDSA P-256 TLS 1.3/1.2 configuration for LAN Remote Control (HTTPS :8080 & WSS :4455).
//! Localhost (127.0.0.1) stays plain HTTP/WS for zero-friction adb-forward tooling;
//! enabling LAN Access (0.0.0.0) automatically enforces TLS encryption on both ports.

use std::net::{IpAddr, UdpSocket};
use std::sync::{Arc, OnceLock};

use log::{error, info};
use rcgen::{CertificateParams, DistinguishedName, DnType, KeyPair, SanType};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
use rustls::ServerConfig;

struct LanTlsBundle {
    config: Arc<ServerConfig>,
    fingerprint: String,
}

static LAN_TLS: OnceLock<Result<LanTlsBundle, String>> = OnceLock::new();

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

fn build_lan_tls() -> Result<LanTlsBundle, String> {
    let _ = rustls::crypto::ring::default_provider().install_default();

    let mut params = CertificateParams::new(vec!["localhost".to_string()])
        .map_err(|e| format!("rcgen params failed: {e}"))?;
    let mut dn = DistinguishedName::new();
    dn.push(DnType::CommonName, "Panggung Studio LAN Remote");
    dn.push(DnType::OrganizationName, "Panggung Studio");
    params.distinguished_name = dn;
    params.subject_alt_names.push(SanType::IpAddress(IpAddr::from([127, 0, 0, 1])));
    if let Some(lan_ip) = detect_lan_ipv4() {
        params.subject_alt_names.push(SanType::IpAddress(lan_ip));
    }

    let key_pair = KeyPair::generate().map_err(|e| format!("rcgen keypair failed: {e}"))?;
    let cert = params
        .self_signed(&key_pair)
        .map_err(|e| format!("rcgen self-signed cert failed: {e}"))?;

    let cert_der: CertificateDer<'static> = cert.der().clone();
    let fingerprint = sha256_fingerprint_hex(cert_der.as_ref());
    let key_der = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(key_pair.serialize_der()));

    let mut cfg = ServerConfig::builder()
        .with_no_client_auth()
        .with_single_cert(vec![cert_der], key_der)
        .map_err(|e| format!("rustls ServerConfig failed: {e}"))?;
    cfg.alpn_protocols = vec![b"http/1.1".to_vec()];

    info!("Initialized LAN TLS 1.3/1.2 self-signed ECDSA cert (SHA-256={fingerprint})");
    Ok(LanTlsBundle {
        config: Arc::new(cfg),
        fingerprint,
    })
}

pub fn get_or_init_lan_tls() -> Option<Arc<ServerConfig>> {
    match LAN_TLS.get_or_init(build_lan_tls) {
        Ok(b) => Some(b.config.clone()),
        Err(e) => {
            error!("LAN TLS unavailable (failing closed): {e}");
            None
        }
    }
}

pub fn current_fingerprint() -> String {
    match LAN_TLS.get() {
        Some(Ok(b)) => b.fingerprint.clone(),
        _ => String::new(),
    }
}
