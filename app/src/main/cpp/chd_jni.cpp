#include <jni.h>

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <string>
#include <utility>
#include <vector>

#include "libchdr/cdrom.h"
#include "libchdr/chd.h"

namespace {

constexpr uint32_t kCdMetadataTag = CDROM_TRACK_METADATA_TAG;
constexpr uint32_t kCdMetadata2Tag = CDROM_TRACK_METADATA2_TAG;
constexpr uint32_t kFrameSize = CD_FRAME_SIZE;
constexpr uint32_t kSectorSize = CD_MAX_SECTOR_DATA;
constexpr uint32_t kTrackPadding = CD_TRACK_PADDING;

struct TrackInfo {
    uint32_t number;
    std::string type;
    uint32_t frames;
    uint32_t pregap;
    uint32_t postgap;
};

uint32_t padTrackFrames(uint32_t frames) {
    return (frames + (kTrackPadding - 1U)) & ~(kTrackPadding - 1U);
}

bool isDataTrack(const std::string& type) {
    return type != "AUDIO";
}

bool loadTrackInfo(chd_file* chd, uint32_t index, TrackInfo* trackInfo) {
    char metadata[256] = {};
    uint32_t metadataLength = 0;
    uint32_t metadataTag = 0;

    if (chd_get_metadata(chd, kCdMetadata2Tag, index, metadata, sizeof(metadata), &metadataLength, &metadataTag, nullptr) == CHDERR_NONE) {
        int track = 0;
        int frames = 0;
        int pregap = 0;
        int postgap = 0;
        char type[32] = {};
        char subtype[32] = {};
        char pregapType[32] = {};
        char pregapSubtype[32] = {};
        if (std::sscanf(
                metadata,
                CDROM_TRACK_METADATA2_FORMAT,
                &track,
                type,
                subtype,
                &frames,
                &pregap,
                pregapType,
                pregapSubtype,
                &postgap
            ) == 8) {
            trackInfo->number = static_cast<uint32_t>(track);
            trackInfo->type = type;
            trackInfo->frames = static_cast<uint32_t>(frames);
            trackInfo->pregap = static_cast<uint32_t>(pregap);
            trackInfo->postgap = static_cast<uint32_t>(postgap);
            return true;
        }
    }

    if (chd_get_metadata(chd, kCdMetadataTag, index, metadata, sizeof(metadata), &metadataLength, &metadataTag, nullptr) == CHDERR_NONE) {
        int track = 0;
        int frames = 0;
        char type[32] = {};
        char subtype[32] = {};
        if (std::sscanf(metadata, CDROM_TRACK_METADATA_FORMAT, &track, type, subtype, &frames) == 4) {
            trackInfo->number = static_cast<uint32_t>(track);
            trackInfo->type = type;
            trackInfo->frames = static_cast<uint32_t>(frames);
            trackInfo->pregap = 0;
            trackInfo->postgap = 0;
            return true;
        }
    }

    return false;
}

class ChdDiscHandle {
public:
    explicit ChdDiscHandle(std::string path)
        : path_(std::move(path)) {
    }

    ~ChdDiscHandle() {
        if (chd_ != nullptr) {
            chd_close(chd_);
        }
    }

    bool open(std::string* error) {
        chd_error result = chd_open(path_.c_str(), CHD_OPEN_READ, nullptr, &chd_);
        if (result != CHDERR_NONE) {
            *error = chd_error_string(result);
            return false;
        }

        const chd_header* header = chd_get_header(chd_);
        if (header == nullptr) {
            *error = "missing CHD header";
            return false;
        }

        hunkBytes_ = header->hunkbytes;
        hunkBuffer_.resize(hunkBytes_);

        uint32_t trackIndex = 0;
        uint32_t accumulatedFrames = 0;
        TrackInfo trackInfo{};
        while (loadTrackInfo(chd_, trackIndex, &trackInfo)) {
            if (isDataTrack(trackInfo.type)) {
                firstTrackStartFrame_ = accumulatedFrames;
                firstTrackFrames_ = trackInfo.frames;
                return true;
            }
            accumulatedFrames += padTrackFrames(trackInfo.frames);
            trackIndex++;
        }

        *error = "no data track found";
        return false;
    }

    uint64_t logicalLength() const {
        return static_cast<uint64_t>(firstTrackFrames_) * kSectorSize;
    }

