#include "ssh_crypto.h"

#include <algorithm>
#include <cstring>
#include <fcntl.h>
#include <unistd.h>
#if defined(__ANDROID__) || defined(__linux__)
#include <sys/random.h>
#endif

namespace svpn {
namespace crypto {

// -----------------------------------------------------------------------------
// Helpers
// -----------------------------------------------------------------------------
static inline uint32_t load32_be(const uint8_t* p) {
    return (uint32_t(p[0]) << 24) | (uint32_t(p[1]) << 16) | (uint32_t(p[2]) << 8) | uint32_t(p[3]);
}
static inline void store32_be(uint8_t* p, uint32_t v) {
    p[0] = uint8_t(v >> 24);
    p[1] = uint8_t(v >> 16);
    p[2] = uint8_t(v >> 8);
    p[3] = uint8_t(v);
}
static inline uint64_t load64_be(const uint8_t* p) {
    return (uint64_t(load32_be(p)) << 32) | load32_be(p + 4);
}
static inline void store64_be(uint8_t* p, uint64_t v) {
    store32_be(p, uint32_t(v >> 32));
    store32_be(p + 4, uint32_t(v));
}
static inline uint32_t load32_le(const uint8_t* p) {
    return uint32_t(p[0]) | (uint32_t(p[1]) << 8) | (uint32_t(p[2]) << 16) | (uint32_t(p[3]) << 24);
}
static inline void store32_le(uint8_t* p, uint32_t v) {
    p[0] = uint8_t(v);
    p[1] = uint8_t(v >> 8);
    p[2] = uint8_t(v >> 16);
    p[3] = uint8_t(v >> 24);
}
static inline void store64_le(uint8_t* p, uint64_t v) {
    store32_le(p, uint32_t(v));
    store32_le(p + 4, uint32_t(v >> 32));
}
static inline uint32_t rotr32(uint32_t x, int n) { return (x >> n) | (x << (32 - n)); }
static inline uint32_t rotl32(uint32_t x, int n) { return (x << n) | (x >> (32 - n)); }
static inline uint64_t rotr64(uint64_t x, int n) { return (x >> n) | (x << (64 - n)); }

bool random_bytes(uint8_t* out, size_t len) {
    size_t off = 0;
#if defined(__ANDROID__) || defined(__linux__)
    while (off < len) {
        ssize_t r = getrandom(out + off, len - off, 0);
        if (r > 0) {
            off += size_t(r);
            continue;
        }
        if (r < 0 && errno == EINTR) continue;
        break;
    }
    if (off == len) return true;
#endif
    int fd = open("/dev/urandom", O_RDONLY);
    if (fd < 0) return false;
    while (off < len) {
        ssize_t r = read(fd, out + off, len - off);
        if (r > 0) {
            off += size_t(r);
        } else if (r < 0 && errno == EINTR) {
            continue;
        } else {
            close(fd);
            return false;
        }
    }
    close(fd);
    return true;
}

bool ct_equal(const uint8_t* a, const uint8_t* b, size_t len) {
    uint8_t diff = 0;
    for (size_t i = 0; i < len; ++i) diff |= (a[i] ^ b[i]);
    return diff == 0;
}

// -----------------------------------------------------------------------------
// SHA-1
// -----------------------------------------------------------------------------
void sha1(const uint8_t* data, size_t len, uint8_t out[20]) {
    uint32_t h[5] = {0x67452301u, 0xEFCDAB89u, 0x98BADCFEu, 0x10325476u, 0xC3D2E1F0u};
    uint8_t buf[64]{};
    size_t rem = 0;

    auto process = [&](const uint8_t* blk) {
        uint32_t w[80];
        for (int i = 0; i < 16; ++i) w[i] = load32_be(blk + i * 4);
        for (int i = 16; i < 80; ++i) w[i] = rotl32(w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16], 1);
        uint32_t a = h[0], b = h[1], c = h[2], d = h[3], e = h[4];
        for (int i = 0; i < 80; ++i) {
            uint32_t f, k;
            if (i < 20) { f = (b & c) | ((~b) & d); k = 0x5A827999u; }
            else if (i < 40) { f = b ^ c ^ d; k = 0x6ED9EBA1u; }
            else if (i < 60) { f = (b & c) | (b & d) | (c & d); k = 0x8F1BBCDCu; }
            else { f = b ^ c ^ d; k = 0xCA62C1D6u; }
            uint32_t t = rotl32(a, 5) + f + e + k + w[i];
            e = d; d = c; c = rotl32(b, 30); b = a; a = t;
        }
        h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e;
    };

    size_t i = 0;
    while (i + 64 <= len) {
        process(data + i);
        i += 64;
    }
    rem = len - i;
    if (rem > 0) std::memcpy(buf, data + i, rem);
    buf[rem++] = 0x80;
    if (rem > 56) {
        std::memset(buf + rem, 0, 64 - rem);
        process(buf);
        rem = 0;
    }
    std::memset(buf + rem, 0, 56 - rem);
    store64_be(buf + 56, uint64_t(len) * 8);
    process(buf);

