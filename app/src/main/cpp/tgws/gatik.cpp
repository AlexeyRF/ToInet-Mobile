#if defined(_WIN32)
    #define WIN32_LEAN_AND_MEAN
    #include <windows.h>
    #include <winsock2.h>
    #include <ws2tcpip.h>
    #pragma comment(lib, "ws2_32.lib")
    typedef int socklen_t;
#else
    #include <sys/types.h>
    #include <sys/socket.h>
    #include <netinet/in.h>
    #include <netinet/tcp.h>
    #include <arpa/inet.h>
    #include <unistd.h>
    #include <fcntl.h>
    #include <signal.h>
    #include <netdb.h>
    #include <errno.h>

    typedef int SOCKET;
    #define INVALID_SOCKET (-1)
    #define SOCKET_ERROR (-1)
    #define closesocket(s) close(s)
    #define SD_BOTH SHUT_RDWR
#endif

#include <iostream>
#include <vector>
#include <string>
#include <thread>
#include <atomic>
#include <chrono>
#include <cstring>
#include <fstream>
#include <algorithm>
#include <mutex>
#include <jni.h>

#if defined(__ANDROID__)
    #include <android/log.h>
    #define TAG "GatikNative"
    #define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
    #define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#else
    #define LOGI(...) do { printf("[Gatik] "); printf(__VA_ARGS__); printf("\n"); } while(0)
    #define LOGE(...) do { fprintf(stderr, "[Gatik ERROR] "); fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n"); } while(0)
#endif

static std::atomic<bool> g_running(false);
static std::atomic<int64_t> g_global_upload_mode_until(0);
static SOCKET g_listen_sock = INVALID_SOCKET;
static std::thread g_server_thread;
static std::mutex g_start_mutex;

static const size_t UPLOAD_THRESHOLD = 1024;
static const size_t BUFFER_SIZE = 16384;

static std::string g_listen_host = "127.0.0.1";
static int g_listen_port = 1777;

static std::string g_download_proxy_host = "127.0.0.1";
static int g_download_proxy_port = 9853; // TOR

static std::string g_upload_proxy_host = "127.0.0.1";
static int g_upload_proxy_port = 1480;   // TGWS

static int64_t get_current_time_sec() {
    return std::chrono::duration_cast<std::chrono::seconds>(
        std::chrono::system_clock::now().time_since_epoch()
    ).count();
}

static bool recv_exact(SOCKET s, uint8_t* buf, size_t len) {
    size_t total = 0;
    while (total < len) {
        int r = recv(s, reinterpret_cast<char*>(buf + total), static_cast<int>(len - total), 0);
        if (r <= 0) {
            return false;
        }
        total += static_cast<size_t>(r);
    }
    return true;
}

static bool send_all(SOCKET s, const uint8_t* buf, size_t len) {
    size_t total = 0;
    while (total < len) {
        int r = send(s, reinterpret_cast<const char*>(buf + total), static_cast<int>(len - total), 0);
        if (r <= 0) {
            return false;
        }
        total += static_cast<size_t>(r);
    }
    return true;
}

static SOCKET connect_to(const std::string& host, int port) {
    SOCKET sock = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (sock == INVALID_SOCKET) {
        return INVALID_SOCKET;
    }

    int nodelay = 1;
    setsockopt(sock, IPPROTO_TCP, TCP_NODELAY, reinterpret_cast<const char*>(&nodelay), sizeof(nodelay));

    sockaddr_in addr;
    std::memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons(static_cast<uint16_t>(port));
    if (inet_pton(AF_INET, host.c_str(), &addr.sin_addr) <= 0) {
        // Fallback for hostname resolution
        struct hostent* he = gethostbyname(host.c_str());
        if (!he || !he->h_addr_list[0]) {
            closesocket(sock);
            return INVALID_SOCKET;
        }
        std::memcpy(&addr.sin_addr, he->h_addr_list[0], sizeof(addr.sin_addr));
    }

    if (connect(sock, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) == SOCKET_ERROR) {
        closesocket(sock);
        return INVALID_SOCKET;
    }
    return sock;
}

