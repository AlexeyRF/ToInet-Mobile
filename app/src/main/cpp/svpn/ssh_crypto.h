#pragma once

#include <cstdint>
#include <cstddef>
#include <string>
#include <vector>

namespace svpn {
namespace crypto {

// Fill buffer with cryptographically secure random bytes. Returns false on failure.
bool random_bytes(uint8_t* out, size_t len);

// SHA-1 (20 bytes)
void sha1(const uint8_t* data, size_t len, uint8_t out[20]);

// SHA-256 (32 bytes)
void sha256(const uint8_t* data, size_t len, uint8_t out[32]);

// SHA-512 (64 bytes)
void sha512(const uint8_t* data, size_t len, uint8_t out[64]);

// HMAC-SHA1 / HMAC-SHA256 / HMAC-SHA512
void hmac_sha1(const uint8_t* key, size_t key_len, const uint8_t* data, size_t data_len, uint8_t out[20]);
void hmac_sha256(const uint8_t* key, size_t key_len, const uint8_t* data, size_t data_len, uint8_t out[32]);
void hmac_sha512(const uint8_t* key, size_t key_len, const uint8_t* data, size_t data_len, uint8_t out[64]);

// Constant-time memory comparison
bool ct_equal(const uint8_t* a, const uint8_t* b, size_t len);

// X25519 (RFC 7748)
void x25519_public_key(uint8_t pk[32], const uint8_t sk[32]);
bool x25519_shared_secret(uint8_t ss[32], const uint8_t sk[32], const uint8_t pk[32]);

// Ed25519 (RFC 8032)
// sk64 is 32-byte seed || 32-byte public key
void ed25519_sign(uint8_t sig[64], const uint8_t* msg, size_t msg_len, const uint8_t sk64[64]);
bool ed25519_verify(const uint8_t sig[64], const uint8_t* msg, size_t msg_len, const uint8_t pk[32]);

// AES-CTR (128 or 256 bit key)
struct AesCtr {
    uint32_t rk[60]{};
    int rounds = 0;
    uint8_t counter[16]{};

    void init(const uint8_t* key, size_t key_len, const uint8_t iv[16]);
    void crypt(const uint8_t* in, uint8_t* out, size_t len);
};

// OpenSSH chacha20-poly1305@openssh.com
struct ChaCha20Poly1305OpenSsh {
    uint8_t k2[32]{}; // main key (bytes 0..31)
    uint8_t k1[32]{}; // length key (bytes 32..63)

    void init(const uint8_t key64[64]);
    void encrypt_packet(uint64_t seq, const uint8_t* pkt_len_be4, const uint8_t* payload, size_t payload_len,
                        uint8_t* out_enc_len4, uint8_t* out_enc_payload, uint8_t out_tag16[16]);
    void decrypt_length(uint64_t seq, const uint8_t enc_len4[4], uint8_t out_len_be4[4]);
    bool decrypt_payload(uint64_t seq, const uint8_t enc_len4[4], const uint8_t* enc_payload, size_t payload_len,
                         const uint8_t tag16[16], uint8_t* out_payload);
};

// Parsed SSH private key (Ed25519 or RSA)
struct PrivateKey {
    enum class Type { None, Ed25519, Rsa };
    Type type = Type::None;

    // Ed25519: 64-byte secret key (seed32 || pub32), 32-byte pub
    uint8_t ed_sk64[64]{};
    uint8_t ed_pk[32]{};

    // RSA parameters (big-endian unsigned bytes)
    std::vector<uint8_t> rsa_n;
    std::vector<uint8_t> rsa_e;
    std::vector<uint8_t> rsa_d;

    // Returns SSH public key blob (wire format)
    std::vector<uint8_t> public_key_blob() const;
    // Returns algorithm name for SSH_MSG_USERAUTH_REQUEST ("ssh-ed25519" or "rsa-sha2-256")
    std::string algorithm_name() const;
    // Sign data and return SSH signature blob (wire format: string alg || string sig_bytes)
    bool sign(const uint8_t* data, size_t len, const std::string& alg, std::vector<uint8_t>& out_sig_blob) const;
};

// Parse PEM (PKCS#1 RSA, PKCS#8 Ed25519/RSA) or OpenSSH private key (Ed25519/RSA, unencrypted or AES-CTR encrypted)
bool parse_private_key(const std::string& pem_text, const std::string& passphrase, PrivateKey& out_key, std::string& out_error);

} // namespace crypto
} // namespace svpn
