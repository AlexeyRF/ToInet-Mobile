#include "ssh_tunnel.h"
#include "ssh_crypto.h"

#include <arpa/inet.h>
#include <cerrno>
#include <chrono>
#include <cstring>
#include <fcntl.h>
#include <netdb.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>
#include <unordered_map>
#include <vector>

#if defined(__ANDROID__)
#include <android/log.h>
#define SVPN_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "SvpnNative", __VA_ARGS__)
#define SVPN_LOGW(...) __android_log_print(ANDROID_LOG_WARN, "SvpnNative", __VA_ARGS__)
#define SVPN_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "SvpnNative", __VA_ARGS__)
#else
#define SVPN_LOGI(...) ((void)0)
#define SVPN_LOGW(...) ((void)0)
#define SVPN_LOGE(...) ((void)0)
#endif

namespace svpn {

namespace {

constexpr uint8_t SSH_MSG_DISCONNECT = 1;
constexpr uint8_t SSH_MSG_IGNORE = 2;
constexpr uint8_t SSH_MSG_DEBUG = 4;
constexpr uint8_t SSH_MSG_SERVICE_REQUEST = 5;
constexpr uint8_t SSH_MSG_SERVICE_ACCEPT = 6;
constexpr uint8_t SSH_MSG_EXT_INFO = 7;
constexpr uint8_t SSH_MSG_KEXINIT = 20;
constexpr uint8_t SSH_MSG_NEWKEYS = 21;
constexpr uint8_t SSH_MSG_KEX_ECDH_INIT = 30;
constexpr uint8_t SSH_MSG_KEX_ECDH_REPLY = 31;

constexpr uint8_t SSH_MSG_USERAUTH_REQUEST = 50;
constexpr uint8_t SSH_MSG_USERAUTH_FAILURE = 51;
constexpr uint8_t SSH_MSG_USERAUTH_SUCCESS = 52;
constexpr uint8_t SSH_MSG_USERAUTH_BANNER = 53;
constexpr uint8_t SSH_MSG_USERAUTH_INFO_REQUEST = 60;
constexpr uint8_t SSH_MSG_USERAUTH_INFO_RESPONSE = 61;

constexpr uint8_t SSH_MSG_GLOBAL_REQUEST = 80;
constexpr uint8_t SSH_MSG_REQUEST_FAILURE = 82;
constexpr uint8_t SSH_MSG_CHANNEL_OPEN = 90;
constexpr uint8_t SSH_MSG_CHANNEL_OPEN_CONFIRMATION = 91;
constexpr uint8_t SSH_MSG_CHANNEL_OPEN_FAILURE = 92;
constexpr uint8_t SSH_MSG_CHANNEL_WINDOW_ADJUST = 93;
constexpr uint8_t SSH_MSG_CHANNEL_DATA = 94;
constexpr uint8_t SSH_MSG_CHANNEL_EOF = 96;
constexpr uint8_t SSH_MSG_CHANNEL_CLOSE = 97;

constexpr uint32_t LOCAL_WINDOW_SIZE = 2 * 1024 * 1024;
constexpr uint32_t LOCAL_MAX_PACKET = 32768;

inline uint32_t rd_u32(const uint8_t* p) {
    return (uint32_t(p[0]) << 24) | (uint32_t(p[1]) << 16) | (uint32_t(p[2]) << 8) | uint32_t(p[3]);
}
inline void wr_u32(uint8_t* p, uint32_t v) {
    p[0] = uint8_t(v >> 24);
    p[1] = uint8_t(v >> 16);
    p[2] = uint8_t(v >> 8);
    p[3] = uint8_t(v);
}

struct BufWriter {
    std::vector<uint8_t> data;

    void u8(uint8_t v) { data.push_back(v); }
    void b(bool v) { data.push_back(v ? 1 : 0); }
    void u32(uint32_t v) {
        uint8_t tmp[4];
        wr_u32(tmp, v);
        data.insert(data.end(), tmp, tmp + 4);
    }
    void bytes(const uint8_t* p, size_t len) {
        u32(uint32_t(len));
        if (len > 0) data.insert(data.end(), p, p + len);
    }
    void str(const std::string& s) {
        bytes(reinterpret_cast<const uint8_t*>(s.data()), s.size());
    }
    void mpint_unsigned(const uint8_t* p, size_t len) {
        size_t start = 0;
        while (start < len && p[start] == 0) ++start;
        if (start == len) {
            u32(0);
            return;
        }
        bool pad = (p[start] & 0x80) != 0;
        u32(uint32_t(len - start + (pad ? 1 : 0)));
        if (pad) data.push_back(0);
        data.insert(data.end(), p + start, p + len);
    }
};

struct BufReader {
    const uint8_t* p;
    size_t len;
    size_t off = 0;

