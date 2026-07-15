#include <Geode/Geode.hpp>
#include <Geode/loader/SettingV3.hpp>
#include <Geode/modify/MenuLayer.hpp>

#include <cmath>
#include <cstdint>
#include <limits>
#include <string>

#ifdef GEODE_IS_ANDROID
#include <jni.h>
#include <Geode/cocos/platform/android/jni/JniHelper.h>
#endif

using namespace geode::prelude;

namespace {
    struct RefreshResult {
        bool requested = false;
        bool exactModeFound = false;
        float requestedHz = 0.f;
        float maxExposedHz = 0.f;
        int exactModeId = 0;
        std::string error;
    };

    bool g_shownResult = false;

#ifdef GEODE_IS_ANDROID
    bool clearJniException(JNIEnv* env, char const* where) {
        if (!env || !env->ExceptionCheck()) {
            return false;
        }

        env->ExceptionDescribe();
        env->ExceptionClear();
        log::warn("Excepción JNI en {}", where);
        return true;
    }

    JNIEnv* getJniEnv() {
        auto vm = cocos2d::JniHelper::getJavaVM();
        if (!vm) {
            return nullptr;
        }

        JNIEnv* env = nullptr;
        auto status = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
        return status == JNI_OK ? env : nullptr;
    }

    jobject getCocosSurfaceView(JNIEnv* env) {
        auto viewClass = env->FindClass("org/cocos2dx/lib/Cocos2dxGLSurfaceView");
        if (!viewClass || clearJniException(env, "FindClass Cocos2dxGLSurfaceView")) {
            return nullptr;
        }

        auto companionField = env->GetStaticFieldID(
            viewClass,
            "Companion",
            "Lorg/cocos2dx/lib/Cocos2dxGLSurfaceView$Companion;"
        );
        if (!companionField || clearJniException(env, "GetStaticFieldID Companion")) {
            env->DeleteLocalRef(viewClass);
            return nullptr;
        }

        auto companion = env->GetStaticObjectField(viewClass, companionField);
        if (!companion || clearJniException(env, "GetStaticObjectField Companion")) {
            env->DeleteLocalRef(viewClass);
            return nullptr;
        }

        auto companionClass = env->GetObjectClass(companion);
        auto getter = env->GetMethodID(
            companionClass,
            "getCocos2dxGLSurfaceView",
            "()Lorg/cocos2dx/lib/Cocos2dxGLSurfaceView;"
        );

        if (!getter || clearJniException(env, "GetMethodID getCocos2dxGLSurfaceView")) {
            env->DeleteLocalRef(companionClass);
            env->DeleteLocalRef(companion);
            env->DeleteLocalRef(viewClass);
            return nullptr;
        }

        auto view = env->CallObjectMethod(companion, getter);
        if (clearJniException(env, "CallObjectMethod getCocos2dxGLSurfaceView")) {
            view = nullptr;
        }

        env->DeleteLocalRef(companionClass);
        env->DeleteLocalRef(companion);
        env->DeleteLocalRef(viewClass);
        return view;
    }

