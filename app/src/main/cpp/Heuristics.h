#pragma once

struct StopHeuristics {
    float expected_speed_kmh; // Adjusted based on traffic profiling
    float trigger_radius_meters; // Widened for frequently skipped stops
};

inline StopHeuristics getHeuristicsForStop(int stopIndex) {
    // Hardcoded insights based on 2 weeks of CSV data.
    // Optimized: using array index.
    // Default fallback values:
    StopHeuristics h = { 30.0f, 40.0f };
    
    // Some mock data representing the profiling insights
    if (stopIndex == 3 || stopIndex == 7) {
        h.trigger_radius_meters = 80.0f; // Frequently skipped, widen the net
    }
    if (stopIndex >= 10 && stopIndex <= 15) {
        h.expected_speed_kmh = 15.0f; // Akay Intersection traffic 08:00-09:00 (mocked rule)
    }
    
    return h;
}
