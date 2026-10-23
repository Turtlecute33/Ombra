use crate::positive_u31::PositiveU31;
use crate::quic_tuning::small_udp_payload;
use anyhow::Context;
use serde::{Deserialize, Serialize};
use socket2::{Domain, Protocol, Socket, Type};
use std::io;
use std::net::{Ipv4Addr, SocketAddrV4};
#[cfg(not(target_os = "windows"))]
use std::os::fd::AsRawFd;
#[cfg(not(target_os = "windows"))]
use std::ptr::addr_of_mut;
#[cfg(not(target_os = "windows"))]
use std::{mem, ptr};

#[cfg(target_os = "linux")]
pub const FWMARK: u32 = u32::from_be_bytes(*b"obsc");

/// UDP socket buffer sizes (Ombra patch P4, B6). When the single quinn driver runs late (little core, thermal limit),
/// a small kernel default (often 208 KiB on Android) drops bursts, which looks like path loss to the relay and to the
/// inner TCP. Without CAP_NET_ADMIN the kernel caps them at `rmem_max` and `wmem_max`. Only UDP: SO_RCVBUF on a TCP
/// socket turns off receive autotuning.
const UDP_RECV_BUFFER_SIZE: usize = 4 << 20;
const UDP_SEND_BUFFER_SIZE: usize = 2 << 20;

#[derive(PartialEq, Eq, Clone, Debug, Deserialize, Serialize)]
pub struct NetworkInterface {
    pub name: String,
    pub index: PositiveU31,
    /// Android: `Network.getNetworkHandle()` of the network. The outer sockets are bound to it (Ombra patch P4, D1).
    /// 0: no binding.
    #[serde(default)]
    pub network_handle: u64,
    /// The MTU that the OS side found for the network, on Android CLAT-aware (Ombra patch P4). `None`: unknown.
    #[serde(default)]
    pub mtu_hint: Option<u16>,
    #[cfg(target_os = "windows")]
    pub ip: std::net::IpAddr,
    #[cfg(target_os = "windows")]
    pub mtu: i32,
}

impl NetworkInterface {
    /// Whether both are the same network, maybe with another MTU hint.
    pub fn same_network(&self, other: &Self) -> bool {
        let same = self.name == other.name && self.index == other.index && self.network_handle == other.network_handle;
        #[cfg(target_os = "windows")]
        let same = same && self.ip == other.ip;
        same
    }

    /// The interface MTU for the MTU decisions: the smaller of the hint and `read_mtu` (what Rust read from the
    /// interface), or the one that is known. On an IPv6-only Android network the IPv4 packets to the relay go through
    /// the CLAT interface (`v4-` + name), whose MTU only the hint has.
    pub fn decision_mtu(&self, read_mtu: Option<u16>) -> Option<u16> {
        self.mtu_hint.into_iter().chain(read_mtu).min()
    }
}

fn new_socket(network_interface: Option<&NetworkInterface>, ty: Type, protocol: Option<Protocol>) -> io::Result<Socket> {
    let socket = Socket::new(Domain::IPV4, ty, protocol)?;
    #[cfg(not(any(target_os = "android", target_os = "linux", target_os = "windows")))]
    if let Some(network_interface) = network_interface {
        socket.bind_device_by_index_v4(Some(network_interface.index.into()))?;
    }
    #[cfg(target_os = "linux")]
    {
        let _ = network_interface;
        socket.set_mark(FWMARK)?;
    }
    #[allow(unused_mut)]
    let mut bind_addr = SocketAddrV4::new(Ipv4Addr::UNSPECIFIED, 0).into();
    #[cfg(target_os = "windows")]
    if let Some(interface) = network_interface {
        bind_addr = std::net::SocketAddr::new(interface.ip, 0).into();
    }
    #[cfg(target_os = "android")]
    if let Some(network_interface) = network_interface
        && network_interface.network_handle != 0
    {
        bind_to_android_network(&socket, network_interface.network_handle)?;
    }
    socket.bind(&bind_addr)?;
    Ok(socket)
}