    bool setWindowPreference(
        JNIEnv* env,
        jobject view,
        float targetHz,
        int exactModeId,
        bool useExactMode
    ) {
        auto viewClass = env->GetObjectClass(view);
        auto getContext = env->GetMethodID(
            viewClass,
            "getContext",
            "()Landroid/content/Context;"
        );

        if (!getContext || clearJniException(env, "View.getContext")) {
            env->DeleteLocalRef(viewClass);
            return false;
        }

        auto context = env->CallObjectMethod(view, getContext);
        if (!context || clearJniException(env, "Call View.getContext")) {
            env->DeleteLocalRef(viewClass);
            return false;
        }

        auto contextClass = env->GetObjectClass(context);
        auto getWindow = env->GetMethodID(
            contextClass,
            "getWindow",
            "()Landroid/view/Window;"
        );

        if (!getWindow || clearJniException(env, "Activity.getWindow")) {
            env->DeleteLocalRef(contextClass);
            env->DeleteLocalRef(context);
            env->DeleteLocalRef(viewClass);
            return false;
        }

        auto window = env->CallObjectMethod(context, getWindow);
        if (!window || clearJniException(env, "Call Activity.getWindow")) {
            env->DeleteLocalRef(contextClass);
            env->DeleteLocalRef(context);
            env->DeleteLocalRef(viewClass);
            return false;
        }

        auto windowClass = env->GetObjectClass(window);
        auto getAttributes = env->GetMethodID(
            windowClass,
            "getAttributes",
            "()Landroid/view/WindowManager$LayoutParams;"
        );
        auto setAttributes = env->GetMethodID(
            windowClass,
            "setAttributes",
            "(Landroid/view/WindowManager$LayoutParams;)V"
        );

        if (!getAttributes || !setAttributes ||
            clearJniException(env, "Window attributes methods")) {
            env->DeleteLocalRef(windowClass);
            env->DeleteLocalRef(window);
            env->DeleteLocalRef(contextClass);
            env->DeleteLocalRef(context);
            env->DeleteLocalRef(viewClass);
            return false;
        }

        auto attrs = env->CallObjectMethod(window, getAttributes);
        if (!attrs || clearJniException(env, "Window.getAttributes")) {
            env->DeleteLocalRef(windowClass);
            env->DeleteLocalRef(window);
            env->DeleteLocalRef(contextClass);
            env->DeleteLocalRef(context);
            env->DeleteLocalRef(viewClass);
            return false;
        }

        auto attrsClass = env->GetObjectClass(attrs);
        auto refreshField = env->GetFieldID(
            attrsClass,
            "preferredRefreshRate",
            "F"
        );
        auto modeField = env->GetFieldID(
            attrsClass,
            "preferredDisplayModeId",
            "I"
        );

        if (refreshField) {
            env->SetFloatField(attrs, refreshField, targetHz);
        } else {
            clearJniException(env, "preferredRefreshRate");
        }

        if (modeField) {
            env->SetIntField(
                attrs,
                modeField,
                useExactMode ? exactModeId : 0
            );
        } else {
            clearJniException(env, "preferredDisplayModeId");
        }

        env->CallVoidMethod(window, setAttributes, attrs);
        auto failed = clearJniException(env, "Window.setAttributes");

        env->DeleteLocalRef(attrsClass);
        env->DeleteLocalRef(attrs);
        env->DeleteLocalRef(windowClass);
        env->DeleteLocalRef(window);
        env->DeleteLocalRef(contextClass);
        env->DeleteLocalRef(context);
        env->DeleteLocalRef(viewClass);
        return !failed;
    }

    bool setSurfaceFrameRate(JNIEnv* env, jobject view, float targetHz) {
        auto viewClass = env->GetObjectClass(view);
        auto getHolder = env->GetMethodID(
            viewClass,
            "getHolder",
            "()Landroid/view/SurfaceHolder;"
        );

        if (!getHolder || clearJniException(env, "SurfaceView.getHolder")) {
            env->DeleteLocalRef(viewClass);
            return false;
        }

        auto holder = env->CallObjectMethod(view, getHolder);
        if (!holder || clearJniException(env, "Call SurfaceView.getHolder")) {
            env->DeleteLocalRef(viewClass);
            return false;
        }

        auto holderClass = env->GetObjectClass(holder);
        auto getSurface = env->GetMethodID(
            holderClass,
            "getSurface",
            "()Landroid/view/Surface;"
        );

        if (!getSurface || clearJniException(env, "SurfaceHolder.getSurface")) {
            env->DeleteLocalRef(holderClass);
            env->DeleteLocalRef(holder);
            env->DeleteLocalRef(viewClass);
            return false;
        }

        auto surface = env->CallObjectMethod(holder, getSurface);
        if (!surface || clearJniException(env, "Call SurfaceHolder.getSurface")) {
            env->DeleteLocalRef(holderClass);
            env->DeleteLocalRef(holder);
            env->DeleteLocalRef(viewClass);
            return false;
        }

        auto surfaceClass = env->GetObjectClass(surface);
        bool success = false;

        // API 31+: setFrameRate(frameRate, compatibility, changeStrategy)
        auto setFrameRate3 = env->GetMethodID(
            surfaceClass,
            "setFrameRate",
            "(FII)V"
        );

        if (setFrameRate3 && !env->ExceptionCheck()) {
            constexpr jint FRAME_RATE_COMPATIBILITY_DEFAULT = 0;
            constexpr jint CHANGE_FRAME_RATE_ALWAYS = 1;
            env->CallVoidMethod(
                surface,
                setFrameRate3,
                static_cast<jfloat>(targetHz),
                FRAME_RATE_COMPATIBILITY_DEFAULT,
                CHANGE_FRAME_RATE_ALWAYS
            );
            success = !clearJniException(env, "Surface.setFrameRate(FII)");
        } else {
            clearJniException(env, "Buscar Surface.setFrameRate(FII)");

            // API 30: setFrameRate(frameRate, compatibility)
            auto setFrameRate2 = env->GetMethodID(
                surfaceClass,
                "setFrameRate",
                "(FI)V"
            );
            if (setFrameRate2 && !env->ExceptionCheck()) {
                constexpr jint FRAME_RATE_COMPATIBILITY_DEFAULT = 0;
                env->CallVoidMethod(
                    surface,
                    setFrameRate2,
                    static_cast<jfloat>(targetHz),
                    FRAME_RATE_COMPATIBILITY_DEFAULT
                );
                success = !clearJniException(env, "Surface.setFrameRate(FI)");
            } else {
                clearJniException(env, "Buscar Surface.setFrameRate(FI)");
            }
        }

        env->DeleteLocalRef(surfaceClass);
        env->DeleteLocalRef(surface);
        env->DeleteLocalRef(holderClass);
        env->DeleteLocalRef(holder);
        env->DeleteLocalRef(viewClass);
        return success;
    }

