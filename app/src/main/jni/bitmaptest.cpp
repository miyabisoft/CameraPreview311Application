//
// Created by miyabi on 2016/03/21.
//

#include <cassert>
#include <cmath>
#include <cstdio>

#include "bitmaptest.h"
#include "Unity/IUnityGraphics.h"

// --------------------------------------------------------------------------
// Include headers for the graphics APIs we support
#if SUPPORT_D3D9
#   include <d3d9.h>
#	include "Unity/IUnityGraphicsD3D9.h"
#endif
#if SUPPORT_D3D11
#	include <d3d11.h>
#	include "Unity/IUnityGraphicsD3D11.h"
#endif
#if SUPPORT_D3D12
#	include <d3d12.h>
#	include "Unity/IUnityGraphicsD3D12.h"
#endif

#if SUPPORT_OPENGL_LEGACY
#	include "GL/glew.h"
#endif

#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
//#include <malloc.h>

#ifdef __ANDROID__
#include <android/log.h>
#include <android/bitmap.h>
#include <GLES/gl.h>
#include <GLES2/gl2.h>
#include <GLES/glext.h>

#endif


#define  LOG_TAG    "libbitmapjni"
#define  LOGI(...)  __android_log_print(ANDROID_LOG_INFO,LOG_TAG,__VA_ARGS__)
#define  LOGE(...)  __android_log_print(ANDROID_LOG_ERROR,LOG_TAG,__VA_ARGS__)

static IUnityInterfaces* s_UnityInterfaces = NULL;
static IUnityGraphics* s_Graphics = NULL;
static UnityGfxRenderer s_RendererType = kUnityGfxRendererNull;

static void* g_TexturePointer = NULL;
static unsigned char *buffer;
static int nSize;
static int status = 0;
static int g_width;
static int g_height;

extern "C" {
void UNITY_INTERFACE_EXPORT UNITY_INTERFACE_API
        UnityPluginLoad(IUnityInterfaces *unityInterfaces);
void UNITY_INTERFACE_EXPORT UNITY_INTERFACE_API
        UnityPluginUnload();
static void UNITY_INTERFACE_API OnGraphicsDeviceEvent(UnityGfxDeviceEventType eventType);
UnityRenderingEvent UNITY_INTERFACE_EXPORT UNITY_INTERFACE_API GetRenderEventFunc();
static void UNITY_INTERFACE_API OnRenderEvent(int eventID);

void UNITY_INTERFACE_EXPORT UNITY_INTERFACE_API SetTextureFromUnity(void *texturePtr);
void setImadeDatatoUnity(unsigned char *buf);
int getWidth();
int getHeight();
int getCheckData();
void setCheckData(int val);
void createCheckTexture(unsigned char *arr, int w, int h, int ch);

#ifdef __ANDROID__
JNIEXPORT void JNICALL Java_miyabi_com_camerapreview311_MainRenderer_sendImageData(JNIEnv *env,
                                                                                   jobject obj,
                                                                                   jbyteArray array,
                                                                                   jint w, jint h,
                                                                                   jint ch);
JNIEXPORT void JNICALL Java_miyabi_com_camerapreview311_CameraPluginModule_setCheckdata(JNIEnv *env,
                                                                                        jobject obj,
                                                                                        jint data);
JNIEXPORT void JNICALL Java_miyabi_com_camerapreview311_MainRenderer_renderPlasma(JNIEnv *env,
                                                                                  jobject obj,
                                                                                  jobject bitmap,
                                                                                  jlong time_ms);
JNIEXPORT void JNICALL Java_miyabi_com_camerapreview311_MainRenderer_createCheckTexture1(
        JNIEnv *env, jobject obj, jbyteArray array, jint w, jint h, jint ch);
#endif
}