/// Binds the socket to the Android network with this `Network.getNetworkHandle()` (Ombra patch P4, D1). Its packets
/// then leave on that network, not on the default network of the moment, and a network change needs a new socket.
#[cfg(target_os = "android")]
fn bind_to_android_network(socket: &Socket, network_handle: u64) -> io::Result<()> {
    // NDK `<android/multinetwork.h>`, API 23. `net_handle_t` is a `uint64_t`.
    #[link(name = "android")]
    unsafe extern "C" {
        fn android_setsocknetwork(network: u64, fd: libc::c_int) -> libc::c_int;
    }
    // SAFETY: the socket is open for the call, which only sets the network of the socket.
    match unsafe { android_setsocknetwork(network_handle, socket.as_raw_fd()) } {
        0 => Ok(()),
        _ => Err(io::Error::last_os_error()),
    }
}

pub fn new_udp(network_interface: Option<&NetworkInterface>) -> io::Result<std::net::UdpSocket> {
    let socket = new_socket(network_interface, Type::DGRAM, Some(Protocol::UDP))?;
    // A failure shows in the effective sizes, which are logged.
    let _ = socket.set_recv_buffer_size(UDP_RECV_BUFFER_SIZE);
    let _ = socket.set_send_buffer_size(UDP_SEND_BUFFER_SIZE);
    tracing::info!(
        message_id = "Bf6pWz2R",
        recv_buffer_size = ?socket.recv_buffer_size(),
        send_buffer_size = ?socket.send_buffer_size(),
        "UDP socket buffers"
    );
    Ok(socket.into())
}

pub fn new_nonblocking_tcp(network_interface: Option<&NetworkInterface>) -> io::Result<tokio::net::TcpSocket> {
    let socket = new_socket(network_interface, Type::STREAM, Some(Protocol::TCP))?;
    socket.set_nonblocking(true)?;
    Ok(tokio::net::TcpSocket::from_std_stream(socket.into()))
}

#[cfg(not(any(target_os = "android", target_os = "ios", target_os = "macos", target_os = "windows")))]
const SIOCGIFMTU: libc::Ioctl = libc::SIOCGIFMTU;

#[cfg(target_os = "android")]
const SIOCGIFMTU: libc::Ioctl = crate::int_helper::try_c_ulong_into_c_int(libc::SIOCGIFMTU).unwrap();

#[cfg(any(target_os = "ios", target_os = "macos"))]
const SIOCGIFMTU: libc::c_ulong = 3223349555; // From sys/sockio.h.

#[cfg(not(target_os = "windows"))]
pub fn interface_mtu(interface: &NetworkInterface) -> anyhow::Result<i32> {
    let name = &interface.name;
    let socket = Socket::new_raw(Domain::IPV4, Type::DGRAM, None)?;

    let mut name_buf: [u8; libc::IFNAMSIZ] = [0; _];
    // Note: It isn't clear if the name needs to be null terminated if it is the maximum length but we just assume so.
    anyhow::ensure!(name_buf.len() > name.len(), "Interface name too long.");
    name_buf[..name.len()].copy_from_slice(name.as_bytes());
    #[allow(clippy::useless_transmute)]
    let name_buf: [libc::c_char; libc::IFNAMSIZ] = unsafe { mem::transmute(name_buf) };

    unsafe {
        let mut ifreq = mem::MaybeUninit::<libc::ifreq>::uninit();
        ptr::write(addr_of_mut!((*ifreq.as_mut_ptr()).ifr_name), name_buf);

        let r = libc::ioctl(socket.as_raw_fd(), SIOCGIFMTU, ifreq.as_mut_ptr());
        if r < 0 {
            Err(io::Error::last_os_error().into())
        } else {
            Ok(ifreq.assume_init().ifr_ifru.ifru_mtu)
        }
    }
}

#[cfg(target_os = "windows")]
pub fn interface_mtu(interface: &NetworkInterface) -> anyhow::Result<i32> {
    Ok(interface.mtu)
}