    bool u8(uint8_t& v) {
        if (off + 1 > len) return false;
        v = p[off++];
        return true;
    }
    bool u32(uint32_t& v) {
        if (off + 4 > len) return false;
        v = rd_u32(p + off);
        off += 4;
        return true;
    }
    bool str(std::string& s) {
        uint32_t n = 0;
        if (!u32(n) || off + n > len) return false;
        s.assign(reinterpret_cast<const char*>(p + off), n);
        off += n;
        return true;
    }
    bool bytes(std::vector<uint8_t>& out) {
        uint32_t n = 0;
        if (!u32(n) || off + n > len) return false;
        out.assign(p + off, p + off + n);
        off += n;
        return true;
    }
    bool skip_str() {
        uint32_t n = 0;
        if (!u32(n) || off + n > len) return false;
        off += n;
        return true;
    }
};

std::string strip_brackets(const std::string& host) {
    if (host.size() >= 2 && host.front() == '[' && host.back() == ']') {
        return host.substr(1, host.size() - 2);
    }
    return host;
}

bool try_parse_numeric_ip(const std::string& raw_host, int port, sockaddr_storage& out_ss, socklen_t& out_len) {
    std::string host = strip_brackets(raw_host);
    std::memset(&out_ss, 0, sizeof(out_ss));

    sockaddr_in sa4{};
    if (inet_pton(AF_INET, host.c_str(), &sa4.sin_addr) == 1) {
        sa4.sin_family = AF_INET;
        sa4.sin_port = htons(uint16_t(port));
        std::memcpy(&out_ss, &sa4, sizeof(sa4));
        out_len = sizeof(sa4);
        return true;
    }

    sockaddr_in6 sa6{};
    if (inet_pton(AF_INET6, host.c_str(), &sa6.sin6_addr) == 1) {
        sa6.sin6_family = AF_INET6;
        sa6.sin6_port = htons(uint16_t(port));
        std::memcpy(&out_ss, &sa6, sizeof(sa6));
        out_len = sizeof(sa6);
        return true;
    }

    return false;
}

bool set_nonblocking(int fd) {
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags < 0) return false;
    return fcntl(fd, F_SETFL, flags | O_NONBLOCK) == 0;
}

int connect_tcp(const std::string& host, const std::string& resolved_hint, int port,
                int timeout_ms, const ProtectSocketFn& protect_fn, std::string& out_err) {
    std::vector<std::pair<sockaddr_storage, socklen_t>> candidates;

    // 1. Direct numeric IP check — ZERO DNS lookup!
    sockaddr_storage ss{};
    socklen_t sslen = 0;
    if (try_parse_numeric_ip(host, port, ss, sslen)) {
        candidates.emplace_back(ss, sslen);
    } else if (!resolved_hint.empty() && try_parse_numeric_ip(resolved_hint, port, ss, sslen)) {
        candidates.emplace_back(ss, sslen);
    } else {
        // 2. Domain name: resolve via getaddrinfo
        addrinfo hints{};
        hints.ai_family = AF_UNSPEC;
        hints.ai_socktype = SOCK_STREAM;
        addrinfo* res = nullptr;
        std::string port_str = std::to_string(port);
        int gai = getaddrinfo(host.c_str(), port_str.c_str(), &hints, &res);
        if (gai != 0 || !res) {
            out_err = std::string("DNS resolve failed for ") + host + ": " + gai_strerror(gai);
            return -1;
        }
        for (addrinfo* p = res; p != nullptr; p = p->ai_next) {
            if (p->ai_addr && p->ai_addrlen <= sizeof(sockaddr_storage)) {
                sockaddr_storage s{};
                std::memcpy(&s, p->ai_addr, p->ai_addrlen);
                candidates.emplace_back(s, socklen_t(p->ai_addrlen));
            }
        }
        freeaddrinfo(res);
    }

    if (candidates.empty()) {
        out_err = "No addresses found for " + host;
        return -1;
    }

    for (const auto& cand : candidates) {
        int fd = socket(cand.first.ss_family, SOCK_STREAM, IPPROTO_TCP);
        if (fd < 0) {
            out_err = std::string("socket() failed: ") + std::strerror(errno);
            continue;
        }

        if (protect_fn && !protect_fn(fd)) {
            SVPN_LOGW("protect_socket returned false for fd %d", fd);
        }

        int one = 1;
        setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
        setsockopt(fd, SOL_SOCKET, SO_KEEPALIVE, &one, sizeof(one));
        set_nonblocking(fd);

        int rc = connect(fd, reinterpret_cast<const sockaddr*>(&cand.first), cand.second);
        if (rc == 0) return fd;

        if (errno != EINPROGRESS) {
            out_err = std::string("connect() failed: ") + std::strerror(errno);
            close(fd);
            continue;
        }

        pollfd pfd{fd, POLLOUT, 0};
        int prc = poll(&pfd, 1, timeout_ms);
        if (prc <= 0) {
            out_err = (prc == 0) ? "TCP connect timeout" : (std::string("poll() failed: ") + std::strerror(errno));
            close(fd);
            continue;
        }

        int so_err = 0;
        socklen_t len = sizeof(so_err);
        if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &so_err, &len) < 0 || so_err != 0) {
            out_err = std::string("connect() error: ") + std::strerror(so_err ? so_err : errno);
            close(fd);
            continue;
        }

        return fd;
    }

    return -1;
}

std::string pick_first_match(const std::string& client_list, const std::string& server_list) {
    size_t pos = 0;
    while (pos < client_list.size()) {
        size_t comma = client_list.find(',', pos);
        std::string item = (comma == std::string::npos)
            ? client_list.substr(pos)
            : client_list.substr(pos, comma - pos);

        size_t spos = 0;
        while (spos < server_list.size()) {
            size_t scomma = server_list.find(',', spos);
            std::string sitem = (scomma == std::string::npos)
                ? server_list.substr(spos)
                : server_list.substr(spos, scomma - spos);
            if (item == sitem) return item;
            if (scomma == std::string::npos) break;
            spos = scomma + 1;
        }
        if (comma == std::string::npos) break;
        pos = comma + 1;
    }
    return "";
}

enum class CipherType { None, ChaCha20Poly1305, Aes128Ctr, Aes256Ctr };
enum class MacType { None, HmacSha256, HmacSha512, HmacSha1 };

struct CipherState {
    CipherType cipher = CipherType::None;
    MacType mac = MacType::None;
    crypto::ChaCha20Poly1305OpenSsh chacha;
    crypto::AesCtr aes;
    std::vector<uint8_t> mac_key;
    uint64_t seq = 0;

    size_t mac_len() const {
        if (cipher == CipherType::ChaCha20Poly1305) return 16;
        switch (mac) {
            case MacType::HmacSha256: return 32;
            case MacType::HmacSha512: return 64;
            case MacType::HmacSha1: return 20;
            default: return 0;
        }
    }

    size_t block_size() const {
        if (cipher == CipherType::Aes128Ctr || cipher == CipherType::Aes256Ctr) return 16;
        return 8;
    }

    void compute_mac(uint64_t pkt_seq, const uint8_t* raw_pkt, size_t raw_len, uint8_t* out_mac) const {
        std::vector<uint8_t> input(4 + raw_len);
        wr_u32(input.data(), uint32_t(pkt_seq & 0xffffffffu));
        std::memcpy(input.data() + 4, raw_pkt, raw_len);
        if (mac == MacType::HmacSha256) {
            crypto::hmac_sha256(mac_key.data(), mac_key.size(), input.data(), input.size(), out_mac);
        } else if (mac == MacType::HmacSha512) {
            crypto::hmac_sha512(mac_key.data(), mac_key.size(), input.data(), input.size(), out_mac);
        } else if (mac == MacType::HmacSha1) {
            crypto::hmac_sha1(mac_key.data(), mac_key.size(), input.data(), input.size(), out_mac);
        }
    }
};

class SshTransport {
public:
    int fd = -1;
    std::string client_version = "SSH-2.0-ToInetSSH_1.0";
    std::string server_version;
    std::vector<uint8_t> client_kexinit;
    std::vector<uint8_t> server_kexinit;
    std::vector<uint8_t> session_id;

    CipherState tx;
    CipherState rx;

    std::vector<uint8_t> in_buf;
    std::vector<uint8_t> out_buf;
    bool chacha_len_decrypted = false;
    uint32_t chacha_pending_len = 0;
    uint8_t chacha_enc_len[4]{};

    bool send_raw_all(const uint8_t* data, size_t len, int timeout_ms) {
        size_t off = 0;
        while (off < len) {
            ssize_t w = send(fd, data + off, len - off, MSG_NOSIGNAL);
            if (w > 0) {
                off += size_t(w);
                continue;
            }
            if (w < 0 && (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR)) {
                pollfd pfd{fd, POLLOUT, 0};
                if (poll(&pfd, 1, timeout_ms) <= 0) return false;
                continue;
            }
            return false;
        }
        return true;
    }