// Unity plugin load event
void UNITY_INTERFACE_EXPORT UNITY_INTERFACE_API
        UnityPluginLoad(IUnityInterfaces* unityInterfaces)
{
    s_UnityInterfaces = unityInterfaces;
    s_Graphics = unityInterfaces->Get<IUnityGraphics>();

    s_Graphics->RegisterDeviceEventCallback(OnGraphicsDeviceEvent);

    // Run OnGraphicsDeviceEvent(initialize) manually on plugin load
    // to not miss the event in case the graphics device is already initialized
    OnGraphicsDeviceEvent(kUnityGfxDeviceEventInitialize);
}

// Unity plugin unload event
void UNITY_INTERFACE_EXPORT UNITY_INTERFACE_API
        UnityPluginUnload()
{
    s_Graphics->UnregisterDeviceEventCallback(OnGraphicsDeviceEvent);
}

static void UNITY_INTERFACE_API
        OnGraphicsDeviceEvent(UnityGfxDeviceEventType eventType)
{
    switch (eventType)
    {
        case kUnityGfxDeviceEventInitialize:
        {
            s_RendererType = s_Graphics->GetRenderer();
            //TODO: user initialization code
            break;
        }
        case kUnityGfxDeviceEventShutdown:
        {
            s_RendererType = kUnityGfxRendererNull;
            //TODO: user shutdown code
            break;
        }
        case kUnityGfxDeviceEventBeforeReset:
        {
            //TODO: user Direct3D 9 code
            break;
        }
        case kUnityGfxDeviceEventAfterReset:
        {
            //TODO: user Direct3D 9 code
            break;
        }
    };
}

static void FillTextureFromCode (int width, int height, int stride, unsigned char* dst)
{
    for (int y = 0; y < height; ++y)
    {
        unsigned char* ptr = dst;
        for (int x = 0; x < width; ++x)
        {
            // Write the texture pixel
            ptr[0] = 255;
            ptr[1] = 255;
            ptr[2] = 0;
            ptr[3] = 255;

            // To next pixel (our pixels are 4 bpp)
            ptr += 4;
        }

        // To next image row
        dst += stride;
    }
}

// --------------------------------------------------------------------------
// GetRenderEventFunc, an example function we export which is used to get a rendering event callback function.
UnityRenderingEvent UNITY_INTERFACE_EXPORT UNITY_INTERFACE_API GetRenderEventFunc()
{
    return OnRenderEvent;
}

static int cunt = 0;
static void UNITY_INTERFACE_API OnRenderEvent(int eventID)
{
    if (status == 1) {
        if (g_TexturePointer) {
            GLuint textureId = (GLuint)(size_t)(g_TexturePointer);
#ifdef __ANDROID__
            __android_log_print(ANDROID_LOG_DEBUG,
                                "bitmaptest.cpp", "textureId : = %d", textureId);
#endif
            // GL_TEXTURE_2D GL_TEXTURE_EXTERNAL_OES
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, textureId);
            assert(glGetError() == GL_NO_ERROR);
            // glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, g_width, g_height, 0, GL_RGBA, GL_UNSIGNED_BYTE, buffer);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, g_width, g_height, GL_RGBA, GL_UNSIGNED_BYTE, buffer);
            assert(glGetError() == GL_NO_ERROR);

            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            assert(glGetError() == GL_NO_ERROR);
            delete[] buffer;
            status = 0;
#ifdef __ANDROID__
            __android_log_print(ANDROID_LOG_DEBUG,
                                "bitmaptest.cpp", "cunt : = %d", ++cunt);
#endif

        }
    }
}

void createCheckTexture(unsigned char* arr, int w, int h, int ch)
{
    // test data
    int n = 0;
    for (int i = 0; i < w; ++i) {
        for (int j = 0; j < h; ++j) {
            for (int k = 0; k < ch; ++k) {
                arr[n++] = ( (i + j) % 2 == 0 ) ? 255 : 0;
            }
        }
    }
}

static int chkdata = 100;
int getCheckData()
{
    return chkdata;
}
void setCheckData(int val)
{
    chkdata = val;
}


