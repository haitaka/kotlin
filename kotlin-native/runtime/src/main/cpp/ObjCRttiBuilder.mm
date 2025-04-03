// TODO copyright
#include "ObjCRttiBuilder.h" // TODO import?

#if KONAN_OBJC_INTEROP

#include <cstdlib>
#include <map>
#import <mutex>
#include <string>
#include <unordered_set>
#include <vector>

#import <Foundation/Foundation.h>
#import <objc/message.h>
#import <objc/runtime.h>
#import <objc/objc-exception.h>
#import <dispatch/dispatch.h>

#import "std_support/Span.hpp"

#import "ObjcExport.h"
#import "Runtime.h"
#import "concurrent/Mutex.hpp"
#import "Exceptions.h"
#import "Natives.h"
#import "TypeInfoObjCExportAddition.hpp"
#import "WritableTypeInfo.hpp"
#import "ObjCInterop.h"

using namespace kotlin;

static int anonymousClassNextId = 0;

auto implementedInterfaces(const TypeInfo* type) {
    return std_support::span<const TypeInfo* const>(type->implementedInterfaces_, type->implementedInterfacesCount_);
}

auto virtualAdapters(const ObjCTypeAdapter* typeAdapter) {
    return std_support::span<const ObjCToKotlinMethodAdapter>(typeAdapter->virtualAdapters, typeAdapter->virtualAdapterNum);
}

template <typename T, template<typename> typename Iterable>
auto toSet(const Iterable<T>& iterable) {
    return std::unordered_set(iterable.begin(), iterable.end());
}

template <typename T, template<typename> typename Set>
bool contains(const Set<T>& set, const T& element) {
    return set.find(element) != set.end();
}


static void addProtocolForInterface(Class clazz, const TypeInfo* interfaceInfo) {
    const ObjCTypeAdapter* protocolAdapter = getTypeAdapter(interfaceInfo);
    if (protocolAdapter != nullptr) {
        addProtocolForAdapter(clazz, protocolAdapter);
    }
}


ObjCClassBuilder::ObjCClassBuilder(const TypeInfo* typeInfo, Class cls) : typeInfo_(typeInfo), class_(cls) {
    RuntimeAssert(typeInfo->superType_ != nullptr, "");
    RuntimeAssert(!compiler::swiftExport(), "Not available with Swift Export");
}

Class ObjCClassBuilder::create(Class superClass) {
    RuntimeAssert(class_ == nullptr, "");
    kotlin::NativeOrUnregisteredThreadGuard threadStateGuard(/* reentrant = */ true);

    std::string className = nextClassName();

    class_ = objc_allocateClassPair(superClass, className.c_str(), 0);
    RuntimeCheck(class_ != nullptr, "");

    addOverrideAdapters();

    objc_registerClassPair(class_);

    // TODO: it will probably never be requested, since such a class can't be instantiated in Objective-C.
    setAssociatedTypeInfo(class_, typeInfo_);

    return class_;
}

std::string ObjCClassBuilder::nextClassName() {
    int classIndex = (anonymousClassNextId++);
    std::string className = Kotlin_ObjCInterop_getUniquePrefix();
    className += "_kobjcc";
    className += std::to_string(classIndex);
    return className;
}

void ObjCClassBuilder::addOverrideAdapters() {
    // TODO: optimize by adding virtual adapters only for overridden methods.

    if (getTypeAdapter(typeInfo_->superType_) == nullptr) {
        // class for super type is also synthesized, no need to add class adapters;
    } else {
        for (const TypeInfo* superType = typeInfo_->superType_; superType != nullptr; superType = superType->superType_) {
            const ObjCTypeAdapter* typeAdapter = getTypeAdapter(superType);
            if (typeAdapter != nullptr) {
                addVirtualAdapters(typeAdapter);
            }
        }
    }

    std::unordered_set<const TypeInfo*> superImplementedInterfaces = toSet(implementedInterfaces(typeInfo_->superType_));

    for (auto interface : implementedInterfaces(typeInfo_)) {
        if (auto typeAdapter = getTypeAdapter(interface)) {
            // Note: we could avoid adding virtual adapters if inherited from super type,
            // but what's the point?
            addVirtualAdapters(typeAdapter);
            if (!contains(superImplementedInterfaces, interface)) {
                addProtocolForAdapter(typeAdapter);
            }
        }
    }
}

void ObjCClassBuilder::addVirtualAdapters(const ObjCTypeAdapter* typeAdapter) {
    for (auto& adapter : virtualAdapters(typeAdapter)) {
        addMethodAdapter(&adapter);
    }
}

void ObjCClassBuilder::addMethodAdapter(const ObjCToKotlinMethodAdapter* adapter) {
    SEL selector = sel_registerName(adapter->selector);
    class_addMethod(class_, selector, adapter->imp, adapter->encoding);
}

void ObjCClassBuilder::addProtocolForAdapter(const ObjCTypeAdapter* protocolAdapter) {
    Protocol* protocol = objc_getProtocol(protocolAdapter->objCName);
    if (protocol != nullptr) {
        class_addProtocol(class_, protocol);
        class_addProtocol(object_getClass(class_), protocol);
    } else {
        // TODO: construct the protocol in compiler instead, because this case can't be handled easily.
    }
}

#endif // KONAN_OBJC_INTEROP
