#include "NavigationFSM.h"
#include "Heuristics.h"
#include <cmath>
#include <algorithm>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

constexpr double EARTH_RADIUS_METERS = 6371000.0;

inline double deg2Rad(double deg) { return deg * (M_PI / 180.0); }

NavigationFSM::NavigationFSM() : active_target_index(-1), last_cross_track_error(0.0) {}

void NavigationFSM::setRoute(const std::vector<double>& lats, const std::vector<double>& lons) {
    route.clear();
    for (size_t i = 0; i < lats.size(); ++i) {
        route.push_back({lats[i], lons[i], StopState::APPROACHING});
    }
    active_target_index = -1;
}

double NavigationFSM::distanceMeters(double lat1, double lon1, double lat2, double lon2) {
    double rLat1 = deg2Rad(lat1);
    double rLon1 = deg2Rad(lon1);
    double rLat2 = deg2Rad(lat2);
    double rLon2 = deg2Rad(lon2);
    double val = std::sin(rLat1)*std::sin(rLat2) + std::cos(rLat1)*std::cos(rLat2)*std::cos(rLon2 - rLon1);
    val = std::max(-1.0, std::min(1.0, val));
    return EARTH_RADIUS_METERS * std::acos(val);
}

double NavigationFSM::crossTrackError(double pLat, double pLon, double aLat, double aLon, double bLat, double bLon) {
    // Calculate cross-track error in meters
    // Find bearing from A to B
    double dLon = deg2Rad(bLon - aLon);
    double rLatA = deg2Rad(aLat);
    double rLatB = deg2Rad(bLat);
    
    double y = std::sin(dLon) * std::cos(rLatB);
    double x = std::cos(rLatA) * std::sin(rLatB) - std::sin(rLatA) * std::cos(rLatB) * std::cos(dLon);
    double brngAB = std::atan2(y, x);
    
    // Find bearing from A to P
    dLon = deg2Rad(pLon - aLon);
    double rLatP = deg2Rad(pLat);
    y = std::sin(dLon) * std::cos(rLatP);
    x = std::cos(rLatA) * std::sin(rLatP) - std::sin(rLatA) * std::cos(rLatP) * std::cos(dLon);
    double brngAP = std::atan2(y, x);
    
    double distAP_rad = distanceMeters(aLat, aLon, pLat, pLon) / EARTH_RADIUS_METERS;
    
    double cte_rad = std::asin(std::sin(distAP_rad) * std::sin(brngAP - brngAB));
    return std::abs(cte_rad * EARTH_RADIUS_METERS);
}

int NavigationFSM::updateLocation(double lat, double lon) {
    if (route.empty()) return -1;
    
    if (active_target_index == -1) {
        int searchLimit = std::min((int)route.size(), 10);
        double minDistance = -1.0;
        int bestIdx = 0;
        
        for (int i = 0; i < searchLimit; ++i) {
            double d = distanceMeters(lat, lon, route[i].lat, route[i].lon);
            if (minDistance < 0.0 || d < minDistance) {
                minDistance = d;
                bestIdx = i;
            }
        }
        
        if (minDistance > 800.0) {
            for (int i = searchLimit; i < (int)route.size(); ++i) {
                double d = distanceMeters(lat, lon, route[i].lat, route[i].lon);
                if (minDistance < 0.0 || d < minDistance) {
                    minDistance = d;
                    bestIdx = i;
                }
            }
        }
        active_target_index = bestIdx;
        return active_target_index;
    }
    
    if (active_target_index < 0 || active_target_index >= (int)route.size()) return active_target_index;
    
    int n = active_target_index;
    if (n > 0) {
        last_cross_track_error = crossTrackError(lat, lon, route[n - 1].lat, route[n - 1].lon, route[n].lat, route[n].lon);
    } else if (n + 1 < (int)route.size()) {
        last_cross_track_error = crossTrackError(lat, lon, route[n].lat, route[n].lon, route[n + 1].lat, route[n + 1].lon);
    }
    
    StopData& stopN = route[n];
    
    StopHeuristics heuristicsN = getHeuristicsForStop(n);
    double distN = distanceMeters(lat, lon, stopN.lat, stopN.lon);
    
    // Check if arrived at N
    if (distN <= heuristicsN.trigger_radius_meters && stopN.state == StopState::APPROACHING) {
        stopN.state = StopState::ARRIVED;
    }
    
    // Check for skipped / passed logic using next stop N+1
    while (n + 1 < (int)route.size()) {
        double distN1 = distanceMeters(lat, lon, route[n + 1].lat, route[n + 1].lon);
        
        // If we are closer to N+1 than N, and we are definitively outside N's trigger radius
        if (distN1 < distN && distN > heuristicsN.trigger_radius_meters * 1.5) {
            // We are moving away from N and towards N+1
            if (route[n].state == StopState::APPROACHING) {
                route[n].state = StopState::SKIPPED;
            } else if (route[n].state == StopState::ARRIVED) {
                route[n].state = StopState::PASSED;
            }
            active_target_index = n + 1;
            n = active_target_index;
            heuristicsN = getHeuristicsForStop(n);
            distN = distN1;
        } else {
            break;
        }
    }
    
    if (n + 1 >= (int)route.size()) {
        // Last stop logic
        if (distN > heuristicsN.trigger_radius_meters * 2.0 && stopN.state == StopState::ARRIVED) {
            stopN.state = StopState::PASSED;
        }
    }
    
    return active_target_index;
}

StopState NavigationFSM::getStopState(int index) const {
    if (index >= 0 && index < (int)route.size()) {
        return route[index].state;
    }
    return StopState::PASSED; // Default invalid
}