    for (int j = 0; j < 5; ++j) store32_be(out + j * 4, h[j]);
}

// -----------------------------------------------------------------------------
// SHA-256
// -----------------------------------------------------------------------------
void sha256(const uint8_t* data, size_t len, uint8_t out[32]) {
    static const uint32_t K[64] = {
        0x428a2f98u, 0x71374491u, 0xb5c0fbcfu, 0xe9b5dba5u, 0x3956c25bu, 0x59f111f1u, 0x923f82a4u, 0xab1c5ed5u,
        0xd807aa98u, 0x12835b01u, 0x243185beu, 0x550c7dc3u, 0x72be5d74u, 0x80deb1feu, 0x9bdc06a7u, 0xc19bf174u,
        0xe49b69c1u, 0xefbe4786u, 0x0fc19dc6u, 0x240ca1ccu, 0x2de92c6fu, 0x4a7484aau, 0x5cb0a9dcu, 0x76f988dau,
        0x983e5152u, 0xa831c66du, 0xb00327c8u, 0xbf597fc7u, 0xc6e00bf3u, 0xd5a79147u, 0x06ca6351u, 0x14292967u,
        0x27b70a85u, 0x2e1b2138u, 0x4d2c6dfcu, 0x53380d13u, 0x650a7354u, 0x766a0abbu, 0x81c2c92eu, 0x92722c85u,
        0xa2bfe8a1u, 0xa81a664bu, 0xc24b8b70u, 0xc76c51a3u, 0xd192e819u, 0xd6990624u, 0xf40e3585u, 0x106aa070u,
        0x19a4c116u, 0x1e376c08u, 0x2748774cu, 0x34b0bcb5u, 0x391c0cb3u, 0x4ed8aa4au, 0x5b9cca4fu, 0x682e6ff3u,
        0x748f82eeu, 0x78a5636fu, 0x84c87814u, 0x8cc70208u, 0x90befffau, 0xa4506cebu, 0xbef9a3f7u, 0xc67178f2u
    };
    uint32_t h[8] = {
        0x6a09e667u, 0xbb67ae85u, 0x3c6ef372u, 0xa54ff53au,
        0x510e527fu, 0x9b05688cu, 0x1f83d9abu, 0x5be0cd19u
    };
    auto process = [&](const uint8_t* blk) {
        uint32_t w[64];
        for (int i = 0; i < 16; ++i) w[i] = load32_be(blk + i * 4);
        for (int i = 16; i < 64; ++i) {
            uint32_t s0 = rotr32(w[i - 15], 7) ^ rotr32(w[i - 15], 18) ^ (w[i - 15] >> 3);
            uint32_t s1 = rotr32(w[i - 2], 17) ^ rotr32(w[i - 2], 19) ^ (w[i - 2] >> 10);
            w[i] = w[i - 16] + s0 + w[i - 7] + s1;
        }
        uint32_t a = h[0], b = h[1], c = h[2], d = h[3], e = h[4], f = h[5], g = h[6], hh = h[7];
        for (int i = 0; i < 64; ++i) {
            uint32_t S1 = rotr32(e, 6) ^ rotr32(e, 11) ^ rotr32(e, 25);
            uint32_t ch = (e & f) ^ ((~e) & g);
            uint32_t t1 = hh + S1 + ch + K[i] + w[i];
            uint32_t S0 = rotr32(a, 2) ^ rotr32(a, 13) ^ rotr32(a, 22);
            uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
            uint32_t t2 = S0 + maj;
            hh = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2;
        }
        h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hh;
    };

    uint8_t buf[64]{};
    size_t i = 0;
    while (i + 64 <= len) {
        process(data + i);
        i += 64;
    }
    size_t rem = len - i;
    if (rem > 0) std::memcpy(buf, data + i, rem);
    buf[rem++] = 0x80;
    if (rem > 56) {
        std::memset(buf + rem, 0, 64 - rem);
        process(buf);
        rem = 0;
    }
    std::memset(buf + rem, 0, 56 - rem);
    store64_be(buf + 56, uint64_t(len) * 8);
    process(buf);

    for (int j = 0; j < 8; ++j) store32_be(out + j * 4, h[j]);
}

// -----------------------------------------------------------------------------
// SHA-512
// -----------------------------------------------------------------------------
void sha512(const uint8_t* data, size_t len, uint8_t out[64]) {
    static const uint64_t K[80] = {
        0x428a2f98d728ae22ULL, 0x7137449123ef65cdULL, 0xb5c0fbcfec4d3b2fULL, 0xe9b5dba58189dbbcULL,
        0x3956c25bf348b538ULL, 0x59f111f1b605d019ULL, 0x923f82a4af194f9bULL, 0xab1c5ed5da6d8118ULL,
        0xd807aa98a3030242ULL, 0x12835b0145706fbeULL, 0x243185be4ee4b28cULL, 0x550c7dc3d5ffb4e2ULL,
        0x72be5d74f27b896fULL, 0x80deb1fe3b1696b1ULL, 0x9bdc06a725c71235ULL, 0xc19bf174cf692694ULL,
        0xe49b69c19ef14ad2ULL, 0xefbe4786384f25e3ULL, 0x0fc19dc68b8cd5b5ULL, 0x240ca1cc77ac9c65ULL,
        0x2de92c6f592b0275ULL, 0x4a7484aa6ea6e483ULL, 0x5cb0a9dcbd41fbd4ULL, 0x76f988da831153b5ULL,
        0x983e5152ee66dfabULL, 0xa831c66d2db43210ULL, 0xb00327c898fb213fULL, 0xbf597fc7beef0ee4ULL,
        0xc6e00bf33da88fc2ULL, 0xd5a79147930aa725ULL, 0x06ca6351e003826fULL, 0x142929670a0e6e70ULL,
        0x27b70a8546d22ffcULL, 0x2e1b21385c26c926ULL, 0x4d2c6dfc5ac42aedULL, 0x53380d139d95b3dfULL,
        0x650a73548baf63deULL, 0x766a0abb3c77b2a8ULL, 0x81c2c92e47edaee6ULL, 0x92722c851482353bULL,
        0xa2bfe8a14cf10364ULL, 0xa81a664bbc423001ULL, 0xc24b8b70d0f89791ULL, 0xc76c51a30654be30ULL,
        0xd192e819d6ef5218ULL, 0xd69906245565a910ULL, 0xf40e35855771202aULL, 0x106aa07032bbd1b8ULL,
        0x19a4c116b8d2d0c8ULL, 0x1e376c085141ab53ULL, 0x2748774cdf8eeb99ULL, 0x34b0bcb5e19b48a8ULL,
        0x391c0cb3c5c95a63ULL, 0x4ed8aa4ae3418acbULL, 0x5b9cca4f7763e373ULL, 0x682e6ff3d6b2b8a3ULL,
        0x748f82ee5defb2fcULL, 0x78a5636f43172f60ULL, 0x84c87814a1f0ab72ULL, 0x8cc702081a6439ecULL,
        0x90befffa23631e28ULL, 0xa4506cebde82bde9ULL, 0xbef9a3f7b2c67915ULL, 0xc67178f2e372532bULL,
        0xca273eceea26619cULL, 0xd186b8c721c0c207ULL, 0xeada7dd6cde0eb1eULL, 0xf57d4f7fee6ed178ULL,
        0x06f067aa72176fbaULL, 0x0a637dc5a2c898a6ULL, 0x113f9804bef90daeULL, 0x1b710b35131c471bULL,
        0x28db77f523047d84ULL, 0x32caab7b40c72493ULL, 0x3c9ebe0a15c9bebcULL, 0x431d67c49c100d4cULL,
        0x4cc5d4becb3e42b6ULL, 0x597f299cfc657e2aULL, 0x5fcb6fab3ad6faecULL, 0x6c44198c4a475817ULL
    };
    uint64_t h[8] = {
        0x6a09e667f3bcc908ULL, 0xbb67ae8584caa73bULL, 0x3c6ef372fe94f82bULL, 0xa54ff53a5f1d36f1ULL,
        0x510e527fade682d1ULL, 0x9b05688c2b3e6c1fULL, 0x1f83d9abfb41bd6bULL, 0x5be0cd19137e2179ULL
    };
    auto process = [&](const uint8_t* blk) {
        uint64_t w[80];
        for (int i = 0; i < 16; ++i) w[i] = load64_be(blk + i * 8);
        for (int i = 16; i < 80; ++i) {
            uint64_t s0 = rotr64(w[i - 15], 1) ^ rotr64(w[i - 15], 8) ^ (w[i - 15] >> 7);
            uint64_t s1 = rotr64(w[i - 2], 19) ^ rotr64(w[i - 2], 61) ^ (w[i - 2] >> 6);
            w[i] = w[i - 16] + s0 + w[i - 7] + s1;
        }
        uint64_t a = h[0], b = h[1], c = h[2], d = h[3], e = h[4], f = h[5], g = h[6], hh = h[7];
        for (int i = 0; i < 80; ++i) {
            uint64_t S1 = rotr64(e, 14) ^ rotr64(e, 18) ^ rotr64(e, 41);
            uint64_t ch = (e & f) ^ ((~e) & g);
            uint64_t t1 = hh + S1 + ch + K[i] + w[i];
            uint64_t S0 = rotr64(a, 28) ^ rotr64(a, 34) ^ rotr64(a, 39);
            uint64_t maj = (a & b) ^ (a & c) ^ (b & c);
            uint64_t t2 = S0 + maj;
            hh = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2;
        }
        h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hh;
    };

    uint8_t buf[128]{};
    size_t i = 0;
    while (i + 128 <= len) {
        process(data + i);
        i += 128;
    }
    size_t rem = len - i;
    if (rem > 0) std::memcpy(buf, data + i, rem);
    buf[rem++] = 0x80;
    if (rem > 112) {
        std::memset(buf + rem, 0, 128 - rem);
        process(buf);
        rem = 0;
    }
    std::memset(buf + rem, 0, 120 - rem);
    store64_be(buf + 120, uint64_t(len) * 8);
    process(buf);

    for (int j = 0; j < 8; ++j) store64_be(out + j * 8, h[j]);
}

// -----------------------------------------------------------------------------
// HMAC
// -----------------------------------------------------------------------------
template <size_t BlockSize, size_t DigestSize, typename HashFn>
static void hmac_impl(HashFn hash_fn, const uint8_t* key, size_t key_len,
                      const uint8_t* data, size_t data_len, uint8_t* out) {
    uint8_t k_pad[BlockSize]{};
    if (key_len > BlockSize) {
        hash_fn(key, key_len, k_pad);
    } else if (key_len > 0) {
        std::memcpy(k_pad, key, key_len);
    }

    std::vector<uint8_t> inner(BlockSize + data_len);
    for (size_t i = 0; i < BlockSize; ++i) inner[i] = k_pad[i] ^ 0x36;
    if (data_len > 0) std::memcpy(inner.data() + BlockSize, data, data_len);
    uint8_t inner_hash[DigestSize];
    hash_fn(inner.data(), inner.size(), inner_hash);

    uint8_t outer[BlockSize + DigestSize];
    for (size_t i = 0; i < BlockSize; ++i) outer[i] = k_pad[i] ^ 0x5c;
    std::memcpy(outer + BlockSize, inner_hash, DigestSize);
    hash_fn(outer, sizeof(outer), out);
}

void hmac_sha1(const uint8_t* key, size_t key_len, const uint8_t* data, size_t data_len, uint8_t out[20]) {
    hmac_impl<64, 20>(sha1, key, key_len, data, data_len, out);
}
void hmac_sha256(const uint8_t* key, size_t key_len, const uint8_t* data, size_t data_len, uint8_t out[32]) {
    hmac_impl<64, 32>(sha256, key, key_len, data, data_len, out);
}
void hmac_sha512(const uint8_t* key, size_t key_len, const uint8_t* data, size_t data_len, uint8_t out[64]) {
    hmac_impl<128, 64>(sha512, key, key_len, data, data_len, out);
}

// -----------------------------------------------------------------------------
// Curve25519 / Ed25519 (TweetNaCl-derived, public domain)
// -----------------------------------------------------------------------------
using gf = int64_t[16];

static void set25519(gf r, const gf a) {
    for (int i = 0; i < 16; ++i) r[i] = a[i];
}
static void car25519(gf o) {
    for (int i = 0; i < 16; ++i) {
        o[i] += (1LL << 16);
        int64_t c = o[i] >> 16;
        o[(i + 1) * (i < 15)] += c - 1 + 37 * (c - 1) * (i == 15);
        o[i] -= c << 16;
    }
}
static void sel25519(gf p, gf q, int b) {
    int64_t c = ~(b - 1);
    for (int i = 0; i < 16; ++i) {
        int64_t t = c & (p[i] ^ q[i]);
        p[i] ^= t;
        q[i] ^= t;
    }
}
static void pack25519(uint8_t* o, const gf n) {
    gf m, t;
    set25519(t, n);
    car25519(t);
    car25519(t);
    car25519(t);
    for (int j = 0; j < 2; ++j) {
        m[0] = t[0] - 0xffed;
        for (int i = 1; i < 15; ++i) {
            m[i] = t[i] - 0xffff - ((m[i - 1] >> 16) & 1);
            m[i - 1] &= 0xffff;
        }
        m[15] = t[15] - 0x7fff - ((m[14] >> 16) & 1);
        int b = int((m[15] >> 16) & 1);
        m[14] &= 0xffff;
        sel25519(t, m, 1 - b);
    }
    for (int i = 0; i < 16; ++i) {
        o[2 * i] = uint8_t(t[i] & 0xff);
        o[2 * i + 1] = uint8_t(t[i] >> 8);
    }
}
static void unpack25519(gf o, const uint8_t* n) {
    for (int i = 0; i < 16; ++i) o[i] = int64_t(n[2 * i]) + (int64_t(n[2 * i + 1]) << 8);
    o[15] &= 0x7fff;
}
static void A(gf o, const gf a, const gf b) {
    for (int i = 0; i < 16; ++i) o[i] = a[i] + b[i];
}
static void Z(gf o, const gf a, const gf b) {
    for (int i = 0; i < 16; ++i) o[i] = a[i] - b[i];
}
static void M(gf o, const gf a, const gf b) {
    int64_t t[31]{};
    for (int i = 0; i < 16; ++i)
        for (int j = 0; j < 16; ++j)
            t[i + j] += a[i] * b[j];
    for (int i = 0; i < 15; ++i) t[i] += 38 * t[i + 16];
    for (int i = 0; i < 16; ++i) o[i] = t[i];
    car25519(o);
    car25519(o);
}
static void S(gf o, const gf a) { M(o, a, a); }
static void inv25519(gf o, const gf i) {
    gf c;
    set25519(c, i);
    for (int a = 253; a >= 0; --a) {
        S(c, c);
        if (a != 2 && a != 4) M(c, c, i);
    }
    set25519(o, c);
}
static void pow2523(gf o, const gf i) {
    gf c;
    set25519(c, i);
    for (int a = 250; a >= 0; --a) {
        S(c, c);
        if (a != 1) M(c, c, i);
    }
    set25519(o, c);
}

static const gf _121665 = {0xDB41, 1};

bool x25519_shared_secret(uint8_t ss[32], const uint8_t sk[32], const uint8_t pk[32]) {
    uint8_t z[32];
    std::memcpy(z, sk, 32);
    z[0] &= 248;
    z[31] &= 127;
    z[31] |= 64;

    gf x, a = {1}, b = {0}, c = {0}, d = {1}, e, f;
    unpack25519(x, pk);
    set25519(b, x);

    for (int i = 254; i >= 0; --i) {
        int r = (z[i >> 3] >> (i & 7)) & 1;
        sel25519(a, b, r);
        sel25519(c, d, r);
        A(e, a, c);
        Z(a, a, c);
        A(c, b, d);
        Z(b, b, d);
        S(d, e);
        S(f, a);
        M(a, c, a);
        M(c, b, e);
        A(e, a, c);
        Z(a, a, c);
        S(b, a);
        Z(c, d, f);
        M(a, c, _121665);
        A(a, a, d);
        M(c, c, a);
        M(a, d, f);
        M(d, b, x);
        S(b, e);
        sel25519(a, b, r);
        sel25519(c, d, r);
    }
    inv25519(c, c);
    M(a, a, c);
    pack25519(ss, a);

    // Check for all-zero shared secret (low-order point)
    uint8_t acc = 0;
    for (int i = 0; i < 32; ++i) acc |= ss[i];
    return acc != 0;
}

void x25519_public_key(uint8_t pk[32], const uint8_t sk[32]) {
    static const uint8_t basepoint[32] = {9};
    x25519_shared_secret(pk, sk, basepoint);
}

// Ed25519 helpers
static const gf gf0 = {0};
static const gf gf1 = {1};
static const gf D_ED = {0x78a3, 0x1359, 0x4dca, 0x75eb, 0xd8ab, 0x4141, 0x0a4d, 0x0070,
                       0xe898, 0x7779, 0x4079, 0x8cc7, 0xfe73, 0x2b6f, 0x6cee, 0x5203};
static const gf D2_ED = {0xf159, 0x26b2, 0x9b94, 0xebd6, 0xb156, 0x8283, 0x149a, 0x00e0,
                        0xd130, 0xeef3, 0x80f2, 0x198e, 0xfce7, 0x56df, 0xd9dc, 0x2406};
static const gf X_ED = {0xd51a, 0x8f25, 0x2d60, 0xc956, 0xa7b2, 0x9525, 0xc760, 0x692c,
                       0xdc5c, 0xfdd6, 0xe231, 0xc0a4, 0x53fe, 0xcd6e, 0x36d3, 0x2169};
static const gf Y_ED = {0x6658, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666,
                       0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666};
static const gf I_ED = {0xa0b0, 0x4a0e, 0x1b27, 0xc4ee, 0xe478, 0xad2f, 0x1806, 0x2f43,
                       0xd7a7, 0x3dfb, 0x0099, 0x2b4d, 0xdf0b, 0x4fc1, 0x2480, 0x2b83};

static int par25519(const gf a) {
    uint8_t d[32];
    pack25519(d, a);
    return d[0] & 1;
}
static int neq25519(const gf a, const gf b) {
    uint8_t c[32], d[32];
    pack25519(c, a);
    pack25519(d, b);
    return !ct_equal(c, d, 32);
}
static void cswap(gf p[4], gf q[4], uint8_t b) {
    for (int i = 0; i < 4; ++i) sel25519(p[i], q[i], b);
}
static void add_pt(gf p[4], gf q[4]) {
    gf a, b, c, d, e, f, g, h, t;
    Z(a, p[1], p[0]);
    Z(t, q[1], q[0]);
    M(a, a, t);
    A(b, p[0], p[1]);
    A(t, q[0], q[1]);
    M(b, b, t);
    M(c, p[3], q[3]);
    M(c, c, D2_ED);
    M(d, p[2], q[2]);
    A(d, d, d);
    Z(e, b, a);
    Z(f, d, c);
    A(g, d, c);
    A(h, b, a);
    M(p[0], e, f);
    M(p[1], h, g);
    M(p[2], g, f);
    M(p[3], e, h);
}
static void scalarmult_pt(gf p[4], gf q[4], const uint8_t* s) {
    set25519(p[0], gf0);
    set25519(p[1], gf1);
    set25519(p[2], gf1);
    set25519(p[3], gf0);
    for (int i = 255; i >= 0; --i) {
        uint8_t b = (s[i / 8] >> (i & 7)) & 1;
        cswap(p, q, b);
        add_pt(q, p);
        add_pt(p, p);
        cswap(p, q, b);
    }
}
static void scalarbase_pt(gf p[4], const uint8_t* s) {
    gf q[4];
    set25519(q[0], X_ED);
    set25519(q[1], Y_ED);
    set25519(q[2], gf1);
    M(q[3], X_ED, Y_ED);
    scalarmult_pt(p, q, s);
}
static void pack_pt(uint8_t* r, gf p[4]) {
    gf tx, ty, zi;
    inv25519(zi, p[2]);
    M(tx, p[0], zi);
    M(ty, p[1], zi);
    pack25519(r, ty);
    r[31] ^= uint8_t(par25519(tx) << 7);
}
static int unpackneg_pt(gf r[4], const uint8_t p[32]) {
    gf t, chk, num, den, den2, den4, den6;
    set25519(r[2], gf1);
    unpack25519(r[1], p);
    S(num, r[1]);
    M(den, num, D_ED);
    Z(num, num, r[2]);
    A(den, r[2], den);
    S(den2, den);
    S(den4, den2);
    M(den6, den4, den2);
    M(t, den6, num);
    M(t, t, den);
    pow2523(t, t);
    M(t, t, num);
    M(t, t, den);
    M(t, t, den);
    M(r[0], t, den);
    S(chk, r[0]);
    M(chk, chk, den);
    if (neq25519(chk, num)) M(r[0], r[0], I_ED);
    S(chk, r[0]);
    M(chk, chk, den);
    if (neq25519(chk, num)) return -1;
    if (par25519(r[0]) == (p[31] >> 7)) Z(r[0], gf0, r[0]);
    M(r[3], r[0], r[1]);
    return 0;
}

static const uint64_t L_ED[32] = {
    0xed, 0xd3, 0xf5, 0x5c, 0x1a, 0x63, 0x12, 0x58, 0xd6, 0x9c, 0xf7, 0xa2, 0xde, 0xf9, 0xde, 0x14,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x10
};
static void modL(uint8_t* r, int64_t x[64]) {
    for (int i = 63; i >= 32; --i) {
        int64_t carry = 0;
        int j;
        for (j = i - 32; j < i - 12; ++j) {
            x[j] += carry - 16 * x[i] * int64_t(L_ED[j - (i - 32)]);
            carry = (x[j] + 128) >> 8;
            x[j] -= carry << 8;
        }
        x[j] += carry;
        x[i] = 0;
    }
    int64_t carry = 0;
    for (int j = 0; j < 32; ++j) {
        x[j] += carry - (x[31] >> 4) * int64_t(L_ED[j]);
        carry = x[j] >> 8;
        x[j] &= 255;
    }
    for (int j = 0; j < 32; ++j) x[j] -= carry * int64_t(L_ED[j]);
    for (int i = 0; i < 32; ++i) {
        x[i + 1] += x[i] >> 8;
        r[i] = uint8_t(x[i] & 255);
    }
}
static void reduce64(uint8_t* r) {
    int64_t x[64];
    for (int i = 0; i < 64; ++i) x[i] = uint64_t(r[i]);
    std::memset(r, 0, 64);
    modL(r, x);
}

void ed25519_sign(uint8_t sig[64], const uint8_t* msg, size_t msg_len, const uint8_t sk64[64]) {
    uint8_t d[64], h[64], r[64];
    sha512(sk64, 32, d);
    d[0] &= 248;
    d[31] &= 127;
    d[31] |= 64;

    std::vector<uint8_t> buf(32 + msg_len);
    std::memcpy(buf.data(), d + 32, 32);
    if (msg_len > 0) std::memcpy(buf.data() + 32, msg, msg_len);
    sha512(buf.data(), buf.size(), r);
    reduce64(r);

    gf p[4];
    scalarbase_pt(p, r);
    pack_pt(sig, p);

    std::vector<uint8_t> hbuf(64 + msg_len);
    std::memcpy(hbuf.data(), sig, 32);
    std::memcpy(hbuf.data() + 32, sk64 + 32, 32);
    if (msg_len > 0) std::memcpy(hbuf.data() + 64, msg, msg_len);
    sha512(hbuf.data(), hbuf.size(), h);
    reduce64(h);

    int64_t x[64]{};
    for (int i = 0; i < 32; ++i) x[i] = int64_t(r[i]);
    for (int i = 0; i < 32; ++i)
        for (int j = 0; j < 32; ++j)
            x[i + j] += int64_t(h[i]) * int64_t(d[j]);
    modL(sig + 32, x);
}

bool ed25519_verify(const uint8_t sig[64], const uint8_t* msg, size_t msg_len, const uint8_t pk[32]) {
    gf p[4], q[4];
    if (unpackneg_pt(q, pk)) return false;

    std::vector<uint8_t> hbuf(64 + msg_len);
    std::memcpy(hbuf.data(), sig, 32);
    std::memcpy(hbuf.data() + 32, pk, 32);
    if (msg_len > 0) std::memcpy(hbuf.data() + 64, msg, msg_len);
    uint8_t h[64];
    sha512(hbuf.data(), hbuf.size(), h);
    reduce64(h);

    scalarmult_pt(p, q, h);
    gf b[4];
    scalarbase_pt(b, sig + 32);
    add_pt(p, b);
    uint8_t t[32];
    pack_pt(t, p);
    return ct_equal(sig, t, 32);
}

// -----------------------------------------------------------------------------
// AES-128 / AES-256 CTR
// -----------------------------------------------------------------------------
static const uint8_t AES_SBOX[256] = {
    0x63,0x7c,0x77,0x7b,0xf2,0x6b,0x6f,0xc5,0x30,0x01,0x67,0x2b,0xfe,0xd7,0xab,0x76,
    0xca,0x82,0xc9,0x7d,0xfa,0x59,0x47,0xf0,0xad,0xd4,0xa2,0xaf,0x9c,0xa4,0x72,0xc0,
    0xb7,0xfd,0x93,0x26,0x36,0x3f,0xf7,0xcc,0x34,0xa5,0xe5,0xf1,0x71,0xd8,0x31,0x15,
    0x04,0xc7,0x23,0xc3,0x18,0x96,0x05,0x9a,0x07,0x12,0x80,0xe2,0xeb,0x27,0xb2,0x75,
    0x09,0x83,0x2c,0x1a,0x1b,0x6e,0x5a,0xa0,0x52,0x3b,0xd6,0xb3,0x29,0xe3,0x2f,0x84,
    0x53,0xd1,0x00,0xed,0x20,0xfc,0xb1,0x5b,0x6a,0xcb,0xbe,0x39,0x4a,0x4c,0x58,0xcf,
    0xd0,0xef,0xaa,0xfb,0x43,0x4d,0x33,0x85,0x45,0xf9,0x02,0x7f,0x50,0x3c,0x9f,0xa8,
    0x51,0xa3,0x40,0x8f,0x92,0x9d,0x38,0xf5,0xbc,0xb6,0xda,0x21,0x10,0xff,0xf3,0xd2,
    0xcd,0x0c,0x13,0xec,0x5f,0x97,0x44,0x17,0xc4,0xa7,0x7e,0x3d,0x64,0x5d,0x19,0x73,
    0x60,0x81,0x4f,0xdc,0x22,0x2a,0x90,0x88,0x46,0xee,0xb8,0x14,0xde,0x5e,0x0b,0xdb,
    0xe0,0x32,0x3a,0x0a,0x49,0x06,0x24,0x5c,0xc2,0xd3,0xac,0x62,0x91,0x95,0xe4,0x79,
    0xe7,0xc8,0x37,0x6d,0x8d,0xd5,0x4e,0xa9,0x6c,0x56,0xf4,0xea,0x65,0x7a,0xae,0x08,
    0xba,0x78,0x25,0x2e,0x1c,0xa6,0xb4,0xc6,0xe8,0xdd,0x74,0x1f,0x4b,0xbd,0x8b,0x8a,
    0x70,0x3e,0xb5,0x66,0x48,0x03,0xf6,0x0e,0x61,0x35,0x57,0xb9,0x86,0xc1,0x1d,0x9e,
    0xe1,0xf8,0x98,0x11,0x69,0xd9,0x8e,0x94,0x9b,0x1e,0x87,0xe9,0xce,0x55,0x28,0xdf,
    0x8c,0xa1,0x89,0x0d,0xbf,0xe6,0x42,0x68,0x41,0x99,0x2d,0x0f,0xb0,0x54,0xbb,0x16
};

static inline uint32_t sub_word(uint32_t w) {
    return (uint32_t(AES_SBOX[(w >> 24) & 0xff]) << 24) |
           (uint32_t(AES_SBOX[(w >> 16) & 0xff]) << 16) |
           (uint32_t(AES_SBOX[(w >> 8) & 0xff]) << 8) |
           uint32_t(AES_SBOX[w & 0xff]);
}
static inline uint8_t xtime(uint8_t x) { return uint8_t((x << 1) ^ (((x >> 7) & 1) * 0x1b)); }

static void aes_encrypt_block(const uint32_t* rk, int rounds, const uint8_t in[16], uint8_t out[16]) {
    uint8_t s[16];
    std::memcpy(s, in, 16);
    auto add_rk = [&](int r) {
        for (int c = 0; c < 4; ++c) {
            uint32_t w = rk[r * 4 + c];
            s[c * 4 + 0] ^= uint8_t(w >> 24);
            s[c * 4 + 1] ^= uint8_t(w >> 16);
            s[c * 4 + 2] ^= uint8_t(w >> 8);
            s[c * 4 + 3] ^= uint8_t(w);
        }
    };
    add_rk(0);
    for (int r = 1; r <= rounds; ++r) {
        for (int i = 0; i < 16; ++i) s[i] = AES_SBOX[s[i]];
        // ShiftRows
        uint8_t t = s[1]; s[1] = s[5]; s[5] = s[9]; s[9] = s[13]; s[13] = t;
        t = s[2]; s[2] = s[10]; s[10] = t; t = s[6]; s[6] = s[14]; s[14] = t;
        t = s[15]; s[15] = s[11]; s[11] = s[7]; s[7] = s[3]; s[3] = t;
        if (r < rounds) {
            for (int c = 0; c < 4; ++c) {
                uint8_t a0 = s[c*4+0], a1 = s[c*4+1], a2 = s[c*4+2], a3 = s[c*4+3];
                uint8_t u = a0 ^ a1 ^ a2 ^ a3;
                s[c*4+0] ^= u ^ xtime(a0 ^ a1);
                s[c*4+1] ^= u ^ xtime(a1 ^ a2);
                s[c*4+2] ^= u ^ xtime(a2 ^ a3);
                s[c*4+3] ^= u ^ xtime(a3 ^ a0);
            }
        }
        add_rk(r);
    }
    std::memcpy(out, s, 16);
}

void AesCtr::init(const uint8_t* key, size_t key_len, const uint8_t iv[16]) {
    static const uint32_t RCON[11] = {0,0x01000000,0x02000000,0x04000000,0x08000000,0x10000000,0x20000000,0x40000000,0x80000000,0x1b000000,0x36000000};
    int nk = int(key_len / 4);
    rounds = nk + 6;
    for (int i = 0; i < nk; ++i) rk[i] = load32_be(key + i * 4);
    int total_words = 4 * (rounds + 1);
    for (int i = nk; i < total_words; ++i) {
        uint32_t tmp = rk[i - 1];
        if (i % nk == 0) tmp = sub_word(rotl32(tmp, 8)) ^ RCON[i / nk];
        else if (nk > 6 && (i % nk) == 4) tmp = sub_word(tmp);
        rk[i] = rk[i - nk] ^ tmp;
    }
    std::memcpy(counter, iv, 16);
}

void AesCtr::crypt(const uint8_t* in, uint8_t* out, size_t len) {
    uint8_t stream[16];
    size_t off = 0;
    while (off < len) {
        aes_encrypt_block(rk, rounds, counter, stream);
        for (int i = 15; i >= 0; --i) {
            if (++counter[i] != 0) break;
        }
        size_t chunk = std::min<size_t>(16, len - off);
        for (size_t i = 0; i < chunk; ++i) out[off + i] = in[off + i] ^ stream[i];
        off += chunk;
    }
}

static const uint8_t AES_INV_SBOX[256] = {
    0x52,0x09,0x6a,0xd5,0x30,0x36,0xa5,0x38,0xbf,0x40,0xa3,0x9e,0x81,0xf3,0xd7,0xfb,
    0x7c,0xe3,0x39,0x82,0x9b,0x2f,0xff,0x87,0x34,0x8e,0x43,0x44,0xc4,0xde,0xe9,0xcb,
    0x54,0x7b,0x94,0x32,0xa6,0xc2,0x23,0x3d,0xee,0x4c,0x95,0x0b,0x42,0xfa,0xc3,0x4e,
    0x08,0x2e,0xa1,0x66,0x28,0xd9,0x24,0xb2,0x76,0x5b,0xa2,0x49,0x6d,0x8b,0xd1,0x25,
    0x72,0xf8,0xf6,0x64,0x86,0x68,0x98,0x16,0xd4,0xa4,0x5c,0xcc,0x5d,0x65,0xb6,0x92,
    0x6c,0x70,0x48,0x50,0xfd,0xed,0xb9,0xda,0x5e,0x15,0x46,0x57,0xa7,0x8d,0x9d,0x84,
    0x90,0xd8,0xab,0x00,0x8c,0xbc,0xd3,0x0a,0xf7,0xe4,0x58,0x05,0xb8,0xb3,0x45,0x06,
    0xd0,0x2c,0x1e,0x8f,0xca,0x3f,0x0f,0x02,0xc1,0xaf,0xbd,0x03,0x01,0x13,0x8a,0x6b,
    0x3a,0x91,0x11,0x41,0x4f,0x67,0xdc,0xea,0x97,0xf2,0xcf,0xce,0xf0,0xb4,0xe6,0x73,
    0x96,0xac,0x74,0x22,0xe7,0xad,0x35,0x85,0xe2,0xf9,0x37,0xe8,0x1c,0x75,0xdf,0x6e,
    0x47,0xf1,0x1a,0x71,0x1d,0x29,0xc5,0x89,0x6f,0xb7,0x62,0x0e,0xaa,0x18,0xbe,0x1b,
    0xfc,0x56,0x3e,0x4b,0xc6,0xd2,0x79,0x20,0x9a,0xdb,0xc0,0xfe,0x78,0xcd,0x5a,0xf4,
    0x1f,0xdd,0xa8,0x33,0x88,0x07,0xc7,0x31,0xb1,0x12,0x10,0x59,0x27,0x80,0xec,0x5f,
    0x60,0x51,0x7f,0xa9,0x19,0xb5,0x4a,0x0d,0x2d,0xe5,0x7a,0x9f,0x93,0xc9,0x9c,0xef,
    0xa0,0xe0,0x3b,0x4d,0xae,0x2a,0xf5,0xb0,0xc8,0xeb,0xbb,0x3c,0x83,0x53,0x99,0x61,
    0x17,0x2b,0x04,0x7e,0xba,0x77,0xd6,0x26,0xe1,0x69,0x14,0x63,0x55,0x21,0x0c,0x7d
};

static inline uint8_t gf_mul(uint8_t a, uint8_t b) {
    uint8_t p = 0;
    for (int i = 0; i < 8; ++i) {
        if (b & 1) p ^= a;
        bool hi = (a & 0x80) != 0;
        a <<= 1;
        if (hi) a ^= 0x1b;
        b >>= 1;
    }
    return p;
}

static void aes_decrypt_block(const uint32_t* rk, int rounds, const uint8_t in[16], uint8_t out[16]) {
    uint8_t s[16];
    std::memcpy(s, in, 16);
    auto add_rk = [&](int r) {
        for (int c = 0; c < 4; ++c) {
            uint32_t w = rk[r * 4 + c];
            s[c * 4 + 0] ^= uint8_t(w >> 24);
            s[c * 4 + 1] ^= uint8_t(w >> 16);
            s[c * 4 + 2] ^= uint8_t(w >> 8);
            s[c * 4 + 3] ^= uint8_t(w);
        }
    };
    add_rk(rounds);
    for (int r = rounds - 1; r >= 0; --r) {
        uint8_t t = s[13]; s[13] = s[9]; s[9] = s[5]; s[5] = s[1]; s[1] = t;
        t = s[2]; s[2] = s[10]; s[10] = t; t = s[6]; s[6] = s[14]; s[14] = t;
        t = s[3]; s[3] = s[7]; s[7] = s[11]; s[11] = s[15]; s[15] = t;
        for (int i = 0; i < 16; ++i) s[i] = AES_INV_SBOX[s[i]];
        add_rk(r);
        if (r > 0) {
            for (int c = 0; c < 4; ++c) {
                uint8_t a0 = s[c*4+0], a1 = s[c*4+1], a2 = s[c*4+2], a3 = s[c*4+3];
                s[c*4+0] = gf_mul(a0,14)^gf_mul(a1,11)^gf_mul(a2,13)^gf_mul(a3,9);
                s[c*4+1] = gf_mul(a0,9)^gf_mul(a1,14)^gf_mul(a2,11)^gf_mul(a3,13);
                s[c*4+2] = gf_mul(a0,13)^gf_mul(a1,9)^gf_mul(a2,14)^gf_mul(a3,11);
                s[c*4+3] = gf_mul(a0,11)^gf_mul(a1,13)^gf_mul(a2,9)^gf_mul(a3,14);
            }
        }
    }
    std::memcpy(out, s, 16);
}

static bool aes_cbc_decrypt(const uint8_t* key, size_t key_len, const uint8_t iv[16],
                            const uint8_t* in, size_t len, uint8_t* out) {
    if (len == 0 || (len % 16) != 0) return false;
    AesCtr aes;
    aes.init(key, key_len, iv);
    uint8_t prev[16];
    std::memcpy(prev, iv, 16);
    for (size_t off = 0; off < len; off += 16) {
        uint8_t dec[16];
        aes_decrypt_block(aes.rk, aes.rounds, in + off, dec);
        for (int i = 0; i < 16; ++i) out[off + i] = dec[i] ^ prev[i];
        std::memcpy(prev, in + off, 16);
    }
    return true;
}

// -----------------------------------------------------------------------------
// MD5 (for legacy encrypted PEM EVP_BytesToKey)
// -----------------------------------------------------------------------------
static void md5(const uint8_t* data, size_t len, uint8_t out[16]) {
    static const uint32_t K[64] = {
        0xd76aa478,0xe8c7b756,0x242070db,0xc1bdceee,0xf57c0faf,0x4787c62a,0xa8304613,0xfd469501,
        0x698098d8,0x8b44f7af,0xffff5bb1,0x895cd7be,0x6b901122,0xfd987193,0xa679438e,0x49b40821,
        0xf61e2562,0xc040b340,0x265e5a51,0xe9b6c7aa,0xd62f105d,0x02441453,0xd8a1e681,0xe7d3fbc8,
        0x21e1cde6,0xc33707d6,0xf4d50d87,0x455a14ed,0xa9e3e905,0xfcefa3f8,0x676f02d9,0x8d2a4c8a,
        0xfffa3942,0x8771f681,0x6d9d6122,0xfde5380c,0xa4beea44,0x4bdecfa9,0xf6bb4b60,0xbebfbc70,
        0x289b7ec6,0xeaa127fa,0xd4ef3085,0x04881d05,0xd9d4d039,0xe6db99e5,0x1fa27cf8,0xc4ac5665,
        0xf4292244,0x432aff97,0xab9423a7,0xfc93a039,0x655b59c3,0x8f0ccc92,0xffeff47d,0x85845dd1,
        0x6fa87e4f,0xfe2ce6e0,0xa3014314,0x4e0811a1,0xf7537e82,0xbd3af235,0x2ad7d2bb,0xeb86d391
    };
    static const int S[64] = {
        7,12,17,22,7,12,17,22,7,12,17,22,7,12,17,22,
        5,9,14,20,5,9,14,20,5,9,14,20,5,9,14,20,
        4,11,16,23,4,11,16,23,4,11,16,23,4,11,16,23,
        6,10,15,21,6,10,15,21,6,10,15,21,6,10,15,21
    };
    uint32_t h[4] = {0x67452301u, 0xefcdab89u, 0x98badcfeu, 0x10325476u};
    auto process = [&](const uint8_t* blk) {
        uint32_t m[16];
        for (int i = 0; i < 16; ++i) m[i] = load32_le(blk + i * 4);
        uint32_t a = h[0], b = h[1], c = h[2], d = h[3];
        for (int i = 0; i < 64; ++i) {
            uint32_t f, g;
            if (i < 16) { f = (b & c) | ((~b) & d); g = uint32_t(i); }
            else if (i < 32) { f = (d & b) | ((~d) & c); g = uint32_t((5 * i + 1) % 16); }
            else if (i < 48) { f = b ^ c ^ d; g = uint32_t((3 * i + 5) % 16); }
            else { f = c ^ (b | (~d)); g = uint32_t((7 * i) % 16); }
            uint32_t tmp = d;
            d = c; c = b;
            b = b + rotl32(a + f + K[i] + m[g], S[i]);
            a = tmp;
        }
        h[0] += a; h[1] += b; h[2] += c; h[3] += d;
    };
    uint8_t buf[64]{};
    size_t i = 0;
    while (i + 64 <= len) { process(data + i); i += 64; }
    size_t rem = len - i;
    if (rem > 0) std::memcpy(buf, data + i, rem);
    buf[rem++] = 0x80;
    if (rem > 56) { std::memset(buf + rem, 0, 64 - rem); process(buf); rem = 0; }
    std::memset(buf + rem, 0, 56 - rem);
    store64_le(buf + 56, uint64_t(len) * 8);
    process(buf);
    for (int j = 0; j < 4; ++j) store32_le(out + j * 4, h[j]);
}

// -----------------------------------------------------------------------------
// bcrypt_pbkdf (OpenSSH encrypted private key KDF)
// -----------------------------------------------------------------------------
static const uint32_t BF_P_INIT[18] = {
    0x243f6a88u,0x85a308d3u,0x13198a2eu,0x03707344u,0xa4093822u,0x299f31d0u,
    0x082efa98u,0xec4e6c89u,0x452821e6u,0x38d01377u,0xbe5466cfu,0x34e90c6cu,
    0xc0ac29b7u,0xc97c50ddu,0x3f84d5b5u,0xb5470917u,0x9216d5d9u,0x8979fb1bu
};
static const uint32_t BF_S_INIT[4][256] = {
    {
        0xd1310ba6u,0x98dfb5acu,0x2ffd72dbu,0xd01adfb7u,0xb8e1afedu,0x6a267e96u,0xba7c9045u,0xf12c7f99u,
        0x24a19947u,0xb3916cf7u,0x0801f2e2u,0x858efc16u,0x636920d8u,0x71574e69u,0xa458fea3u,0xf4933d7eu,
        0x0d95748fu,0x728eb658u,0x718bcd58u,0x82154aeeu,0x7b54a41du,0xc25a59b5u,0x9c30d539u,0x2af26013u,
        0xc5d1b023u,0x286085f0u,0xca417918u,0xb8db38efu,0x8e79dcb0u,0x603a180eu,0x6c9e0e8bu,0xb01e8a3eu,
        0xd71577c1u,0xbd314b27u,0x78af2fdau,0x55605c60u,0xe65525f3u,0xaa55ab94u,0x57489862u,0x63e81440u,
        0x55ca396au,0x2aab10b6u,0xb4cc5c34u,0x1141e8ceu,0xa15486afu,0x7c72e993u,0xb3ee1411u,0x636fbc2au,
        0x2ba9c55du,0x741831f6u,0xce5c3e16u,0x9b87931eu,0xafd6ba33u,0x6c24cf5cu,0x7a325381u,0x28958677u,
        0x3b8f4898u,0x6b4bb9afu,0xc4bfe81bu,0x66282193u,0x61d809ccu,0xfb21a991u,0x487cac60u,0x5dec8032u,
        0xef845d5du,0xe98575b1u,0xdc262302u,0xeb651b88u,0x23893e81u,0xd396acc5u,0x0f6d6ff3u,0x83f44239u,
        0x2e0b4482u,0xa4842004u,0x69c8f04au,0x9e1f9b5eu,0x21c66842u,0xf6e96c9au,0x670c9c61u,0xabd388f0u,
        0x6a51a0d2u,0xd8542f68u,0x960fa728u,0xab5133a3u,0x6eef0b6cu,0x137a3be4u,0xba3bf050u,0x7efb2a98u,
        0xa1f1651du,0x39af0176u,0x66ca593eu,0x82430e88u,0x8cee8619u,0x456f9fb4u,0x7d84a5c3u,0x3b8b5ebeu,
        0xe06f75d8u,0x85c12073u,0x401a449fu,0x56c16aa6u,0x4ed3aa62u,0x363f7706u,0x1bfedf72u,0x429b023du,
        0x37d0d724u,0xd00a1248u,0xdb0fead3u,0x49f1c09bu,0x075372c9u,0x80991b7bu,0x25d479d8u,0xf6e8def7u,
        0xe3fe501au,0xb6794c3bu,0x976ce0bdu,0x04c006bau,0xc1a94fb6u,0x409f60c4u,0x5e5c9ec2u,0x196a2463u,
        0x68fb6fafu,0x3e6c53b5u,0x1339b2ebu,0x3b52ec6fu,0x6dfc511fu,0x9b30952cu,0xcc814544u,0xaf5ebd09u,
        0xbee3d004u,0xde334afdu,0x660f2807u,0x192e4bb3u,0xc0cba857u,0x45c8740fu,0xd20b5f39u,0xb9d3fbdbu,
        0x5579c0bdu,0x1a60320au,0xd6a100c6u,0x402c7279u,0x679f25feu,0xfb1fa3ccu,0x8ea5e9f8u,0xdb3222f8u,
        0x3c7516dfu,0xfd616b15u,0x2f501ec8u,0xad0552abu,0x323db5fau,0xfd238760u,0x53317b48u,0x3e00df82u,
        0x9e5c57bbu,0xca6f8ca0u,0x1a87562eu,0xdf1769dbu,0xd542a8f6u,0x287effc3u,0xac6732c6u,0x8c4f5573u,
        0x695b27b0u,0xbbca58c8u,0xe1ffa35du,0xb8f011a0u,0x10fa3d98u,0xfd2183b8u,0x4afcb56cu,0x2dd1d35bu,
        0x9a53e479u,0xb6f84565u,0xd28e49bcu,0x4bfb9790u,0xe1ddf2dau,0xa4cb7e33u,0x62fb1341u,0xcee4c6e8u,
        0xef20cadau,0x36774c01u,0xd07e9efeu,0x2bf11fb4u,0x95dbda4du,0xae909198u,0xeaad8e71u,0x6b93d5a0u,
        0xd08ed1d0u,0xafc725e0u,0x8e3c5b2fu,0x8e7594b7u,0x8ff6e2fbu,0xf2122b64u,0x8888b812u,0x900df01cu,
        0x4fad5ea0u,0x688fc31cu,0xd1cff191u,0xb3a8c1adu,0x2f2f2218u,0xbe0e1777u,0xea752dfeu,0x8b021fa1u,
        0xe5a0cc0fu,0xb56f74e8u,0x18acf3d6u,0xce89e299u,0xb4a84fe0u,0xfd13e0b7u,0x7cc43b81u,0xd2ada8d9u,
        0x165fa266u,0x80957705u,0x93cc7314u,0x211a1477u,0xe6ad2065u,0x77b5fa86u,0xc75442f5u,0xfb9d35cfu,
        0xebcdaf0cu,0x7b3e89a0u,0xd6411bd3u,0xae1e7e49u,0x00250e2du,0x2071b35eu,0x226800bbu,0x57b8e0afu,
        0x2464369bu,0xf009b91eu,0x5563911du,0x59dfa6aau,0x78c14389u,0xd95a537fu,0x207d5ba2u,0x02e5b9c5u,
        0x83260376u,0x6295cfa9u,0x11c81968u,0x4e734a41u,0xb3472dcau,0x7b14a94au,0x1b510052u,0x9a532915u,
        0xd60f573fu,0xbc9bc6e4u,0x2b60a476u,0x81e67400u,0x08ba6fb5u,0x571be91fu,0xf296ec6bu,0x2a0dd915u,
        0xb6636521u,0xe7b9f9b6u,0xff34052eu,0xc5855664u,0x53b02d5du,0xa99f8fa1u,0x08ba4799u,0x6e85076au
    },
    {
        0x4b7a70e9u,0xb5b32944u,0xdb75092eu,0xc4192623u,0xad6ea6b0u,0x49a7df7du,0x9cee60b8u,0x8fedb266u,
        0xecaa8c71u,0x699a17ffu,0x5664526cu,0xc2b19ee1u,0x193602a5u,0x75094c29u,0xa0591340u,0xe4183a3eu,
        0x3f54989au,0x5b429d65u,0x6b8fe4d6u,0x99f73fd6u,0xa1d29c07u,0xefe830f5u,0x4d2d38e6u,0xf0255dc1u,
        0x4cdd2086u,0x8470eb26u,0x6382e9c6u,0x021ecc5eu,0x09686b3fu,0x3ebaefc9u,0x3c971814u,0x6b6a70a1u,
        0x687f3584u,0x52a0e286u,0xb79c5305u,0xaa500737u,0x3e07841cu,0x7fdeae5cu,0x8e7d44ecu,0x5716f2b8u,
        0xb03ada37u,0xf0500c0du,0xf01c1f04u,0x0200b3ffu,0xae0cf51au,0x3cb574b2u,0x25837a58u,0xdc0921bdu,
        0xd19113f9u,0x7ca92ff6u,0x94324773u,0x22f54701u,0x3ae5e581u,0x37c2dadcu,0xc8b57634u,0x9af3dda7u,
        0xa9446146u,0x0fd0030eu,0xecc8c73eu,0xa4751e41u,0xe238cd99u,0x3bea0e2fu,0x3280bba1u,0x183eb331u,
        0x4e548b38u,0x4f6db908u,0x6f420d03u,0xf60a04bfu,0x2cb81290u,0x24977c79u,0x5679b072u,0xbcaf89afu,
        0xde9a771fu,0xd9930810u,0xb38bae12u,0xdccf3f2eu,0x5512721fu,0x2e6b7124u,0x501adde6u,0x9f84cd87u,
        0x7a584718u,0x7408da17u,0xbc9f9abcu,0xe94b7d8cu,0xec7aec3au,0xdb851dfau,0x63094366u,0xc464c3d2u,
        0xef1c1847u,0x3215d908u,0xdd433b37u,0x24c2ba16u,0x12a14d43u,0x2a65c451u,0x50940002u,0x133ae4ddu,
        0x71dff89eu,0x10314e55u,0x81ac77d6u,0x5f11199bu,0x043556f1u,0xd7a3c76bu,0x3c11183bu,0x5924a509u,
        0xf28fe6edu,0x97f1fbfau,0x9ebabf2cu,0x1e153c6eu,0x86e34570u,0xeae96fb1u,0x860e5e0au,0x5a3e2ab3u,
        0x771fe71cu,0x4e3d06fau,0x2965dcb9u,0x99e71d0fu,0x803e89d6u,0x5266c825u,0x2e4cc978u,0x9c10b36au,
        0xc6150ebau,0x94e2ea78u,0xa5fc3c53u,0x1e0a2df4u,0xf2f74ea7u,0x361d2b3du,0x1939260fu,0x19c27960u,
        0x5223a708u,0xf71312b6u,0xebadfe6eu,0xeac31f66u,0xe3bc4595u,0xa67bc883u,0xb17f37d1u,0x018cff28u,
        0xc332ddefu,0xbe6c5aa5u,0x65582185u,0x68ab9802u,0xeecea50fu,0xdb2f953bu,0x2aef7dadu,0x5b6e2f84u,
        0x1521b628u,0x29076170u,0xecdd4775u,0x619f1510u,0x13cca830u,0xeb61bd96u,0x0334fe1eu,0xaa0363cfu,
        0xb5735c90u,0x4c70a239u,0xd59e9e0bu,0xcbaade14u,0xeecc86bcu,0x60622ca7u,0x9cab5cabu,0xb2f3846eu,
        0x648b1eafu,0x19bdf0cau,0xa02369b9u,0x655abb50u,0x40685a32u,0x3c2ab4b3u,0x319ee9d5u,0xc021b8f7u,
        0x9b540b19u,0x875fa099u,0x95f7997eu,0x623d7da8u,0xf837889au,0x97e32d77u,0x11ed935fu,0x16681281u,
        0x0e358829u,0xc7e61fd6u,0x96dedfa1u,0x7858ba99u,0x57f584a5u,0x1b227263u,0x9b83c3ffu,0x1ac24696u,
        0xcdb30aebu,0x532e3054u,0x8fd948e4u,0x6dbc3128u,0x58ebf2efu,0x34c6ffeau,0xfe28ed61u,0xee7c3c73u,
        0x5d4a14d9u,0xe864b7e3u,0x42105d14u,0x203e13e0u,0x45eee2b6u,0xa3aaabeau,0xdb6c4f15u,0xfacb4fd0u,
        0xc742f442u,0xef6abbb5u,0x654f3b1du,0x41cd2105u,0xd81e799eu,0x86854dc7u,0xe44b476au,0x3d816250u,
        0xcf62a1f2u,0x5b8d2646u,0xfc8883a0u,0xc1c7b6a3u,0x7f1524c3u,0x69cb7492u,0x47848a0bu,0x5692b285u,
        0x095bbf00u,0xad19489du,0x1462b174u,0x23820e00u,0x58428d2au,0x0c55f5eau,0x1dadf43eu,0x233f7061u,
        0x3372f092u,0x8d937e41u,0xd65fecf1u,0x6c223bdbu,0x7cde3759u,0xcbee7460u,0x4085f2a7u,0xce77326eu,
        0xa6078084u,0x19f8509eu,0xe8efd855u,0x61d99735u,0xa969a7aau,0xc50c06c2u,0x5a04abfcu,0x800bcadcu,
        0x9e447a2eu,0xc3453484u,0xfdd56705u,0x0e1e9ec9u,0xdb73dbd3u,0x105588cdu,0x675fda79u,0xe3674340u,
        0xc5c43465u,0x713e38d8u,0x3d28f89eu,0xf16dff20u,0x153e21e7u,0x8fb03d4au,0xe6e39f2bu,0xdb83adf7u
    },
    {
        0xe93d5a68u,0x948140f7u,0xf64c261cu,0x94692934u,0x411520f7u,0x7602d4f7u,0xbcf46b2eu,0xd4a20068u,
        0xd4082471u,0x3320f46au,0x43b7d4b7u,0x500061afu,0x1e39f62eu,0x97244546u,0x14214f74u,0xbf8b8840u,
        0x4d95fc1du,0x96b591afu,0x70f4ddd3u,0x66a02f45u,0xbfbc09ecu,0x03bd9785u,0x7fac6dd0u,0x31cb8504u,
        0x96eb27b3u,0x55fd3941u,0xda2547e6u,0xabca0a9au,0x28507825u,0x530429f4u,0x0a2c86dau,0xe9b66dfbu,
        0x68dc1462u,0xd7486900u,0x680ec0a4u,0x27a18deeu,0x4f3ffea2u,0xe887ad8cu,0xb58ce006u,0x7af4d6b6u,
        0xaace1e7cu,0xd3375fecu,0xce78a399u,0x406b2a42u,0x20fe9e35u,0xd9f385b9u,0xee39d7abu,0x3b124e8bu,
        0x1dc9faf7u,0x4b6d1856u,0x26a36631u,0xeae397b2u,0x3a6efa74u,0xdd5b4332u,0x6841e7f7u,0xca7820fbu,
        0xfb0af54eu,0xd8feb397u,0x454056acu,0xba489527u,0x55533a3au,0x20838d87u,0xfe6ba9b7u,0xd096954bu,
        0x55a867bcu,0xa1159a58u,0xcca92963u,0x99e1db33u,0xa62a4a56u,0x3f3125f9u,0x5ef47e1cu,0x9029317cu,
        0xfdf8e802u,0x04272f70u,0x80bb155cu,0x05282ce3u,0x95c11548u,0xe4c66d22u,0x48c1133fu,0xc70f86dcu,
        0x07f9c9eeu,0x41041f0fu,0x404779a4u,0x5d886e17u,0x325f51ebu,0xd59bc0d1u,0xf2bcc18fu,0x41113564u,
        0x257b7834u,0x602a9c60u,0xdff8e8a3u,0x1f636c1bu,0x0e12b4c2u,0x02e1329eu,0xaf664fd1u,0xcad18115u,
        0x6b2395e0u,0x333e92e1u,0x3b240b62u,0xeebeb922u,0x85b2a20eu,0xe6ba0d99u,0xde720c8cu,0x2da2f728u,
        0xd0127845u,0x95b794fdu,0x647d0862u,0xe7ccf5f0u,0x5449a36fu,0x877d48fau,0xc39dfd27u,0xf33e8d1eu,
        0x0a476341u,0x992eff74u,0x3a6f6eabu,0xf4f8fd37u,0xa812dc60u,0xa1ebddf8u,0x991be14cu,0xdb6e6b0du,
        0xc67b5510u,0x6d672c37u,0x2765d43bu,0xdcd0e804u,0xf1290dc7u,0xcc00ffa3u,0xb5390f92u,0x690fed0bu,
        0x667b9ffbu,0xcedb7d9cu,0xa091cf0bu,0xd9155ea3u,0xbb132f88u,0x515bad24u,0x7b9479bfu,0x763bd6ebu,
        0x37392eb3u,0xcc115979u,0x8026e297u,0xf42e312du,0x6842ada7u,0xc66a2b3bu,0x12754cccu,0x782ef11cu,
        0x6a124237u,0xb79251e7u,0x06a1bbe6u,0x4bfb6350u,0x1a6b1018u,0x11caedfau,0x3d25bdd8u,0xe2e1c3c9u,
        0x44421659u,0x0a121386u,0xd90cec6eu,0xd5abea2au,0x64af674eu,0xda86a85fu,0xbebfe988u,0x64e4c3feu,
        0x9dbc8057u,0xf0f7c086u,0x60787bf8u,0x6003604du,0xd1fd8346u,0xf6381fb0u,0x7745ae04u,0xd736fcccu,
        0x83426b33u,0xf01eab71u,0xb0804187u,0x3c005e5fu,0x77a057beu,0xbde8ae24u,0x55464299u,0xbf582e61u,
        0x4e58f48fu,0xf2ddfda2u,0xf474ef38u,0x8789bdc2u,0x5366f9c3u,0xc8b38e74u,0xb475f255u,0x46fcd9b9u,
        0x7aeb2661u,0x8b1ddf84u,0x846a0e79u,0x915f95e2u,0x466e598eu,0x20b45770u,0x8cd55591u,0xc902de4cu,
        0xb90bace1u,0xbb8205d0u,0x11a86248u,0x7574a99eu,0xb77f19b6u,0xe0a9dc09u,0x662d09a1u,0xc4324633u,
        0xe85a1f02u,0x09f0be8cu,0x4a99a025u,0x1d6efe10u,0x1ab93d1du,0x0ba5a4dfu,0xa186f20fu,0x2868f169u,
        0xdcb7da83u,0x573906feu,0xa1e2ce9bu,0x4fcd7f52u,0x50115e01u,0xa70683fau,0xa002b5c4u,0x0de6d027u,
        0x9af88c27u,0x773f8641u,0xc3604c06u,0x61a806b5u,0xf0177a28u,0xc0f586e0u,0x006058aau,0x30dc7d62u,
        0x11e69ed7u,0x2338ea63u,0x53c2dd94u,0xc2c21634u,0xbbcbee56u,0x90bcb6deu,0xebfc7da1u,0xce591d76u,
        0x6f05e409u,0x4b7c0188u,0x39720a3du,0x7c927c24u,0x86e3725fu,0x724d9db9u,0x1ac15bb4u,0xd39eb8fcu,
        0xed545578u,0x08fca5b5u,0xd83d7cd3u,0x4dad0fc4u,0x1e50ef5eu,0xb161e6f8u,0xa28514d9u,0x6c51133cu,
        0x6fd5c7e7u,0x56e14ec4u,0x362abfceu,0xddc6c837u,0xd79a3234u,0x92638212u,0x670efa8eu,0x406000e0u
    },
    {
        0x3a39ce37u,0xd3faf5cfu,0xabc27737u,0x5ac52d1bu,0x5cb0679eu,0x4fa33742u,0xd3822740u,0x99bc9bbeu,
        0xd5118e9du,0xbf0f7315u,0xd62d1c7eu,0xc700c47bu,0xb78c1b6bu,0x21a19045u,0xb26eb1beu,0x6a366eb4u,
        0x5748ab2fu,0xbc946e79u,0xc6a376d2u,0x6549c2c8u,0x530ff8eeu,0x468dde7du,0xd5730a1du,0x4cd04dc6u,
        0x2939bbdbu,0xa9ba4650u,0xac9526e8u,0xbe5ee304u,0xa1fad5f0u,0x6a2d519au,0x63ef8ce2u,0x9a86ee22u,
        0xc089c2b8u,0x43242ef6u,0xa51e03aau,0x9cf2d0a4u,0x83c061bau,0x9be96a4du,0x8fe51550u,0xba645bd6u,
        0x2826a2f9u,0xa73a3ae1u,0x4ba99586u,0xef5562e9u,0xc72fefd3u,0xf752f7dau,0x3f046f69u,0x77fa0a59u,
        0x80e4a915u,0x87b08601u,0x9b09e6adu,0x3b3ee593u,0xe990fd5au,0x9e34d797u,0x2cf0b7d9u,0x022b8b51u,
        0x96d5ac3au,0x017da67du,0xd1cf3ed6u,0x7c7d2d28u,0x1f9f25cfu,0xadf2b89bu,0x5ad6b472u,0x5a88f54cu,
        0xe029ac71u,0xe019a5e6u,0x47b0acfdu,0xed93fa9bu,0xe8d3c48du,0x283b57ccu,0xf8d56629u,0x79132e28u,
        0x785f0191u,0xed756055u,0xf7960e44u,0xe3d35e8cu,0x15056dd4u,0x88f46dbau,0x03a16125u,0x0564f0bdu,
        0xc3eb9e15u,0x3c9057a2u,0x97271aecu,0xa93a072au,0x1b3f6d9bu,0x1e6321f5u,0xf59c66fbu,0x26dcf319u,
        0x7533d928u,0xb155fdf5u,0x03563482u,0x8aba3cbbu,0x28517711u,0xc20ad9f8u,0xabcc5167u,0xccad925fu,
        0x4de81751u,0x3830dc8eu,0x379d5862u,0x9320f991u,0xea7a90c2u,0xfb3e7bceu,0x5121ce64u,0x774fbe32u,
        0xa8b6e37eu,0xc3293d46u,0x48de5369u,0x6413e680u,0xa2ae0810u,0xdd6db224u,0x69852dfdu,0x09072166u,
        0xb39a460au,0x6445c0ddu,0x586cdecfu,0x1c20c8aeu,0x5bbef7ddu,0x1b588d40u,0xccd2017fu,0x6bb4e3bbu,
        0xdda26a7eu,0x3a59ff45u,0x3e350a44u,0xbcb4cdd5u,0x72eacea8u,0xfa6484bbu,0x8d6612aeu,0xbf3c6f47u,
        0xd29be463u,0x542f5d9eu,0xaec2771bu,0xf64e6370u,0x740e0d8du,0xe75b1357u,0xf8721671u,0xaf537d5du,
        0x4040cb08u,0x4eb4e2ccu,0x34d2466au,0x0115af84u,0xe1b00428u,0x95983a1du,0x06b89fb4u,0xce6ea048u,
        0x6f3f3b82u,0x3520ab82u,0x011a1d4bu,0x277227f8u,0x611560b1u,0xe7933fdcu,0xbb3a792bu,0x344525bdu,
        0xa08839e1u,0x51ce794bu,0x2f32c9b7u,0xa01fbac9u,0xe01cc87eu,0xbcc7d1f6u,0xcf0111c3u,0xa1e8aac7u,
        0x1a908749u,0xd44fbd9au,0xd0dadecbu,0xd50ada38u,0x0339c32au,0xc6913667u,0x8df9317cu,0xe0b12b4fu,
        0xf79e59b7u,0x43f5bb3au,0xf2d519ffu,0x27d9459cu,0xbf97222cu,0x15e6fc2au,0x0f91fc71u,0x9b941525u,
        0xfae59361u,0xceb69cebu,0xc2a86459u,0x12baa8d1u,0xb6c1075eu,0xe3056a0cu,0x10d25065u,0xcb03a442u,
        0xe0ec6e0eu,0x1698db3bu,0x4c98a0beu,0x3278e964u,0x9f1f9532u,0xe0d392dfu,0xd3a0342bu,0x8971f21eu,
        0x1b0a7441u,0x4ba3348cu,0xc5be7120u,0xc37632d8u,0xdf359f8du,0x9b992f2eu,0xe60b6f47u,0x0fe3f11du,
        0xe54cda54u,0x1edad891u,0xce6279cfu,0xcd3e7e6fu,0x1618b166u,0xfd2c1d05u,0x848fd2c5u,0xf6fb2299u,
        0xf523f357u,0xa6327623u,0x93a83531u,0x56cccd02u,0xacf08162u,0x5a75ebb5u,0x6e163697u,0x88d273ccu,
        0xde966292u,0x81b949d0u,0x4c50901bu,0x71c65614u,0xe6c6c7bdu,0x327a140au,0x45e1d006u,0xc3f27b9au,
        0xc9aa53fdu,0x62a80f00u,0xbb25bfe2u,0x35bdd2f6u,0x71126905u,0xb2040222u,0xb6cbcf7cu,0xcd769c2bu,
        0x53113ec0u,0x1640e3d3u,0x38abbd60u,0x2547adf0u,0xba38209cu,0xf746ce76u,0x77afa1c5u,0x20756060u,
        0x85cbfe4eu,0x8ae88dd8u,0x7aaaf9b0u,0x4cf9aa7eu,0x1948c25cu,0x02fb8a8cu,0x01c36ae4u,0xd6ebe1f9u,
        0x90d4f869u,0xa65cdea0u,0x3f09252du,0xc208e69fu,0xb74e6132u,0xce77e25bu,0x578fdfe3u,0x3ac372e6u
    }
};

struct BlowfishState {
    uint32_t P[18];
    uint32_t S[4][256];
};

static inline uint32_t bf_F(const BlowfishState& st, uint32_t x) {
    uint32_t h = st.S[0][x >> 24] + st.S[1][(x >> 16) & 0xff];
    return (h ^ st.S[2][(x >> 8) & 0xff]) + st.S[3][x & 0xff];
}

static void bf_encipher(const BlowfishState& st, uint32_t& xl, uint32_t& xr) {
    uint32_t l = xl, r = xr;
    for (int i = 0; i < 16; i += 2) {
        l ^= st.P[i]; r ^= bf_F(st, l);
        r ^= st.P[i + 1]; l ^= bf_F(st, r);
    }
    xl = r ^ st.P[17];
    xr = l ^ st.P[16];
}

static void bf_expand(BlowfishState& st, const uint8_t* data, size_t dlen, const uint8_t* key, size_t klen) {
    size_t j = 0;
    for (int i = 0; i < 18; ++i) {
        uint32_t w = 0;
        for (int k = 0; k < 4; ++k) { w = (w << 8) | key[j]; j = (j + 1) % klen; }
        st.P[i] ^= w;
    }
    uint32_t l = 0, r = 0;
    j = 0;
    auto next_d = [&]() -> uint32_t {
        uint32_t w = 0;
        for (int k = 0; k < 4; ++k) { w = (w << 8) | data[j]; j = (j + 1) % dlen; }
        return w;
    };
    for (int i = 0; i < 18; i += 2) {
        l ^= next_d(); r ^= next_d();
        bf_encipher(st, l, r);
        st.P[i] = l; st.P[i + 1] = r;
    }
    for (int s = 0; s < 4; ++s) {
        for (int i = 0; i < 256; i += 2) {
            l ^= next_d(); r ^= next_d();
            bf_encipher(st, l, r);
            st.S[s][i] = l; st.S[s][i + 1] = r;
        }
    }
}

static void bf_expand0(BlowfishState& st, const uint8_t* key, size_t klen) {
    size_t j = 0;
    for (int i = 0; i < 18; ++i) {
        uint32_t w = 0;
        for (int k = 0; k < 4; ++k) { w = (w << 8) | key[j]; j = (j + 1) % klen; }
        st.P[i] ^= w;
    }
    uint32_t l = 0, r = 0;
    for (int i = 0; i < 18; i += 2) {
        bf_encipher(st, l, r);
        st.P[i] = l; st.P[i + 1] = r;
    }
    for (int s = 0; s < 4; ++s) {
        for (int i = 0; i < 256; i += 2) {
            bf_encipher(st, l, r);
            st.S[s][i] = l; st.S[s][i + 1] = r;
        }
    }
}

static void bcrypt_hash(const uint8_t pass_sha[64], const uint8_t salt_sha[64], uint8_t out[32]) {
    BlowfishState st;
    std::memcpy(st.P, BF_P_INIT, sizeof(st.P));
    std::memcpy(st.S, BF_S_INIT, sizeof(st.S));
    bf_expand(st, salt_sha, 64, pass_sha, 64);
    for (int i = 0; i < 64; ++i) {
        bf_expand0(st, salt_sha, 64);
        bf_expand0(st, pass_sha, 64);
    }
    uint32_t cdata[8] = {
        0x4f787963u, 0x6f686561u, 0x626c6f77u, 0x66697368u,
        0x7363616cu, 0x61626c65u, 0x6b657973u, 0x31323334u
    };
    // OpenBSD "OxychromaticBlowfishSwatDynamite"
    static const char* BCRYPT_MAGIC_STR = "OxychromaticBlowfishSwatDynamite";
    for (int i = 0; i < 8; ++i) cdata[i] = load32_be(reinterpret_cast<const uint8_t*>(BCRYPT_MAGIC_STR) + i * 4);
    for (int r = 0; r < 64; ++r) {
        for (int i = 0; i < 8; i += 2) bf_encipher(st, cdata[i], cdata[i + 1]);
    }
    for (int i = 0; i < 8; ++i) store32_le(out + i * 4, cdata[i]);
}

static bool bcrypt_pbkdf(const std::string& pass, const uint8_t* salt, size_t salt_len,
                         uint32_t rounds, uint8_t* out_key, size_t key_len) {
    if (rounds < 1 || key_len == 0 || key_len > 1024 || salt_len == 0) return false;
    uint8_t sha2_pass[64];
    sha512(reinterpret_cast<const uint8_t*>(pass.data()), pass.size(), sha2_pass);

    size_t stride = (key_len + 31) / 32;
    size_t amt = (key_len + stride - 1) / stride;
    std::vector<uint8_t> countsalt(salt_len + 4);
    std::memcpy(countsalt.data(), salt, salt_len);

    std::vector<uint8_t> key(stride * 32, 0);
    for (size_t count = 1; key_len > 0; ++count) {
        store32_be(countsalt.data() + salt_len, uint32_t(count));
        uint8_t sha2_salt[64], tmpout[32], out[32];
        sha512(countsalt.data(), countsalt.size(), sha2_salt);
        bcrypt_hash(sha2_pass, sha2_salt, tmpout);
        std::memcpy(out, tmpout, 32);

        for (uint32_t r = 1; r < rounds; ++r) {
            sha512(tmpout, 32, sha2_salt);
            bcrypt_hash(sha2_pass, sha2_salt, tmpout);
            for (int j = 0; j < 32; ++j) out[j] ^= tmpout[j];
        }
        size_t take = std::min(amt, key_len);
        for (size_t i = 0; i < take; ++i) {
            key[i * stride + (count - 1)] = out[i];
        }
        key_len -= take;
    }
    std::memcpy(out_key, key.data(), std::min(key.size(), size_t(stride * amt)));
    return true;
}

// -----------------------------------------------------------------------------
// ChaCha20 & Poly1305 (OpenSSH chacha20-poly1305@openssh.com)
// -----------------------------------------------------------------------------
static void chacha20_block(const uint8_t key[32], uint64_t seq_be, uint64_t counter, uint8_t out[64]) {
    uint32_t s[16];
    s[0] = 0x61707865u; s[1] = 0x3320646eu; s[2] = 0x79622d32u; s[3] = 0x6b206574u;
    for (int i = 0; i < 8; ++i) s[4 + i] = load32_le(key + i * 4);
    uint8_t ctr_le[8], nonce_be[8];
    store64_le(ctr_le, counter);
    store64_be(nonce_be, seq_be);
    s[12] = load32_le(ctr_le);
    s[13] = load32_le(ctr_le + 4);
    s[14] = load32_le(nonce_be);
    s[15] = load32_le(nonce_be + 4);

    uint32_t x[16];
    std::memcpy(x, s, sizeof(x));
    auto qr = [&](int a, int b, int c, int d) {
        x[a] += x[b]; x[d] = rotl32(x[d] ^ x[a], 16);
        x[c] += x[d]; x[b] = rotl32(x[b] ^ x[c], 12);
        x[a] += x[b]; x[d] = rotl32(x[d] ^ x[a], 8);
        x[c] += x[d]; x[b] = rotl32(x[b] ^ x[c], 7);
    };
    for (int i = 0; i < 10; ++i) {
        qr(0, 4, 8, 12); qr(1, 5, 9, 13); qr(2, 6, 10, 14); qr(3, 7, 11, 15);
        qr(0, 5, 10, 15); qr(1, 6, 11, 12); qr(2, 7, 8, 13); qr(3, 4, 9, 14);
    }
    for (int i = 0; i < 16; ++i) store32_le(out + i * 4, x[i] + s[i]);
}

static void chacha20_xor(const uint8_t key[32], uint64_t seq, uint64_t start_ctr,
                         const uint8_t* in, uint8_t* out, size_t len) {
    uint8_t blk[64];
    size_t off = 0;
    uint64_t ctr = start_ctr;
    while (off < len) {
        chacha20_block(key, seq, ctr++, blk);
        size_t chunk = std::min<size_t>(64, len - off);
        for (size_t i = 0; i < chunk; ++i) out[off + i] = in[off + i] ^ blk[i];
        off += chunk;
    }
}

static void poly1305_mac(const uint8_t key32[32], const uint8_t* msg, size_t len, uint8_t tag[16]) {
    uint32_t r0 = (load32_le(key32 + 0)) & 0x3ffffff;
    uint32_t r1 = (load32_le(key32 + 3) >> 2) & 0x3ffff03;
    uint32_t r2 = (load32_le(key32 + 6) >> 4) & 0x3ffc0ff;
    uint32_t r3 = (load32_le(key32 + 9) >> 6) & 0x3f03fff;
    uint32_t r4 = (load32_le(key32 + 12) >> 8) & 0x00fffff;

    uint32_t s1 = r1 * 5, s2 = r2 * 5, s3 = r3 * 5, s4 = r4 * 5;
    uint32_t h0 = 0, h1 = 0, h2 = 0, h3 = 0, h4 = 0;

    size_t off = 0;
    while (off < len) {
        uint8_t blk[16]{};
        size_t chunk = std::min<size_t>(16, len - off);
        std::memcpy(blk, msg + off, chunk);
        uint32_t hibit = (chunk == 16) ? (1u << 24) : 0;
        if (chunk < 16) blk[chunk] = 1;

        h0 += (load32_le(blk + 0)) & 0x3ffffff;
        h1 += (load32_le(blk + 3) >> 2) & 0x3ffffff;
        h2 += (load32_le(blk + 6) >> 4) & 0x3ffffff;
        h3 += (load32_le(blk + 9) >> 6) & 0x3ffffff;
        h4 += (load32_le(blk + 12) >> 8) | hibit;

        uint64_t d0 = uint64_t(h0)*r0 + uint64_t(h1)*s4 + uint64_t(h2)*s3 + uint64_t(h3)*s2 + uint64_t(h4)*s1;
        uint64_t d1 = uint64_t(h0)*r1 + uint64_t(h1)*r0 + uint64_t(h2)*s4 + uint64_t(h3)*s3 + uint64_t(h4)*s2;
        uint64_t d2 = uint64_t(h0)*r2 + uint64_t(h1)*r1 + uint64_t(h2)*r0 + uint64_t(h3)*s4 + uint64_t(h4)*s3;
        uint64_t d3 = uint64_t(h0)*r3 + uint64_t(h1)*r2 + uint64_t(h2)*r1 + uint64_t(h3)*r0 + uint64_t(h4)*s4;
        uint64_t d4 = uint64_t(h0)*r4 + uint64_t(h1)*r3 + uint64_t(h2)*r2 + uint64_t(h3)*r1 + uint64_t(h4)*r0;

        uint32_t c;
        c = uint32_t(d0 >> 26); h0 = uint32_t(d0) & 0x3ffffff; d1 += c;
        c = uint32_t(d1 >> 26); h1 = uint32_t(d1) & 0x3ffffff; d2 += c;
        c = uint32_t(d2 >> 26); h2 = uint32_t(d2) & 0x3ffffff; d3 += c;
        c = uint32_t(d3 >> 26); h3 = uint32_t(d3) & 0x3ffffff; d4 += c;
        c = uint32_t(d4 >> 26); h4 = uint32_t(d4) & 0x3ffffff; h0 += c * 5;
        c = h0 >> 26; h0 &= 0x3ffffff; h1 += c;

        off += chunk;
    }

    uint32_t c;
    c = h1 >> 26; h1 &= 0x3ffffff; h2 += c;
    c = h2 >> 26; h2 &= 0x3ffffff; h3 += c;
    c = h3 >> 26; h3 &= 0x3ffffff; h4 += c;
    c = h4 >> 26; h4 &= 0x3ffffff; h0 += c * 5;
    c = h0 >> 26; h0 &= 0x3ffffff; h1 += c;

    uint32_t g0 = h0 + 5; c = g0 >> 26; g0 &= 0x3ffffff;
    uint32_t g1 = h1 + c; c = g1 >> 26; g1 &= 0x3ffffff;
    uint32_t g2 = h2 + c; c = g2 >> 26; g2 &= 0x3ffffff;
    uint32_t g3 = h3 + c; c = g3 >> 26; g3 &= 0x3ffffff;
    uint32_t g4 = h4 + c - (1u << 26);

    uint32_t mask = (g4 >> 31) - 1;
    g0 &= mask; g1 &= mask; g2 &= mask; g3 &= mask; g4 &= mask;
    mask = ~mask;
    h0 = (h0 & mask) | g0; h1 = (h1 & mask) | g1; h2 = (h2 & mask) | g2;
    h3 = (h3 & mask) | g3; h4 = (h4 & mask) | g4;

    uint64_t f0 = ((h0) | (h1 << 26)) + uint64_t(load32_le(key32 + 16));
    uint64_t f1 = ((h1 >> 6) | (h2 << 20)) + uint64_t(load32_le(key32 + 20)) + (f0 >> 32);
    uint64_t f2 = ((h2 >> 12) | (h3 << 14)) + uint64_t(load32_le(key32 + 24)) + (f1 >> 32);
    uint64_t f3 = ((h3 >> 18) | (h4 << 8)) + uint64_t(load32_le(key32 + 28)) + (f2 >> 32);

    store32_le(tag + 0, uint32_t(f0));
    store32_le(tag + 4, uint32_t(f1));
    store32_le(tag + 8, uint32_t(f2));
    store32_le(tag + 12, uint32_t(f3));
}

void ChaCha20Poly1305OpenSsh::init(const uint8_t key64[64]) {
    std::memcpy(k2, key64, 32);
    std::memcpy(k1, key64 + 32, 32);
}

void ChaCha20Poly1305OpenSsh::encrypt_packet(uint64_t seq, const uint8_t* pkt_len_be4,
                                             const uint8_t* payload, size_t payload_len,
                                             uint8_t* out_enc_len4, uint8_t* out_enc_payload,
                                             uint8_t out_tag16[16]) {
    uint8_t otk_blk[64];
    chacha20_block(k2, seq, 0, otk_blk);
    chacha20_xor(k1, seq, 0, pkt_len_be4, out_enc_len4, 4);
    chacha20_xor(k2, seq, 1, payload, out_enc_payload, payload_len);

    std::vector<uint8_t> mac_input(4 + payload_len);
    std::memcpy(mac_input.data(), out_enc_len4, 4);
    if (payload_len > 0) std::memcpy(mac_input.data() + 4, out_enc_payload, payload_len);
    poly1305_mac(otk_blk, mac_input.data(), mac_input.size(), out_tag16);
}

void ChaCha20Poly1305OpenSsh::decrypt_length(uint64_t seq, const uint8_t enc_len4[4], uint8_t out_len_be4[4]) {
    chacha20_xor(k1, seq, 0, enc_len4, out_len_be4, 4);
}

bool ChaCha20Poly1305OpenSsh::decrypt_payload(uint64_t seq, const uint8_t enc_len4[4],
                                              const uint8_t* enc_payload, size_t payload_len,
                                              const uint8_t tag16[16], uint8_t* out_payload) {
    uint8_t otk_blk[64];
    chacha20_block(k2, seq, 0, otk_blk);

    std::vector<uint8_t> mac_input(4 + payload_len);
    std::memcpy(mac_input.data(), enc_len4, 4);
    if (payload_len > 0) std::memcpy(mac_input.data() + 4, enc_payload, payload_len);
    uint8_t expected_tag[16];
    poly1305_mac(otk_blk, mac_input.data(), mac_input.size(), expected_tag);
    if (!ct_equal(expected_tag, tag16, 16)) return false;

    chacha20_xor(k2, seq, 1, enc_payload, out_payload, payload_len);
    return true;
}

// -----------------------------------------------------------------------------
// BigInt for RSA signing (rsa-sha2-256 / rsa-sha2-512 / ssh-rsa)
// -----------------------------------------------------------------------------
struct BigInt {
    std::vector<uint32_t> d; // little-endian base 2^32

