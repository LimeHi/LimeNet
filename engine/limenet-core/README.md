# LimeNet Core (experimental)

First native-core milestone: a local SOCKS5 `CONNECT` listener with one outbound
protocol, VLESS over TLS/TCP. TLS certificate verification is enabled and cannot
be disabled. This is experimental and is not yet wired into the LimeNet UI or
Android VPN service; Xray and Sing-box remain the production engines.

## Build and test

```powershell
cargo test --manifest-path engine/limenet-core/Cargo.toml
cargo build --release --manifest-path engine/limenet-core/Cargo.toml
```

On Windows, install the Rust MSVC toolchain and Visual Studio C++ Build Tools.
If building from a plain PowerShell without the MSVC environment, run Cargo from
an x64 Native Tools command prompt or first load `vcvars64.bat`.

## Config

Pass a JSON file with `--config`. Keep the file private because it contains the
VLESS UUID. Example:

```json
{
  "listen": "127.0.0.1:10808",
  "server": "edge.example.net",
  "port": 443,
  "uuid": "00112233-4455-6677-8899-aabbccddeeff",
  "sni": "edge.example.net",
  "connect_timeout_secs": 10
}
```

Run `target/release/limenet-core.exe --config private-config.json`. Current
limits: SOCKS5 no-auth `CONNECT` only; outbound VLESS TCP over TLS; domain,
IPv4 and IPv6 destinations. No Reality, transports, UDP, TUN, routing rules,
DNS proxy, VMess, Trojan or Shadowsocks yet.

Bind only to `127.0.0.1` or `::1`: this prototype has no SOCKS authentication
and rejects non-loopback listeners. The config includes a VLESS UUID; keep it
out of source control and logs.

The test suite includes an in-process TLS VLESS server with a temporary
self-signed certificate. It verifies the SOCKS5 handshake, VLESS UUID and
destination encoding, TLS certificate validation, and bidirectional TCP relay;
the test certificate is trusted only by that test client and is not a runtime
option to disable production certificate checks.
