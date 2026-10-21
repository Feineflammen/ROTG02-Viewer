#include <jni.h>

#include <errno.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <unistd.h>

#include <array>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <iomanip>
#include <sstream>
#include <string>
#include <vector>

namespace {

constexpr unsigned int kStreamingInterface = 1;
constexpr unsigned int kStreamingAlt = 11;
constexpr unsigned char kVideoEndpoint = 0x83;
constexpr int kPacketSize = 3000;
constexpr int kPacketsPerUrb = 8;
constexpr int kUrbCount = 8;
constexpr uint32_t kFrameInterval100ns = 333333;
constexpr uint32_t kMaxFrameBytes = 640u * 480u * 2u;
constexpr uint32_t kRequestedPayloadBytes = 3000;
constexpr size_t kAssemblerMaxBytes = 1024u * 1024u;

constexpr uint8_t UVC_SET_CUR = 0x01;
constexpr uint8_t UVC_GET_CUR = 0x81;
constexpr uint16_t UVC_VS_PROBE_CONTROL = 0x0100;
constexpr uint16_t UVC_VS_COMMIT_CONTROL = 0x0200;

std::atomic<bool> gStopRequested{false};

uint32_t getLe32(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) |
           (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) |
           (static_cast<uint32_t>(p[3]) << 24);
}

void putLe16(uint8_t* p, uint16_t v) {
    p[0] = static_cast<uint8_t>(v & 0xff);
    p[1] = static_cast<uint8_t>((v >> 8) & 0xff);
}

void putLe32(uint8_t* p, uint32_t v) {
    p[0] = static_cast<uint8_t>(v & 0xff);
    p[1] = static_cast<uint8_t>((v >> 8) & 0xff);
    p[2] = static_cast<uint8_t>((v >> 16) & 0xff);
    p[3] = static_cast<uint8_t>((v >> 24) & 0xff);
}

uint64_t nowNs() {
    return static_cast<uint64_t>(
        std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::steady_clock::now().time_since_epoch()
        ).count()
    );
}

std::string errnoText(const char* op) {
    std::ostringstream out;
    out << op << " fehlgeschlagen: errno=" << errno
        << " (" << std::strerror(errno) << ")";
    return out.str();
}

int setInterface(int fd, unsigned int interfaceNumber, unsigned int altSetting) {
    usbdevfs_setinterface si{};
    si.interface = interfaceNumber;
    si.altsetting = altSetting;
    return ioctl(fd, USBDEVFS_SETINTERFACE, &si);
}

int controlTransfer(
    int fd,
    uint8_t requestType,
    uint8_t request,
    uint16_t value,
    uint16_t index,
    void* data,
    uint16_t length,
    uint32_t timeoutMs
) {
    usbdevfs_ctrltransfer ctrl{};
    ctrl.bRequestType = requestType;
    ctrl.bRequest = request;
    ctrl.wValue = value;
    ctrl.wIndex = index;
    ctrl.wLength = length;
    ctrl.timeout = timeoutMs;
    ctrl.data = data;
    return ioctl(fd, USBDEVFS_CONTROL, &ctrl);
}

struct UrbContext {
    usbdevfs_urb* urb = nullptr;
    uint8_t* buffer = nullptr;
    bool submitted = false;
};

void freeUrb(UrbContext& ctx) {
    std::free(ctx.buffer);
    std::free(ctx.urb);
    ctx.buffer = nullptr;
    ctx.urb = nullptr;
    ctx.submitted = false;
}