    RefreshResult requestHighRefresh() {
        RefreshResult result;
        result.requestedHz = static_cast<float>(
            Mod::get()->getSettingValue<int64_t>("target-hz")
        );

        auto env = getJniEnv();
        if (!env) {
            result.error = "No se pudo obtener JNIEnv";
            return result;
        }

        auto view = getCocosSurfaceView(env);
        if (!view) {
            result.error = "No se encontró el GLSurfaceView de Geode";
            return result;
        }

        auto viewClass = env->GetObjectClass(view);
        auto getDisplay = env->GetMethodID(
            viewClass,
            "getDisplay",
            "()Landroid/view/Display;"
        );

        if (!getDisplay || clearJniException(env, "View.getDisplay")) {
            result.error = "No se pudo acceder a la pantalla";
            env->DeleteLocalRef(viewClass);
            env->DeleteLocalRef(view);
            return result;
        }

        auto display = env->CallObjectMethod(view, getDisplay);
        if (!display || clearJniException(env, "Call View.getDisplay")) {
            result.error = "La vista todavía no está conectada a una pantalla";
            env->DeleteLocalRef(viewClass);
            env->DeleteLocalRef(view);
            return result;
        }

        auto displayClass = env->GetObjectClass(display);
        auto getModes = env->GetMethodID(
            displayClass,
            "getSupportedModes",
            "()[Landroid/view/Display$Mode;"
        );

        jobjectArray modes = nullptr;
        if (getModes && !env->ExceptionCheck()) {
            modes = static_cast<jobjectArray>(
                env->CallObjectMethod(display, getModes)
            );
            clearJniException(env, "Display.getSupportedModes");
        } else {
            clearJniException(env, "Buscar Display.getSupportedModes");
        }

        float closestDifference = std::numeric_limits<float>::max();
        int closestModeId = 0;

        if (modes) {
            auto modeClass = env->FindClass("android/view/Display$Mode");
            auto getRefreshRate = modeClass
                ? env->GetMethodID(modeClass, "getRefreshRate", "()F")
                : nullptr;
            auto getModeId = modeClass
                ? env->GetMethodID(modeClass, "getModeId", "()I")
                : nullptr;

            if (modeClass && getRefreshRate && getModeId &&
                !clearJniException(env, "Métodos Display.Mode")) {
                const auto count = env->GetArrayLength(modes);

                for (jsize i = 0; i < count; ++i) {
                    auto mode = env->GetObjectArrayElement(modes, i);
                    if (!mode) {
                        continue;
                    }

                    const auto rate = env->CallFloatMethod(mode, getRefreshRate);
                    const auto modeId = env->CallIntMethod(mode, getModeId);

                    result.maxExposedHz = std::max(result.maxExposedHz, rate);

                    const auto difference = std::fabs(rate - result.requestedHz);
                    if (difference < closestDifference) {
                        closestDifference = difference;
                        closestModeId = modeId;
                    }

                    log::info(
                        "Modo Android expuesto: id={} @ {:.3f} Hz",
                        modeId,
                        rate
                    );
                    env->DeleteLocalRef(mode);
                }

                // Acepta pequeñas variaciones como 143.999 Hz.
                if (closestDifference <= 1.0f) {
                    result.exactModeFound = true;
                    result.exactModeId = closestModeId;
                }
            } else {
                clearJniException(env, "Leer modos de pantalla");
            }

            if (modeClass) {
                env->DeleteLocalRef(modeClass);
            }
            env->DeleteLocalRef(modes);
        }

        const bool exactModeEnabled =
            Mod::get()->getSettingValue<bool>("exact-mode") &&
            result.exactModeFound;

        const bool windowUpdated = setWindowPreference(
            env,
            view,
            result.requestedHz,
            result.exactModeId,
            exactModeEnabled
        );
        const bool surfaceUpdated = setSurfaceFrameRate(
            env,
            view,
            result.requestedHz
        );

        result.requested = windowUpdated || surfaceUpdated;

        log::info(
            "Solicitud de refresco: objetivo={:.3f} Hz, máximo expuesto={:.3f} Hz, "
            "modeId exacto={}, ventana={}, superficie={}",
            result.requestedHz,
            result.maxExposedHz,
            result.exactModeFound ? result.exactModeId : 0,
            windowUpdated,
            surfaceUpdated
        );

        env->DeleteLocalRef(displayClass);
        env->DeleteLocalRef(display);
        env->DeleteLocalRef(viewClass);
        env->DeleteLocalRef(view);
        return result;
    }
#else
    RefreshResult requestHighRefresh() {
        RefreshResult result;
        result.error = "Este mod solo funciona en Android";
        return result;
    }
#endif

