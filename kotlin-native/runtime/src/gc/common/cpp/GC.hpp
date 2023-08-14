/*
 * Copyright 2010-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

#pragma once

#include <atomic>
#include <cstdint>

#include "ExtraObjectData.hpp"
#include "GCScheduler.hpp"
#include "Memory.h"
#include "Utils.hpp"
#include "std_support/Memory.hpp"

namespace kotlin {

namespace mm {
class ThreadData;
}

namespace gc {

class GC : private Pinned {
public:
    class Impl;

    class ThreadData : private Pinned {
    public:
        class Impl;

        ThreadData(GC& gc, mm::ThreadData& threadData) noexcept;
        ~ThreadData();

        Impl& impl() noexcept { return *impl_; }

        void PublishObjectFactory() noexcept;
        void ClearForTests() noexcept;

        ObjHeader* CreateObject(const TypeInfo* typeInfo) noexcept;
        ArrayHeader* CreateArray(const TypeInfo* typeInfo, uint32_t elements) noexcept;
        mm::ExtraObjectData& CreateExtraObjectDataForObject(ObjHeader* object, const TypeInfo* typeInfo) noexcept;
        void DestroyUnattachedExtraObjectData(mm::ExtraObjectData& extraObject) noexcept;

        void OnSuspendForGC() noexcept;

        void safePoint() noexcept;

        void onThreadRegistration() noexcept;

    private:
        std_support::unique_ptr<Impl> impl_;
    };

    explicit GC(gcScheduler::GCScheduler& gcScheduler) noexcept;
    ~GC();

    Impl& impl() noexcept { return *impl_; }

    static size_t GetAllocatedHeapSize(ObjHeader* object) noexcept;

    size_t GetTotalHeapObjectsSizeBytes() const noexcept;

    void ClearForTests() noexcept;

    void StartFinalizerThreadIfNeeded() noexcept;
    void StopFinalizerThreadIfRunning() noexcept;
    bool FinalizersThreadIsRunning() noexcept;

    static void processObjectInMark(void* state, ObjHeader* object) noexcept;
    static void processArrayInMark(void* state, ArrayHeader* array) noexcept;
    static void processFieldInMark(void* state, ObjHeader* field) noexcept;

    // TODO: These should exist only in the scheduler.
    int64_t Schedule() noexcept;
    void WaitFinished(int64_t epoch) noexcept;
    void WaitFinalizers(int64_t epoch) noexcept;

    static const size_t objectDataSize;
    static bool SweepObject(void* objectData) noexcept;

    static void DestroyExtraObjectData(mm::ExtraObjectData& extraObject) noexcept;

private:
    std_support::unique_ptr<Impl> impl_;
};

bool isMarked(ObjHeader* object) noexcept;
OBJ_GETTER(tryRef, std::atomic<ObjHeader*>& object) noexcept;

inline constexpr bool kSupportsMultipleMutators = true;

} // namespace gc
} // namespace kotlin
