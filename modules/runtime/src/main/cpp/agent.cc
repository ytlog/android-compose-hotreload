// JVMTI agent: the Android counterpart of 'java.lang.instrument.Instrumentation' used by the upstream agent.
// Structural redefinition is provided by ART DCEVM ('DcevmRuntime.initialize' must run before attaching).

#include <jni.h>
#include <jvmti.h>

#include <cstring>
#include <vector>

namespace {

jvmtiEnv* jvmti = nullptr;
jvmtiExtensionFunction add_to_dex_class_loader = nullptr;

constexpr const char* kAddToDexClassLoader = "com.android.art.classloader.add_to_dex_class_loader";

void DeallocateExtensionFunctions(jvmtiEnv* env, jint count, jvmtiExtensionFunctionInfo* functions) {
    for (jint i = 0; i < count; ++i) {
        auto& function = functions[i];
        for (jint p = 0; p < function.param_count; ++p) {
            env->Deallocate(reinterpret_cast<unsigned char*>(function.params[p].name));
        }
        env->Deallocate(reinterpret_cast<unsigned char*>(function.params));
        env->Deallocate(reinterpret_cast<unsigned char*>(function.errors));
        env->Deallocate(reinterpret_cast<unsigned char*>(function.id));
        env->Deallocate(reinterpret_cast<unsigned char*>(function.short_description));
    }
    env->Deallocate(reinterpret_cast<unsigned char*>(functions));
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM* vm, char*, void*) {
    if (jvmti != nullptr) return JNI_OK;

    jvmtiEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JVMTI_VERSION_1_2) != JNI_OK) return JNI_ERR;

    jvmtiCapabilities capabilities{};
    capabilities.can_redefine_classes = 1;
    if (env->AddCapabilities(&capabilities) != JVMTI_ERROR_NONE) {
        env->DisposeEnvironment();
        return JNI_ERR;
    }

    jint count = 0;
    jvmtiExtensionFunctionInfo* functions = nullptr;
    if (env->GetExtensionFunctions(&count, &functions) == JVMTI_ERROR_NONE) {
        for (jint i = 0; i < count; ++i) {
            if (std::strcmp(functions[i].id, kAddToDexClassLoader) == 0) add_to_dex_class_loader = functions[i].func;
        }
        DeallocateExtensionFunctions(env, count, functions);
    }

    jvmti = env;
    return JNI_OK;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_jetbrains_compose_reload_android_JvmtiAgent_nativeIsAttached(JNIEnv*, jclass) {
    return jvmti != nullptr ? JNI_TRUE : JNI_FALSE;
}

// Equivalent of 'Instrumentation.redefineClasses': one atomic batch
extern "C" JNIEXPORT jint JNICALL
Java_org_jetbrains_compose_reload_android_JvmtiAgent_nativeRedefineClasses(
    JNIEnv* jni, jclass, jobjectArray classes, jobjectArray definitions) {
    if (jvmti == nullptr) return JVMTI_ERROR_INVALID_ENVIRONMENT;
    if (classes == nullptr || definitions == nullptr) return JVMTI_ERROR_NULL_POINTER;

    const jint count = jni->GetArrayLength(classes);
    if (count != jni->GetArrayLength(definitions)) return JVMTI_ERROR_ILLEGAL_ARGUMENT;

    // Class references must stay valid until 'RedefineClasses' returns
    if (jni->PushLocalFrame(count + 16) != JNI_OK) return JVMTI_ERROR_OUT_OF_MEMORY;

    std::vector<std::vector<unsigned char>> buffers(count);
    std::vector<jvmtiClassDefinition> batch(count);
    jint result = JVMTI_ERROR_NONE;

    for (jint i = 0; i < count && result == JVMTI_ERROR_NONE; ++i) {
        auto type = static_cast<jclass>(jni->GetObjectArrayElement(classes, i));
        auto bytes = static_cast<jbyteArray>(jni->GetObjectArrayElement(definitions, i));
        if (type == nullptr || bytes == nullptr) {
            result = JVMTI_ERROR_NULL_POINTER;
            break;
        }
        const jint size = jni->GetArrayLength(bytes);
        buffers[i].resize(size);
        jni->GetByteArrayRegion(bytes, 0, size, reinterpret_cast<jbyte*>(buffers[i].data()));
        jni->DeleteLocalRef(bytes);
        if (jni->ExceptionCheck()) {
            result = JVMTI_ERROR_INTERNAL;
            break;
        }
        batch[i] = {type, size, buffers[i].data()};
    }

    if (result == JVMTI_ERROR_NONE) result = jvmti->RedefineClasses(count, batch.data());
    jni->PopLocalFrame(nullptr);
    return result;
}

// ART extension: appends a dex file (jar) to the given 'dalvik.system.BaseDexClassLoader'
extern "C" JNIEXPORT jint JNICALL
Java_org_jetbrains_compose_reload_android_JvmtiAgent_nativeAddToDexClassLoader(
    JNIEnv* jni, jclass, jobject loader, jstring path) {
    if (jvmti == nullptr) return JVMTI_ERROR_INVALID_ENVIRONMENT;
    if (add_to_dex_class_loader == nullptr) return JVMTI_ERROR_NOT_AVAILABLE;
    if (loader == nullptr || path == nullptr) return JVMTI_ERROR_NULL_POINTER;

    const char* chars = jni->GetStringUTFChars(path, nullptr);
    if (chars == nullptr) return JVMTI_ERROR_OUT_OF_MEMORY;
    const jint result = add_to_dex_class_loader(jvmti, loader, chars);
    jni->ReleaseStringUTFChars(path, chars);
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_jetbrains_compose_reload_android_JvmtiAgent_nativeErrorName(JNIEnv* jni, jclass, jint error) {
    if (jvmti == nullptr) return jni->NewStringUTF("JVMTI_UNAVAILABLE");
    char* name = nullptr;
    if (jvmti->GetErrorName(static_cast<jvmtiError>(error), &name) != JVMTI_ERROR_NONE || name == nullptr) {
        return jni->NewStringUTF("UNKNOWN_JVMTI_ERROR");
    }
    jstring result = jni->NewStringUTF(name);
    jvmti->Deallocate(reinterpret_cast<unsigned char*>(name));
    return result;
}
