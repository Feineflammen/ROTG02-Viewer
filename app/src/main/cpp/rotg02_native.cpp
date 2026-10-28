#include <jni.h>

#include <errno.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <unistd.h>

#include <algorithm>
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

// Generic UVC streaming transport over Linux USBFS.
//
// Kotlin parses the USB descriptors and passes the stream configuration
// (interface, format/frame index, frame interval, alternate settings or bulk
// endpoint). This file performs PROBE/COMMIT, selects the alternate setting,
// queues isochronous or bulk URBs, parses UVC payload headers and assembles
// complete MJPEG or YUY2 frames.

namespace {

// Tried in order: some host controllers refuse large isochronous URBs with
// ENOMEM (the ROTG02 on Samsung needs 8 packets per URB).
constexpr std::array<int, 3> kIsoPacketsPerUrbOptions{32, 16, 8};
constexpr int kIsoUrbCount = 8;
constexpr int kBulkUrbCount = 8;
constexpr size_t kBulkMaxUrbBytes = 64 * 1024;
constexpr size_t kAbsoluteMaxFrameBytes = 32u * 1024u * 1024u;

constexpr uint8_t UVC_SET_CUR = 0x01;
constexpr uint8_t UVC_GET_CUR = 0x81;
constexpr uint16_t UVC_VS_PROBE_CONTROL = 0x0100;
constexpr uint16_t UVC_VS_COMMIT_CONTROL = 0x0200;

constexpr int KIND_MJPEG = 0;
constexpr int KIND_YUY2 = 1;

std::atomic<bool> gStopRequested{false};

struct AltSetting {
    unsigned int alt = 0;
    unsigned char endpoint = 0;
    int packetBytes = 0;
};

struct StreamConfig {
    unsigned int interfaceNumber = 1;
    uint8_t formatIndex = 1;
    uint8_t frameIndex = 1;
    uint32_t interval = 333333;
    int probeLength = 26;
    size_t maxFrameBytes = 640u * 480u * 2u;
    int kind = KIND_MJPEG;
    size_t expectedRawBytes = 0;  // YUY2 only: width * height * 2
    bool bulk = false;
    unsigned char bulkEndpoint = 0;
    int bulkPacketBytes = 512;
    std::vector<AltSetting> alts;  // isochronous alternates, ascending by packetBytes
};

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
    out << op << " failed: errno=" << errno
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
    size_t bufferBytes = 0;
    int packets = 0;
    bool submitted = false;
};

void freeUrb(UrbContext& ctx) {
    std::free(ctx.buffer);
    std::free(ctx.urb);
    ctx.buffer = nullptr;
    ctx.urb = nullptr;
    ctx.submitted = false;
}

bool allocateIsoUrb(UrbContext& ctx, unsigned char endpoint, int packetBytes, int packets) {
    const size_t urbBytes =
        sizeof(usbdevfs_urb) +
        static_cast<size_t>(packets) * sizeof(usbdevfs_iso_packet_desc);

    ctx.bufferBytes = static_cast<size_t>(packetBytes) * static_cast<size_t>(packets);
    ctx.packets = packets;
    ctx.urb = static_cast<usbdevfs_urb*>(std::calloc(1, urbBytes));
    ctx.buffer = static_cast<uint8_t*>(std::malloc(ctx.bufferBytes));

    if (ctx.urb == nullptr || ctx.buffer == nullptr) {
        freeUrb(ctx);
        return false;
    }

    ctx.urb->type = USBDEVFS_URB_TYPE_ISO;
    ctx.urb->endpoint = endpoint;
    ctx.urb->flags = USBDEVFS_URB_ISO_ASAP;
    ctx.urb->buffer = ctx.buffer;
    ctx.urb->buffer_length = static_cast<int>(ctx.bufferBytes);
    ctx.urb->number_of_packets = packets;
    ctx.urb->usercontext = &ctx;

    for (int i = 0; i < packets; ++i) {
        ctx.urb->iso_frame_desc[i].length = static_cast<unsigned int>(packetBytes);
    }
    return true;
}

bool allocateBulkUrb(UrbContext& ctx, unsigned char endpoint, size_t bytes) {
    ctx.bufferBytes = bytes;
    ctx.packets = 0;
    ctx.urb = static_cast<usbdevfs_urb*>(std::calloc(1, sizeof(usbdevfs_urb)));
    ctx.buffer = static_cast<uint8_t*>(std::malloc(bytes));

    if (ctx.urb == nullptr || ctx.buffer == nullptr) {
        freeUrb(ctx);
        return false;
    }

    ctx.urb->type = USBDEVFS_URB_TYPE_BULK;
    ctx.urb->endpoint = endpoint;
    ctx.urb->buffer = ctx.buffer;
    ctx.urb->buffer_length = static_cast<int>(bytes);
    ctx.urb->usercontext = &ctx;
    return true;
}

