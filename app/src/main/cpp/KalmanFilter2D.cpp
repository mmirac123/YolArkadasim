#include "KalmanFilter2D.h"
#include <cmath>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

constexpr double EARTH_RADIUS_METERS = 6371000.0;

KalmanFilter2D::KalmanFilter2D() : current_lat(0), current_lon(0), last_timestamp(0), Q_val(0.00001) {
    P[0][0] = 1.0; P[0][1] = 0.0;
    P[1][0] = 0.0; P[1][1] = 1.0;
}

void KalmanFilter2D::reset(double initial_lat, double initial_lon, long long initial_time) {
    current_lat = initial_lat;
    current_lon = initial_lon;
    last_timestamp = initial_time;
    P[0][0] = 1.0; P[0][1] = 0.0;
    P[1][0] = 0.0; P[1][1] = 1.0;
}

void KalmanFilter2D::update(double z_lat, double z_lon, float speed, float bearing, float accuracy, float accelX, float accelY, float accelZ, long long timestamp) {
    if (last_timestamp == 0) {
        reset(z_lat, z_lon, timestamp);
        return;
    }
    
    double dt = (timestamp - last_timestamp) / 1000.0; // Assuming timestamp is in milliseconds
    if (dt <= 0) return;
    
    // 1. Prediction Step
    // Convert bearing to radians. 0 degrees = North.
    double bearing_rad = bearing * M_PI / 180.0;
    
    // Displacement in meters
    double d_north = speed * std::cos(bearing_rad) * dt;
    double d_east = speed * std::sin(bearing_rad) * dt;
    
    // Convert displacement to degrees
    double d_lat = (d_north / EARTH_RADIUS_METERS) * (180.0 / M_PI);
    double d_lon = (d_east / (EARTH_RADIUS_METERS * std::cos(current_lat * M_PI / 180.0))) * (180.0 / M_PI);
    
    double pred_lat = current_lat + d_lat;
    double pred_lon = current_lon + d_lon;
    
    // Predict Covariance P = P + Q
    P[0][0] += Q_val * dt;
    P[1][1] += Q_val * dt;
    
    // 2. Update Step
    // Measurement noise covariance R based on GPS accuracy.
    // Convert accuracy (meters) to degrees approximately.
    double acc_deg = (accuracy / EARTH_RADIUS_METERS) * (180.0 / M_PI);
    double R_val = acc_deg * acc_deg; 
    
    // Kalman Gain K = P / (P + R) (simplified for diagonal R)
    double S_lat = P[0][0] + R_val;
    double S_lon = P[1][1] + R_val;
    
    double K_lat = P[0][0] / S_lat;
    double K_lon = P[1][1] / S_lon;
    
    // Update state
    current_lat = pred_lat + K_lat * (z_lat - pred_lat);
    current_lon = pred_lon + K_lon * (z_lon - pred_lon);
    
    // Update covariance P = (I - K) * P
    P[0][0] = (1.0 - K_lat) * P[0][0];
    P[1][1] = (1.0 - K_lon) * P[1][1];
    
    last_timestamp = timestamp;
}