    bool recv_some(int timeout_ms, std::string& out_err) {
        uint8_t tmp[16384];
        ssize_t r = recv(fd, tmp, sizeof(tmp), 0);
        if (r > 0) {
            in_buf.insert(in_buf.end(), tmp, tmp + r);
            return true;
        }
        if (r == 0) {
            out_err = "Connection closed by remote SSH server";
            return false;
        }
        if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) {
            if (timeout_ms == 0) return true;
            pollfd pfd{fd, POLLIN, 0};
            int prc = poll(&pfd, 1, timeout_ms);
            if (prc <= 0) {
                out_err = (prc == 0) ? "SSH read timeout" : "SSH poll error";
                return false;
            }
            return recv_some(0, out_err);
        }
        out_err = std::string("SSH recv error: ") + std::strerror(errno);
        return false;
    }

    void queue_packet(const std::vector<uint8_t>& payload) {
        size_t bsize = tx.block_size();
        size_t hdr_len = (tx.cipher == CipherType::ChaCha20Poly1305) ? 0 : 4;
        size_t pad_len = bsize - ((hdr_len + 1 + payload.size()) % bsize);
        if (pad_len < 4) pad_len += bsize;

        uint32_t pkt_len = uint32_t(1 + payload.size() + pad_len);
        std::vector<uint8_t> plain(4 + pkt_len);
        wr_u32(plain.data(), pkt_len);
        plain[4] = uint8_t(pad_len);
        if (!payload.empty()) std::memcpy(plain.data() + 5, payload.data(), payload.size());
        crypto::random_bytes(plain.data() + 5 + payload.size(), pad_len);

        if (tx.cipher == CipherType::None) {
            out_buf.insert(out_buf.end(), plain.begin(), plain.end());
        } else if (tx.cipher == CipherType::ChaCha20Poly1305) {
            std::vector<uint8_t> pkt(4 + pkt_len + 16);
            tx.chacha.encrypt_packet(tx.seq, plain.data(), plain.data() + 4, pkt_len,
                                     pkt.data(), pkt.data() + 4, pkt.data() + 4 + pkt_len);
            out_buf.insert(out_buf.end(), pkt.begin(), pkt.end());
        } else {
            uint8_t mac[64];
            tx.compute_mac(tx.seq, plain.data(), plain.size(), mac);
            std::vector<uint8_t> enc(plain.size());
            tx.aes.crypt(plain.data(), enc.data(), plain.size());
            out_buf.insert(out_buf.end(), enc.begin(), enc.end());
            out_buf.insert(out_buf.end(), mac, mac + tx.mac_len());
        }
        tx.seq++;
    }

    bool flush_sync(int timeout_ms) {
        if (out_buf.empty()) return true;
        bool ok = send_raw_all(out_buf.data(), out_buf.size(), timeout_ms);
        out_buf.clear();
        return ok;
    }

    bool flush_nonblocking(std::string& out_err) {
        while (!out_buf.empty()) {
            ssize_t w = send(fd, out_buf.data(), out_buf.size(), MSG_NOSIGNAL);
            if (w > 0) {
                out_buf.erase(out_buf.begin(), out_buf.begin() + w);
                continue;
            }
            if (w < 0 && (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR)) {
                return true;
            }
            out_err = std::string("SSH send error: ") + std::strerror(errno);
            return false;
        }
        return true;
    }

    // Try to pop one decrypted SSH payload from in_buf.
    // Returns 1 if packet popped, 0 if need more data, -1 on protocol/MAC error.
    int try_pop_packet(std::vector<uint8_t>& out_payload, std::string& out_err) {
        if (rx.cipher == CipherType::ChaCha20Poly1305) {
            if (!chacha_len_decrypted) {
                if (in_buf.size() < 4) return 0;
                std::memcpy(chacha_enc_len, in_buf.data(), 4);
                uint8_t dec_len[4];
                rx.chacha.decrypt_length(rx.seq, chacha_enc_len, dec_len);
                chacha_pending_len = rd_u32(dec_len);
                if (chacha_pending_len < 2 || chacha_pending_len > 256 * 1024) {
                    out_err = "Invalid SSH packet length";
                    return -1;
                }
                chacha_len_decrypted = true;
            }
            size_t total = 4 + size_t(chacha_pending_len) + 16;
            if (in_buf.size() < total) return 0;

            std::vector<uint8_t> plain(chacha_pending_len);
            if (!rx.chacha.decrypt_payload(rx.seq, chacha_enc_len, in_buf.data() + 4,
                                           chacha_pending_len, in_buf.data() + 4 + chacha_pending_len,
                                           plain.data())) {
                out_err = "SSH ChaCha20-Poly1305 MAC verification failed";
                return -1;
            }
            in_buf.erase(in_buf.begin(), in_buf.begin() + ptrdiff_t(total));
            chacha_len_decrypted = false;
            rx.seq++;

            uint8_t pad = plain[0];
            if (pad >= chacha_pending_len) {
                out_err = "Invalid SSH padding";
                return -1;
            }
            out_payload.assign(plain.begin() + 1, plain.end() - pad);
            return 1;
        }

        size_t bsize = rx.block_size();
        if (!chacha_len_decrypted) {
            if (in_buf.size() < bsize) return 0;
            if (rx.cipher != CipherType::None) {
                rx.aes.crypt(in_buf.data(), in_buf.data(), bsize);
            }
            chacha_pending_len = rd_u32(in_buf.data());
            if (chacha_pending_len < 2 || chacha_pending_len > 256 * 1024) {
                out_err = "Invalid SSH packet length";
                return -1;
            }
            chacha_len_decrypted = true;
        }

        size_t mlen = rx.mac_len();
        size_t raw_len = 4 + size_t(chacha_pending_len);
        size_t total = raw_len + mlen;
        if (in_buf.size() < total) return 0;

        if (rx.cipher != CipherType::None && raw_len > bsize) {
            rx.aes.crypt(in_buf.data() + bsize, in_buf.data() + bsize, raw_len - bsize);
        }
        if (mlen > 0) {
            uint8_t expected[64];
            rx.compute_mac(rx.seq, in_buf.data(), raw_len, expected);
            if (!crypto::ct_equal(expected, in_buf.data() + raw_len, mlen)) {
                out_err = "SSH HMAC verification failed";
                return -1;
            }
        }

        uint8_t pad = in_buf[4];
        if (pad >= chacha_pending_len) {
            out_err = "Invalid SSH padding";
            return -1;
        }
        out_payload.assign(in_buf.begin() + 5, in_buf.begin() + ptrdiff_t(raw_len - pad));
        in_buf.erase(in_buf.begin(), in_buf.begin() + ptrdiff_t(total));
        chacha_len_decrypted = false;
        rx.seq++;
        return 1;
    }

    bool read_packet_sync(std::vector<uint8_t>& out_payload, int timeout_ms, std::string& out_err) {
        while (true) {
            int pop = try_pop_packet(out_payload, out_err);
            if (pop < 0) return false;
            if (pop > 0) {
                if (!out_payload.empty()) {
                    uint8_t t = out_payload[0];
                    if (t == SSH_MSG_IGNORE || t == SSH_MSG_DEBUG || t == SSH_MSG_EXT_INFO || t == SSH_MSG_USERAUTH_BANNER) {
                        continue;
                    }
                    if (t == SSH_MSG_DISCONNECT) {
                        BufReader r{out_payload.data(), out_payload.size(), 1};
                        uint32_t reason = 0; std::string desc;
                        r.u32(reason); r.str(desc);
                        out_err = "SSH disconnected (" + std::to_string(reason) + "): " + desc;
                        return false;
                    }
                }
                return true;
            }
            if (!recv_some(timeout_ms, out_err)) return false;
        }
    }

    bool perform_handshake(const SshConfig& cfg, std::string& out_err) {
        // 1. Version exchange
        std::string banner = client_version + "\r\n";
        if (!send_raw_all(reinterpret_cast<const uint8_t*>(banner.data()), banner.size(), cfg.connect_timeout_ms)) {
            out_err = "Failed sending SSH version banner";
            return false;
        }

        auto start_time = std::chrono::steady_clock::now();
        while (true) {
            auto it = std::find(in_buf.begin(), in_buf.end(), '\n');
            if (it != in_buf.end()) {
                size_t line_len = size_t(it - in_buf.begin());
                std::string line(reinterpret_cast<const char*>(in_buf.data()), line_len);
                in_buf.erase(in_buf.begin(), it + 1);
                if (!line.empty() && line.back() == '\r') line.pop_back();
                if (line.rfind("SSH-2.0-", 0) == 0 || line.rfind("SSH-1.99-", 0) == 0) {
                    server_version = line;
                    break;
                }
                continue;
            }
            auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now() - start_time).count();
            if (elapsed > cfg.connect_timeout_ms) {
                out_err = "SSH version exchange timeout";
                return false;
            }
            if (!recv_some(int(cfg.connect_timeout_ms - elapsed), out_err)) return false;
        }

        // 2. KEXINIT
        const std::string kex_algs = "curve25519-sha256,curve25519-sha256@libssh.org";
        const std::string host_algs = "ssh-ed25519,rsa-sha2-512,rsa-sha2-256,ssh-rsa,ecdsa-sha2-nistp256";
        const std::string enc_algs = "chacha20-poly1305@openssh.com,aes256-ctr,aes128-ctr";
        const std::string mac_algs = "hmac-sha2-256,hmac-sha2-512,hmac-sha1";
        const std::string comp_algs = "none";

        BufWriter kw;
        kw.u8(SSH_MSG_KEXINIT);
        uint8_t cookie[16];
        crypto::random_bytes(cookie, 16);
        kw.data.insert(kw.data.end(), cookie, cookie + 16);
        kw.str(kex_algs);
        kw.str(host_algs);
        kw.str(enc_algs);
        kw.str(enc_algs);
        kw.str(mac_algs);
        kw.str(mac_algs);
        kw.str(comp_algs);
        kw.str(comp_algs);
        kw.str("");
        kw.str("");
        kw.b(false);
        kw.u32(0);
        client_kexinit = kw.data;
        queue_packet(client_kexinit);
        if (!flush_sync(cfg.connect_timeout_ms)) {
            out_err = "Failed sending KEXINIT";
            return false;
        }

        if (!read_packet_sync(server_kexinit, cfg.connect_timeout_ms, out_err)) return false;
        if (server_kexinit.empty() || server_kexinit[0] != SSH_MSG_KEXINIT || server_kexinit.size() < 17) {
            out_err = "Expected SSH_MSG_KEXINIT from server";
            return false;
        }

        BufReader kr{server_kexinit.data(), server_kexinit.size(), 17};
        std::string srv_kex, srv_host, srv_enc_c2s, srv_enc_s2c, srv_mac_c2s, srv_mac_s2c, srv_comp_c2s, srv_comp_s2c;
        if (!kr.str(srv_kex) || !kr.str(srv_host) || !kr.str(srv_enc_c2s) || !kr.str(srv_enc_s2c) ||
            !kr.str(srv_mac_c2s) || !kr.str(srv_mac_s2c) || !kr.str(srv_comp_c2s) || !kr.str(srv_comp_s2c)) {
            out_err = "Malformed server KEXINIT";
            return false;
        }

        std::string chosen_kex = pick_first_match(kex_algs, srv_kex);
        std::string chosen_enc_c2s = pick_first_match(enc_algs, srv_enc_c2s);
        std::string chosen_enc_s2c = pick_first_match(enc_algs, srv_enc_s2c);
        std::string chosen_mac_c2s = pick_first_match(mac_algs, srv_mac_c2s);
        std::string chosen_mac_s2c = pick_first_match(mac_algs, srv_mac_s2c);
        if (chosen_kex.empty() || chosen_enc_c2s.empty() || chosen_enc_s2c.empty()) {
            out_err = "No compatible SSH KEX/cipher algorithm";
            return false;
        }
        if ((chosen_enc_c2s != "chacha20-poly1305@openssh.com" && chosen_mac_c2s.empty()) ||
            (chosen_enc_s2c != "chacha20-poly1305@openssh.com" && chosen_mac_s2c.empty())) {
            out_err = "No compatible SSH MAC algorithm";
            return false;
        }

        // 3. Curve25519 ECDH
        uint8_t ecdh_sk[32], ecdh_pk[32];
        crypto::random_bytes(ecdh_sk, 32);
        crypto::x25519_public_key(ecdh_pk, ecdh_sk);

        BufWriter ei;
        ei.u8(SSH_MSG_KEX_ECDH_INIT);
        ei.bytes(ecdh_pk, 32);
        queue_packet(ei.data);
        if (!flush_sync(cfg.connect_timeout_ms)) {
            out_err = "Failed sending KEX_ECDH_INIT";
            return false;
        }

        std::vector<uint8_t> reply;
        if (!read_packet_sync(reply, cfg.connect_timeout_ms, out_err)) return false;
        if (reply.empty() || reply[0] != SSH_MSG_KEX_ECDH_REPLY) {
            out_err = "Expected SSH_MSG_KEX_ECDH_REPLY";
            return false;
        }

        BufReader er{reply.data(), reply.size(), 1};
        std::vector<uint8_t> k_s, q_s, sig_s;
        if (!er.bytes(k_s) || !er.bytes(q_s) || !er.bytes(sig_s) || q_s.size() != 32) {
            out_err = "Malformed KEX_ECDH_REPLY";
            return false;
        }

        uint8_t shared_k[32];
        if (!crypto::x25519_shared_secret(shared_k, ecdh_sk, q_s.data())) {
            out_err = "Invalid X25519 shared secret";
            return false;
        }

        BufWriter hw;
        hw.str(client_version);
        hw.str(server_version);
        hw.bytes(client_kexinit.data(), client_kexinit.size());
        hw.bytes(server_kexinit.data(), server_kexinit.size());
        hw.bytes(k_s.data(), k_s.size());
        hw.bytes(ecdh_pk, 32);
        hw.bytes(q_s.data(), 32);
        hw.mpint_unsigned(shared_k, 32);

        uint8_t H[32];
        crypto::sha256(hw.data.data(), hw.data.size(), H);
        if (session_id.empty()) session_id.assign(H, H + 32);

        // Verify Ed25519 host key signature if server sent ssh-ed25519
        {
            BufReader hkr{k_s.data(), k_s.size(), 0};
            std::string hk_alg;
            std::vector<uint8_t> hk_pub;
            if (hkr.str(hk_alg) && hk_alg == "ssh-ed25519" && hkr.bytes(hk_pub) && hk_pub.size() == 32) {
                BufReader sr{sig_s.data(), sig_s.size(), 0};
                std::string sig_alg;
                std::vector<uint8_t> sig_bytes;
                if (!sr.str(sig_alg) || !sr.bytes(sig_bytes) || sig_bytes.size() != 64 ||
                    !crypto::ed25519_verify(sig_bytes.data(), H, 32, hk_pub.data())) {
                    out_err = "Server Ed25519 host signature verification failed";
                    return false;
                }
            }
        }

        // 4. NEWKEYS
        queue_packet({SSH_MSG_NEWKEYS});
        if (!flush_sync(cfg.connect_timeout_ms)) {
            out_err = "Failed sending NEWKEYS";
            return false;
        }
        std::vector<uint8_t> nk;
        if (!read_packet_sync(nk, cfg.connect_timeout_ms, out_err) || nk.empty() || nk[0] != SSH_MSG_NEWKEYS) {
            if (out_err.empty()) out_err = "Expected SSH_MSG_NEWKEYS";
            return false;
        }

        // Derive keys
        auto derive_key = [&](char letter, size_t need) -> std::vector<uint8_t> {
            BufWriter kw_k;
            kw_k.mpint_unsigned(shared_k, 32);

            std::vector<uint8_t> out;
            std::vector<uint8_t> seed = kw_k.data;
            seed.insert(seed.end(), H, H + 32);
            seed.push_back(uint8_t(letter));
            seed.insert(seed.end(), session_id.begin(), session_id.end());

            uint8_t block[32];
            crypto::sha256(seed.data(), seed.size(), block);
            out.insert(out.end(), block, block + 32);

            while (out.size() < need) {
                std::vector<uint8_t> ext = kw_k.data;
                ext.insert(ext.end(), H, H + 32);
                ext.insert(ext.end(), out.begin(), out.end());
                crypto::sha256(ext.data(), ext.size(), block);
                out.insert(out.end(), block, block + 32);
            }
            out.resize(need);
            return out;
        };

        auto setup_cipher = [&](CipherState& st, const std::string& enc, const std::string& mac, bool is_tx) {
            char iv_ch = is_tx ? 'A' : 'B';
            char key_ch = is_tx ? 'C' : 'D';
            char mac_ch = is_tx ? 'E' : 'F';

            if (enc == "chacha20-poly1305@openssh.com") {
                st.cipher = CipherType::ChaCha20Poly1305;
                st.mac = MacType::None;
                auto k64 = derive_key(key_ch, 64);
                st.chacha.init(k64.data());
            } else {
                auto iv = derive_key(iv_ch, 16);
                size_t klen = (enc == "aes256-ctr") ? 32 : 16;
                st.cipher = (klen == 32) ? CipherType::Aes256Ctr : CipherType::Aes128Ctr;
                auto key = derive_key(key_ch, klen);
                st.aes.init(key.data(), klen, iv.data());

                if (mac == "hmac-sha2-512") {
                    st.mac = MacType::HmacSha512;
                    st.mac_key = derive_key(mac_ch, 64);
                } else if (mac == "hmac-sha2-256") {
                    st.mac = MacType::HmacSha256;
                    st.mac_key = derive_key(mac_ch, 32);
                } else {
                    st.mac = MacType::HmacSha1;
                    st.mac_key = derive_key(mac_ch, 20);
                }
            }
        };

        setup_cipher(tx, chosen_enc_c2s, chosen_mac_c2s, true);
        setup_cipher(rx, chosen_enc_s2c, chosen_mac_s2c, false);

        // 5. Userauth service
        BufWriter sr;
        sr.u8(SSH_MSG_SERVICE_REQUEST);
        sr.str("ssh-userauth");
        queue_packet(sr.data);
        if (!flush_sync(cfg.connect_timeout_ms)) {
            out_err = "Failed sending SERVICE_REQUEST";
            return false;
        }
        std::vector<uint8_t> sa;
        if (!read_packet_sync(sa, cfg.connect_timeout_ms, out_err) || sa.empty() || sa[0] != SSH_MSG_SERVICE_ACCEPT) {
            if (out_err.empty()) out_err = "Server rejected ssh-userauth service";
            return false;
        }

        // 6. Authenticate
        if (!cfg.private_key.empty()) {
            crypto::PrivateKey pkey;
            if (!crypto::parse_private_key(cfg.private_key, cfg.passphrase, pkey, out_err)) {
                return false;
            }
            std::string alg = pkey.algorithm_name();
            std::vector<uint8_t> pub_blob = pkey.public_key_blob();

            BufWriter to_sign;
            to_sign.bytes(session_id.data(), session_id.size());
            to_sign.u8(SSH_MSG_USERAUTH_REQUEST);
            to_sign.str(cfg.user);
            to_sign.str("ssh-connection");
            to_sign.str("publickey");
            to_sign.b(true);
            to_sign.str(alg);
            to_sign.bytes(pub_blob.data(), pub_blob.size());

            std::vector<uint8_t> sig_blob;
            if (!pkey.sign(to_sign.data.data(), to_sign.data.size(), alg, sig_blob)) {
                out_err = "Failed signing SSH userauth challenge";
                return false;
            }

            BufWriter auth;
            auth.u8(SSH_MSG_USERAUTH_REQUEST);
            auth.str(cfg.user);
            auth.str("ssh-connection");
            auth.str("publickey");
            auth.b(true);
            auth.str(alg);
            auth.bytes(pub_blob.data(), pub_blob.size());
            auth.bytes(sig_blob.data(), sig_blob.size());
            queue_packet(auth.data);
        } else {
            BufWriter auth;
            auth.u8(SSH_MSG_USERAUTH_REQUEST);
            auth.str(cfg.user);
            auth.str("ssh-connection");
            auth.str("password");
            auth.b(false);
            auth.str(cfg.password);
            queue_packet(auth.data);
        }

        if (!flush_sync(cfg.connect_timeout_ms)) {
            out_err = "Failed sending USERAUTH_REQUEST";
            return false;
        }

        std::vector<uint8_t> ar;
        if (!read_packet_sync(ar, cfg.connect_timeout_ms, out_err)) return false;
        if (!ar.empty() && ar[0] == SSH_MSG_USERAUTH_SUCCESS) {
            return true;
        }

        // If password failed and server allows keyboard-interactive, try it
        if (!ar.empty() && ar[0] == SSH_MSG_USERAUTH_FAILURE && cfg.private_key.empty()) {
            BufReader fr{ar.data(), ar.size(), 1};
            std::string methods;
            fr.str(methods);
            if (methods.find("keyboard-interactive") != std::string::npos) {
                BufWriter ki;
                ki.u8(SSH_MSG_USERAUTH_REQUEST);
                ki.str(cfg.user);
                ki.str("ssh-connection");
                ki.str("keyboard-interactive");
                ki.str("");
                ki.str("");
                queue_packet(ki.data);
                if (!flush_sync(cfg.connect_timeout_ms)) {
                    out_err = "Failed sending keyboard-interactive request";
                    return false;
                }
                while (true) {
                    if (!read_packet_sync(ar, cfg.connect_timeout_ms, out_err)) return false;
                    if (ar.empty()) continue;
                    if (ar[0] == SSH_MSG_USERAUTH_SUCCESS) return true;
                    if (ar[0] == SSH_MSG_USERAUTH_INFO_REQUEST) {
                        BufReader ir{ar.data(), ar.size(), 1};
                        ir.skip_str(); ir.skip_str(); ir.skip_str();
                        uint32_t nprompts = 0;
                        ir.u32(nprompts);
                        BufWriter resp;
                        resp.u8(SSH_MSG_USERAUTH_INFO_RESPONSE);
                        resp.u32(nprompts);
                        for (uint32_t i = 0; i < nprompts; ++i) resp.str(cfg.password);
                        queue_packet(resp.data);
                        if (!flush_sync(cfg.connect_timeout_ms)) {
                            out_err = "Failed sending keyboard-interactive response";
                            return false;
                        }
                        continue;
                    }
                    break;
                }
            }
        }

        out_err = "SSH authentication failed";
        return false;
    }
};

