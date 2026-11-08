#include <errno.h>
#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/socket.h>
#include <getopt.h>

#include "error.h"
#include "main.h"

extern int server_fd;
static pthread_mutex_t proxy_mutex = PTHREAD_MUTEX_INITIALIZER;
static int proxy_started = 0;
static int proxy_running = 0;

int __wrap_daemon(__attribute__((unused)) int nochdir, __attribute__((unused)) int noclose) {
    LOG(LOG_S, "daemon mode is not supported in the native worker");
    errno = EPERM;
    return -1;
}

static jint internal_start_proxy(JNIEnv *env, jobjectArray args) {
    pthread_mutex_lock(&proxy_mutex);
    if (proxy_running) {
        pthread_mutex_unlock(&proxy_mutex);
        LOG(LOG_S, "proxy process already running");
        return -1;
    }
    proxy_started = 1;
    pthread_mutex_unlock(&proxy_mutex);

    int argc = (*env)->GetArrayLength(env, args);
    char **argv = calloc(argc + 1, sizeof(char *));
    int result = -1;
    if (!argv) {
        return result;
    }

    for (int i = 0; i < argc; i++) {
        jstring arg = (jstring) (*env)->GetObjectArrayElement(env, args, i);
        if (!arg) {
            goto cleanup;
        }

        const char *arg_str = (*env)->GetStringUTFChars(env, arg, NULL);
        if (arg_str) {
            argv[i] = strdup(arg_str);
            (*env)->ReleaseStringUTFChars(env, arg, arg_str);
        }
        (*env)->DeleteLocalRef(env, arg);
        if (!argv[i]) {
            goto cleanup;
        }
    }

    LOG(LOG_S, "starting zDPI proxy with %d args", argc);
    pthread_mutex_lock(&proxy_mutex);
    server_fd = -1;
    proxy_running = 1;
    pthread_mutex_unlock(&proxy_mutex);

    optind = 1;
#if defined(__BIONIC__) || defined(__ANDROID__)
    extern int optreset;
    optreset = 1;
#endif

    result = main(argc, argv);

    pthread_mutex_lock(&proxy_mutex);
    proxy_running = 0;
    proxy_started = 0;
    pthread_mutex_unlock(&proxy_mutex);
    LOG(LOG_S, "proxy return code %d", result);

cleanup:
    for (int i = 0; i < argc; i++) free(argv[i]);
    free(argv);
    return result;
}

static jint internal_stop_proxy(void) {
    LOG(LOG_S, "send shutdown to proxy");
    pthread_mutex_lock(&proxy_mutex);
    int result = proxy_running && server_fd >= 0 ? shutdown(server_fd, SHUT_RDWR) : -1;
    pthread_mutex_unlock(&proxy_mutex);
    return result;
}

static jint internal_force_close(void) {
    LOG(LOG_S, "proxy socket force close");
    pthread_mutex_lock(&proxy_mutex);
    int result = server_fd >= 0 ? close(server_fd) : -1;
    server_fd = -1;
    pthread_mutex_unlock(&proxy_mutex);
    return result;
}

JNIEXPORT jint JNICALL
Java_ru_toinet_android_byedpi_core_ByeDpiProxy_jniStartProxy(JNIEnv *env, __attribute__((unused)) jobject thiz, jobjectArray args) {
    return internal_start_proxy(env, args);
}

JNIEXPORT jint JNICALL
Java_ru_toinet_android_byedpi_core_ByeDpiProxy_jniStopProxy(__attribute__((unused)) JNIEnv *env, __attribute__((unused)) jobject thiz) {
    return internal_stop_proxy();
}

JNIEXPORT jint JNICALL
Java_ru_toinet_android_byedpi_core_ByeDpiProxy_jniForceClose(__attribute__((unused)) JNIEnv *env, __attribute__((unused)) jobject thiz) {
    return internal_force_close();
}

JNIEXPORT jint JNICALL
Java_ru_toinet_android_zdpi_core_ZdpiProxy_jniStartProxy(JNIEnv *env, __attribute__((unused)) jobject thiz, jobjectArray args) {
    return internal_start_proxy(env, args);
}

JNIEXPORT jint JNICALL
Java_ru_toinet_android_zdpi_core_ZdpiProxy_jniStopProxy(__attribute__((unused)) JNIEnv *env, __attribute__((unused)) jobject thiz) {
    return internal_stop_proxy();
}

JNIEXPORT jint JNICALL
Java_ru_toinet_android_zdpi_core_ZdpiProxy_jniForceClose(__attribute__((unused)) JNIEnv *env, __attribute__((unused)) jobject thiz) {
    return internal_force_close();
}