static void forward_traffic(SOCKET src, SOCKET dst, bool is_upload, bool is_client_to_server, size_t initial_bytes) {
    std::vector<uint8_t> buffer(BUFFER_SIZE);
    size_t bytes_transferred = initial_bytes;

    while (g_running.load()) {
        int n = recv(src, reinterpret_cast<char*>(buffer.data()), static_cast<int>(buffer.size()), 0);
        if (n <= 0) {
            break;
        }

        if (!send_all(dst, buffer.data(), static_cast<size_t>(n))) {
            break;
        }

        bytes_transferred += static_cast<size_t>(n);

        if (is_client_to_server) {
            int64_t now = get_current_time_sec();
            // If routed via TOR but client sends > 64KB, abort so Telegram reconnects to TGWS
            if (!is_upload && bytes_transferred > 65536) {
                LOGI("Heavy upload detected (%zu bytes) on TOR line! Aborting to trigger TGWS reconnect...", bytes_transferred);
                g_global_upload_mode_until.store(now + 15);
                break;
            }
            if (is_upload && bytes_transferred > 65536) {
                int64_t cur = g_global_upload_mode_until.load();
                int64_t target = now + 15;
                if (target > cur) {
                    g_global_upload_mode_until.store(target);
                }
            }
        }
    }

    shutdown(src, SD_BOTH);
    shutdown(dst, SD_BOTH);
}