/// The QUIC endpoint of the relay connections, and whether it asks the relays for small UDP payloads. A network change
/// moves it to a new socket (Ombra patch P4, D1).
#[derive(Clone)]
pub struct RelayEndpoint {
    pub endpoint: quinn::Endpoint,
    pub small_udp_payload: bool,
}

pub fn new_quic(udp: std::net::UdpSocket, mtu: Option<u16>, force_small_mtu: bool) -> anyhow::Result<RelayEndpoint> {
    let runtime = quinn::default_runtime().context("no quinn runtime found")?;
    let mut endpoint_config = quinn::EndpointConfig::default();
    let small_udp_payload = small_udp_payload(mtu, force_small_mtu);
    if small_udp_payload {
        match force_small_mtu {
            true => tracing::info!(
                message_id = "kq0AuTsT",
                "forcing relay to use small UDP payload due to small MTU experimental flag being set"
            ),
            false => tracing::info!(
                message_id = "TF51QUHb",
                mtu,
                "forcing relay to use small UDP payload due to low network MTU"
            ),
        }
        // TODO: Remove once relays does MTU discovery https://linear.app/soveng/issue/OBS-3201/replace-client-side-max-udp-payload-size-constraint-with-relay-side
        endpoint_config
            // A less conservative udp payload size could be calculated as `mtu - IPV4_UDP_OVERHEAD`, but:
            // - this is an uncommon case (for networks with very low MTU)
            // - packet size distribution tends to be bimodal, the exact fragmentation threshold doesn't matter much
            // - technically QUIC and IP overhead aren't fixed
            // - this will be removed once the relay supports MTU discovery
            // - 1200 is the hard lower limit for QUIC and easily fits WG fragments and has the best compatibility with low-MTU network environments
            .max_udp_payload_size(1200)
            .context("invalid max_udp_payload_size")?;
    }
    let endpoint = quinn::Endpoint::new(endpoint_config, None, udp, runtime)?;
    Ok(RelayEndpoint { endpoint, small_udp_payload })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn udp_socket_buffers_are_larger_than_the_default() {
        let default = Socket::new(Domain::IPV4, Type::DGRAM, Some(Protocol::UDP)).unwrap();
        let tuned = Socket::from(new_udp(None).unwrap());
        assert!(tuned.recv_buffer_size().unwrap() > default.recv_buffer_size().unwrap());
        assert!(tuned.send_buffer_size().unwrap() > default.send_buffer_size().unwrap());
    }

    fn interface(network_handle: u64, mtu_hint: Option<u16>) -> NetworkInterface {
        NetworkInterface { name: "wlan0".into(), index: PositiveU31::try_from(3).unwrap(), network_handle, mtu_hint }
    }

    #[test]
    fn the_mtu_hint_counts_when_it_is_smaller_or_the_only_one() {
        // CLAT on an IPv6-only network: the hint has the smaller MTU of the `v4-` interface.
        assert_eq!(interface(1, Some(1480)).decision_mtu(Some(1500)), Some(1480));
        assert_eq!(interface(1, Some(1500)).decision_mtu(Some(1400)), Some(1400));
        assert_eq!(interface(1, Some(1280)).decision_mtu(None), Some(1280));
        assert_eq!(interface(1, None).decision_mtu(Some(1500)), Some(1500));
        assert_eq!(interface(1, None).decision_mtu(None), None);
    }

    #[test]
    fn another_mtu_hint_is_the_same_network() {
        assert!(interface(1, Some(1500)).same_network(&interface(1, Some(1280))));
        assert!(interface(1, None).same_network(&interface(1, Some(1280))));
        assert!(!interface(1, None).same_network(&interface(2, None)));
        let other_interface = NetworkInterface { name: "rmnet0".into(), ..interface(1, None) };
        assert!(!interface(1, None).same_network(&other_interface));
    }

    #[test]
    fn network_interface_json_without_the_new_fields_decodes() {
        let json = serde_json::json!({"name": "wlan0", "index": 3});
        assert_eq!(serde_json::from_value::<NetworkInterface>(json).unwrap(), interface(0, None));
    }
}