bool submitUrb(int fd, UrbContext& ctx, std::ostringstream& out) {
    ctx.urb->status = 0;
    ctx.urb->actual_length = 0;
    ctx.urb->error_count = 0;

    for (int i = 0; i < ctx.packets; ++i) {
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

class FrameAssembler {
public:
    FrameAssembler(int kind, size_t maxBytes, size_t expectedRawBytes)
        : kind_(kind), maxBytes_(maxBytes), expectedRawBytes_(expectedRawBytes) {
        frame_.reserve(std::min<size_t>(maxBytes_, 8u * 1024u * 1024u));
    }

    void markCurrentCorrupt() {
        if (haveFid_) frameBad_ = true;
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
            // Many cameras never set EOF and only toggle FID.
            // The previous frame is complete if it validates.
            finishFrame(env, sink, callback, true);
            beginFrame(fid);
        }

        if (!frameBad_ && len > 0) {
            if (frame_.size() + len > maxBytes_) {
                ++oversizeDrops_;
                frame_.clear();
                frameBad_ = true;
            } else {
                frame_.insert(frame_.end(), data, data + len);
            }
        }

        if (eof) {
            finishFrame(env, sink, callback, false);
            haveFid_ = false;
        }
    }

    uint64_t deliveredFrames() const { return deliveredFrames_; }
    uint64_t callbackErrors() const { return callbackErrors_; }
    uint64_t oversizeDrops() const { return oversizeDrops_; }
    uint64_t corruptDrops() const { return corruptDrops_; }
    uint64_t invalidDrops() const { return invalidDrops_; }
    size_t lastInvalidSize() const { return lastInvalidSize_; }

private:
    void beginFrame(uint8_t fid) {
        frame_.clear();
        currentFid_ = fid;
        haveFid_ = true;
        frameBad_ = false;
    }

    void finishFrame(JNIEnv* env, jobject sink, jmethodID callback, bool viaFidToggle) {
        if (frame_.empty() && !frameBad_) return;

        if (frameBad_) {
            ++corruptDrops_;
        } else if (validate()) {
            deliver(env, sink, callback);
        } else if (!viaFidToggle || !frame_.empty()) {
            ++invalidDrops_;
            lastInvalidSize_ = frame_.size();
        }
        frame_.clear();
        frameBad_ = false;
    }

    bool validate() {
        if (kind_ == KIND_YUY2) {
            return expectedRawBytes_ > 0 && frame_.size() == expectedRawBytes_;
        }
        // MJPEG: SOI at start, EOI at end (some cameras pad with zeros).
        while (frame_.size() > 4 && frame_.back() == 0x00) frame_.pop_back();
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
            array, 0, static_cast<jsize>(frame_.size()),
            reinterpret_cast<const jbyte*>(frame_.data())
        );

        if (!env->ExceptionCheck()) {
            env->CallVoidMethod(sink, callback, array, static_cast<jlong>(nowNs()));
        }

        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            ++callbackErrors_;
        } else {
            ++deliveredFrames_;
        }
        env->DeleteLocalRef(array);
    }

    int kind_;
    size_t maxBytes_;
    size_t expectedRawBytes_;
    std::vector<uint8_t> frame_;
    uint8_t currentFid_ = 0;
    bool haveFid_ = false;
    bool frameBad_ = false;
    uint64_t deliveredFrames_ = 0;
    uint64_t callbackErrors_ = 0;
    uint64_t oversizeDrops_ = 0;
    uint64_t corruptDrops_ = 0;
    uint64_t invalidDrops_ = 0;
    size_t lastInvalidSize_ = 0;
};

struct Metrics {
    uint64_t urbs = 0;
    uint64_t packets = 0;
    uint64_t packetErrors = 0;
    uint64_t invalidHeaders = 0;
    uint64_t uvcErrorHeaders = 0;
    uint64_t payloadBytes = 0;
};

