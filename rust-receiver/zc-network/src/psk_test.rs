use rustls::client::Resumption;

fn main() {
    let config = rustls::ClientConfig::builder()
        .with_root_certificates(rustls::RootCertStore::empty())
        .with_no_client_auth();
    // How to add PSK?
}
