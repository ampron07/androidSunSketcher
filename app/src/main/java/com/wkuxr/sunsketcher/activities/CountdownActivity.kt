package com.wkuxr.sunsketcher.activities
import java.util.Calendar
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.CountDownTimer
import android.text.SpannableStringBuilder
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.text.bold
import com.wkuxr.sunsketcher.databinding.ActivityCountdownBinding
import com.wkuxr.sunsketcher.location.LocToTime
import com.wkuxr.sunsketcher.location.LocationAccess
import com.wkuxr.sunsketcher.location.LocationAccess.LocationResultCallback

class CountdownActivity : AppCompatActivity() {
    companion object {
        lateinit var singleton: CountdownActivity
    }
    lateinit var binding: ActivityCountdownBinding

    //first function that is run implicitly when this activity is opened
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCountdownBinding.inflate(layoutInflater)
        setContentView(binding.root)

        //get a reference to the shared preferences
        val prefs = getSharedPreferences("eclipseDetails", MODE_PRIVATE)
        val hasConfirmDeny = prefs.getInt("upload", -1) //if -1, hasn't taken images yet

        var intent: Intent? = null
        when (hasConfirmDeny) {
            -2 -> intent = if (prefs.getBoolean("cropped", false)) { //-2 means not yet confirmed or denied upload, but has taken images; if cropped is true, all images have been cropped
                Intent(this, SendConfirmationActivity::class.java)
            } else { //cropped is false if not all images have been cropped (this is also the default if cropped is not found)
                //Intent(this, ImageCroppingActivity::class.java)
                //Intent(this, SendConfirmationActivity::class.java)
                Intent(this, ImageCroppingActivity::class.java)
            }

            0 -> intent = Intent(this, FinishedInfoDenyActivity::class.java) //0 means upload was denied
            1 -> intent = if (prefs.getBoolean("uploadSuccessful", false)) { //1 means the user allowed upload; uploadSuccessful means all data has been uploaded if true
                Intent(this, FinishedCompleteActivity::class.java)
            } else { //uploadSuccessful is false if not all data has been uploaded (this is also the default if the variable isn't found)
                Intent(this, FinishedInfoActivity::class.java)
            }
            //default
            else -> {}
        }
        //switch to the respective screen if necessary
        if (intent != null) {
            this.startActivity(intent)
        }

        //set the formatted text to be displayed
        singleton = this
        var str = SpannableStringBuilder("Please turn your ringer ").bold{append("off")}.append(" and Do Not Disturb ").bold{append("on!")}
        binding.countdownInfoText.text = str

        var str2 = SpannableStringBuilder("Set the phone down with the ").bold{append("REAR CAMERA")}.append(" facing the Sun. Enjoy totality, you can check your phone again 5 minutes after totality ends.")
        binding.countdownInfoPage2Text.text = str2

