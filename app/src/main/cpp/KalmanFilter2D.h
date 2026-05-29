#pragma once

class KalmanFilter2D {
public:
    KalmanFilter2D();
    void reset(double initial_lat, double initial_lon, long long initial_time);
    
    // speed in m/s, bearing in degrees (0=North, 90=East), accuracy in meters, acceleration in m/s^2
    void update(double lat, double lon, float speed, float bearing, float accuracy, float accelX, float accelY, float accelZ, long long timestamp);
    
    double getLat() const { return current_lat; }
    double getLon() const { return current_lon; }

private:
    double current_lat;
    double current_lon;
    long long last_timestamp;
    
    // Covariance matrix of the state (2x2)
    // [P_lat_lat, P_lat_lon]
    // [P_lon_lat, P_lon_lon]
    double P[2][2];
    
    // Process noise covariance Q (tuned constant)
    double Q_val; 
};
