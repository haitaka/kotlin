// TODO copyright
#pragma once

#if KONAN_OBJC_INTEROP

#import <objc/runtime.h>

#import "TypeInfoObjCExportAddition.hpp"
#import "Utils.hpp"

namespace kotlin {

class ObjCClassBuilder : Pinned {
public:
    explicit ObjCClassBuilder(const TypeInfo* typeInfo) : ObjCClassBuilder(typeInfo, nullptr) {}
    ObjCClassBuilder(const TypeInfo* typeInfo, Class cls);

    Class create(Class superClass);

    void addDirectAdapter(const ObjCTypeAdapter* typeAdapter);
    void addClassAdapters(const ObjCTypeAdapter* typeAdapter);

    void addProtocolForInterface(const TypeInfo* interfaceInfo);

private:
    const TypeInfo* const typeInfo_;
    Class class_ = nullptr;

    std::string nextClassName();
    void addOverrideAdapters();
    void addVirtualAdapters(const ObjCTypeAdapter* typeAdapter);
    void addMethodAdapter(const ObjCToKotlinMethodAdapter* adapter);
    void addProtocolForAdapter(const ObjCTypeAdapter* protocolAdapter);
};

}

#endif