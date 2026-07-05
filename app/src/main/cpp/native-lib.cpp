#include <jni.h>
#include <cmath>
#include <algorithm>
#include <memory>
#include <string>
#include <vector>
#include "Logger.h"
#include "KalmanFilter2D.h"
#include "NavigationFSM.h"

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

namespace {

constexpr double EARTH_RADIUS_METERS = 6371000.0;

std::unique_ptr<Logger> g_logger;
KalmanFilter2D g_kalman;
NavigationFSM g_fsm;
bool g_engine_initialized = false;

inline double degToRad(double degrees) {
    return degrees * (M_PI / 180.0);
}

double haversineDistance(double lat1, double lon1, double lat2, double lon2) {
    const double rLat1 = degToRad(lat1);
    const double rLon1 = degToRad(lon1);
    const double rLat2 = degToRad(lat2);
    const double rLon2 = degToRad(lon2);

    double val = std::sin(rLat1) * std::sin(rLat2) + std::cos(rLat1) * std::cos(rLat2) * std::cos(rLon2 - rLon1);
    val = std::max(-1.0, std::min(1.0, val));
    return EARTH_RADIUS_METERS * std::acos(val);
}

} // namespace

// Single set of JNI bindings for TrackingService (the only native caller).
extern "C" {

JNIEXPORT jdouble JNICALL Java_com_example_yolarkadasim_service_TrackingService_calculateDistance(JNIEnv*, jobject, jdouble lat1, jdouble lon1, jdouble lat2, jdouble lon2) { return haversineDistance(lat1, lon1, lat2, lon2); }

JNIEXPORT jint JNICALL Java_com_example_yolarkadasim_service_TrackingService_checkRouteDirection(JNIEnv*, jobject, jint cur, jint prev, jint dest) { if (prev < 0 || cur == prev) return -1; return (std::abs(dest - cur) < std::abs(dest - prev)) ? 0 : 1; }

JNIEXPORT void JNICALL Java_com_example_yolarkadasim_service_TrackingService_initNavigationEngine(JNIEnv* env, jobject, jstring storagePath, jboolean enableLogging) {
    const char* pathStr = env->GetStringUTFChars(storagePath, nullptr);
    if (pathStr) {
        g_logger = std::make_unique<Logger>(std::string(pathStr), enableLogging);
        env->ReleaseStringUTFChars(storagePath, pathStr);
    }
    g_engine_initialized = true;
}

// Clears filter and FSM state so a new trip does not inherit the previous
// trip's position/timestamp (Kalman) or stop states (FSM).
JNIEXPORT void JNICALL Java_com_example_yolarkadasim_service_TrackingService_resetEngine(JNIEnv*, jobject) {
    g_kalman.reset(0.0, 0.0, 0);
    g_fsm.setRoute({}, {});
}

JNIEXPORT void JNICALL Java_com_example_yolarkadasim_service_TrackingService_setEngineRoute(JNIEnv* env, jobject, jdoubleArray lats, jdoubleArray lons) {
    const jsize count = env->GetArrayLength(lats);
    jdouble* latData = env->GetDoubleArrayElements(lats, nullptr);
    jdouble* lonData = env->GetDoubleArrayElements(lons, nullptr);
    std::vector<double> vLats(latData, latData + count);
    std::vector<double> vLons(lonData, lonData + count);
    g_fsm.setRoute(vLats, vLons);
    env->ReleaseDoubleArrayElements(lats, latData, JNI_ABORT);
    env->ReleaseDoubleArrayElements(lons, lonData, JNI_ABORT);
}

JNIEXPORT void JNICALL Java_com_example_yolarkadasim_service_TrackingService_processEngineLocation(JNIEnv* env, jobject, jdouble lat, jdouble lon, jfloat speed, jfloat bearing, jfloat accuracy, jfloat accelX, jfloat accelY, jfloat accelZ, jlong timestamp) {
    if (g_logger) g_logger->logLocation(lat, lon, speed, bearing, accuracy, accelX, accelY, accelZ, timestamp);
    g_kalman.update(lat, lon, speed, bearing, accuracy, accelX, accelY, accelZ, timestamp);
    g_fsm.updateLocation(g_kalman.getLat(), g_kalman.getLon());
}

JNIEXPORT jint JNICALL Java_com_example_yolarkadasim_service_TrackingService_getEngineActiveStopIndex(JNIEnv*, jobject) { return g_fsm.getActiveStopIndex(); }

JNIEXPORT jint JNICALL Java_com_example_yolarkadasim_service_TrackingService_getEngineStopState(JNIEnv*, jobject, jint index) { return static_cast<jint>(g_fsm.getStopState(index)); }

JNIEXPORT jdouble JNICALL Java_com_example_yolarkadasim_service_TrackingService_getEngineSmoothedLat(JNIEnv*, jobject) { return g_kalman.getLat(); }

JNIEXPORT jdouble JNICALL Java_com_example_yolarkadasim_service_TrackingService_getEngineSmoothedLon(JNIEnv*, jobject) { return g_kalman.getLon(); }

JNIEXPORT jdouble JNICALL Java_com_example_yolarkadasim_service_TrackingService_getEngineDeviation(JNIEnv*, jobject) { return g_fsm.getCrossTrackError(); }

} // extern "C"