    static BigInt from_be(const uint8_t* bytes, size_t len) {
        BigInt r;
        size_t i = len;
        while (i > 0) {
            size_t take = std::min<size_t>(4, i);
            uint32_t v = 0;
            for (size_t j = 0; j < take; ++j) v = (v << 8) | bytes[i - take + j];
            r.d.push_back(v);
            i -= take;
        }
        r.trim();
        return r;
    }

    std::vector<uint8_t> to_be(size_t exact_len) const {
        std::vector<uint8_t> out(exact_len, 0);
        for (size_t i = 0; i < d.size(); ++i) {
            for (int b = 0; b < 4; ++b) {
                size_t pos = i * 4 + b;
                if (pos < exact_len) {
                    out[exact_len - 1 - pos] = uint8_t((d[i] >> (b * 8)) & 0xff);
                }
            }
        }
        return out;
    }

    void trim() {
        while (!d.empty() && d.back() == 0) d.pop_back();
    }

    int cmp(const BigInt& o) const {
        if (d.size() != o.d.size()) return d.size() < o.d.size() ? -1 : 1;
        for (size_t i = d.size(); i-- > 0;) {
            if (d[i] != o.d[i]) return d[i] < o.d[i] ? -1 : 1;
        }
        return 0;
    }

    static BigInt sub(const BigInt& a, const BigInt& b) {
        BigInt r = a;
        uint64_t borrow = 0;
        for (size_t i = 0; i < r.d.size(); ++i) {
            uint64_t bi = (i < b.d.size()) ? b.d[i] : 0;
            uint64_t diff = uint64_t(r.d[i]) - bi - borrow;
            r.d[i] = uint32_t(diff);
            borrow = (diff >> 63) & 1;
        }
        r.trim();
        return r;
    }

