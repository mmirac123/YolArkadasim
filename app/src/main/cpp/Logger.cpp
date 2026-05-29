#include "Logger.h"
#include <iostream>
#include <ctime>

Logger::Logger(const std::string& directory, bool enableLogging) : enabled(enableLogging) {
    if (enabled) {
        std::string path = directory + "/gps_log_" + std::to_string(time(nullptr)) + ".csv";
        file.open(path, std::ios::out | std::ios::app);
        if (file.is_open()) {
            file << "timestamp,lat,lon,speed,bearing,accuracy,accelX,accelY,accelZ\n";
        }
    }
}

Logger::~Logger() {
    if (file.is_open()) {
        file.close();
    }
}

void Logger::logLocation(double lat, double lon, float speed, float bearing, float accuracy, float accelX, float accelY, float accelZ, long long timestamp) {
    if (!enabled || !file.is_open()) return;
    file << timestamp << "," << lat << "," << lon << "," << speed << "," << bearing << "," << accuracy << "," << accelX << "," << accelY << "," << accelZ << "\n";
    file.flush();
}
