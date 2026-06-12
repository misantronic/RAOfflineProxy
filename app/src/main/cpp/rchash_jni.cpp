#include <jni.h>

#include <vector>

#include "rchash_glue.h"

namespace {
constexpr int kMaxCandidates = 8;
constexpr int kHashStride = 33; /* 32 hex chars + NUL, matches rc_hash */

/* Bridges rc_hash's random-access reads to a Kotlin RomDataSource. The whole
 * hash runs synchronously on the calling thread, so the JNIEnv is reused. */
struct DataSourceContext {
    JNIEnv* env;
    jobject dataSource;
    jmethodID readMethod;   /* int read(long offset, byte[] buffer, int length) */
    jmethodID lengthMethod; /* long getLength() */
};

long long dataSourceSize(void* ctx) {
    auto* d = static_cast<DataSourceContext*>(ctx);
    return static_cast<long long>(d->env->CallLongMethod(d->dataSource, d->lengthMethod));
}

int dataSourceRead(void* ctx, long long offset, void* buffer, int bytes) {
    auto* d = static_cast<DataSourceContext*>(ctx);
    JNIEnv* env = d->env;
    jbyteArray array = env->NewByteArray(bytes);
    if (array == nullptr) {
        return 0;
    }
    const jint got = env->CallIntMethod(
        d->dataSource, d->readMethod, static_cast<jlong>(offset), array, static_cast<jint>(bytes));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        env->DeleteLocalRef(array);
        return 0;
    }
    if (got > 0) {
        env->GetByteArrayRegion(array, 0, got, static_cast<jbyte*>(buffer));
    }
    env->DeleteLocalRef(array);
    return got > 0 ? static_cast<int>(got) : 0;
}

jobjectArray buildHashArray(JNIEnv* env, const char* buffer, int count) {
    jclass stringClass = env->FindClass("java/lang/String");
    const int safeCount = (count > 0) ? count : 0;
    jobjectArray result = env->NewObjectArray(safeCount, stringClass, nullptr);
    for (int i = 0; i < safeCount; ++i) {
        jstring hash = env->NewStringUTF(buffer + static_cast<size_t>(i) * kHashStride);
        env->SetObjectArrayElement(result, i, hash);
        env->DeleteLocalRef(hash);
    }
    return result;
}
}  // namespace

/* Returns the rc_hash candidate hashes for the file at `path` as a String[],
 * in iterator order (most-likely console first). Empty array on failure. */
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_raofflineproxy_proxy_hash_RcHashNativeBridge_nativeHashFile(
    JNIEnv* env,
    jclass,
    jstring path
) {
    jclass stringClass = env->FindClass("java/lang/String");

    const char* rawPath = (path != nullptr) ? env->GetStringUTFChars(path, nullptr) : nullptr;
    if (rawPath == nullptr) {
        return env->NewObjectArray(0, stringClass, nullptr);
    }

    std::vector<char> buffer(static_cast<size_t>(kMaxCandidates) * kHashStride, 0);
    const int count = raproxy_hash_file(rawPath, buffer.data(), kMaxCandidates);
    env->ReleaseStringUTFChars(path, rawPath);

    return buildHashArray(env, buffer.data(), count);
}

/* Hashes a GameCube/Wii disc whose decompressed bytes are provided by a Kotlin
 * RomDataSource (used for container formats: RVZ/CISO/GCZ/WBFS, and raw GCM). */
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_raofflineproxy_proxy_hash_RcHashNativeBridge_nativeHashDiscDataSource(
    JNIEnv* env,
    jclass,
    jobject dataSource
) {
    if (dataSource == nullptr) {
        return buildHashArray(env, nullptr, 0);
    }

    jclass dsClass = env->GetObjectClass(dataSource);
    jmethodID readMethod = env->GetMethodID(dsClass, "read", "(J[BI)I");
    if (readMethod == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return buildHashArray(env, nullptr, 0);
    }
    jmethodID lengthMethod = env->GetMethodID(dsClass, "getLength", "()J");
    if (lengthMethod == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return buildHashArray(env, nullptr, 0);
    }

    DataSourceContext ctx{env, dataSource, readMethod, lengthMethod};
    std::vector<char> buffer(static_cast<size_t>(kMaxCandidates) * kHashStride, 0);
    const int count = raproxy_hash_disc_datasource(
        &ctx, dataSourceSize, dataSourceRead, buffer.data(), kMaxCandidates);

    return buildHashArray(env, buffer.data(), count);
}