        getLocation()

    }

    var timerSet = false

    //this function accesses the GPS location, calculates the time of totality (if the user is not in the path of totality, it says so in the UI and does not do anything), and uses it to schedule when to switch to the camera activity
    private fun getLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            //request permission again if it wasn't given
            requestPermissions(arrayOf("android.permission.ACCESS_FINE_LOCATION"), 1)
        } else {
            binding.countdownLocationDetailsText.text = "Getting GPS Location"

            //prevent phone from automatically locking
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            //create an instance of the LocationAccess class
            val locAccess = LocationAccess(this)

            //use it to get the location once
            locAccess.getCurrentLocation(object : LocationResultCallback {
                override fun onLocationResult(location: Location) {
                    //get the coordinates from the callback parameter
                    var lat = location.latitude
                    var lon = location.longitude
                    var alt = location.altitude

                    //todo: for testing 2024 eclipse (location spoof)
                    //lat = 47.6683
                    //lon = -60.7450

                    //TODO: for testing 2026 eclipse (location spoof)
                    //lat = 80.26822
                    //lon = -27.75176
                    //alt = 1213.0

                    //TODO: for testing 2017 eclipse (location spoof)
                    //lat = 36.99032
                    //lon = -86.443602

                    //get contact times using obtained location TODO: use for actual app releases
                    //val contactTimes = LocToTime.calculatefor(lat, lon, alt)

                    //TODO: TEST ONLY - fake Bowling Green location, totality at 11:00 PM. Remove before release.
                    lat = 36.9685
                    lon = -86.4808
                    val start = java.time.ZonedDateTime.now(java.time.ZoneId.of("America/Chicago"))
                        .with(java.time.LocalTime.of(23, 0))
                        .toInstant().toEpochMilli()
                    val contactTimes = longArrayOf(start, start + 160_000L)

                    //make sure the user is actually in eclipse path before trying to do any scheduling stuff
                    if (contactTimes[0] != Long.MAX_VALUE) {
                        //make it visible that something is happening by showing the device's location on screen
                        val details = "Your location:\nLatitude: $lat\nLongitude: $lon"
                        Log.d("Timing", details)
                        Log.d("Timing", "Unix c2: " + contactTimes[0] + "\nUnix c3: " + contactTimes[1])
                        binding.countdownLocationDetailsText.text = details

                        //store the unix time for the start and end of totality and the location in SharedPreferences
                        val prefs = getSharedPreferences("eclipseDetails", MODE_PRIVATE).edit()
                        prefs.putLong("startTime", contactTimes[0])
                        prefs.putLong("endTime", contactTimes[1])
                        prefs.putFloat("lat", lat.toFloat())
                        prefs.putFloat("lon", lon.toFloat())
                        prefs.putFloat("alt", alt.toFloat())
                        prefs.apply() //save the shared preferences asynchronously

                        //go to camera 60 seconds prior to C2
                        if (!timerSet) {
                            Log.d("Timing", "Creating timer.")
                            timerSet = !timerSet

                            val countdownTimeDiff = ((contactTimes[0]) - 60 * 1000) - System.currentTimeMillis() //TODO: use
                            //val countdownTimeDiff = 5000L //TODO: remove (schedules switch to camera activity for 5 seconds from current time)

                            //check to make sure if it is past C2 - 1 minute already
                            if(countdownTimeDiff > 0) {
                                //create a countdown timer that ticks once per second
                                object : CountDownTimer(countdownTimeDiff, 1000) {

                                    //at each tick, update the countdown timer on screen
                                    override fun onTick(millisUntilFinished: Long) {
                                        var seconds = millisUntilFinished / 1000
                                        var minutes = seconds / 60
                                        seconds %= 60
                                        val hours = minutes / 60
                                        minutes %= 60

                                        binding.countdownTimeText.text = "${if (hours > 0) { "$hours:" } else { "" }}${if (minutes > 0) { "${if (minutes < 10) { if(hours > 0) { "0" } else { "" } } else { "" } + "$minutes"}:" } else { if(hours > 0) { "00:" } else { "0:" }}}${if (seconds < 10) { "0" } else { "" } + "$seconds"} UNTIL FIRST PHOTO IS TAKEN"
                                    }

                                    //when the timer reaches 0, switch to the camera activity
                                    override fun onFinish() {
                                        val intent = Intent(singleton, CameraActivity::class.java)
                                        singleton.startActivity(intent)
                                    }
                                }.start() //start the countdown timer
                            } else {
                                //totality has already started
                                binding.countdownTimeText.text = "Totality has already started at your location."
                            }

                        }
                    } else {
                        //user is not in the path of totality
                        binding.countdownLocationDetailsText.text = "Your location:\nLatitude: $lat\nLongitude: $lon"
                        binding.countdownTimeText.text = "Not in path of totality."
                    }


                }

                //failed to get location
                override fun onLocationFailed() {
                    binding.countdownLocationDetailsText.text = ""
                    binding.countdownTimeText.text = "Unable to get location. Did you allow access permissions?"
                }
            })
        }
    }

    //switch the visible UI elements when the left and right arrows are pressed
    fun onArrowClick(v: View){
        if(v.id == binding.countdownArrowRight.id){
            binding.countdownArrowRight.visibility = View.GONE
            binding.countdownLocationDetailsText.visibility = View.GONE
            binding.countdownInfoText.visibility = View.GONE
            binding.countdownDoNotDisturbImage.visibility = View.GONE


            binding.countdownArrowLeft.visibility = View.VISIBLE
            binding.countdownPhoneStandImage.visibility = View.VISIBLE
            binding.countdownInfoPage2Text.visibility = View.VISIBLE
        } else {
            binding.countdownArrowLeft.visibility = View.GONE
            binding.countdownPhoneStandImage.visibility = View.GONE
            binding.countdownInfoPage2Text.visibility = View.GONE

            binding.countdownArrowRight.visibility = View.VISIBLE
            binding.countdownLocationDetailsText.visibility = View.VISIBLE
            binding.countdownInfoText.visibility = View.VISIBLE
            binding.countdownDoNotDisturbImage.visibility = View.VISIBLE
        }
    }
}