    static BigInt mul_mod(const BigInt& a, const BigInt& b, const BigInt& mod) {
        BigInt prod;
        prod.d.assign(a.d.size() + b.d.size(), 0);
        for (size_t i = 0; i < a.d.size(); ++i) {
            uint64_t carry = 0;
            for (size_t j = 0; j < b.d.size(); ++j) {
                uint64_t cur = prod.d[i + j] + uint64_t(a.d[i]) * b.d[j] + carry;
                prod.d[i + j] = uint32_t(cur);
                carry = cur >> 32;
            }
            prod.d[i + b.d.size()] += uint32_t(carry);
        }
        prod.trim();
        // Bitwise modulo reduction
        BigInt rem;
        for (size_t i = prod.d.size(); i-- > 0;) {
            for (int bit = 31; bit >= 0; --bit) {
                uint32_t carry = (prod.d[i] >> bit) & 1;
                for (size_t k = 0; k < rem.d.size(); ++k) {
                    uint32_t next_c = rem.d[k] >> 31;
                    rem.d[k] = (rem.d[k] << 1) | carry;
                    carry = next_c;
                }
                if (carry) rem.d.push_back(carry);
                rem.trim();
                if (rem.cmp(mod) >= 0) rem = sub(rem, mod);
            }
        }
        return rem;
    }

