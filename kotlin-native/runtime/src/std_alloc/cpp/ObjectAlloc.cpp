/*
 * Copyright 2010-2022 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

#include "ObjectAlloc.hpp"

#include <atomic>

#include "Memory.h"

#include <cstdlib>

using namespace kotlin;

namespace {

std::atomic<size_t> allocatedBytesCounter = 0;

} // namespace

void kotlin::initObjectPool() noexcept {}

void* kotlin::allocateInObjectPool(size_t size) noexcept {
    // TODO: Check that alignment to kObjectAlignment is satisfied.
    void* result = calloc(1, size);
    auto newSize = allocatedBytesCounter.fetch_add(size, std::memory_order_relaxed);
    newSize += size;
    OnMemoryAllocation(newSize);
    return result;
}

void kotlin::freeInObjectPool(void* ptr, size_t size) noexcept {
    allocatedBytesCounter.fetch_sub(size, std::memory_order_relaxed);
    free(ptr);
}

void kotlin::compactObjectPoolInCurrentThread() noexcept {}

void kotlin::compactObjectPoolInMainThread() noexcept {}

size_t kotlin::allocatedBytes() noexcept {
    return allocatedBytesCounter.load();
}
