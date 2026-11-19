package com.wkuxr.sunsketcher.location;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;


//simple adapter for the FusedLocationProviderClient
public class LocationAccess {
    private final Context context;
    private final FusedLocationProviderClient fusedLocationProviderClient;

    //constructor
    public LocationAccess(Context context) {
        this.context = context;
        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context);
    }

    //callback interface for whenever a location result is obtained or failed
    public interface LocationResultCallback {
        void onLocationResult(Location location);
        void onLocationFailed();
    }

    //creates a location request for the FusedLocationProviderClient
    public void getCurrentLocation(final LocationResultCallback callback) {
        //check if the necessary permissions are given
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // Handle the case where location permissions are not granted
            if (callback != null) {
                callback.onLocationFailed();
            }
            return;
        }

        // Try getting last known location first for an immediate result
        fusedLocationProviderClient.getLastLocation()
                .addOnSuccessListener(location -> {
                    if (location != null) {
                        Log.d("LocationAccess", "Last known location received.");
                        if (callback != null) {
                            callback.onLocationResult(location);
                        }
                    } else {
                        Log.d("LocationAccess", "Last known location was null, requesting fresh updates.");
                        requestFreshLocation(callback);
                    }
                })
                .addOnFailureListener(e -> {
                    Log.w("LocationAccess", "Failed to get last known location, requesting fresh updates.", e);
                    requestFreshLocation(callback);
                });
    }

    private void requestFreshLocation(final LocationResultCallback callback) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            if (callback != null) {
                callback.onLocationFailed();
            }
            return;
        }

        //create a location request
        LocationRequest locationRequest = LocationRequest.create()
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setInterval(10000)
                .setFastestInterval(5000);
        Log.d("LocationAccess", "Created location request.");

        try {
            fusedLocationProviderClient.requestLocationUpdates(locationRequest, new LocationCallback() {
                @Override
                public void onLocationResult(LocationResult locationResult) {
                    super.onLocationResult(locationResult);
                    Log.d("LocationAccess", "Location received.");
                    Location lastLocation = locationResult.getLastLocation();
                    if (lastLocation == null && !locationResult.getLocations().isEmpty()) {
                        lastLocation = locationResult.getLocations().get(0);
                    }
                    if (lastLocation != null && callback != null) {
                        callback.onLocationResult(lastLocation);
                    } else if (callback != null) {
                        callback.onLocationFailed();
                    }
                    fusedLocationProviderClient.removeLocationUpdates(this);
                }
            }, Looper.getMainLooper()).addOnFailureListener(e -> {
                Log.e("LocationAccess", "Failed to request location updates", e);
                if (callback != null) {
                    callback.onLocationFailed();
                }
            });
        } catch (Exception e) {
            Log.e("LocationAccess", "Exception requesting location updates", e);
            if (callback != null) {
                callback.onLocationFailed();
            }
        }
    }
}