    static BigInt pow_mod(const BigInt& base, const BigInt& exp, const BigInt& mod) {
        BigInt res;
        res.d.push_back(1);
        BigInt b = mul_mod(base, res, mod);
        for (size_t i = 0; i < exp.d.size(); ++i) {
            for (int bit = 0; bit < 32; ++bit) {
                if ((exp.d[i] >> bit) & 1) res = mul_mod(res, b, mod);
                bool has_more = (i + 1 < exp.d.size()) || (exp.d[i] >> (bit + 1)) != 0;
                if (has_more) b = mul_mod(b, b, mod);
                else break;
            }
        }
        return res;
    }
};

// -----------------------------------------------------------------------------
// Private key parsing & signing
// -----------------------------------------------------------------------------
static void write_u32(std::vector<uint8_t>& out, uint32_t v) {
    uint8_t b[4];
    store32_be(b, v);
    out.insert(out.end(), b, b + 4);
}
static void write_str(std::vector<uint8_t>& out, const uint8_t* p, size_t len) {
    write_u32(out, uint32_t(len));
    out.insert(out.end(), p, p + len);
}
static void write_str(std::vector<uint8_t>& out, const std::string& s) {
    write_str(out, reinterpret_cast<const uint8_t*>(s.data()), s.size());
}
static void write_mpint(std::vector<uint8_t>& out, const std::vector<uint8_t>& be) {
    size_t start = 0;
    while (start < be.size() && be[start] == 0) ++start;
    if (start == be.size()) {
        write_u32(out, 0);
        return;
    }
    bool need_pad = (be[start] & 0x80) != 0;
    write_u32(out, uint32_t(be.size() - start + (need_pad ? 1 : 0)));
    if (need_pad) out.push_back(0);
    out.insert(out.end(), be.begin() + ptrdiff_t(start), be.end());
}

std::vector<uint8_t> PrivateKey::public_key_blob() const {
    std::vector<uint8_t> out;
    if (type == Type::Ed25519) {
        write_str(out, "ssh-ed25519");
        write_str(out, ed_pk, 32);
    } else if (type == Type::Rsa) {
        write_str(out, "ssh-rsa");
        write_mpint(out, rsa_e);
        write_mpint(out, rsa_n);
    }
    return out;
}

std::string PrivateKey::algorithm_name() const {
    if (type == Type::Ed25519) return "ssh-ed25519";
    if (type == Type::Rsa) return "rsa-sha2-256";
    return "";
}

bool PrivateKey::sign(const uint8_t* data, size_t len, const std::string& alg, std::vector<uint8_t>& out_sig_blob) const {
    out_sig_blob.clear();
    if (type == Type::Ed25519) {
        uint8_t sig[64];
        ed25519_sign(sig, data, len, ed_sk64);
        write_str(out_sig_blob, "ssh-ed25519");
        write_str(out_sig_blob, sig, 64);
        return true;
    }
    if (type == Type::Rsa) {
        if (rsa_n.empty() || rsa_d.empty()) return false;
        size_t k = rsa_n.size();
        while (k > 0 && rsa_n[rsa_n.size() - k] == 0) --k;
        if (k < 64) return false;

        std::vector<uint8_t> digest_info;
        if (alg == "rsa-sha2-256") {
            static const uint8_t prefix[] = {0x30,0x31,0x30,0x0d,0x06,0x09,0x60,0x86,0x48,0x01,0x65,0x03,0x04,0x02,0x01,0x05,0x00,0x04,0x20};
            uint8_t h[32];
            sha256(data, len, h);
            digest_info.assign(prefix, prefix + sizeof(prefix));
            digest_info.insert(digest_info.end(), h, h + 32);
        } else if (alg == "rsa-sha2-512") {
            static const uint8_t prefix[] = {0x30,0x51,0x30,0x0d,0x06,0x09,0x60,0x86,0x48,0x01,0x65,0x03,0x04,0x02,0x03,0x05,0x00,0x04,0x40};
            uint8_t h[64];
            sha512(data, len, h);
            digest_info.assign(prefix, prefix + sizeof(prefix));
            digest_info.insert(digest_info.end(), h, h + 64);
        } else {
            static const uint8_t prefix[] = {0x30,0x21,0x30,0x09,0x06,0x05,0x2b,0x0e,0x03,0x02,0x1a,0x05,0x00,0x04,0x14};
            uint8_t h[20];
            sha1(data, len, h);
            digest_info.assign(prefix, prefix + sizeof(prefix));
            digest_info.insert(digest_info.end(), h, h + 20);
        }

        if (k < digest_info.size() + 11) return false;
        std::vector<uint8_t> em(k, 0xff);
        em[0] = 0x00;
        em[1] = 0x01;
        size_t ps_len = k - digest_info.size() - 3;
        em[2 + ps_len] = 0x00;
        std::memcpy(em.data() + 3 + ps_len, digest_info.data(), digest_info.size());

        BigInt m = BigInt::from_be(em.data(), em.size());
        BigInt d = BigInt::from_be(rsa_d.data(), rsa_d.size());
        BigInt n = BigInt::from_be(rsa_n.data(), rsa_n.size());
        BigInt s = BigInt::pow_mod(m, d, n);
        std::vector<uint8_t> sig_bytes = s.to_be(k);

        write_str(out_sig_blob, alg);
        write_str(out_sig_blob, sig_bytes.data(), sig_bytes.size());
        return true;
    }
    return false;
}

static bool base64_decode(const std::string& in, std::vector<uint8_t>& out) {
    static const int T[256] = {
        -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
        -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
        -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,62,-1,-1,-1,63,
        52,53,54,55,56,57,58,59,60,61,-1,-1,-1,-2,-1,-1,
        -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9,10,11,12,13,14,
        15,16,17,18,19,20,21,22,23,24,25,-1,-1,-1,-1,-1,
        -1,26,27,28,29,30,31,32,33,34,35,36,37,38,39,40,
        41,42,43,44,45,46,47,48,49,50,51,-1,-1,-1,-1,-1,
        -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
        -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
        -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
        -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
        -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
        -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
        -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
        -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1
    };
    out.clear();
    uint32_t val = 0;
    int bits = -8;
    for (unsigned char c : in) {
        if (c == ' ' || c == '\r' || c == '\n' || c == '\t') continue;
        if (c == '=') break;
        int d = T[c];
        if (d < 0) return false;
        val = (val << 6) + uint32_t(d);
        bits += 6;
        if (bits >= 0) {
            out.push_back(uint8_t((val >> bits) & 0xff));
            bits -= 8;
        }
    }
    return true;
}

struct WireReader {
    const uint8_t* p;
    size_t len;
    size_t off = 0;