struct ForwardChannel {
    uint32_t local_id = 0;
    uint32_t remote_id = 0;
    uint32_t local_window = LOCAL_WINDOW_SIZE;
    uint32_t remote_window = 0;
    uint32_t remote_max_pkt = 32768;

    int sock_fd = -1;
    enum class State {
        SocksGreeting,
        SocksRequest,
        OpeningChannel,
        Streaming,
        Closing
    } state = State::SocksGreeting;

    std::vector<uint8_t> sock_in;
    std::vector<uint8_t> sock_out;
    bool local_eof = false;
    bool remote_eof = false;
    bool sent_eof = false;
    bool sent_close = false;
    bool recv_close = false;
};

int create_listen_socket(const std::string& bind_host, int port, std::string& out_err) {
    int fd = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (fd < 0) {
        out_err = std::string("SOCKS socket() failed: ") + std::strerror(errno);
        return -1;
    }
    int one = 1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    set_nonblocking(fd);

    sockaddr_in sa{};
    sa.sin_family = AF_INET;
    sa.sin_port = htons(uint16_t(port));
    if (inet_pton(AF_INET, bind_host.c_str(), &sa.sin_addr) != 1) {
        inet_pton(AF_INET, "127.0.0.1", &sa.sin_addr);
    }
    if (bind(fd, reinterpret_cast<sockaddr*>(&sa), sizeof(sa)) < 0) {
        out_err = std::string("SOCKS bind(") + bind_host + ":" + std::to_string(port) + ") failed: " + std::strerror(errno);
        close(fd);
        return -1;
    }
    if (listen(fd, 128) < 0) {
        out_err = std::string("SOCKS listen() failed: ") + std::strerror(errno);
        close(fd);
        return -1;
    }
    return fd;
}

} // namespace