// Parses one UVC payload header. Returns header length or 0 when invalid.
size_t parseHeader(const uint8_t* p, size_t len, uint8_t& flags) {
    if (len < 2) return 0;
    const size_t headerLen = p[0];
    // UVC headers are 2..12 bytes; allow some slack for odd firmware.
    if (headerLen < 2 || headerLen > 64 || headerLen > len) return 0;
    flags = p[1];
    return headerLen;
}

bool negotiate(int fd, StreamConfig& cfg, uint32_t& maxPayload, std::ostringstream& out) {
    std::array<uint8_t, 48> probe{};
    int length = cfg.probeLength;

    for (int attempt = 0; attempt < 2; ++attempt) {
        probe.fill(0);
        // Start from the device's current values when it reports them.
        controlTransfer(fd, 0xA1, UVC_GET_CUR, UVC_VS_PROBE_CONTROL,
                        cfg.interfaceNumber, probe.data(),
                        static_cast<uint16_t>(length), 1000);

        putLe16(probe.data(), 1);  // bmHint: keep dwFrameInterval
        probe[2] = cfg.formatIndex;
        probe[3] = cfg.frameIndex;
        putLe32(probe.data() + 4, cfg.interval);

        int rc = controlTransfer(fd, 0x21, UVC_SET_CUR, UVC_VS_PROBE_CONTROL,
                                 cfg.interfaceNumber, probe.data(),
                                 static_cast<uint16_t>(length), 1500);
        if (rc < 0) {
            out << errnoText("UVC SET_CUR PROBE") << " (len=" << length << ")\n";
            if (length != 26) {
                length = 26;  // some devices only accept the UVC 1.0 layout
                continue;
            }
            return false;
        }

        probe.fill(0);
        rc = controlTransfer(fd, 0xA1, UVC_GET_CUR, UVC_VS_PROBE_CONTROL,
                             cfg.interfaceNumber, probe.data(),
                             static_cast<uint16_t>(length), 1500);
        if (rc < 0) {
            out << errnoText("UVC GET_CUR PROBE") << "\n";
            return false;
        }
        break;
    }

    const uint8_t gotFormat = probe[2];
    const uint8_t gotFrame = probe[3];
    const uint32_t gotInterval = getLe32(probe.data() + 4);
    const uint32_t maxFrame = getLe32(probe.data() + 18);
    maxPayload = getLe32(probe.data() + 22);

    out << "PROBE format=" << static_cast<int>(gotFormat)
        << " frame=" << static_cast<int>(gotFrame)
        << " interval=" << gotInterval
        << " maxFrame=" << maxFrame
        << " maxPayload=" << maxPayload
        << " len=" << length << "\n";

    if (gotFormat != cfg.formatIndex || gotFrame != cfg.frameIndex) {
        out << "Device changed format/frame (requested "
            << static_cast<int>(cfg.formatIndex) << "/"
            << static_cast<int>(cfg.frameIndex) << "); stream aborted\n";
        return false;
    }

    if (maxFrame > cfg.maxFrameBytes && maxFrame <= kAbsoluteMaxFrameBytes) {
        cfg.maxFrameBytes = maxFrame;
    }

    int rc = controlTransfer(fd, 0x21, UVC_SET_CUR, UVC_VS_COMMIT_CONTROL,
                             cfg.interfaceNumber, probe.data(),
                             static_cast<uint16_t>(length), 1500);
    if (rc < 0) {
        out << errnoText("UVC SET_CUR COMMIT") << "\n";
        return false;
    }
    return true;
}