bool allocateUrb(UrbContext& ctx) {
    const size_t urbBytes =
        sizeof(usbdevfs_urb) +
        static_cast<size_t>(kPacketsPerUrb) * sizeof(usbdevfs_iso_packet_desc);

    ctx.urb = static_cast<usbdevfs_urb*>(std::calloc(1, urbBytes));
    ctx.buffer = static_cast<uint8_t*>(
        std::malloc(static_cast<size_t>(kPacketSize) * kPacketsPerUrb)
    );

    if (ctx.urb == nullptr || ctx.buffer == nullptr) {
        freeUrb(ctx);
        return false;
    }

    ctx.urb->type = USBDEVFS_URB_TYPE_ISO;
    ctx.urb->endpoint = kVideoEndpoint;
    ctx.urb->flags = USBDEVFS_URB_ISO_ASAP;
    ctx.urb->buffer = ctx.buffer;
    ctx.urb->buffer_length = kPacketSize * kPacketsPerUrb;
    ctx.urb->number_of_packets = kPacketsPerUrb;
    ctx.urb->usercontext = &ctx;

    for (int i = 0; i < kPacketsPerUrb; ++i) {
        ctx.urb->iso_frame_desc[i].length = kPacketSize;
    }

    return true;
}

bool submitUrb(int fd, UrbContext& ctx, std::ostringstream& out) {
    ctx.urb->status = 0;
    ctx.urb->actual_length = 0;
    ctx.urb->error_count = 0;

    for (int i = 0; i < kPacketsPerUrb; ++i) {
        ctx.urb->iso_frame_desc[i].actual_length = 0;
        ctx.urb->iso_frame_desc[i].status = 0;
    }

    if (ioctl(fd, USBDEVFS_SUBMITURB, ctx.urb) < 0) {
        out << errnoText("USBDEVFS_SUBMITURB") << "\n";
        return false;
    }

    ctx.submitted = true;
    return true;
}

class UvcJpegAssembler {
public:
    UvcJpegAssembler() {
        frame_.reserve(kMaxFrameBytes);
    }

    void markCurrentCorrupt() {
        if (haveFid_) {
            frameBad_ = true;
        }
    }

    void consumePacket(
        uint8_t fid,
        bool eof,
        const uint8_t* data,
        size_t len,
        JNIEnv* env,
        jobject sink,
        jmethodID callback
    ) {
        if (!haveFid_) {
            beginFrame(fid);
        } else if (fid != currentFid_) {
            if (!frame_.empty() || frameBad_) {
                ++incompleteDrops_;
            }
            beginFrame(fid);
        }

        if (!frameBad_ && len > 0) {
            if (frame_.size() + len > kAssemblerMaxBytes) {
                ++oversizeDrops_;
                frame_.clear();
                frameBad_ = true;
            } else {
                frame_.insert(frame_.end(), data, data + len);
            }
        }

        if (!eof) {
            return;
        }

        if (frameBad_) {
            ++corruptDrops_;
        } else if (isCompleteJpeg()) {
            deliver(env, sink, callback);
        } else {
            ++invalidJpegDrops_;
        }

        frame_.clear();
        frameBad_ = false;
        haveFid_ = false;
    }

    uint64_t deliveredFrames() const {
        return deliveredFrames_;
    }

    uint64_t callbackErrors() const {
        return callbackErrors_;
    }

    uint64_t oversizeDrops() const {
        return oversizeDrops_;
    }

    uint64_t incompleteDrops() const {
        return incompleteDrops_;
    }

    uint64_t corruptDrops() const {
        return corruptDrops_;
    }

    uint64_t invalidJpegDrops() const {
        return invalidJpegDrops_;
    }

private:
    void beginFrame(uint8_t fid) {
        frame_.clear();
        currentFid_ = fid;
        haveFid_ = true;
        frameBad_ = false;
    }

    bool isCompleteJpeg() const {
        return frame_.size() >= 4 &&
               frame_[0] == 0xff &&
               frame_[1] == 0xd8 &&
               frame_[frame_.size() - 2] == 0xff &&
               frame_[frame_.size() - 1] == 0xd9;
    }

    void deliver(JNIEnv* env, jobject sink, jmethodID callback) {
        jbyteArray array = env->NewByteArray(static_cast<jsize>(frame_.size()));
        if (array == nullptr) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            ++callbackErrors_;
            return;
        }

