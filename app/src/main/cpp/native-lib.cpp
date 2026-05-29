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

double pointToSegmentDistance(double pLat, double pLon,
                              double aLat, double aLon,
                              double bLat, double bLon) {

    const double avgLat = degToRad((aLat + pLat + bLat) / 3.0);
    const double cosAvgLat = std::cos(avgLat);

    const double px = degToRad(pLon - aLon) * cosAvgLat * EARTH_RADIUS_METERS;
    const double py = degToRad(pLat - aLat) * EARTH_RADIUS_METERS;

    const double bx = degToRad(bLon - aLon) * cosAvgLat * EARTH_RADIUS_METERS;
    const double by = degToRad(bLat - aLat) * EARTH_RADIUS_METERS;

    const double v_len_sq = bx*bx + by*by;
    if (v_len_sq < 0.1) return haversineDistance(pLat, pLon, aLat, aLon);

    double t = (px * bx + py * by) / v_len_sq;

    if (t < 0.0) return std::sqrt(px*px + py*py);
    if (t > 1.0) return std::sqrt((px-bx)*(px-bx) + (py-by)*(py-by));

    const double dx = px - (t * bx);
    const double dy = py - (t * by);
    return std::sqrt(dx*dx + dy*dy);
}

// Core Logic Helpers
int core_findNearestStopIndex(JNIEnv *env, jdouble currentLat, jdouble currentLon,
                            jdoubleArray stopLats, jdoubleArray stopLons, jint previousIndex) {
    const jsize count = env->GetArrayLength(stopLats);
    if (count == 0) return -1;
    jdouble *lats = env->GetDoubleArrayElements(stopLats, nullptr);
    jdouble *lons = env->GetDoubleArrayElements(stopLons, nullptr);

    int resultIndex = previousIndex;

    if (previousIndex < 0) {
        // Initial search: First 10 stops prioritization
        int searchLimit = std::min((int)count, 10);
        double minDistance = -1.0;
        int bestIdx = 0;
        for (int i = 0; i < searchLimit; ++i) {
            double d = haversineDistance(currentLat, currentLon, lats[i], lons[i]);
            if (minDistance < 0.0 || d < minDistance) {
                minDistance = d;
                bestIdx = i;
            }
        }

        if (minDistance > 800.0) {
            for (int i = searchLimit; i < (int)count; ++i) {
                double d = haversineDistance(currentLat, currentLon, lats[i], lons[i]);
                if (minDistance < 0.0 || d < minDistance) {
                    minDistance = d;
                    bestIdx = i;
                }
            }
        }
        resultIndex = bestIdx;
    }
    else {
        // Strict forward tracking
        int maxSearch = std::min((int)count - 1, previousIndex + 4);
        for (int i = previousIndex + 1; i <= maxSearch; ++i) {
            double d = haversineDistance(currentLat, currentLon, lats[i], lons[i]);
            if (d <= 35.0) {
                resultIndex = i;
                break;
            }
        }
    }

    env->ReleaseDoubleArrayElements(stopLats, lats, JNI_ABORT);
    env->ReleaseDoubleArrayElements(stopLons, lons, JNI_ABORT);
    return resultIndex;
}

double core_calculatePolylineDeviation(JNIEnv* env, jdouble currentLat, jdouble currentLon,
                                     jdoubleArray polylineLats, jdoubleArray polylineLons, jint previousIndex) {
    const jsize count = env->GetArrayLength(polylineLats);
    if (count < 2) return -1.0;
    jdouble* lats = env->GetDoubleArrayElements(polylineLats, nullptr);
    jdouble* lons = env->GetDoubleArrayElements(polylineLons, nullptr);
    double minDeviation = -1.0;
    int start = std::max(0, previousIndex - 1);
    int end = std::min((int)count - 2, previousIndex + 4);
    for (int i = start; i <= end; ++i) {
        double dev = pointToSegmentDistance(currentLat, currentLon, lats[i], lons[i], lats[i+1], lons[i+1]);
        if (minDeviation < 0.0 || dev < minDeviation) minDeviation = dev;
    }
    env->ReleaseDoubleArrayElements(polylineLats, lats, JNI_ABORT);
    env->ReleaseDoubleArrayElements(polylineLons, lons, JNI_ABORT);
    return minDeviation;
}

} // namespace