bool test_ssh_connection(const SshConfig& cfg, ProtectSocketFn protect_socket, std::string& out_error) {
    int fd = connect_tcp(cfg.host, cfg.resolved_ip_hint, cfg.port, cfg.connect_timeout_ms, protect_socket, out_error);
    if (fd < 0) return false;

    SshTransport tr;
    tr.fd = fd;
    bool ok = tr.perform_handshake(cfg, out_error);
    close(fd);
    return ok;
}

SshTunnelClient::~SshTunnelClient() {
    stop();
}

std::string SshTunnelClient::last_error() const {
    return last_error_;
}

void SshTunnelClient::wake_loop() {
    if (wakeup_pipe_[1] >= 0) {
        uint8_t b = 1;
        ssize_t unused = write(wakeup_pipe_[1], &b, 1);
        (void)unused;
    }
}

bool SshTunnelClient::start(const SshConfig& cfg, TunnelCallbacks callbacks) {
    if (running_.exchange(true)) return true;
    connected_.store(false);
    proxy_listening_.store(false);
    last_error_.clear();

    if (pipe(wakeup_pipe_) != 0) {
        wakeup_pipe_[0] = wakeup_pipe_[1] = -1;
    } else {
        set_nonblocking(wakeup_pipe_[0]);
        set_nonblocking(wakeup_pipe_[1]);
    }

    worker_ = std::thread(&SshTunnelClient::run_loop, this, cfg, std::move(callbacks));
    return true;
}

