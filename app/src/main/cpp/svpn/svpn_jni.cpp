#include <jni.h>
#include <memory>
#include <string>

#include "ssh_tunnel.h"

namespace {

std::string jstring_to_std(JNIEnv* env, jstring js) {
    if (!js) return "";
    const char* utf = env->GetStringUTFChars(js, nullptr);
    if (!utf) return "";
    std::string out(utf);
    env->ReleaseStringUTFChars(js, utf);
    return out;
}

struct ScopedJniAttach {
    JavaVM* vm = nullptr;
    JNIEnv* env = nullptr;
    bool attached = false;

    explicit ScopedJniAttach(JavaVM* jvm) : vm(jvm) {
        if (!vm) return;
        jint rc = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
        if (rc == JNI_EDETACHED) {
            if (vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                attached = true;
            } else {
                env = nullptr;
            }
        } else if (rc != JNI_OK) {
            env = nullptr;
        }
    }
    ~ScopedJniAttach() {
        if (attached && vm) {
            vm->DetachCurrentThread();
        }
    }
};

struct NativeTunnelHolder {
    JavaVM* jvm = nullptr;
    jobject cb_ref = nullptr;
    jmethodID mid_protect = nullptr;
    jmethodID mid_connected = nullptr;
    jmethodID mid_disconnected = nullptr;
    jmethodID mid_error = nullptr;
    svpn::SshTunnelClient client;
};

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_ru_toinet_android_svpn_SshTunnel_nativeCreate(JNIEnv* env, jobject /*thiz*/) {
    auto* holder = new NativeTunnelHolder();
    env->GetJavaVM(&holder->jvm);
    return reinterpret_cast<jlong>(holder);
}

JNIEXPORT void JNICALL
Java_ru_toinet_android_svpn_SshTunnel_nativeDestroy(JNIEnv* env, jobject /*thiz*/, jlong handle) {
    auto* holder = reinterpret_cast<NativeTunnelHolder*>(handle);
    if (!holder) return;
    holder->client.stop();
    if (holder->cb_ref) {
        env->DeleteGlobalRef(holder->cb_ref);
        holder->cb_ref = nullptr;
    }
    delete holder;
}

JNIEXPORT jboolean JNICALL
Java_ru_toinet_android_svpn_SshTunnel_nativeStart(
    JNIEnv* env, jobject /*thiz*/, jlong handle,
    jstring jhost, jstring jresolved_hint, jint port,
    jstring juser, jstring jpassword, jstring jprivate_key, jstring jpassphrase,
    jstring jbind_host, jint local_port, jint timeout_ms, jobject callback_obj) {

    auto* holder = reinterpret_cast<NativeTunnelHolder*>(handle);
    if (!holder) return JNI_FALSE;

    holder->client.stop();
    if (holder->cb_ref) {
        env->DeleteGlobalRef(holder->cb_ref);
        holder->cb_ref = nullptr;
    }

    if (callback_obj) {
        holder->cb_ref = env->NewGlobalRef(callback_obj);
        jclass cls = env->GetObjectClass(callback_obj);
        holder->mid_protect = env->GetMethodID(cls, "onNativeProtectSocket", "(I)Z");
        holder->mid_connected = env->GetMethodID(cls, "onNativeConnected", "()V");
        holder->mid_disconnected = env->GetMethodID(cls, "onNativeDisconnected", "()V");
        holder->mid_error = env->GetMethodID(cls, "onNativeError", "(Ljava/lang/String;)V");
        env->DeleteLocalRef(cls);
    }

    svpn::SshConfig cfg;
    cfg.host = jstring_to_std(env, jhost);
    cfg.resolved_ip_hint = jstring_to_std(env, jresolved_hint);
    cfg.port = int(port);
    cfg.user = jstring_to_std(env, juser);
    cfg.password = jstring_to_std(env, jpassword);
    cfg.private_key = jstring_to_std(env, jprivate_key);
    cfg.passphrase = jstring_to_std(env, jpassphrase);
    cfg.bind_host = jstring_to_std(env, jbind_host);
    cfg.local_port = int(local_port);
    cfg.connect_timeout_ms = int(timeout_ms);

    svpn::TunnelCallbacks cbs;
    cbs.protect_socket = [holder](int fd) -> bool {
        if (!holder->cb_ref || !holder->mid_protect) return true;
        ScopedJniAttach att(holder->jvm);
        if (!att.env) return false;
        jboolean ok = att.env->CallBooleanMethod(holder->cb_ref, holder->mid_protect, jint(fd));
        if (att.env->ExceptionCheck()) {
            att.env->ExceptionClear();
            return false;
        }
        return ok == JNI_TRUE;
    };
    cbs.on_connected = [holder]() {
        if (!holder->cb_ref || !holder->mid_connected) return;
        ScopedJniAttach att(holder->jvm);
        if (!att.env) return;
        att.env->CallVoidMethod(holder->cb_ref, holder->mid_connected);
        if (att.env->ExceptionCheck()) att.env->ExceptionClear();
    };
    cbs.on_disconnected = [holder]() {
        if (!holder->cb_ref || !holder->mid_disconnected) return;
        ScopedJniAttach att(holder->jvm);
        if (!att.env) return;
        att.env->CallVoidMethod(holder->cb_ref, holder->mid_disconnected);
        if (att.env->ExceptionCheck()) att.env->ExceptionClear();
    };
    cbs.on_error = [holder](const std::string& err) {
        if (!holder->cb_ref || !holder->mid_error) return;
        ScopedJniAttach att(holder->jvm);
        if (!att.env) return;
        jstring jerr = att.env->NewStringUTF(err.c_str());
        att.env->CallVoidMethod(holder->cb_ref, holder->mid_error, jerr);
        if (jerr) att.env->DeleteLocalRef(jerr);
        if (att.env->ExceptionCheck()) att.env->ExceptionClear();
    };

    return holder->client.start(cfg, std::move(cbs)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_ru_toinet_android_svpn_SshTunnel_nativeStop(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    auto* holder = reinterpret_cast<NativeTunnelHolder*>(handle);
    if (holder) holder->client.stop();
}

JNIEXPORT jboolean JNICALL
Java_ru_toinet_android_svpn_SshTunnel_nativeIsConnected(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    auto* holder = reinterpret_cast<NativeTunnelHolder*>(handle);
    return (holder && holder->client.is_connected()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_ru_toinet_android_svpn_SshTunnel_nativeIsProxyListening(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    auto* holder = reinterpret_cast<NativeTunnelHolder*>(handle);
    return (holder && holder->client.is_proxy_listening()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_ru_toinet_android_svpn_SshTunnel_nativeLastError(JNIEnv* env, jobject /*thiz*/, jlong handle) {
    auto* holder = reinterpret_cast<NativeTunnelHolder*>(handle);
    if (!holder) return nullptr;
    std::string err = holder->client.last_error();
    if (err.empty()) return nullptr;
    return env->NewStringUTF(err.c_str());
}

JNIEXPORT jstring JNICALL
Java_ru_toinet_android_svpn_SshTunnel_nativeTestConnection(
    JNIEnv* env, jclass /*cls*/,
    jstring jhost, jstring jresolved_hint, jint port,
    jstring juser, jstring jpassword, jstring jprivate_key, jstring jpassphrase,
    jint timeout_ms, jobject vpn_service) {

    svpn::SshConfig cfg;
    cfg.host = jstring_to_std(env, jhost);
    cfg.resolved_ip_hint = jstring_to_std(env, jresolved_hint);
    cfg.port = int(port);
    cfg.user = jstring_to_std(env, juser);
    cfg.password = jstring_to_std(env, jpassword);
    cfg.private_key = jstring_to_std(env, jprivate_key);
    cfg.passphrase = jstring_to_std(env, jpassphrase);
    cfg.connect_timeout_ms = int(timeout_ms);

    jmethodID mid_protect = nullptr;
    if (vpn_service) {
        jclass cls = env->GetObjectClass(vpn_service);
        mid_protect = env->GetMethodID(cls, "protect", "(I)Z");
        env->DeleteLocalRef(cls);
    }

    svpn::ProtectSocketFn protect_fn = [env, vpn_service, mid_protect](int fd) -> bool {
        if (!vpn_service || !mid_protect) return true;
        jboolean ok = env->CallBooleanMethod(vpn_service, mid_protect, jint(fd));
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return false;
        }
        return ok == JNI_TRUE;
    };

    std::string err;
    if (svpn::test_ssh_connection(cfg, protect_fn, err)) {
        return nullptr;
    }
    return env->NewStringUTF(err.c_str());
}

} // extern "C"