std::string runStream(JNIEnv* env, int javaFd, jobject sink, StreamConfig cfg) {
    std::ostringstream out;
    out << "UVC native stream (" << (cfg.bulk ? "bulk" : "isochronous")
        << ", " << (cfg.kind == KIND_MJPEG ? "MJPEG" : "YUY2") << ")\n";

    jclass sinkClass = env->GetObjectClass(sink);
    if (sinkClass == nullptr) {
        out << "FrameSink class not found\n";
        return out.str();
    }
    jmethodID callback = env->GetMethodID(sinkClass, "onFrame", "([BJ)V");
    env->DeleteLocalRef(sinkClass);
    if (callback == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        out << "FrameSink.onFrame(byte[], long) not found\n";
        return out.str();
    }

    const int fd = dup(javaFd);
    if (fd < 0) {
        out << errnoText("dup(fileDescriptor)") << "\n";
        return out.str();
    }

    bool streamingAlt = false;
    std::vector<UrbContext> urbs;

    auto finish = [&]() {
        if (streamingAlt) {
            if (setInterface(fd, cfg.interfaceNumber, 0) < 0 && errno != ENODEV) {
                out << errnoText("SETINTERFACE alt=0") << "\n";
            }
            streamingAlt = false;
        }
        close(fd);
        for (auto& ctx : urbs) freeUrb(ctx);
    };

    if (gStopRequested.load(std::memory_order_acquire)) {
        finish();
        out << "Stream start cancelled before UVC setup\n";
        return out.str();
    }

    if (setInterface(fd, cfg.interfaceNumber, 0) < 0) {
        out << errnoText("SETINTERFACE alt0") << "\n";
        finish();
        return out.str();
    }

    uint32_t maxPayload = 0;
    if (!negotiate(fd, cfg, maxPayload, out)) {
        finish();
        return out.str();
    }

    size_t bulkUrbBytes = 0;
    int packetBytes = 0;
    unsigned char endpoint = 0;

    if (cfg.bulk) {
        endpoint = cfg.bulkEndpoint;
        size_t want = maxPayload > 0 ? maxPayload : 16384;
        want = std::min(want, kBulkMaxUrbBytes);
        const size_t mps = static_cast<size_t>(std::max(cfg.bulkPacketBytes, 64));
        bulkUrbBytes = ((want + mps - 1) / mps) * mps;
        out << "Bulk endpoint 0x" << std::hex << static_cast<int>(endpoint) << std::dec
            << ", URB " << bulkUrbBytes << " bytes\n";
    } else {
        if (cfg.alts.empty()) {
            out << "No isochronous alternate setting available\n";
            finish();
            return out.str();
        }
        // Smallest alternate that carries the negotiated payload; otherwise
        // try larger ones, finally the largest.
        size_t first = cfg.alts.size() - 1;
        if (maxPayload > 0) {
            for (size_t i = 0; i < cfg.alts.size(); ++i) {
                if (cfg.alts[i].packetBytes >= static_cast<int>(maxPayload)) {
                    first = i;
                    break;
                }
            }
        }
        for (size_t i = first; i < cfg.alts.size(); ++i) {
            const auto& a = cfg.alts[i];
            if (setInterface(fd, cfg.interfaceNumber, a.alt) == 0) {
                streamingAlt = true;
                packetBytes = a.packetBytes;
                endpoint = a.endpoint;
                out << "Alternate setting " << a.alt << ": endpoint 0x" << std::hex
                    << static_cast<int>(a.endpoint) << std::dec << ", "
                    << a.packetBytes << " bytes/packet\n";
                break;
            }
            out << errnoText("SETINTERFACE") << " (alt " << a.alt << ")\n";
        }
        if (!streamingAlt) {
            finish();
            return out.str();
        }
    }

    const int urbCount = cfg.bulk ? kBulkUrbCount : kIsoUrbCount;
    urbs.resize(static_cast<size_t>(urbCount));
    int active = 0;

    // Isochronous: fall back to smaller URBs when the host controller refuses
    // the larger ones. Bulk has a single attempt.
    const size_t attempts = cfg.bulk ? 1 : kIsoPacketsPerUrbOptions.size();
    for (size_t attempt = 0; attempt < attempts && active == 0; ++attempt) {
        const int packets = kIsoPacketsPerUrbOptions[attempt];
        bool allocated = true;
        for (auto& ctx : urbs) {
            const bool ok = cfg.bulk
                ? allocateBulkUrb(ctx, endpoint, bulkUrbBytes)
                : allocateIsoUrb(ctx, endpoint, packetBytes, packets);
            if (!ok) {
                allocated = false;
                break;
            }
        }
        if (!allocated) {
            out << "URB allocation failed\n";
            break;
        }

        for (auto& ctx : urbs) {
            if (!submitUrb(fd, ctx, out)) break;
            ++active;
        }

        if (!cfg.bulk) {
            if (active < urbCount && active > 0 && active >= urbCount / 2) {
                break;  // enough URBs in flight to stream
            }
            if (active == urbCount) {
                out << "Isochronous URBs: " << packets << " packets each\n";
                break;
            }
            // Too few accepted: cancel them and retry with smaller URBs.
            for (auto& ctx : urbs) {
                if (ctx.submitted) ioctl(fd, USBDEVFS_DISCARDURB, ctx.urb);
            }
            const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(500);
            while (active > 0 && std::chrono::steady_clock::now() < deadline) {
                void* done = nullptr;
                if (ioctl(fd, USBDEVFS_REAPURBNDELAY, &done) == 0 && done != nullptr) {
                    auto* c = static_cast<UrbContext*>(static_cast<usbdevfs_urb*>(done)->usercontext);
                    if (c != nullptr && c->submitted) {
                        c->submitted = false;
                        --active;
                    }
                } else {
                    usleep(500);
                }
            }
            if (active > 0) {
                out << "Could not cancel refused URBs\n";
                break;
            }
            for (auto& ctx : urbs) freeUrb(ctx);
            if (attempt + 1 < attempts) {
                out << "Retrying with " << kIsoPacketsPerUrbOptions[attempt + 1] << " packets per URB\n";
            }
        }
    }

    if (active == 0) {
        finish();
        return out.str();
    }
    out << "URBs queued: " << active << "/" << urbCount << "\n";

    Metrics metrics;
    FrameAssembler assembler(cfg.kind, cfg.maxFrameBytes, cfg.expectedRawBytes);
    bool fatal = false;
    bool deviceGone = false;
    const auto start = std::chrono::steady_clock::now();

    // Bulk payload state: a UVC payload may span several bulk URBs and only
    // its first URB carries the header.
    size_t bulkPayloadPos = 0;
    uint8_t bulkFlags = 0;
    bool bulkPayloadBad = false;

    while (!gStopRequested.load(std::memory_order_acquire) && active > 0) {
        void* completed = nullptr;
        int rc = ioctl(fd, USBDEVFS_REAPURBNDELAY, &completed);

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
            out << "URB without usercontext\n";
            break;
        }
        ctx->submitted = false;
        --active;
        ++metrics.urbs;

        if (cfg.bulk) {
            ++metrics.packets;
            const size_t actual = urb->status == 0 ? static_cast<size_t>(urb->actual_length) : 0;
            if (urb->status != 0) {
                ++metrics.packetErrors;
                assembler.markCurrentCorrupt();
                bulkPayloadPos = 0;
            } else if (actual > 0) {
                const uint8_t* data = ctx->buffer;
                size_t dataLen = actual;
                if (bulkPayloadPos == 0) {
                    uint8_t flags = 0;
                    const size_t headerLen = parseHeader(data, actual, flags);
                    if (headerLen == 0) {
                        ++metrics.invalidHeaders;
                        assembler.markCurrentCorrupt();
                        bulkPayloadBad = true;
                        dataLen = 0;
                    } else {
                        bulkFlags = flags;
                        bulkPayloadBad = (flags & 0x40u) != 0;
                        if (bulkPayloadBad) {
                            ++metrics.uvcErrorHeaders;
                            assembler.markCurrentCorrupt();
                        }
                        data += headerLen;
                        dataLen -= headerLen;
                    }
                }
                bulkPayloadPos += actual;
                const bool payloadEnd =
                    actual < ctx->bufferBytes ||
                    (maxPayload > 0 && bulkPayloadPos >= maxPayload);

                if (!bulkPayloadBad) {
                    metrics.payloadBytes += dataLen;
                    assembler.consumePacket(
                        bulkFlags & 0x01u,
                        payloadEnd && (bulkFlags & 0x02u) != 0,
                        data, dataLen, env, sink, callback
                    );
                }
                if (payloadEnd) {
                    bulkPayloadPos = 0;
                    bulkPayloadBad = false;
                }
            } else {
                bulkPayloadPos = 0;  // zero-length packet ends a payload
            }
        } else {
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
                    ctx->buffer + static_cast<size_t>(i) * static_cast<size_t>(packetBytes);

                uint8_t flags = 0;
                const size_t headerLen = parseHeader(packet, actual, flags);
                if (headerLen == 0) {
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
                metrics.payloadBytes += payloadLen;
                assembler.consumePacket(
                    flags & 0x01u,
                    (flags & 0x02u) != 0,
                    packet + headerLen,
                    payloadLen,
                    env, sink, callback
                );
            }
        }

        if (!gStopRequested.load(std::memory_order_acquire) && submitUrb(fd, *ctx, out)) {
            ++active;
        } else if (!gStopRequested.load(std::memory_order_acquire)) {
            fatal = true;
            break;
        }
    }

    for (auto& ctx : urbs) {
        if (ctx.submitted &&
            ioctl(fd, USBDEVFS_DISCARDURB, ctx.urb) < 0 &&
            errno != EINVAL && errno != ENODEV) {
            out << errnoText("USBDEVFS_DISCARDURB") << "\n";
        }
    }

    const auto drainDeadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(800);
    while (active > 0 && std::chrono::steady_clock::now() < drainDeadline) {
        void* completed = nullptr;
        const int rc = ioctl(fd, USBDEVFS_REAPURBNDELAY, &completed);
        if (rc == 0 && completed != nullptr) {
            auto* ctx = static_cast<UrbContext*>(static_cast<usbdevfs_urb*>(completed)->usercontext);
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
        std::chrono::steady_clock::now() - start).count();

    out << std::fixed << std::setprecision(2)
        << "Stream ended: " << seconds << " s"
        << ", frames=" << assembler.deliveredFrames()
        << ", payload=" << (static_cast<double>(metrics.payloadBytes) / 1000000.0) << " MB"
        << ", packetErrors=" << metrics.packetErrors
        << ", badHeaders=" << metrics.invalidHeaders
        << ", uvcErrors=" << metrics.uvcErrorHeaders
        << ", callbackErrors=" << assembler.callbackErrors()
        << ", oversizeDrops=" << assembler.oversizeDrops()
        << ", corruptDrops=" << assembler.corruptDrops()
        << ", invalidDrops=" << assembler.invalidDrops();
    if (assembler.invalidDrops() > 0) {
        out << " (last invalid size " << assembler.lastInvalidSize() << ")";
    }
    out << ", fatal=" << (fatal ? "true" : "false")
        << ", deviceGone=" << (deviceGone ? "true" : "false") << "\n";

    finish();
    return out.str();
}

}  // namespace

