#pragma once
#include <vector>

enum class StopState {
    APPROACHING = 0,
    ARRIVED = 1,
    SKIPPED = 2,
    PASSED = 3
};

struct StopData {
    double lat;
    double lon;
    StopState state;
};

class NavigationFSM {
public:
    NavigationFSM();
    void setRoute(const std::vector<double>& lats, const std::vector<double>& lons);
    
    // Updates FSM with new location. Returns the index of the currently active target stop.
    int updateLocation(double lat, double lon);
    
    int getActiveStopIndex() const { return active_target_index; }
    StopState getStopState(int index) const;
    double getCrossTrackError() const { return last_cross_track_error; }

private:
    std::vector<StopData> route;
    int active_target_index;
    double last_cross_track_error;
    
    // Helper to calc haversine
    double distanceMeters(double lat1, double lon1, double lat2, double lon2);
    // Cross-track error
    double crossTrackError(double pLat, double pLon, double aLat, double aLon, double bLat, double bLon);
};