extern "C" {

JNIEXPORT jdouble JNICALL Java_com_example_yolarkadasim_MainActivity_calculateDistance(JNIEnv*, jobject, jdouble lat1, jdouble lon1, jdouble lat2, jdouble lon2) { return haversineDistance(lat1, lon1, lat2, lon2); }
JNIEXPORT jdouble JNICALL Java_com_example_yolarkadasim_service_TrackingService_calculateDistance(JNIEnv*, jobject, jdouble lat1, jdouble lon1, jdouble lat2, jdouble lon2) { return haversineDistance(lat1, lon1, lat2, lon2); }

JNIEXPORT jint JNICALL Java_com_example_yolarkadasim_MainActivity_findNearestStopIndex(JNIEnv *env, jobject, jdouble lat, jdouble lon, jdoubleArray lats, jdoubleArray lons, jint prev) { return core_findNearestStopIndex(env, lat, lon, lats, lons, prev); }
JNIEXPORT jint JNICALL Java_com_example_yolarkadasim_service_TrackingService_findNearestStopIndex(JNIEnv *env, jobject, jdouble lat, jdouble lon, jdoubleArray lats, jdoubleArray lons, jint prev) { return core_findNearestStopIndex(env, lat, lon, lats, lons, prev); }

JNIEXPORT jdouble JNICALL Java_com_example_yolarkadasim_MainActivity_calculatePolylineDeviation(JNIEnv* env, jobject, jdouble lat, jdouble lon, jdoubleArray lats, jdoubleArray lons, jint prev) { return core_calculatePolylineDeviation(env, lat, lon, lats, lons, prev); }
JNIEXPORT jdouble JNICALL Java_com_example_yolarkadasim_service_TrackingService_calculatePolylineDeviation(JNIEnv* env, jobject, jdouble lat, jdouble lon, jdoubleArray lats, jdoubleArray lons, jint prev) { return core_calculatePolylineDeviation(env, lat, lon, lats, lons, prev); }

JNIEXPORT jint JNICALL Java_com_example_yolarkadasim_MainActivity_checkRouteDirection(JNIEnv*, jobject, jint cur, jint prev, jint dest) { if (prev < 0 || cur == prev) return -1; return (std::abs(dest - cur) < std::abs(dest - prev)) ? 0 : 1; }
JNIEXPORT jint JNICALL Java_com_example_yolarkadasim_service_TrackingService_checkRouteDirection(JNIEnv*, jobject, jint cur, jint prev, jint dest) { if (prev < 0 || cur == prev) return -1; return (std::abs(dest - cur) < std::abs(dest - prev)) ? 0 : 1; }

JNIEXPORT jint JNICALL Java_com_example_yolarkadasim_MainActivity_checkDirection(JNIEnv*, jobject, jdouble curLat, jdouble curLon, jdouble tarLat, jdouble tarLon, jdouble prevDist) { double curDist = haversineDistance(curLat, curLon, tarLat, tarLon); return (prevDist < 0.0) ? -1 : (curDist > prevDist ? 1 : 0); }

// Engine Bindings
JNIEXPORT void JNICALL Java_com_example_yolarkadasim_MainActivity_initNavigationEngine(JNIEnv* env, jobject, jstring storagePath, jboolean enableLogging) {
    const char* pathStr = env->GetStringUTFChars(storagePath, nullptr);
    g_logger = std::make_unique<Logger>(std::string(pathStr), enableLogging);
    env->ReleaseStringUTFChars(storagePath, pathStr);
    g_engine_initialized = true;
}
JNIEXPORT void JNICALL Java_com_example_yolarkadasim_service_TrackingService_initNavigationEngine(JNIEnv* env, jobject, jstring storagePath, jboolean enableLogging) {
    const char* pathStr = env->GetStringUTFChars(storagePath, nullptr);
    if (pathStr) {
        g_logger = std::make_unique<Logger>(std::string(pathStr), enableLogging);
        env->ReleaseStringUTFChars(storagePath, pathStr);
    }
    g_engine_initialized = true;
}

JNIEXPORT void JNICALL Java_com_example_yolarkadasim_MainActivity_setEngineRoute(JNIEnv* env, jobject, jdoubleArray lats, jdoubleArray lons) {
    const jsize count = env->GetArrayLength(lats);
    jdouble* latData = env->GetDoubleArrayElements(lats, nullptr);
    jdouble* lonData = env->GetDoubleArrayElements(lons, nullptr);
    std::vector<double> vLats(latData, latData + count);
    std::vector<double> vLons(lonData, lonData + count);
    g_fsm.setRoute(vLats, vLons);
    env->ReleaseDoubleArrayElements(lats, latData, JNI_ABORT);
    env->ReleaseDoubleArrayElements(lons, lonData, JNI_ABORT);
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

JNIEXPORT void JNICALL Java_com_example_yolarkadasim_MainActivity_processEngineLocation(JNIEnv* env, jobject, jdouble lat, jdouble lon, jfloat speed, jfloat bearing, jfloat accuracy, jfloat accelX, jfloat accelY, jfloat accelZ, jlong timestamp) {
    if (g_logger) g_logger->logLocation(lat, lon, speed, bearing, accuracy, accelX, accelY, accelZ, timestamp);
    g_kalman.update(lat, lon, speed, bearing, accuracy, accelX, accelY, accelZ, timestamp);
    g_fsm.updateLocation(g_kalman.getLat(), g_kalman.getLon());
}
JNIEXPORT void JNICALL Java_com_example_yolarkadasim_service_TrackingService_processEngineLocation(JNIEnv* env, jobject, jdouble lat, jdouble lon, jfloat speed, jfloat bearing, jfloat accuracy, jfloat accelX, jfloat accelY, jfloat accelZ, jlong timestamp) {
    if (g_logger) g_logger->logLocation(lat, lon, speed, bearing, accuracy, accelX, accelY, accelZ, timestamp);
    g_kalman.update(lat, lon, speed, bearing, accuracy, accelX, accelY, accelZ, timestamp);
    g_fsm.updateLocation(g_kalman.getLat(), g_kalman.getLon());
}

JNIEXPORT jint JNICALL Java_com_example_yolarkadasim_MainActivity_getEngineActiveStopIndex(JNIEnv*, jobject) { return g_fsm.getActiveStopIndex(); }
JNIEXPORT jint JNICALL Java_com_example_yolarkadasim_service_TrackingService_getEngineActiveStopIndex(JNIEnv*, jobject) { return g_fsm.getActiveStopIndex(); }

JNIEXPORT jint JNICALL Java_com_example_yolarkadasim_MainActivity_getEngineStopState(JNIEnv*, jobject, jint index) { return static_cast<jint>(g_fsm.getStopState(index)); }
JNIEXPORT jint JNICALL Java_com_example_yolarkadasim_service_TrackingService_getEngineStopState(JNIEnv*, jobject, jint index) { return static_cast<jint>(g_fsm.getStopState(index)); }

JNIEXPORT jdouble JNICALL Java_com_example_yolarkadasim_MainActivity_getEngineSmoothedLat(JNIEnv*, jobject) { return g_kalman.getLat(); }
JNIEXPORT jdouble JNICALL Java_com_example_yolarkadasim_service_TrackingService_getEngineSmoothedLat(JNIEnv*, jobject) { return g_kalman.getLat(); }

JNIEXPORT jdouble JNICALL Java_com_example_yolarkadasim_MainActivity_getEngineSmoothedLon(JNIEnv*, jobject) { return g_kalman.getLon(); }
JNIEXPORT jdouble JNICALL Java_com_example_yolarkadasim_service_TrackingService_getEngineSmoothedLon(JNIEnv*, jobject) { return g_kalman.getLon(); }
JNIEXPORT jdouble JNICALL Java_com_example_yolarkadasim_MainActivity_getEngineDeviation(JNIEnv*, jobject) { return g_fsm.getCrossTrackError(); }
JNIEXPORT jdouble JNICALL Java_com_example_yolarkadasim_service_TrackingService_getEngineDeviation(JNIEnv*, jobject) { return g_fsm.getCrossTrackError(); }

} // extern "C"