    void showResultOnce(RefreshResult const& result) {
        if (g_shownResult ||
            !Mod::get()->getSettingValue<bool>("show-result")) {
            return;
        }

        g_shownResult = true;

        std::string message;
        if (!result.error.empty()) {
            message = fmt::format(
                "<cr>Error:</c> {}\n\nRevisa el registro de Geode.",
                result.error
            );
        } else if (result.exactModeFound) {
            message = fmt::format(
                "<cg>Solicitud enviada.</c>\n\n"
                "Objetivo: <cy>{:.2f} Hz</c>\n"
                "Máximo expuesto: <cy>{:.2f} Hz</c>\n"
                "modeId exacto: <cy>{}</c>\n\n"
                "Comprueba la frecuencia real con la opción de desarrollador "
                "\"Mostrar frecuencia de actualización\".",
                result.requestedHz,
                result.maxExposedHz,
                result.exactModeId
            );
        } else {
            message = fmt::format(
                "<cy>Android no expuso un modo exacto de {:.2f} Hz.</c>\n\n"
                "Máximo expuesto a Geode: <cr>{:.2f} Hz</c>\n"
                "Aun así se solicitó {:.2f} Hz mediante Surface.setFrameRate.\n\n"
                "Si continúa en 120 Hz, el límite viene del sistema o del firmware, "
                "no del bucle de Geometry Dash.",
                result.requestedHz,
                result.maxExposedHz,
                result.requestedHz
            );
        }

        FLAlertLayer::create(
            "Force 144 Hz",
            message,
            "OK"
        )->show();
    }
}

$on_mod(Loaded) {
    listenForSettingChanges<int64_t>("target-hz", [](int64_t) {
        requestHighRefresh();
    });

    listenForSettingChanges<bool>("exact-mode", [](bool) {
        requestHighRefresh();
    });
}

class $modify(Force144HzMenuLayer, MenuLayer) {
    bool init() {
        if (!MenuLayer::init()) {
            return false;
        }

        auto result = requestHighRefresh();
        showResultOnce(result);
        return true;
    }
};