    bool read_u32(uint32_t& v) {
        if (off + 4 > len) return false;
        v = load32_be(p + off);
        off += 4;
        return true;
    }
    bool read_bytes(std::vector<uint8_t>& out) {
        uint32_t n = 0;
        if (!read_u32(n) || off + n > len) return false;
        out.assign(p + off, p + off + n);
        off += n;
        return true;
    }
    bool read_str(std::string& out) {
        uint32_t n = 0;
        if (!read_u32(n) || off + n > len) return false;
        out.assign(reinterpret_cast<const char*>(p + off), n);
        off += n;
        return true;
    }
};

static bool asn1_read_tlv(const uint8_t* buf, size_t len, size_t& off, uint8_t& tag, const uint8_t*& val, size_t& vlen) {
    if (off >= len) return false;
    tag = buf[off++];
    if (off >= len) return false;
    uint8_t l = buf[off++];
    if ((l & 0x80) == 0) {
        vlen = l;
    } else {
        int n = l & 0x7f;
        if (n == 0 || n > 4 || off + n > len) return false;
        vlen = 0;
        for (int i = 0; i < n; ++i) vlen = (vlen << 8) | buf[off++];
    }
    if (off + vlen > len) return false;
    val = buf + off;
    off += vlen;
    return true;
}

bool parse_private_key(const std::string& pem_text, const std::string& passphrase, PrivateKey& out_key, std::string& out_error) {
    out_key = PrivateKey{};
    auto extract_block = [&](const std::string& begin_marker, const std::string& end_marker, std::vector<uint8_t>& der) -> bool {
        size_t b = pem_text.find(begin_marker);
        if (b == std::string::npos) return false;
        b += begin_marker.size();
        size_t e = pem_text.find(end_marker, b);
        if (e == std::string::npos) return false;
        return base64_decode(pem_text.substr(b, e - b), der);
    };

    std::vector<uint8_t> raw;
    if (extract_block("-----BEGIN OPENSSH PRIVATE KEY-----", "-----END OPENSSH PRIVATE KEY-----", raw)) {
        static const char MAGIC[] = "openssh-key-v1\0";
        if (raw.size() < 15 || std::memcmp(raw.data(), MAGIC, 15) != 0) {
            out_error = "Invalid OpenSSH private key header";
            return false;
        }
        WireReader r{raw.data(), raw.size(), 15};
        std::string cipher, kdf, kdf_opts;
        uint32_t nkeys = 0;
        std::vector<uint8_t> pub_blob, priv_blob;
        if (!r.read_str(cipher) || !r.read_str(kdf) || !r.read_str(kdf_opts) ||
            !r.read_u32(nkeys) || nkeys != 1 || !r.read_bytes(pub_blob) || !r.read_bytes(priv_blob)) {
            out_error = "Malformed OpenSSH private key";
            return false;
        }
        if (cipher != "none" || kdf != "none") {
            if (kdf != "bcrypt") {
                out_error = "Unsupported OpenSSH KDF: " + kdf;
                return false;
            }
            if (passphrase.empty()) {
                out_error = "Private key is encrypted; passphrase required";
                return false;
            }
            WireReader kr{reinterpret_cast<const uint8_t*>(kdf_opts.data()), kdf_opts.size(), 0};
            std::vector<uint8_t> salt;
            uint32_t rounds = 0;
            if (!kr.read_bytes(salt) || !kr.read_u32(rounds) || rounds == 0 || rounds > 4096) {
                out_error = "Malformed bcrypt KDF options in OpenSSH key";
                return false;
            }
            size_t klen = 0, ivlen = 16;
            bool is_ctr = false, is_cbc = false;
            if (cipher == "aes256-ctr") { klen = 32; is_ctr = true; }
            else if (cipher == "aes192-ctr") { klen = 24; is_ctr = true; }
            else if (cipher == "aes128-ctr") { klen = 16; is_ctr = true; }
            else if (cipher == "aes256-cbc") { klen = 32; is_cbc = true; }
            else if (cipher == "aes128-cbc") { klen = 16; is_cbc = true; }
            else {
                out_error = "Unsupported OpenSSH key cipher: " + cipher;
                return false;
            }
            std::vector<uint8_t> derived(klen + ivlen);
            if (!bcrypt_pbkdf(passphrase, salt.data(), salt.size(), rounds, derived.data(), derived.size())) {
                out_error = "Failed deriving key with bcrypt_pbkdf";
                return false;
            }
            std::vector<uint8_t> dec(priv_blob.size());
            if (is_ctr) {
                AesCtr aes;
                aes.init(derived.data(), klen, derived.data() + klen);
                aes.crypt(priv_blob.data(), dec.data(), priv_blob.size());
            } else if (is_cbc) {
                if (!aes_cbc_decrypt(derived.data(), klen, derived.data() + klen,
                                     priv_blob.data(), priv_blob.size(), dec.data())) {
                    out_error = "Invalid encrypted OpenSSH key length";
                    return false;
                }
            }
            priv_blob = std::move(dec);
        }
        WireReader pr{priv_blob.data(), priv_blob.size(), 0};
        uint32_t c1 = 0, c2 = 0;
        std::string key_type;
        if (!pr.read_u32(c1) || !pr.read_u32(c2) || c1 != c2 || !pr.read_str(key_type)) {
            out_error = "Invalid passphrase or corrupted OpenSSH private key";
            return false;
        }
        if (key_type == "ssh-ed25519") {
            std::vector<uint8_t> pk, sk;
            if (!pr.read_bytes(pk) || !pr.read_bytes(sk) || pk.size() != 32 || sk.size() != 64) {
                out_error = "Invalid Ed25519 key in OpenSSH format";
                return false;
            }
            out_key.type = PrivateKey::Type::Ed25519;
            std::memcpy(out_key.ed_pk, pk.data(), 32);
            std::memcpy(out_key.ed_sk64, sk.data(), 64);
            return true;
        }
        if (key_type == "ssh-rsa") {
            std::vector<uint8_t> n, e, d, iqmp, p, q;
            if (!pr.read_bytes(n) || !pr.read_bytes(e) || !pr.read_bytes(d)) {
                out_error = "Invalid RSA key in OpenSSH format";
                return false;
            }
            out_key.type = PrivateKey::Type::Rsa;
            out_key.rsa_n = std::move(n);
            out_key.rsa_e = std::move(e);
            out_key.rsa_d = std::move(d);
            return true;
        }
        out_error = "Unsupported OpenSSH key type: " + key_type;
        return false;
    }

    if (extract_block("-----BEGIN RSA PRIVATE KEY-----", "-----END RSA PRIVATE KEY-----", raw)) {
        if (pem_text.find("Proc-Type: 4,ENCRYPTED") != std::string::npos) {
            if (passphrase.empty()) {
                out_error = "Encrypted PEM RSA key requires passphrase";
                return false;
            }
            size_t dek_pos = pem_text.find("DEK-Info:");
            if (dek_pos == std::string::npos) {
                out_error = "Missing DEK-Info in encrypted PEM";
                return false;
            }
            size_t line_end = pem_text.find('\n', dek_pos);
            std::string dek = pem_text.substr(dek_pos + 9, line_end == std::string::npos ? std::string::npos : line_end - (dek_pos + 9));
            while (!dek.empty() && (dek.front() == ' ' || dek.front() == '\t')) dek.erase(dek.begin());
            while (!dek.empty() && (dek.back() == '\r' || dek.back() == ' ' || dek.back() == '\t')) dek.pop_back();
            size_t comma = dek.find(',');
            if (comma == std::string::npos) {
                out_error = "Malformed DEK-Info in encrypted PEM";
                return false;
            }
            std::string alg_name = dek.substr(0, comma);
            std::string iv_hex = dek.substr(comma + 1);
            size_t klen = 0;
            if (alg_name == "AES-128-CBC") klen = 16;
            else if (alg_name == "AES-256-CBC") klen = 32;
            else {
                out_error = "Unsupported PEM encryption: " + alg_name;
                return false;
            }
            if (iv_hex.size() != 32) {
                out_error = "Invalid IV in PEM DEK-Info";
                return false;
            }
            uint8_t iv[16];
            for (int i = 0; i < 16; ++i) {
                auto hexval = [](char c) -> int {
                    if (c >= '0' && c <= '9') return c - '0';
                    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
                    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
                    return -1;
                };
                int hi = hexval(iv_hex[i * 2]), lo = hexval(iv_hex[i * 2 + 1]);
                if (hi < 0 || lo < 0) { out_error = "Invalid hex IV in PEM"; return false; }
                iv[i] = uint8_t((hi << 4) | lo);
            }
            // OpenSSL EVP_BytesToKey(MD5, salt=iv[0..7], pass, count=1)
            uint8_t key[32]{};
            uint8_t d[16]{};
            size_t d_len = 0, koff = 0;
            while (koff < klen) {
                std::vector<uint8_t> in;
                if (d_len > 0) in.insert(in.end(), d, d + d_len);
                in.insert(in.end(), passphrase.begin(), passphrase.end());
                in.insert(in.end(), iv, iv + 8);
                md5(in.data(), in.size(), d);
                d_len = 16;
                size_t take = std::min<size_t>(16, klen - koff);
                std::memcpy(key + koff, d, take);
                koff += take;
            }
            std::vector<uint8_t> dec(raw.size());
            if (!aes_cbc_decrypt(key, klen, iv, raw.data(), raw.size(), dec.data())) {
                out_error = "Invalid encrypted PEM block length";
                return false;
            }
            uint8_t pad = dec.back();
            if (pad == 0 || pad > 16 || pad > dec.size()) {
                out_error = "Invalid passphrase or corrupted encrypted PEM";
                return false;
            }
            for (size_t i = 0; i < pad; ++i) {
                if (dec[dec.size() - 1 - i] != pad) {
                    out_error = "Invalid passphrase or corrupted encrypted PEM";
                    return false;
                }
            }
            dec.resize(dec.size() - pad);
            raw = std::move(dec);
        }
        size_t off = 0, vlen = 0;
        uint8_t tag = 0;
        const uint8_t* seq = nullptr;
        if (!asn1_read_tlv(raw.data(), raw.size(), off, tag, seq, vlen) || tag != 0x30) {
            out_error = "Invalid RSA PEM ASN.1";
            return false;
        }
        size_t soff = 0;
        auto read_int = [&](std::vector<uint8_t>& out) -> bool {
            uint8_t t = 0; const uint8_t* v = nullptr; size_t l = 0;
            if (!asn1_read_tlv(seq, vlen, soff, t, v, l) || t != 0x02) return false;
            out.assign(v, v + l);
            return true;
        };
        std::vector<uint8_t> ver;
        if (!read_int(ver) || !read_int(out_key.rsa_n) || !read_int(out_key.rsa_e) || !read_int(out_key.rsa_d)) {
            out_error = "Invalid RSA PKCS#1 fields";
            return false;
        }
        out_key.type = PrivateKey::Type::Rsa;
        return true;
    }

    if (extract_block("-----BEGIN PRIVATE KEY-----", "-----END PRIVATE KEY-----", raw)) {
        // PKCS#8 unencrypted: SEQUENCE { version, algId, OCTET STRING key }
        size_t off = 0, vlen = 0;
        uint8_t tag = 0;
        const uint8_t* seq = nullptr;
        if (!asn1_read_tlv(raw.data(), raw.size(), off, tag, seq, vlen) || tag != 0x30) {
            out_error = "Invalid PKCS#8 ASN.1";
            return false;
        }
        size_t soff = 0;
        const uint8_t* v = nullptr; size_t l = 0; uint8_t t = 0;
        if (!asn1_read_tlv(seq, vlen, soff, t, v, l) || t != 0x02) { out_error = "Invalid PKCS#8 version"; return false; }
        if (!asn1_read_tlv(seq, vlen, soff, t, v, l) || t != 0x30) { out_error = "Invalid PKCS#8 algId"; return false; }
        const uint8_t* alg_seq = v; size_t alg_len = l; size_t aoff = 0;
        const uint8_t* oid = nullptr; size_t oid_len = 0;
        if (!asn1_read_tlv(alg_seq, alg_len, aoff, t, oid, oid_len) || t != 0x06) { out_error = "Invalid PKCS#8 OID"; return false; }
        if (!asn1_read_tlv(seq, vlen, soff, t, v, l) || t != 0x04) { out_error = "Invalid PKCS#8 key octet"; return false; }

        // Ed25519 OID: 1.3.101.112 -> 2b 65 70
        if (oid_len == 3 && oid[0] == 0x2b && oid[1] == 0x65 && oid[2] == 0x70) {
            size_t koff = 0; const uint8_t* seed = nullptr; size_t seed_len = 0;
            if (!asn1_read_tlv(v, l, koff, t, seed, seed_len) || t != 0x04 || seed_len != 32) {
                out_error = "Invalid PKCS#8 Ed25519 seed";
                return false;
            }
            uint8_t d[64];
            sha512(seed, 32, d);
            d[0] &= 248; d[31] &= 127; d[31] |= 64;
            gf p[4];
            scalarbase_pt(p, d);
            pack_pt(out_key.ed_pk, p);
            std::memcpy(out_key.ed_sk64, seed, 32);
            std::memcpy(out_key.ed_sk64 + 32, out_key.ed_pk, 32);
            out_key.type = PrivateKey::Type::Ed25519;
            return true;
        }
        // RSA OID: 1.2.840.113549.1.1.1 -> 2a 86 48 86 f7 0d 01 01 01
        static const uint8_t RSA_OID[] = {0x2a,0x86,0x48,0x86,0xf7,0x0d,0x01,0x01,0x01};
        if (oid_len == sizeof(RSA_OID) && std::memcmp(oid, RSA_OID, sizeof(RSA_OID)) == 0) {
            size_t roff = 0, rvlen = 0; const uint8_t* rseq = nullptr;
            if (!asn1_read_tlv(v, l, roff, t, rseq, rvlen) || t != 0x30) {
                out_error = "Invalid PKCS#8 RSA inner key";
                return false;
            }
            size_t rsoff = 0;
            auto read_int = [&](std::vector<uint8_t>& out) -> bool {
                uint8_t tt = 0; const uint8_t* vv = nullptr; size_t ll = 0;
                if (!asn1_read_tlv(rseq, rvlen, rsoff, tt, vv, ll) || tt != 0x02) return false;
                out.assign(vv, vv + ll);
                return true;
            };
            std::vector<uint8_t> ver;
            if (!read_int(ver) || !read_int(out_key.rsa_n) || !read_int(out_key.rsa_e) || !read_int(out_key.rsa_d)) {
                out_error = "Invalid PKCS#8 RSA fields";
                return false;
            }
            out_key.type = PrivateKey::Type::Rsa;
            return true;
        }
        out_error = "Unsupported PKCS#8 key algorithm";
        return false;
    }

    out_error = "Unrecognized private key format (expected OpenSSH or PEM)";
    return false;
}

} // namespace crypto
} // namespace svpn