void SshTunnelClient::stop() {
    bool was_running = running_.exchange(false);
    connected_.store(false);
    proxy_listening_.store(false);
    wake_loop();
    if (worker_.joinable()) {
        worker_.join();
    }
    for (int i = 0; i < 2; ++i) {
        if (wakeup_pipe_[i] >= 0) {
            close(wakeup_pipe_[i]);
            wakeup_pipe_[i] = -1;
        }
    }
    (void)was_running;
}

void SshTunnelClient::run_loop(SshConfig cfg, TunnelCallbacks callbacks) {
    std::string err;
    int ssh_fd = connect_tcp(cfg.host, cfg.resolved_ip_hint, cfg.port, cfg.connect_timeout_ms,
                             callbacks.protect_socket, err);
    if (ssh_fd < 0 || !running_.load()) {
        if (ssh_fd >= 0) close(ssh_fd);
        last_error_ = err;
        running_.store(false);
        if (callbacks.on_error) callbacks.on_error(err);
        return;
    }

    SshTransport tr;
    tr.fd = ssh_fd;
    if (!tr.perform_handshake(cfg, err) || !running_.load()) {
        close(ssh_fd);
        last_error_ = err;
        running_.store(false);
        if (callbacks.on_error) callbacks.on_error(err);
        return;
    }

    int listen_fd = create_listen_socket(cfg.bind_host, cfg.local_port, err);
    if (listen_fd < 0 || !running_.load()) {
        close(ssh_fd);
        if (listen_fd >= 0) close(listen_fd);
        last_error_ = err;
        running_.store(false);
        if (callbacks.on_error) callbacks.on_error(err);
        return;
    }

    connected_.store(true);
    proxy_listening_.store(true);
    SVPN_LOGI("SSH direct-tcpip tunnel connected, SOCKS5 listening on %s:%d", cfg.bind_host.c_str(), cfg.local_port);
    if (callbacks.on_connected) callbacks.on_connected();

    std::unordered_map<uint32_t, std::unique_ptr<ForwardChannel>> channels;
    uint32_t next_chan_id = 1;
    auto last_keepalive = std::chrono::steady_clock::now();

    auto close_channel = [&](ForwardChannel& ch) {
        if (ch.sock_fd >= 0) {
            close(ch.sock_fd);
            ch.sock_fd = -1;
        }
        if (ch.state == ForwardChannel::State::Streaming && !ch.sent_close) {
            BufWriter cw;
            cw.u8(SSH_MSG_CHANNEL_CLOSE);
            cw.u32(ch.remote_id);
            tr.queue_packet(cw.data);
            ch.sent_close = true;
        }
        ch.state = ForwardChannel::State::Closing;
    };

    while (running_.load()) {
        std::vector<pollfd> pfds;
        pfds.reserve(3 + channels.size());

        // 0: wakeup pipe
        if (wakeup_pipe_[0] >= 0) pfds.push_back({wakeup_pipe_[0], POLLIN, 0});
        // ssh_fd
        short ssh_events = POLLIN | (tr.out_buf.empty() ? 0 : POLLOUT);
        pfds.push_back({ssh_fd, ssh_events, 0});
        // listen_fd
        pfds.push_back({listen_fd, POLLIN, 0});

        std::vector<uint32_t> chan_ids;
        chan_ids.reserve(channels.size());
        for (auto& kv : channels) {
            ForwardChannel& ch = *kv.second;
            if (ch.sock_fd < 0) continue;
            short ev = 0;
            if (ch.state == ForwardChannel::State::SocksGreeting || ch.state == ForwardChannel::State::SocksRequest) {
                ev |= POLLIN;
            } else if (ch.state == ForwardChannel::State::Streaming) {
                if (!ch.local_eof && ch.remote_window > 0) ev |= POLLIN;
                if (!ch.sock_out.empty()) ev |= POLLOUT;
            }
            if (!ch.sock_out.empty() && ch.state != ForwardChannel::State::Streaming) {
                ev |= POLLOUT;
            }
            if (ev != 0) {
                pfds.push_back({ch.sock_fd, ev, 0});
                chan_ids.push_back(kv.first);
            }
        }

        int prc = poll(pfds.data(), nfds_t(pfds.size()), 1000);
        if (prc < 0) {
            if (errno == EINTR) continue;
            err = std::string("poll() failed: ") + std::strerror(errno);
            break;
        }
        if (!running_.load()) break;

        size_t idx = 0;
        if (wakeup_pipe_[0] >= 0) {
            if (pfds[idx].revents & POLLIN) {
                uint8_t drain[64];
                while (read(wakeup_pipe_[0], drain, sizeof(drain)) > 0) {}
            }
            idx++;
        }

        // SSH socket
        short ssh_rev = pfds[idx++].revents;
        if (ssh_rev & (POLLERR | POLLHUP | POLLNVAL)) {
            err = "SSH socket closed";
            break;
        }
        if (ssh_rev & POLLIN) {
            if (!tr.recv_some(0, err)) break;
            std::vector<uint8_t> pkt;
            while (true) {
                int pop = tr.try_pop_packet(pkt, err);
                if (pop < 0) { running_.store(false); break; }
                if (pop == 0) break;
                if (pkt.empty()) continue;

                uint8_t msg = pkt[0];
                if (msg == SSH_MSG_DISCONNECT) {
                    BufReader r{pkt.data(), pkt.size(), 1};
                    uint32_t reason = 0; std::string desc;
                    r.u32(reason); r.str(desc);
                    err = "SSH disconnected: " + desc;
                    running_.store(false);
                    break;
                } else if (msg == SSH_MSG_GLOBAL_REQUEST) {
                    BufReader r{pkt.data(), pkt.size(), 1};
                    std::string req; uint8_t want_reply = 0;
                    if (r.str(req) && r.u8(want_reply) && want_reply) {
                        tr.queue_packet({SSH_MSG_REQUEST_FAILURE});
                    }
                } else if (msg == SSH_MSG_CHANNEL_OPEN_CONFIRMATION) {
                    BufReader r{pkt.data(), pkt.size(), 1};
                    uint32_t rec_ch = 0, snd_ch = 0, init_win = 0, max_pkt = 0;
                    if (r.u32(rec_ch) && r.u32(snd_ch) && r.u32(init_win) && r.u32(max_pkt)) {
                        auto it = channels.find(rec_ch);
                        if (it != channels.end() && it->second->state == ForwardChannel::State::OpeningChannel) {
                            ForwardChannel& ch = *it->second;
                            ch.remote_id = snd_ch;
                            ch.remote_window = init_win;
                            ch.remote_max_pkt = std::min<uint32_t>(max_pkt, 32768);
                            ch.state = ForwardChannel::State::Streaming;
                            static const uint8_t ok_rep[10] = {0x05,0x00,0x00,0x01,0x7f,0x00,0x00,0x01,0x00,0x00};
                            ch.sock_out.insert(ch.sock_out.end(), ok_rep, ok_rep + 10);
                        }
                    }
                } else if (msg == SSH_MSG_CHANNEL_OPEN_FAILURE) {
                    BufReader r{pkt.data(), pkt.size(), 1};
                    uint32_t rec_ch = 0;
                    if (r.u32(rec_ch)) {
                        auto it = channels.find(rec_ch);
                        if (it != channels.end()) {
                            static const uint8_t fail_rep[10] = {0x05,0x05,0x00,0x01,0,0,0,0,0,0};
                            ssize_t unused = send(it->second->sock_fd, fail_rep, 10, MSG_NOSIGNAL);
                            (void)unused;
                            close_channel(*it->second);
                        }
                    }
                } else if (msg == SSH_MSG_CHANNEL_WINDOW_ADJUST) {
                    BufReader r{pkt.data(), pkt.size(), 1};
                    uint32_t rec_ch = 0, bytes_add = 0;
                    if (r.u32(rec_ch) && r.u32(bytes_add)) {
                        auto it = channels.find(rec_ch);
                        if (it != channels.end()) {
                            it->second->remote_window += bytes_add;
                        }
                    }
                } else if (msg == SSH_MSG_CHANNEL_DATA) {
                    BufReader r{pkt.data(), pkt.size(), 1};
                    uint32_t rec_ch = 0, dlen = 0;
                    if (r.u32(rec_ch) && r.u32(dlen) && r.off + dlen <= pkt.size()) {
                        auto it = channels.find(rec_ch);
                        if (it != channels.end() && it->second->sock_fd >= 0) {
                            ForwardChannel& ch = *it->second;
                            ch.sock_out.insert(ch.sock_out.end(), pkt.data() + r.off, pkt.data() + r.off + dlen);
                            if (ch.local_window >= dlen) ch.local_window -= dlen;
                            else ch.local_window = 0;
                            if (ch.local_window < LOCAL_WINDOW_SIZE / 2) {
                                uint32_t add = LOCAL_WINDOW_SIZE - ch.local_window;
                                ch.local_window = LOCAL_WINDOW_SIZE;
                                BufWriter ww;
                                ww.u8(SSH_MSG_CHANNEL_WINDOW_ADJUST);
                                ww.u32(ch.remote_id);
                                ww.u32(add);
                                tr.queue_packet(ww.data);
                            }
                        }
                    }
                } else if (msg == SSH_MSG_CHANNEL_EOF) {
                    BufReader r{pkt.data(), pkt.size(), 1};
                    uint32_t rec_ch = 0;
                    if (r.u32(rec_ch)) {
                        auto it = channels.find(rec_ch);
                        if (it != channels.end()) {
                            it->second->remote_eof = true;
                            if (it->second->sock_out.empty() && it->second->sock_fd >= 0) {
                                shutdown(it->second->sock_fd, SHUT_WR);
                            }
                        }
                    }
                } else if (msg == SSH_MSG_CHANNEL_CLOSE) {
                    BufReader r{pkt.data(), pkt.size(), 1};
                    uint32_t rec_ch = 0;
                    if (r.u32(rec_ch)) {
                        auto it = channels.find(rec_ch);
                        if (it != channels.end()) {
                            it->second->recv_close = true;
                            close_channel(*it->second);
                        }
                    }
                }
            }
            if (!err.empty()) break;
        }

        // Accept new SOCKS5 clients
        short listen_rev = pfds[idx++].revents;
        if (listen_rev & POLLIN) {
            while (true) {
                int cfd = accept(listen_fd, nullptr, nullptr);
                if (cfd < 0) break;
                int one = 1;
                setsockopt(cfd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
                set_nonblocking(cfd);
                auto ch = std::make_unique<ForwardChannel>();
                ch->local_id = next_chan_id++;
                ch->sock_fd = cfd;
                channels[ch->local_id] = std::move(ch);
            }
        }

        // Handle SOCKS5 / channel sockets
        for (size_t i = 0; i < chan_ids.size(); ++i) {
            auto it = channels.find(chan_ids[i]);
            if (it == channels.end()) continue;
            ForwardChannel& ch = *it->second;
            short rev = pfds[idx + i].revents;

            if (rev & (POLLERR | POLLNVAL)) {
                close_channel(ch);
                continue;
            }

            if ((rev & POLLOUT) && !ch.sock_out.empty() && ch.sock_fd >= 0) {
                ssize_t w = send(ch.sock_fd, ch.sock_out.data(), ch.sock_out.size(), MSG_NOSIGNAL);
                if (w > 0) {
                    ch.sock_out.erase(ch.sock_out.begin(), ch.sock_out.begin() + w);
                    if (ch.sock_out.empty() && ch.remote_eof) {
                        shutdown(ch.sock_fd, SHUT_WR);
                    }
                } else if (w < 0 && errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) {
                    close_channel(ch);
                    continue;
                }
            }

            if ((rev & (POLLIN | POLLHUP)) && ch.sock_fd >= 0) {
                if (ch.state == ForwardChannel::State::SocksGreeting || ch.state == ForwardChannel::State::SocksRequest) {
                    uint8_t buf[512];
                    ssize_t r = recv(ch.sock_fd, buf, sizeof(buf), 0);
                    if (r <= 0) {
                        if (r == 0 || (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR)) {
                            close_channel(ch);
                        }
                    } else {
                        ch.sock_in.insert(ch.sock_in.end(), buf, buf + r);
                        if (ch.state == ForwardChannel::State::SocksGreeting && ch.sock_in.size() >= 2) {
                            if (ch.sock_in[0] != 5 || ch.sock_in[1] == 0) {
                                close_channel(ch);
                                continue;
                            }
                            size_t need = 2 + size_t(ch.sock_in[1]);
                            if (ch.sock_in.size() >= need) {
                                ch.sock_in.erase(ch.sock_in.begin(), ch.sock_in.begin() + ptrdiff_t(need));
                                static const uint8_t greet[2] = {0x05, 0x00};
                                ch.sock_out.insert(ch.sock_out.end(), greet, greet + 2);
                                ch.state = ForwardChannel::State::SocksRequest;
                            }
                        }
                        if (ch.state == ForwardChannel::State::SocksRequest && ch.sock_in.size() >= 4) {
                            if (ch.sock_in[0] != 5 || ch.sock_in[1] != 1) {
                                static const uint8_t unsup[10] = {0x05,0x07,0x00,0x01,0,0,0,0,0,0};
                                ch.sock_out.insert(ch.sock_out.end(), unsup, unsup + 10);
                                close_channel(ch);
                                continue;
                            }
                            uint8_t atyp = ch.sock_in[3];
                            size_t addr_len = 0;
                            size_t hdr = 4;
                            if (atyp == 1) addr_len = 4;
                            else if (atyp == 4) addr_len = 16;
                            else if (atyp == 3) {
                                if (ch.sock_in.size() < 5) continue;
                                addr_len = ch.sock_in[4];
                                hdr = 5;
                            } else {
                                close_channel(ch);
                                continue;
                            }
                            if (ch.sock_in.size() < hdr + addr_len + 2) continue;

                            std::string target_host;
                            if (atyp == 1) {
                                char ip[INET_ADDRSTRLEN]{};
                                inet_ntop(AF_INET, ch.sock_in.data() + hdr, ip, sizeof(ip));
                                target_host = ip;
                            } else if (atyp == 4) {
                                char ip[INET6_ADDRSTRLEN]{};
                                inet_ntop(AF_INET6, ch.sock_in.data() + hdr, ip, sizeof(ip));
                                target_host = ip;
                            } else {
                                target_host.assign(reinterpret_cast<const char*>(ch.sock_in.data() + hdr), addr_len);
                            }
                            uint16_t target_port = uint16_t((ch.sock_in[hdr + addr_len] << 8) | ch.sock_in[hdr + addr_len + 1]);
                            ch.sock_in.clear();

                            BufWriter co;
                            co.u8(SSH_MSG_CHANNEL_OPEN);
                            co.str("direct-tcpip");
                            co.u32(ch.local_id);
                            co.u32(LOCAL_WINDOW_SIZE);
                            co.u32(LOCAL_MAX_PACKET);
                            co.str(target_host);
                            co.u32(target_port);
                            co.str("127.0.0.1");
                            co.u32(uint32_t(cfg.local_port));
                            tr.queue_packet(co.data);
                            ch.state = ForwardChannel::State::OpeningChannel;
                        }
                    }
                } else if (ch.state == ForwardChannel::State::Streaming && ch.remote_window > 0) {
                    size_t max_read = std::min<size_t>({ch.remote_window, ch.remote_max_pkt, 32768});
                    std::vector<uint8_t> buf(max_read);
                    ssize_t r = recv(ch.sock_fd, buf.data(), max_read, 0);
                    if (r > 0) {
                        ch.remote_window -= uint32_t(r);
                        BufWriter dw;
                        dw.u8(SSH_MSG_CHANNEL_DATA);
                        dw.u32(ch.remote_id);
                        dw.bytes(buf.data(), size_t(r));
                        tr.queue_packet(dw.data);
                    } else if (r == 0) {
                        ch.local_eof = true;
                        if (!ch.sent_eof) {
                            BufWriter ew;
                            ew.u8(SSH_MSG_CHANNEL_EOF);
                            ew.u32(ch.remote_id);
                            tr.queue_packet(ew.data);
                            ch.sent_eof = true;
                        }
                        if (ch.remote_eof && ch.sock_out.empty()) {
                            close_channel(ch);
                        }
                    } else if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) {
                        close_channel(ch);
                    }
                }
            }
        }

        // Cleanup closed channels
        for (auto it = channels.begin(); it != channels.end();) {
            if (it->second->state == ForwardChannel::State::Closing) {
                it = channels.erase(it);
            } else {
                ++it;
            }
        }

        // Periodic keepalive (every 25s)
        auto now = std::chrono::steady_clock::now();
        if (std::chrono::duration_cast<std::chrono::seconds>(now - last_keepalive).count() >= 25) {
            last_keepalive = now;
            BufWriter kw;
            kw.u8(SSH_MSG_GLOBAL_REQUEST);
            kw.str("keepalive@openssh.com");
            kw.b(true);
            tr.queue_packet(kw.data);
        }

        if (!tr.flush_nonblocking(err)) break;
    }

    for (auto& kv : channels) {
        if (kv.second->sock_fd >= 0) close(kv.second->sock_fd);
    }
    close(listen_fd);
    close(ssh_fd);

    bool expected_stop = !running_.exchange(false);
    connected_.store(false);
    proxy_listening_.store(false);

    if (!expected_stop) {
        if (!err.empty()) {
            last_error_ = err;
            SVPN_LOGE("SSH tunnel error: %s", err.c_str());
            if (callbacks.on_error) callbacks.on_error(err);
        } else {
            SVPN_LOGW("SSH tunnel disconnected");
            if (callbacks.on_disconnected) callbacks.on_disconnected();
        }
    }
}

} // namespace svpn
