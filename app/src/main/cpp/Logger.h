#pragma once
#include <string>
#include <fstream>

class Logger {
public:
    Logger(const std::string& directory, bool enableLogging);
    ~Logger();
    
    void logLocation(double lat, double lon, float speed, float bearing, float accuracy, float accelX, float accelY, float accelZ, long long timestamp);

private:
    std::ofstream file;
    bool enabled;
};
