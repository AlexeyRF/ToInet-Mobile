#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
#include <memory>
#include <string>
#include <thread>

namespace svpn {

struct SshConfig {
    std::string host;
    std::string resolved_ip_hint; // Optional pre-resolved IP (only used if host is not numeric)
    int port = 22;
    std::string user = "root";
    std::string password;
    std::string private_key;
    std::string passphrase;
    std::string bind_host = "127.0.0.1";
    int local_port = 1770;
    int connect_timeout_ms = 25000;
};

using ProtectSocketFn = std::function<bool(int fd)>;

struct TunnelCallbacks {
    ProtectSocketFn protect_socket;
    std::function<void()> on_connected;
    std::function<void()> on_disconnected;
    std::function<void(const std::string& error)> on_error;
};

// Synchronously test SSH connection + authentication (no port forwarding).
// Returns true on success; on failure returns false and populates out_error.
bool test_ssh_connection(const SshConfig& cfg, ProtectSocketFn protect_socket, std::string& out_error);

// Minimal SSH-2.0 client that ONLY performs direct-tcpip port forwarding behind a local SOCKS5 proxy.
class SshTunnelClient {
public:
    SshTunnelClient() = default;
    ~SshTunnelClient();

    SshTunnelClient(const SshTunnelClient&) = delete;
    SshTunnelClient& operator=(const SshTunnelClient&) = delete;

    bool start(const SshConfig& cfg, TunnelCallbacks callbacks);
    void stop();

    bool is_connected() const { return connected_.load(); }
    bool is_proxy_listening() const { return proxy_listening_.load(); }
    std::string last_error() const;

private:
    void run_loop(SshConfig cfg, TunnelCallbacks callbacks);
    void wake_loop();

    std::atomic<bool> running_{false};
    std::atomic<bool> connected_{false};
    std::atomic<bool> proxy_listening_{false};
    int wakeup_pipe_[2]{-1, -1};
    std::thread worker_;
    mutable std::string last_error_;
};

} // namespace svpn