static void handle_client(SOCKET client_sock) {
    int nodelay = 1;
    setsockopt(client_sock, IPPROTO_TCP, TCP_NODELAY, reinterpret_cast<const char*>(&nodelay), sizeof(nodelay));

    // 1. SOCKS5 Handshake
    uint8_t hdr[2];
    if (!recv_exact(client_sock, hdr, 2) || hdr[0] != 5) {
        closesocket(client_sock);
        return;
    }
    uint8_t nmethods = hdr[1];
    std::vector<uint8_t> methods(nmethods);
    if (!recv_exact(client_sock, methods.data(), nmethods)) {
        closesocket(client_sock);
        return;
    }

    // Handshake response: No authentication required
    uint8_t hs_reply[2] = {0x05, 0x00};
    if (!send_all(client_sock, hs_reply, 2)) {
        closesocket(client_sock);
        return;
    }

    // 2. CONNECT Request
    uint8_t req_hdr[4];
    if (!recv_exact(client_sock, req_hdr, 4)) {
        closesocket(client_sock);
        return;
    }
    if (req_hdr[0] != 5 || req_hdr[1] != 1) { // Only CONNECT supported
        closesocket(client_sock);
        return;
    }

    uint8_t atyp = req_hdr[3];
    std::vector<uint8_t> dst_addr_bytes;
    std::string dst_display;

    if (atyp == 1) { // IPv4
        dst_addr_bytes.resize(4);
        if (!recv_exact(client_sock, dst_addr_bytes.data(), 4)) {
            closesocket(client_sock);
            return;
        }
        dst_display = std::to_string(dst_addr_bytes[0]) + "." +
                      std::to_string(dst_addr_bytes[1]) + "." +
                      std::to_string(dst_addr_bytes[2]) + "." +
                      std::to_string(dst_addr_bytes[3]);
    } else if (atyp == 3) { // Domain
        uint8_t dlen = 0;
        if (!recv_exact(client_sock, &dlen, 1)) {
            closesocket(client_sock);
            return;
        }
        dst_addr_bytes.resize(dlen);
        if (!recv_exact(client_sock, dst_addr_bytes.data(), dlen)) {
            closesocket(client_sock);
            return;
        }
        dst_display = std::string(reinterpret_cast<char*>(dst_addr_bytes.data()), dlen);
    } else if (atyp == 4) { // IPv6
        dst_addr_bytes.resize(16);
        if (!recv_exact(client_sock, dst_addr_bytes.data(), 16)) {
            closesocket(client_sock);
            return;
        }
        dst_display = "[IPv6]";
    } else {
        closesocket(client_sock);
        return;
    }

    uint8_t dst_port_bytes[2];
    if (!recv_exact(client_sock, dst_port_bytes, 2)) {
        closesocket(client_sock);
        return;
    }
    uint16_t dst_port = (static_cast<uint16_t>(dst_port_bytes[0]) << 8) | dst_port_bytes[1];

    // 3. Early SUCCESS reply to client
    // Telegram will now immediately send the first data packet
    uint8_t success_reply[10] = {0x05, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
    if (!send_all(client_sock, success_reply, 10)) {
        closesocket(client_sock);
        return;
    }

    // 4. Intercept first packet from client
    std::vector<uint8_t> first_chunk(BUFFER_SIZE);
    int r = recv(client_sock, reinterpret_cast<char*>(first_chunk.data()), static_cast<int>(first_chunk.size()), 0);
    if (r <= 0) {
        closesocket(client_sock);
        return;
    }
    first_chunk.resize(static_cast<size_t>(r));

    // Wait slightly (up to 100ms) if chunk is small to assemble full payload if sent in fragments
    while (first_chunk.size() < UPLOAD_THRESHOLD) {
        timeval tv;
        tv.tv_sec = 0;
        tv.tv_usec = 100000; // 100ms
        fd_set fds;
        FD_ZERO(&fds);
        FD_SET(client_sock, &fds);
        int sel = select(client_sock + 1, &fds, NULL, NULL, &tv);
        if (sel <= 0) {
            break;
        }
        uint8_t tmp[BUFFER_SIZE];
        int more = recv(client_sock, reinterpret_cast<char*>(tmp), sizeof(tmp), 0);
        if (more <= 0) {
            break;
        }
        first_chunk.insert(first_chunk.end(), tmp, tmp + more);
    }

    // 5. Routing Decision
    int64_t now = get_current_time_sec();
    bool is_upload = (first_chunk.size() > UPLOAD_THRESHOLD) || (now < g_global_upload_mode_until.load());

    std::string upstream_host = is_upload ? g_upload_proxy_host : g_download_proxy_host;
    int upstream_port = is_upload ? g_upload_proxy_port : g_download_proxy_port;
    const char* route_label = is_upload ? "UPLOAD (TGWS)" : "DOWNLOAD (TOR)";

    LOGI("Connect to %s:%u | First chunk: %zu bytes -> %s", dst_display.c_str(), dst_port, first_chunk.size(), route_label);

    // 6. Connect to upstream proxy
    SOCKET upstream_sock = connect_to(upstream_host, upstream_port);
    if (upstream_sock == INVALID_SOCKET) {
        LOGE("Failed to connect to upstream %s:%d", upstream_host.c_str(), upstream_port);
        closesocket(client_sock);
        return;
    }

    // Upstream Handshake
    uint8_t up_hs[3] = {0x05, 0x01, 0x00};
    if (!send_all(upstream_sock, up_hs, 3)) {
        closesocket(upstream_sock);
        closesocket(client_sock);
        return;
    }
    uint8_t up_hs_resp[2];
    if (!recv_exact(upstream_sock, up_hs_resp, 2) || up_hs_resp[0] != 5 || up_hs_resp[1] != 0) {
        closesocket(upstream_sock);
        closesocket(client_sock);
        return;
    }

    // Upstream CONNECT request
    std::vector<uint8_t> up_req = {0x05, 0x01, 0x00, atyp};
    if (atyp == 3) {
        up_req.push_back(static_cast<uint8_t>(dst_addr_bytes.size()));
    }
    up_req.insert(up_req.end(), dst_addr_bytes.begin(), dst_addr_bytes.end());
    up_req.push_back(dst_port_bytes[0]);
    up_req.push_back(dst_port_bytes[1]);

    if (!send_all(upstream_sock, up_req.data(), up_req.size())) {
        closesocket(upstream_sock);
        closesocket(client_sock);
        return;
    }

    // Upstream CONNECT response
    uint8_t up_resp_hdr[4];
    if (!recv_exact(upstream_sock, up_resp_hdr, 4) || up_resp_hdr[1] != 0) {
        closesocket(upstream_sock);
        closesocket(client_sock);
        return;
    }

    uint8_t up_atyp = up_resp_hdr[3];
    size_t skip_len = 0;
    if (up_atyp == 1) skip_len = 4;
    else if (up_atyp == 3) {
        uint8_t l = 0;
        recv_exact(upstream_sock, &l, 1);
        skip_len = l;
    } else if (up_atyp == 4) skip_len = 16;

    std::vector<uint8_t> dummy(skip_len + 2);
    if (!recv_exact(upstream_sock, dummy.data(), dummy.size())) {
        closesocket(upstream_sock);
        closesocket(client_sock);
        return;
    }

    // 7. Flush intercepted first packet to upstream
    if (!send_all(upstream_sock, first_chunk.data(), first_chunk.size())) {
        closesocket(upstream_sock);
        closesocket(client_sock);
        return;
    }

    // 8. Bidirectional Pipe
    std::thread t1(forward_traffic, client_sock, upstream_sock, is_upload, true, first_chunk.size());
    std::thread t2(forward_traffic, upstream_sock, client_sock, is_upload, false, 0);

    t1.join();
    t2.join();

    closesocket(client_sock);
    closesocket(upstream_sock);
}

void run_gatik_server(const std::string& host, int port, const std::string& tor_host, int tor_port, const std::string& tgws_host, int tgws_port) {
#if !defined(_WIN32)
    signal(SIGPIPE, SIG_IGN);
#else
    WSADATA wsa;
    WSAStartup(MAKEWORD(2, 2), &wsa);
#endif

    g_listen_host = host;
    g_listen_port = port;
    g_download_proxy_host = tor_host;
    g_download_proxy_port = tor_port;
    g_upload_proxy_host = tgws_host;
    g_upload_proxy_port = tgws_port;

    SOCKET listen_sock = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (listen_sock == INVALID_SOCKET) {
        LOGE("socket() failed");
        return;
    }

    int reuse = 1;
    setsockopt(listen_sock, SOL_SOCKET, SO_REUSEADDR, reinterpret_cast<const char*>(&reuse), sizeof(reuse));

    sockaddr_in server_addr;
    std::memset(&server_addr, 0, sizeof(server_addr));
    server_addr.sin_family = AF_INET;
    server_addr.sin_port = htons(static_cast<uint16_t>(g_listen_port));
    inet_pton(AF_INET, g_listen_host.c_str(), &server_addr.sin_addr);

    if (bind(listen_sock, reinterpret_cast<sockaddr*>(&server_addr), sizeof(server_addr)) == SOCKET_ERROR) {
        LOGE("bind() failed for %s:%d", g_listen_host.c_str(), g_listen_port);
        closesocket(listen_sock);
        return;
    }

    if (listen(listen_sock, SOMAXCONN) == SOCKET_ERROR) {
        LOGE("listen() failed");
        closesocket(listen_sock);
        return;
    }

    g_listen_sock = listen_sock;
    g_running.store(true);
    LOGI("SOCKS5 Router started: %s:%d | TOR: %s:%d | TGWS: %s:%d",
         g_listen_host.c_str(), g_listen_port,
         g_download_proxy_host.c_str(), g_download_proxy_port,
         g_upload_proxy_host.c_str(), g_upload_proxy_port);

    while (g_running.load()) {
        timeval tv;
        tv.tv_sec = 1;
        tv.tv_usec = 0;
        fd_set fds;
        FD_ZERO(&fds);
        FD_SET(listen_sock, &fds);

        int sel = select(listen_sock + 1, &fds, NULL, NULL, &tv);
        if (sel > 0 && FD_ISSET(listen_sock, &fds)) {
            SOCKET client_sock = accept(listen_sock, NULL, NULL);
            if (client_sock != INVALID_SOCKET) {
                std::thread(handle_client, client_sock).detach();
            }
        }
    }

    closesocket(listen_sock);
    g_listen_sock = INVALID_SOCKET;
#if defined(_WIN32)
    WSACleanup();
#endif
    LOGI("Gatik Server stopped");
}

void stop_gatik_server() {
    g_running.store(false);
    if (g_listen_sock != INVALID_SOCKET) {
        SOCKET s = g_listen_sock;
        g_listen_sock = INVALID_SOCKET;
        closesocket(s);
    }
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_ru_toinet_android_tgws_GatikNative_nativeStartGatik(
    JNIEnv* env, jclass clazz,
    jstring jhost, jint jport,
    jstring jtorHost, jint jtorPort,
    jstring jtgwsHost, jint jtgwsPort) {

    std::lock_guard<std::mutex> lock(g_start_mutex);
    if (g_running.load()) {
        return JNI_TRUE;
    }

    const char* host_c = env->GetStringUTFChars(jhost, nullptr);
    const char* tor_host_c = env->GetStringUTFChars(jtorHost, nullptr);
    const char* tgws_host_c = env->GetStringUTFChars(jtgwsHost, nullptr);

    std::string host = host_c ? host_c : "127.0.0.1";
    std::string tor_host = tor_host_c ? tor_host_c : "127.0.0.1";
    std::string tgws_host = tgws_host_c ? tgws_host_c : "127.0.0.1";

    if (host_c) env->ReleaseStringUTFChars(jhost, host_c);
    if (tor_host_c) env->ReleaseStringUTFChars(jtorHost, tor_host_c);
    if (tgws_host_c) env->ReleaseStringUTFChars(jtgwsHost, tgws_host_c);

    if (g_server_thread.joinable()) {
        g_server_thread.join();
    }

    g_server_thread = std::thread([host, jport, tor_host, jtorPort, tgws_host, jtgwsPort]() {
        run_gatik_server(host, jport, tor_host, jtorPort, tgws_host, jtgwsPort);
    });

    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_ru_toinet_android_tgws_GatikNative_nativeStopGatik(JNIEnv* env, jclass clazz) {
    std::lock_guard<std::mutex> lock(g_start_mutex);
    stop_gatik_server();
    if (g_server_thread.joinable()) {
        g_server_thread.join();
    }
}

JNIEXPORT jboolean JNICALL
Java_ru_toinet_android_tgws_GatikNative_nativeIsRunning(JNIEnv* env, jclass clazz) {
    return g_running.load() ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"

#if defined(STANDALONE_GATIK_BIN)
int main(int argc, char* argv[]) {
    std::string host = "127.0.0.1";
    int port = 1777;
    std::string tor_host = "127.0.0.1";
    int tor_port = 9853;
    std::string tgws_host = "127.0.0.1";
    int tgws_port = 1480;

    for (int i = 1; i < argc; ++i) {
        std::string arg = argv[i];
        if (arg == "--host" && i + 1 < argc) {
            host = argv[++i];
        } else if (arg == "--port" && i + 1 < argc) {
            port = std::stoi(argv[++i]);
        } else if (arg == "--tor-port" && i + 1 < argc) {
            tor_port = std::stoi(argv[++i]);
        } else if (arg == "--tor-host" && i + 1 < argc) {
            tor_host = argv[++i];
        } else if (arg == "--tgws-port" && i + 1 < argc) {
            tgws_port = std::stoi(argv[++i]);
        } else if (arg == "--tgws-host" && i + 1 < argc) {
            tgws_host = argv[++i];
        }
    }

    run_gatik_server(host, port, tor_host, tor_port, tgws_host, tgws_port);
    return 0;
}
#endif