int getWidth() {
    return g_width;
}

int getHeight() {
    return g_height;
}

void UNITY_INTERFACE_EXPORT UNITY_INTERFACE_API SetTextureFromUnity(void* texturePtr)
{
#ifdef __ANDROID__
    __android_log_print(ANDROID_LOG_DEBUG, "bitmaptest.cpp",
                        "SetTextureFromUnity texturePtr = %d", texturePtr);
#endif
    g_TexturePointer = texturePtr;
}

// Unityのテスチャーにカメラ画像を流し込む
void setImadeDatatoUnity(unsigned char *buf)
{
    if (status == 1) {
#ifdef __ANDROID__
        __android_log_print(ANDROID_LOG_DEBUG, "bitmaptest.cpp",
                            "setImadeDatatoUnity nSize = %d", nSize);
#endif

        memcpy(buf, buffer, nSize);
        free(buffer);
        status = 0;
    }
}

#ifdef __ANDROID__
JNIEXPORT void JNICALL Java_miyabi_com_camerapreview311_CameraPluginModule_setCheckdata(JNIEnv * env, jobject  obj,  jint data)
{
    chkdata = data;
}


JNIEXPORT void JNICALL Java_miyabi_com_camerapreview311_MainRenderer_sendImageData(JNIEnv * env, jobject  obj, jbyteArray array,  jint w, jint h, jint ch)
{
    if (status == 0) {
        jboolean b;
        unsigned char *pCData = (unsigned char *) (env->GetByteArrayElements(array, &b));
        nSize = env->GetArrayLength(array);
#ifdef __ANDROID__
        __android_log_write(ANDROID_LOG_DEBUG, "bitmaptest.cpp", "ndk sendImageData5");
        __android_log_print(ANDROID_LOG_DEBUG, "bitmaptest.cpp",
                            "w = %d h = %d nSize =%d flag = %d", w, h, nSize, b);
#endif
        buffer = new unsigned char[nSize];
        memcpy(buffer, pCData, nSize);
/*
        //スケーリング後の画像のサイズ。
        //条件に合うように拡大する。
        float log_width  = log2(w);
        int newWidth  = (int)pow( 2, (int)log_width + 1 );
        float log_height  = log2(h);
        int newHeight  = (int)pow( 2, (int)log_height + 1 );
        //スケーリング後の画像を格納する配列
        buffer = new unsigned char[newWidth * newHeight * 4];
        //画像のスケーリング。
        //gluScaleImage(GL_RGBA, w, h, GL_UNSIGNED_BYTE, data, newWidth, newHeight, GL_UNSIGNED_BYTE,GL_UNSIGNED_BYTE);
*/
        g_width = w;
        g_height = h;
        status = 1;
    }
}

JNIEXPORT void JNICALL Java_miyabi_com_camerapreview311_MainRenderer_createCheckTexture1(JNIEnv * env, jobject  obj, jbyteArray array,  jint w, jint h, jint ch)
{
    int texWidth;
    int texHeight;
    int chno;

    int len = env->GetArrayLength (array);
    unsigned char* buf = new unsigned char[len];
    env->GetByteArrayRegion (array, 0, len, reinterpret_cast<jbyte*>(buf));
    texWidth = w;
    texHeight = h;
    chno = ch;
    createCheckTexture(buf, texWidth, texHeight, chno);
}

JNIEXPORT void JNICALL Java_miyabi_com_camerapreview311_MainRenderer_renderPlasma(JNIEnv * env, jobject  obj, jobject bitmap,  jlong  time_ms) {
    void *pixels;
    int ret = 0;

    if ((ret = AndroidBitmap_lockPixels(env, bitmap, &pixels)) < 0) {
        LOGE("AndroidBitmap_lockPixels() failed ! error=%d", ret);
    }

    AndroidBitmap_unlockPixels(env, bitmap);
}
#endif
