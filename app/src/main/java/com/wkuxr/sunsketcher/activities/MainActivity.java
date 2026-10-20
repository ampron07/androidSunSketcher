package com.wkuxr.sunsketcher.activities;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;

import com.wkuxr.sunsketcher.App;
import com.wkuxr.sunsketcher.location.LocationAccess;
import com.wkuxr.sunsketcher.location.Sunset;
import com.wkuxr.sunsketcher.databinding.ActivityMainBinding;
import com.wkuxr.sunsketcher.networking.IDRequest;
import java.util.Calendar;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends AppCompatActivity {

    public static MainActivity singleton;   //having a singleton instance of the MainActivity makes so many things significantly easier because it gives us a definite, central reference point for non-static and activity-related functions

    //any time I use a binding object like this, it's so I can reference UI (layout) elements without having to search for them by ID (reduces performance overhead)
    //requires `android { buildFeatures { viewBinding true } }` in the app module's build.gradle
    ActivityMainBinding binding;

    static String[] perms = new String[]{"android.permission.CAMERA","android.permission.ACCESS_FINE_LOCATION","android.permission.WRITE_EXTERNAL_STORAGE","android.permission.INTERNET","android.permission.POST_NOTIFICATIONS"};
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        singleton = this;
        App.setContext(this);

        if (!reqPerm(perms)) {
            return;
        }

        // Legacy functionality temporarily disabled for trimmed scope (no image processing, no server, etc.)
        /*
        SharedPreferences prefs = getSharedPreferences("eclipseDetails", Context.MODE_PRIVATE);
        int hasConfirmDeny = prefs.getInt("upload", -1); //if -1, hasn't taken images yet
        Intent intent = null;
        switch(hasConfirmDeny){
            case -2: //-2 means not yet confirmed or denied upload, but has taken images; if cropped is true, all images have been cropped
                if(prefs.getBoolean("cropped", false)){
                    intent = new Intent(this, SendConfirmationActivity.class);
                } else { //cropped is false if not all images have been cropped (this is also the default if cropped is not found)
                    intent = new Intent(this, ImageCroppingActivity.class);
                }
                break;
            case 0: //denied upload
                intent = new Intent(this, FinishedInfoDenyActivity.class);
                break;
            case 1: //1 means the user allowed upload
                if(prefs.getBoolean("uploadSuccessful", false)){ //uploadSuccessful means all data has been uploaded if true
                    intent = new Intent(this, FinishedCompleteActivity.class);
                } else { //uploadSuccessful is false if not all data has been uploaded (this is also the default if the variable isn't found)
                    intent = new Intent(this, FinishedInfoActivity.class);
                }
                break;
            default: //don't do anything if the above cases aren't true
        }
        //switch to the respective screen if necessary
        if(intent != null) {
            this.startActivity(intent);
        }

        //this commented line allows you to set a specific clientID to make data discernible after transfer to server
        //prefs.edit().putLong("clientID",9999999).commit();

        long clientID = prefs.getLong("clientID", -1);
        //if the app has not yet gotten a clientID from the server, get one
        if(clientID == -1){
            //connect to server to get ID and upload time
            Thread idTimeThread = new Thread(() -> {
                try {
                    IDRequest.clientTransferSequence();
                    Log.d("ClientID", "ClientID: " + prefs.getLong("clientID", -1));
                } catch (Exception e) {
                    Log.e("ClientID", "Could not connect to server to obtain client ID.");
                    e.printStackTrace();
                }
            });
            idTimeThread.start();
        }
        */

        //the commented out code below here relating to clientIDs is debug functionality to display the clientID on screen
        //binding.clientIDText.setText("ClientID: " + clientID);
    }

    @Override
    protected void onResume() {
        super.onResume();

        SharedPreferences prefs = getSharedPreferences("eclipseDetails", Context.MODE_PRIVATE);
        int hasConfirmDeny = prefs.getInt("upload", -1); //if -1, hasn't taken images yet
        Intent intent = null;
        switch(hasConfirmDeny){
            case -2: //-2 means not yet confirmed or denied upload, but has taken images; if cropped is true, all images have been cropped
                if(prefs.getBoolean("cropped", false)){
                    intent = new Intent(this, SendConfirmationActivity.class);
                } else { //cropped is false if not all images have been cropped (this is also the default if cropped is not found)
                    // intent = new Intent(this, ImageCroppingActivity.class);
                    // ^^ Comment out the image cropping reference
                    // intent = new Intent(this, SendConfirmationActivity.class);
                    intent = new Intent(this, ImageCroppingActivity.class);
                }
                break;
            case 0: //denied upload
                intent = new Intent(this, FinishedInfoDenyActivity.class);
                break;
            case 1: //1 means the user allowed upload
                if(prefs.getBoolean("uploadSuccessful", false)){ //uploadSuccessful means all data has been uploaded if true
                    intent = new Intent(this, FinishedCompleteActivity.class);
                } else { //uploadSuccessful is false if not all data has been uploaded (this is also the default if the variable isn't found)
                    intent = new Intent(this, FinishedInfoActivity.class);
                }
                break;
            default: //don't do anything if the above cases aren't true
        }
        //switch to the respective screen if necessary
         if(intent != null) {
             this.startActivity(intent);
         }
    }

    public void start(View v){
        Intent intent;

        //check if the necessary permissions have been allowed
        if(reqPerm(perms)) { //if so, check whether the tutorial has been completed
            SharedPreferences prefs = getSharedPreferences("eclipseDetails", Context.MODE_PRIVATE);

            if (prefs.getBoolean("completedTutorial", false)) {
                //tutorial has been completed, go to location prompt
                intent = new Intent(this, LocationPromptActivity.class);
            } else {
                //tutorial has not been completed, suggest viewing tutorial
                intent = new Intent(this, TutorialPromptActivity.class);
            }
        } else {
            //necessary permissions have not been allowed, so request permissions again
            intent = new Intent(this, PermissionsWarningActivity.class);
        }
        startActivity(intent);
    }

    //functionality for clicking the tutorial button. as named, it opens TutorialActivity
    public void tutorial(View v){
        SharedPreferences prefs = getSharedPreferences("eclipseDetails", Context.MODE_PRIVATE);
        //the tutorial activity must be able to return to the MainActivity or the LocationPromptActivity depending on where it was called from, so discerning that is done with this pref variable
        SharedPreferences.Editor prefEdit = prefs.edit();
        prefEdit.putInt("next", 0);
        prefEdit.apply();

        Intent intent = new Intent(this, TutorialActivity.class);
        startActivity(intent);
    }

    //open the LearnMoreActivity
    public void learnMore(View v){
        Intent intent = new Intent(this, LearnMoreActivity.class);
        startActivity(intent);
    }

    //open the TermsAndConditionsActivity
    public void tac(View v){
        Intent intent = new Intent(this, TermsAndConditionsActivity.class);
        startActivity(intent);
    }

    //request the permissions passed in as a string array if they have not already been granted
    public boolean reqPerm(String[] permissions){
        if ((ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) && (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)) {
            //all required permissions have been granted
            return true;
        }
        else {
            //not all required permissions have been granted
            Log.d("PermissionRequests", "Not all required permissions were granted. Requesting them." +
                    "\nFine location: " + (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) +
                    "\nCamera: " + (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
            );
            requestPermissions(permissions, 1);
            return false;
        }
    }

    // Ensure app knows when user accepts/denies permissions
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == 1) {  // same request code you used in reqPerm()
            boolean fineGranted = false;
            boolean cameraGranted = false;

            for (int i = 0; i < permissions.length; i++) {
                if (permissions[i].equals(Manifest.permission.ACCESS_FINE_LOCATION)) {
                    fineGranted = (grantResults[i] == PackageManager.PERMISSION_GRANTED);
                }
                if (permissions[i].equals(Manifest.permission.CAMERA)) {
                    cameraGranted = (grantResults[i] == PackageManager.PERMISSION_GRANTED);
                }
            }

            if (fineGranted && cameraGranted) {
                Log.d("Permissions", "All required permissions granted.");
            } else {
                Log.w("Permissions", "Missing one or more required permissions.");
            }
        }
    }

}