    int read(uint64_t offset, uint8_t* output, int requestedLength, std::string* error) {
        if (offset >= logicalLength()) {
            return -1;
        }

        const uint64_t available = logicalLength() - offset;
        const int targetLength = static_cast<int>(std::min<uint64_t>(available, static_cast<uint64_t>(requestedLength)));
        int totalRead = 0;

        while (totalRead < targetLength) {
            const uint64_t projectedOffset = offset + static_cast<uint64_t>(totalRead);
            const uint64_t absoluteFrame = firstTrackStartFrame_ + (projectedOffset / kSectorSize);
            const uint32_t sectorOffset = static_cast<uint32_t>(projectedOffset % kSectorSize);
            const uint64_t absoluteOffset = absoluteFrame * kFrameSize;
            const uint32_t hunk = static_cast<uint32_t>(absoluteOffset / hunkBytes_);
            const uint32_t hunkOffset = static_cast<uint32_t>((absoluteOffset % hunkBytes_) + sectorOffset);

            if (cachedHunk_ != hunk) {
                const chd_error result = chd_read(chd_, hunk, hunkBuffer_.data());
                if (result != CHDERR_NONE) {
                    *error = chd_error_string(result);
                    return totalRead > 0 ? totalRead : -1;
                }
                cachedHunk_ = hunk;
            }

            const int bytesUntilSectorEnd = static_cast<int>(kSectorSize - sectorOffset);
            const int bytesUntilHunkEnd = static_cast<int>(hunkBytes_ - hunkOffset);
            const int chunk = std::min(targetLength - totalRead, std::min(bytesUntilSectorEnd, bytesUntilHunkEnd));
            std::memcpy(output + totalRead, hunkBuffer_.data() + hunkOffset, static_cast<size_t>(chunk));
            totalRead += chunk;
        }

        return totalRead;
    }

private:
    std::string path_;
    chd_file* chd_ = nullptr;
    uint32_t hunkBytes_ = 0;
    uint32_t firstTrackStartFrame_ = 0;
    uint32_t firstTrackFrames_ = 0;
    uint32_t cachedHunk_ = UINT32_MAX;
    std::vector<uint8_t> hunkBuffer_;
};

jclass findClass(JNIEnv* env, const char* name) {
    return env->FindClass(name);
}

void throwIOException(JNIEnv* env, const std::string& message) {
    jclass exceptionClass = findClass(env, "java/io/IOException");
    if (exceptionClass != nullptr) {
        env->ThrowNew(exceptionClass, message.c_str());
    }
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_raofflineproxy_proxy_hash_ChdNativeBridge_nativeOpen(
    JNIEnv* env,
    jclass,
    jstring path
) {
    const char* rawPath = env->GetStringUTFChars(path, nullptr);
    if (rawPath == nullptr) {
        return 0;
    }

    auto* handle = new ChdDiscHandle(rawPath);
    env->ReleaseStringUTFChars(path, rawPath);

    std::string error;
    if (!handle->open(&error)) {
        delete handle;
        throwIOException(env, error);
        return 0;
    }

    return reinterpret_cast<jlong>(handle);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_raofflineproxy_proxy_hash_ChdNativeBridge_nativeLength(
    JNIEnv*,
    jclass,
    jlong handle
) {
    auto* discHandle = reinterpret_cast<ChdDiscHandle*>(handle);
    if (discHandle == nullptr) {
        return 0;
    }
    return static_cast<jlong>(discHandle->logicalLength());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_raofflineproxy_proxy_hash_ChdNativeBridge_nativeRead(
    JNIEnv* env,
    jclass,
    jlong handle,
    jlong offset,
    jbyteArray output,
    jint requestedLength
) {
    auto* discHandle = reinterpret_cast<ChdDiscHandle*>(handle);
    if (discHandle == nullptr) {
        return -1;
    }

    jbyte* bytes = env->GetByteArrayElements(output, nullptr);
    if (bytes == nullptr) {
        return -1;
    }

    std::string error;
    const int read = discHandle->read(static_cast<uint64_t>(offset), reinterpret_cast<uint8_t*>(bytes), requestedLength, &error);
    env->ReleaseByteArrayElements(output, bytes, 0);

    if (read < 0 && !error.empty()) {
        throwIOException(env, error);
    }

    return read;
}

extern "C" JNIEXPORT void JNICALL
Java_com_raofflineproxy_proxy_hash_ChdNativeBridge_nativeClose(
    JNIEnv*,
    jclass,
    jlong handle
) {
    auto* discHandle = reinterpret_cast<ChdDiscHandle*>(handle);
    delete discHandle;
}