        env->SetByteArrayRegion(
            array,
            0,
            static_cast<jsize>(frame_.size()),
            reinterpret_cast<const jbyte*>(frame_.data())
        );

        if (!env->ExceptionCheck()) {
            env->CallVoidMethod(
                sink,
                callback,
                array,
                static_cast<jlong>(nowNs())
            );
        }

        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            ++callbackErrors_;
        } else {
            ++deliveredFrames_;
        }

        env->DeleteLocalRef(array);
    }

    std::vector<uint8_t> frame_;
    uint8_t currentFid_ = 0;
    bool haveFid_ = false;
    bool frameBad_ = false;
    uint64_t deliveredFrames_ = 0;
    uint64_t callbackErrors_ = 0;
    uint64_t oversizeDrops_ = 0;
    uint64_t incompleteDrops_ = 0;
    uint64_t corruptDrops_ = 0;
    uint64_t invalidJpegDrops_ = 0;
};

struct Metrics {
    uint64_t urbs = 0;
    uint64_t packets = 0;
    uint64_t packetErrors = 0;
    uint64_t invalidHeaders = 0;
    uint64_t uvcErrorHeaders = 0;
    uint64_t payloadBytes = 0;
};

std::string runStream(JNIEnv* env, int javaFd, jobject sink) {
    std::ostringstream out;
    out << "ROTG02 Viewer native stream\n";

    jclass sinkClass = env->GetObjectClass(sink);
    if (sinkClass == nullptr) {
        out << "FrameSink-Klasse nicht gefunden\n";
        return out.str();
    }

    jmethodID callback = env->GetMethodID(sinkClass, "onJpegFrame", "([BJ)V");
    env->DeleteLocalRef(sinkClass);

    if (callback == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        out << "FrameSink.onJpegFrame(byte[], long) nicht gefunden\n";
        return out.str();
    }

    const int fd = dup(javaFd);
    if (fd < 0) {
        out << errnoText("dup(fileDescriptor)") << "\n";
        return out.str();
    }

    if (gStopRequested.load(std::memory_order_acquire)) {
        close(fd);
        out << "Streamstart vor UVC-Setup abgebrochen\n";
        return out.str();
    }

    bool streamingAlt = false;
    std::vector<UrbContext> urbs(kUrbCount);

    auto finish = [&]() {
        if (streamingAlt) {
            if (setInterface(fd, kStreamingInterface, 0) < 0 && errno != ENODEV) {
                out << errnoText("SETINTERFACE alt=0") << "\n";
            }
            streamingAlt = false;
        }

        close(fd);

        for (auto& ctx : urbs) {
            freeUrb(ctx);
        }
    };

    if (setInterface(fd, kStreamingInterface, 0) < 0) {
        out << errnoText("SETINTERFACE 1/alt0") << "\n";
        finish();
        return out.str();
    }

    std::array<uint8_t, 26> probe{};
    putLe16(probe.data(), 1);
    probe[2] = 1;
    probe[3] = 1;
    putLe32(probe.data() + 4, kFrameInterval100ns);
    putLe32(probe.data() + 18, kMaxFrameBytes);
    putLe32(probe.data() + 22, kRequestedPayloadBytes);

    int rc = controlTransfer(
        fd,
        0x21,
        UVC_SET_CUR,
        UVC_VS_PROBE_CONTROL,
        kStreamingInterface,
        probe.data(),
        static_cast<uint16_t>(probe.size()),
        1500
    );
    if (rc < 0) {
        out << errnoText("UVC SET_CUR PROBE") << "\n";
        finish();
        return out.str();
    }

    probe.fill(0);
    rc = controlTransfer(
        fd,
        0xA1,
        UVC_GET_CUR,
        UVC_VS_PROBE_CONTROL,
        kStreamingInterface,
        probe.data(),
        static_cast<uint16_t>(probe.size()),
        1500
    );
    if (rc < 0) {
        out << errnoText("UVC GET_CUR PROBE") << "\n";
        finish();
        return out.str();
    }

    const uint8_t formatIndex = probe[2];
    const uint8_t frameIndex = probe[3];
    const uint32_t interval = getLe32(probe.data() + 4);
    const uint32_t maxFrame = getLe32(probe.data() + 18);
    const uint32_t maxPayload = getLe32(probe.data() + 22);

    out << "PROBE format=" << static_cast<int>(formatIndex)
        << " frame=" << static_cast<int>(frameIndex)
        << " interval=" << interval
        << " maxFrame=" << maxFrame
        << " maxPayload=" << maxPayload << "\n";

    if (formatIndex != 1 || frameIndex != 1 || maxPayload > kPacketSize) {
        out << "UVC-Aushandlung unerwartet; Stream abgebrochen\n";
        finish();
        return out.str();
    }

    rc = controlTransfer(
        fd,
        0x21,
        UVC_SET_CUR,
        UVC_VS_COMMIT_CONTROL,
        kStreamingInterface,
        probe.data(),
        static_cast<uint16_t>(probe.size()),
        1500
    );
    if (rc < 0) {
        out << errnoText("UVC SET_CUR COMMIT") << "\n";
        finish();
        return out.str();
    }

    if (setInterface(fd, kStreamingInterface, kStreamingAlt) < 0) {
        out << errnoText("SETINTERFACE 1/alt11") << "\n";
        finish();
        return out.str();
    }
    streamingAlt = true;

    for (auto& ctx : urbs) {
        if (!allocateUrb(ctx)) {
            out << "URB allocation fehlgeschlagen\n";
            finish();
            return out.str();
        }
    }

    int active = 0;
    for (auto& ctx : urbs) {
        if (!submitUrb(fd, ctx, out)) break;
        ++active;
    }

    if (active == 0) {
        finish();
        return out.str();
    }

    out << "ISO URBs queued: " << active << "/" << kUrbCount
        << " (" << kPacketsPerUrb << " Pakete/URB)\n";

    Metrics metrics;
    UvcJpegAssembler assembler;
    bool fatal = false;
    bool deviceGone = false;
    const auto start = std::chrono::steady_clock::now();

    while (!gStopRequested.load(std::memory_order_acquire) && active > 0) {
        void* completed = nullptr;
        rc = ioctl(fd, USBDEVFS_REAPURBNDELAY, &completed);

        if (rc < 0) {
            if (errno == EAGAIN || errno == EINTR) {
                usleep(250);
                continue;
            }
            if (errno == ENODEV || errno == ESHUTDOWN) {
                deviceGone = true;
                break;
            }
            out << errnoText("USBDEVFS_REAPURBNDELAY") << "\n";
            fatal = true;
            break;
        }

        if (completed == nullptr) {
            usleep(250);
            continue;
        }

        auto* urb = static_cast<usbdevfs_urb*>(completed);
        auto* ctx = static_cast<UrbContext*>(urb->usercontext);
        if (ctx == nullptr) {
            fatal = true;
            out << "URB ohne usercontext\n";
            break;
        }

        ctx->submitted = false;
        --active;
        ++metrics.urbs;

        for (int i = 0; i < urb->number_of_packets; ++i) {
            const auto& desc = urb->iso_frame_desc[i];
            ++metrics.packets;

            if (desc.status != 0) {
                ++metrics.packetErrors;
                assembler.markCurrentCorrupt();
                continue;
            }

            const size_t actual = static_cast<size_t>(desc.actual_length);
            if (actual == 0) continue;

            const uint8_t* packet =
                ctx->buffer + static_cast<size_t>(i) * kPacketSize;

            if (actual < 2) {
                ++metrics.invalidHeaders;
                assembler.markCurrentCorrupt();
                continue;
            }

            const uint8_t headerLen = packet[0];
            const uint8_t flags = packet[1];

            if (headerLen < 2 || headerLen > actual) {
                ++metrics.invalidHeaders;
                assembler.markCurrentCorrupt();
                continue;
            }

            if ((flags & 0x40u) != 0) {
                ++metrics.uvcErrorHeaders;
                assembler.markCurrentCorrupt();
                continue;
            }

            const size_t payloadLen = actual - headerLen;
            if (payloadLen > 0) {
                metrics.payloadBytes += payloadLen;
                const uint8_t fid = flags & 0x01u;
                const bool eof = (flags & 0x02u) != 0;
                assembler.consumePacket(
                    fid,
                    eof,
                    packet + headerLen,
                    payloadLen,
                    env,
                    sink,
                    callback
                );
            }
        }

        if (
            !gStopRequested.load(std::memory_order_acquire) &&
            submitUrb(fd, *ctx, out)
        ) {
            ++active;
        } else if (!gStopRequested.load(std::memory_order_acquire)) {
            fatal = true;
            break;
        }
    }

    for (auto& ctx : urbs) {
        if (ctx.submitted) {
            if (
                ioctl(fd, USBDEVFS_DISCARDURB, ctx.urb) < 0 &&
                errno != EINVAL &&
                errno != ENODEV
            ) {
                out << errnoText("USBDEVFS_DISCARDURB") << "\n";
            }
        }
    }

    const auto drainDeadline =
        std::chrono::steady_clock::now() + std::chrono::milliseconds(800);

    while (active > 0 && std::chrono::steady_clock::now() < drainDeadline) {
        void* completed = nullptr;
        rc = ioctl(fd, USBDEVFS_REAPURBNDELAY, &completed);

        if (rc == 0 && completed != nullptr) {
            auto* urb = static_cast<usbdevfs_urb*>(completed);
            auto* ctx = static_cast<UrbContext*>(urb->usercontext);
            if (ctx != nullptr && ctx->submitted) {
                ctx->submitted = false;
                --active;
            }
        } else if (rc < 0 && errno != EAGAIN && errno != EINTR) {
            break;
        } else {
            usleep(500);
        }
    }

    const double seconds = std::chrono::duration<double>(
        std::chrono::steady_clock::now() - start
    ).count();

    out << std::fixed << std::setprecision(2)
        << "Stream beendet: " << seconds << " s"
        << ", frames=" << assembler.deliveredFrames()
        << ", payload=" << (metrics.payloadBytes / 1000000.0) << " MB"
        << ", packetErrors=" << metrics.packetErrors
        << ", badHeaders=" << metrics.invalidHeaders
        << ", uvcErrors=" << metrics.uvcErrorHeaders
        << ", callbackErrors=" << assembler.callbackErrors()
        << ", oversizeDrops=" << assembler.oversizeDrops()
        << ", incompleteDrops=" << assembler.incompleteDrops()
        << ", corruptDrops=" << assembler.corruptDrops()
        << ", invalidJpegDrops=" << assembler.invalidJpegDrops()
        << ", fatal=" << (fatal ? "true" : "false")
        << ", deviceGone=" << (deviceGone ? "true" : "false")
        << "\n";

    finish();
    return out.str();
}

}  // namespace

extern "C"
JNIEXPORT void JNICALL
Java_de_feineflammen_rotg02viewer_NativeUvcStream_prepareStream(
    JNIEnv*,
    jobject
) {
    gStopRequested.store(false, std::memory_order_release);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_de_feineflammen_rotg02viewer_NativeUvcStream_runStream(
    JNIEnv* env,
    jobject,
    jint fileDescriptor,
    jobject sink
) {
    const std::string result = runStream(env, fileDescriptor, sink);
    return env->NewStringUTF(result.c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_de_feineflammen_rotg02viewer_NativeUvcStream_stopStream(
    JNIEnv*,
    jobject
) {
    gStopRequested.store(true, std::memory_order_release);
}
