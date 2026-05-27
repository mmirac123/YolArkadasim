#include <jni.h>
#include <cmath>
#include <algorithm>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

namespace {

constexpr double EARTH_RADIUS_METERS = 6371000.0;

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

// Shared Core Implementation
int core_findNearestStopIndex(JNIEnv *env, jdouble currentLat, jdouble currentLon,
                            jdoubleArray stopLats, jdoubleArray stopLons, jint previousIndex) {
    const jsize count = env->GetArrayLength(stopLats);
    if (count == 0) return -1;
    jdouble *lats = env->GetDoubleArrayElements(stopLats, nullptr);
    jdouble *lons = env->GetDoubleArrayElements(stopLons, nullptr);

    int resultIndex = previousIndex;

    if (previousIndex < 0) {
        // --- INITIAL SEARCH: START OF TRIP ---
        // Prioritize the first 10 stops to avoid jumping to return-trip stops in circular routes.
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

        // If the best find in the first 10 stops is reasonably close (< 800m), anchor there.
        // Otherwise, do a global search.
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
        // --- TRACKING MODE: FORWARD WINDOW ---
        // Look ahead 4 stops. Advance ONLY if we are very close to a FUTURE stop (<= 35m).
        int maxSearch = std::min((int)count - 1, previousIndex + 4);
        for (int i = previousIndex + 1; i <= maxSearch; ++i) {
            double d = haversineDistance(currentLat, currentLon, lats[i], lons[i]);
            if (d <= 35.0) { // Tightened from 40m for urban accuracy
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

} // extern "C"