extern "C"
JNIEXPORT void JNICALL
Java_de_feineflammen_rotg02viewer_NativeUvcStream_prepareStream(JNIEnv*, jobject) {
    gStopRequested.store(false, std::memory_order_release);
}

// config layout (see NativeUvcStream.kt):
// [0] interface, [1] format index, [2] frame index, [3] interval (100 ns),
// [4] probe length, [5] max frame bytes, [6] kind (0 MJPEG, 1 YUY2),
// [7] expected raw bytes (YUY2), [8] bulk (0/1), [9] bulk endpoint,
// [10] bulk max packet size, then triplets (alt, endpoint, packet bytes).
extern "C"
JNIEXPORT jstring JNICALL
Java_de_feineflammen_rotg02viewer_NativeUvcStream_runStream(
    JNIEnv* env,
    jobject,
    jint fileDescriptor,
    jobject sink,
    jintArray config
) {
    StreamConfig cfg;
    const jsize n = config != nullptr ? env->GetArrayLength(config) : 0;
    if (n < 11) {
        return env->NewStringUTF("Invalid stream configuration\n");
    }
    std::vector<jint> c(static_cast<size_t>(n));
    env->GetIntArrayRegion(config, 0, n, c.data());

    cfg.interfaceNumber = static_cast<unsigned int>(c[0]);
    cfg.formatIndex = static_cast<uint8_t>(c[1]);
    cfg.frameIndex = static_cast<uint8_t>(c[2]);
    cfg.interval = static_cast<uint32_t>(c[3]);
    cfg.probeLength = std::clamp(static_cast<int>(c[4]), 26, 48);
    cfg.maxFrameBytes = std::clamp<size_t>(static_cast<size_t>(c[5]), 64u * 1024u, kAbsoluteMaxFrameBytes);
    cfg.kind = static_cast<int>(c[6]);
    cfg.expectedRawBytes = static_cast<size_t>(c[7]);
    cfg.bulk = c[8] != 0;
    cfg.bulkEndpoint = static_cast<unsigned char>(c[9]);
    cfg.bulkPacketBytes = static_cast<int>(c[10]);
    for (jsize i = 11; i + 2 < n; i += 3) {
        AltSetting a;
        a.alt = static_cast<unsigned int>(c[static_cast<size_t>(i)]);
        a.endpoint = static_cast<unsigned char>(c[static_cast<size_t>(i + 1)]);
        a.packetBytes = static_cast<int>(c[static_cast<size_t>(i + 2)]);
        if (a.packetBytes > 0) cfg.alts.push_back(a);
    }
    std::sort(cfg.alts.begin(), cfg.alts.end(),
              [](const AltSetting& x, const AltSetting& y) { return x.packetBytes < y.packetBytes; });

    const std::string result = runStream(env, fileDescriptor, sink, cfg);
    return env->NewStringUTF(result.c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_de_feineflammen_rotg02viewer_NativeUvcStream_stopStream(JNIEnv*, jobject) {
    gStopRequested.store(true, std::memory_order_release);
}
