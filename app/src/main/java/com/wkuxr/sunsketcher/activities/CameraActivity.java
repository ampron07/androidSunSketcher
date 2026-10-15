package com.wkuxr.sunsketcher.activities;

import static com.wkuxr.sunsketcher.activities.SendConfirmationActivity.prefs;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.RggbChannelVector;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.wkuxr.sunsketcher.R;
import com.wkuxr.sunsketcher.database.Metadata;
import com.wkuxr.sunsketcher.database.MetadataDB;
import com.wkuxr.sunsketcher.database.MetadataExporter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;


public class CameraActivity extends AppCompatActivity {

    static CameraActivity singleton;

    private static final String TAG = "Camera2VideoImageActivi"; // The name of debug channel

    private static final int REQUEST_CAMERA_PERMISSION_RESULT = 0;
    private static final int STATE_PREVIEW = 0;
    private static final int STATE_WAIT_LOCK = 1;
    private int mCaptureState = STATE_PREVIEW;
    private TextureView mTextureView;
    private final TextureView.SurfaceTextureListener mSurfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            setupCamera(width, height);
            connectCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {

        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {

        }
    };
    private CameraDevice mCameraDevice;
    private final CameraDevice.StateCallback mCameraDeviceStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            mCameraDevice = camera;
            mMediaRecorder = new MediaRecorder();
            startPreview();
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            camera.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            camera.close();
            mCameraDevice = null;
        }
    };
    private HandlerThread mBackgroundHandlerThread;
    private Handler mBackgroundHandler;
    private String mCameraId;
    private Size mPreviewSize;
    private ImageReader mImageReader;

    //callback from images being captured
    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener = new
            ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    mBackgroundHandler.post(new ImageSaver(reader.acquireLatestImage()));
                }
            };

    //called by mOnImageAvailableListener callback above, this class is used to save images asynchronously
    private class ImageSaver implements Runnable {

        private final Image mImage;

        public ImageSaver(Image image) {
            mImage = image;
        }

        @Override
        public void run() {
            try {
                //create the container file
                createImageFileName();
            } catch (IOException e) {
                e.printStackTrace();
            }

            byte[] bytes;
            //currently all images are JPEGs, so this is always false (see header comment of getCroppedData function below
            if(mImage.getFormat() == ImageFormat.RAW_SENSOR){
                bytes = getCroppedData(mImage, 50, 150, 50, 150);
            } else {
                //get the byte array data for the image
                ByteBuffer byteBuffer = mImage.getPlanes()[0].getBuffer();
                bytes = new byte[byteBuffer.remaining()];
                byteBuffer.get(bytes);
            }

            //write the byte array to the file created
            FileOutputStream fileOutputStream = null;
            try {
                fileOutputStream = new FileOutputStream(mImageFileName);
                fileOutputStream.write(bytes);
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                mImage.close();

                Intent mediaStoreUpdateIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                mediaStoreUpdateIntent.setData(Uri.fromFile(new File(mImageFileName)));
                sendBroadcast(mediaStoreUpdateIntent);

                if (fileOutputStream != null) {
                    try {
                        fileOutputStream.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        //TODO: Fix the RAW_SENSOR part of this function because raw images don't work
        //currently useless function, supposed to find the brightest point in a raw image and crop at it; doesn't actually work
        byte[] getCroppedData(Image image, int x1, int x2, int y1, int y2){
            int format = image.getFormat();
            int width = y2 - y1;
            int height = x2 - x1;

            byte[] data = null;

            Image.Plane[] planes = image.getPlanes();

            //currently, all images are JPEGs, so this is always false
            if(format == ImageFormat.RAW_SENSOR){
                int bytesPerPixel = ImageFormat.getBitsPerPixel(format) / 8;
                Log.d("Image_Cropping", "Bytes per pixel: " + bytesPerPixel);
                //create a new data array with the size necessary to hold the cropped image
                data = new byte[height * width * bytesPerPixel];
                Log.d("Image_Cropping", "Output size: " + data.length + "; Dimensions: " + (x2 - x1) + "x" + (y2 - y1));
                for(int i = 0; i < planes.length; i++){
                    ByteBuffer buffer = planes[i].getBuffer();
                    int rowStride = planes[i].getRowStride();
                    Log.d("Image_Cropping", "Plane " + i + " row stride: " + rowStride);
                    int w = (i == 0) ? width : width / 2;

                    for(int row = x1; row < x2; row++){
                        buffer.position((row * rowStride) + y1);
                        int readSize = w * bytesPerPixel;
                        if(buffer.remaining() < readSize){
                            readSize = buffer.remaining();
                        }
                        byte[] printData = new byte[w * bytesPerPixel];
                        buffer.get(printData, 0, readSize);
                        Log.d("Image_Cropping", "Row " + row + " byte data: " + Arrays.toString(printData));
                        //set the buffer position to the beginning of the intended cropped row's data
                        buffer.position((row * rowStride) + y1);
                        //the segment to read needs to be the cropped segment's number of bytes wide
                        readSize = w * bytesPerPixel;
                        if(buffer.remaining() < readSize){
                            readSize = buffer.remaining();
                        }
                        //write readSize bytes from current position in buffer to the correct number of bytes offset in rowData for the current row
                        Log.d("Image_Cropping", "Row byte length: " + readSize + "; Output array offset: " + ((row - x1) * readSize) + "; Buffer position: " + ((row * rowStride) + y1));
                        buffer.get(data, (row - x1) * readSize, readSize);
                    }
                }
            }
            return data;
        }
    }

    private MediaRecorder mMediaRecorder;
    private int mTotalRotation;
    private CameraCaptureSession mPreviewCaptureSession;

    //callback for camera preview
    private final CameraCaptureSession.CaptureCallback mPreviewCaptureCallback = new CameraCaptureSession.CaptureCallback() {
        private void process(CaptureResult captureResult) {
            switch (mCaptureState) {
                case STATE_PREVIEW:
                    // Do nothing
                    break;
                case STATE_WAIT_LOCK:
                    mCaptureState = STATE_PREVIEW;
                    Integer afState = captureResult.get(CaptureResult.CONTROL_AF_STATE);
                    if (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED || afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
                        //This line displays the popup
                        // that is shown after an image is taken. we don't particularly need it in the final product, but it is good for testing.
                        Toast.makeText(getApplicationContext(), "AF Locked!", Toast.LENGTH_SHORT).show();
                    }
                    break;
            }
        }

        @Override
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);

            process(result);
        }
    };

    private CaptureRequest.Builder mCaptureRequestBuilder;

    private File mImageFolder;
    private String mImageFileName;

    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 0);
        ORIENTATIONS.append(Surface.ROTATION_90, 90);
        ORIENTATIONS.append(Surface.ROTATION_180, 180);
        ORIENTATIONS.append(Surface.ROTATION_270, 270);
    }

    private static class CompareSizeByArea implements Comparator<Size> {

        @Override
        public int compare(Size lhs, Size rhs) {
            return Long.signum(((long) lhs.getWidth() * lhs.getHeight()) -
                    ((long) rhs.getWidth() * rhs.getHeight()));
        }
    }

    long startTime;
    long endTime;

    MetadataDB db;

    //first thing that runs in the activity's lifecycle
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);

        //set the App.java context singleton to this activity's instance (so that the database can be used)
        singleton = this;

        //set a flag that prevents the screen from locking
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        //create an instance of the database
        db = MetadataDB.Companion.createDB(this);

        //self explanatory
        createImageFolder();

        //get a reference to the layout's texture view
        mTextureView = findViewById(R.id.textureView);

        //get a reference to the sharedPreferences
        prefs = getSharedPreferences("eclipseDetails", Context.MODE_PRIVATE);

        //perform checks to see if the app should actually be on a later screen
        int hasConfirmDeny = prefs.getInt("upload", -1); //if -1, hasn't taken images yet
        Intent intent = null;
        switch(hasConfirmDeny){
            case -2: //not yet confirmed or denied, but has taken images
                if(prefs.getBoolean("cropped", false)){ //if all images have been cropped, false if not found
                    intent = new Intent(this, SendConfirmationActivity.class);
                } else {
                    // Temporarily disabled (OpenCV not in use)
                    // intent = new Intent(this, ImageCroppingActivity.class);
                }
                break;
            case 0: //denied upload after taking images
                intent = new Intent(this, FinishedInfoDenyActivity.class);
                break;
            case 1: //allowed upload after taking images
                if(prefs.getBoolean("uploadSuccessful", false)){ //upload already finished
                    intent = new Intent(this, FinishedCompleteActivity.class);
                } else { //upload hasn't finished
                    intent = new Intent(this, FinishedInfoActivity.class);
                }
                break;
            default:
        }
        if(intent != null) {
            this.startActivity(intent);
        }

        //get the start and end time of eclipse totality from SharedPreferences, default to Long.MAX_VALUE if not present so the camera sequence doesn't falsely trigger. Randomize both by up to 250ms in either direction.
        long randomizer = (long)((Math.random() * 500) - 250);
        startTime = prefs.getLong("startTime", Long.MAX_VALUE) + randomizer;
        endTime = prefs.getLong("endTime", Long.MAX_VALUE) + randomizer;
    }

    //runs after onCreate
    @Override
    protected void onStart() {
        super.onStart();

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        prefs = getSharedPreferences("eclipseDetails", Context.MODE_PRIVATE);
        prefs.edit().putInt("upload", -2).apply();

        //timer that takes images every 1 seconds for 20 seconds starting 15 seconds before t[c2], then another timer for images every 1s for 20s starting 5s before t[c3]
        //the next three lines are for testing functionality. They perform the same time randomization as above in the onCreate function, but set the start time and end time relative to the current time
        //long randomizer = (long)((Math.random() * 500) - 250); //TODO: remove for actual app releases
        //startTime = System.currentTimeMillis() + 30000 + randomizer; //TODO: remove for actual app releases
        //endTime = startTime + 60000 * 2 + randomizer; //2 minutes after startTime TODO: remove for actual app releases

        long midTime = (endTime + startTime) / 2; //set time for midpoint photo

        //create a timer which is used to schedule all of the timing triggers
        sequenceTimer = new Timer();
        //set timer to start captures at t[c2] - 20 at 1 img per 2 seconds
        Date startC2d1 = new Date(startTime - 20000);
        sequenceTimer.schedule(new StartSequenceTask(),startC2d1);
        //switch capture rate to 2 per second at t[c2] - 10
        Date startC2d2 = new Date(startTime - 10000);
        sequenceTimer.schedule(new FastSequenceTask(), startC2d2);
        //switch capture rate to 1 img per 2 seconds at t[c2] + 10
        Date startC2d3 = new Date(startTime + 10000);
        sequenceTimer.schedule(new StartSequenceTask(), startC2d3);
        //set timer to stop captures at t[c2] + 20
        Date endC2d3 = new Date(startTime + 20000);
        sequenceTimer.schedule(new StopSequenceTask(), endC2d3);
        //set timer to take a single capture at midpoint
        Date mid = new Date(midTime);
        sequenceTimer.schedule(new MidpointCaptureTask(), mid);
        //set timer to start captures at t[c3] - 20
        Date startC3d1 = new Date(endTime - 20000);
        sequenceTimer.schedule(new StartSequenceTask(), startC3d1);
        //switch capture rate to 2 per second at t[c2] - 10
        Date startC3d2 = new Date(endTime - 10000);
        sequenceTimer.schedule(new FastSequenceTask(), startC3d2);
        //switch capture rate to 1 img per 2 seconds at t[c2] + 10
        Date startC3d3 = new Date(endTime + 10000);
        sequenceTimer.schedule(new StartSequenceTask(), startC3d3);
        //set timer to stop captures at t[c3] + 20
        Date endC3d3 = new Date(endTime + 20000);
        sequenceTimer.schedule(new StopSequenceTask(), endC3d3);

        //make sound to signify image capturing complete

        //set timer to switch to SendConfirmationActivity 5 seconds after all photos have been taken
        Date activityTimer = new Date(endTime + 25000);
        sequenceTimer.schedule(new SwitchActivityTask(), activityTimer);
    }

    //if the app is completely closed (as in, the user swiped up in the app list or force closed it from settings), this is the last thing that is called
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if(sequenceTimer != null){
            sequenceTimer.cancel();
        }
    }

    Timer sequenceTimer = null;
    Handler sequenceHandler = new Handler();

    //thread runnable that takes a photo and then waits for 2 seconds
    Runnable sequenceRunnable = new Runnable(){
        @Override
        public void run(){
            startStillCaptureRequest();
            sequenceHandler.postDelayed(this, 2000);
        }
    };

    //scheduled task that removes all fast capture task callbacks and starts the 1-per-2-seconds image captures
    static class StartSequenceTask extends TimerTask {
        public void run(){
            Log.d("CameraDebug", "Starting slow captures.");
            singleton.sequenceHandler.removeCallbacks(singleton.fastSequenceRunnable);
            singleton.sequenceHandler.postDelayed(singleton.sequenceRunnable, 0);
        }
    }

    //thread runnable that takes a photo and then waits for 0.5 seconds
    Runnable fastSequenceRunnable = new Runnable(){
        @Override
        public void run() {
            startStillCaptureRequest();
            sequenceHandler.postDelayed(this, 500);
        }
    };

    //scheduled task that removes all slow capture task callbacks and starts the 2-per-second image captures
    static class FastSequenceTask extends TimerTask {
        public void run(){
            Log.d("CameraDebug", "Starting fast captures.");
            singleton.sequenceHandler.removeCallbacks(singleton.sequenceRunnable);
            singleton.sequenceHandler.postDelayed(singleton.fastSequenceRunnable, 0);
        }
    }

    //scheduled task that removes all callbacks to capture tasks
    static class StopSequenceTask extends TimerTask {
        public void run(){
            singleton.sequenceHandler.removeCallbacks(singleton.sequenceRunnable);
            singleton.sequenceHandler.removeCallbacks(singleton.fastSequenceRunnable);
            Log.d("STOP_CAPTURES", "Image capture sequence callbacks have been removed.");
        }
    }

    //thread runnable that takes a single image with a given exposure time (exposure time is passed in as nanoseconds, so the 5000000 below is 1/200 seconds). Uses lambda expression to shorten code length
    Runnable midpointRunnable = () -> startStillCaptureRequest(5000000);

    //scheduled task that calls the midpointRunnable
    static class MidpointCaptureTask extends TimerTask {
        public void run(){
            /*singleton.closeCamera();
            if (singleton.mTextureView.isAvailable()) {
                singleton.imageFormat = ImageFormat.JPEG;
                singleton.setupCamera(singleton.mTextureView.getWidth(), singleton.mTextureView.getHeight());
                singleton.connectCamera();
            }*/
            singleton.sequenceHandler.post(singleton.midpointRunnable);
            Log.d("MIDPOINT_CAPTURE", "Midpoint photo of eclipse has been taken.");
        }
    }

    //scheduled task that closes the camera and switches context activities
    static class SwitchActivityTask extends TimerTask {
        public void run(){
            // Below disabled temporarily, no image processing
            // Log.d("ACTIVITYSWITCH", "To " + ImageCroppingActivity.class.getName());
            // Intent intent = new Intent(singleton, ImageCroppingActivity.class);

            //temporary replacement code
            Log.d("ACTIVITYSWITCH", "To FinishedInfoActivity");
            // Export metadata.json + metadata.csv to Documents/SunSketcher (matches the iOS app)
            MetadataExporter.INSTANCE.exportAll(singleton);
            Intent intent = new Intent(singleton, FinishedInfoActivity.class);
            singleton.closeCamera();
            singleton.startActivity(intent);
            singleton.finish(); //makes this screen automatically close if someone tries to navigate back to it using the back button, if the code in the onCreate function that switches the activity context fails
        }
    }

    //runs after onStart and whenever the app is re-focused (as in, the user exited the app but did not kill it by swiping up in the app list, and is now re-opening the app)
    @Override
    protected void onResume() {
        super.onResume();

        startBackgroundThread();

        if (mTextureView.isAvailable()) {
            setupCamera(mTextureView.getWidth(), mTextureView.getHeight());
            connectCamera();
        } else {
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
    }

    //if the app is exited (but not killed), close the camera
    @Override
    protected void onPause() {
        closeCamera();

        stopBackgroundThread();

        super.onPause();
    }

    //tbh not sure what this does, Joey wrote it and I haven't touched it
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        View decorView = getWindow().getDecorView();
        if (hasFocus) {
            decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        }
    }

    boolean aeModeOffAvailable = false;

    //changing this will change the output image format, but we've been having trouble with saving and cropping raw images, so unfortunately we are using a lossy format for now; future maintenance may see an attempt at switching to RAW_SENSOR and saving as a DNG in the ImageSaver callback
    int imageFormat = ImageFormat.JPEG;

    CameraManager cameraManager;

    float hyperfocus;

    //as the name of the function suggests, this sets up the camera connection with the highest resolution back-facing camera
    @SuppressWarnings("SuspiciousNameCombination")
    private void setupCamera(int width, int height) {
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            // sets the value of the highest resolution camera as the first one on the device, then iterates through every camera,
            // and determines if it is larger than the largest quality camera
            // and if it is, sets the currentLargest to the new largest camera
            // currentLargest stores the resolution value
            // currentLarge stores the cameraID value
            // camera1 stores the list of cameraDevices
            int index = 0;
            String[] camera1 = cameraManager.getCameraIdList();
            Size currentLargest = getResolution(cameraManager,camera1[index]);
            String currentLarge = camera1[index];
            // add a catch to ensure that camera id 0 is not front facing. if it is, and is the highest resolution, it will stay as the front camera (unwanted functionality).
            // it is very rare that a front camera will have the highest resolution, but it is better to be safe.
            if (cameraManager.getCameraCharacteristics(currentLarge).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT){
                currentLargest = getResolution(cameraManager, camera1[index+1]);
                currentLarge = camera1[index+1];
                index++;
            }
            //check if the next camera is higher resolution than the currently selected one
            for (int i = index+1; i < cameraManager.getCameraIdList().length; i++) {
                if (cameraManager.getCameraCharacteristics(camera1[i]).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT){
                    continue;
                }
                Size temp = getResolution(cameraManager, camera1[i]);
                if (Long.signum((currentLargest.getWidth() * (long)currentLargest.getHeight()) - (temp.getWidth() * (long)temp.getHeight())) < 0) {
                    currentLargest = temp;
                    currentLarge = camera1[i];
                }
            }

            //get the characteristics of the camera chosen
            CameraCharacteristics cameraCharacteristics = cameraManager.getCameraCharacteristics(currentLarge);

            //list its available AE modes
            final int[] availableAeModes = cameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES);
            Log.d("AE_MODE", "Target mode ID: " + CameraCharacteristics.CONTROL_AE_MODE_OFF + "\nAvailable IDs:");
            for(int mode : availableAeModes){
                if (mode == CameraCharacteristics.CONTROL_AE_MODE_OFF) {
                    aeModeOffAvailable = true;
                    break;
                }
            }
            //list its available ISOs
            final Range<Integer> isoRange = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            if(null != isoRange) {
                Log.d("AE_MODE","iso range => lower : " + isoRange.getLower() + ", higher : " + isoRange.getUpper());
            }
            //list its available exposure times
            final Range<Long>  exposureTimeRange = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            if(null!=exposureTimeRange){
                Log.d("AE_MODE","exposure time range => lower : " + exposureTimeRange.getLower() + ", higher : " + exposureTimeRange.getUpper());
            }

            //set the preview of the camera and set the image reader for callbacks when images are taken
            StreamConfigurationMap map = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            int deviceOrientation = getWindowManager().getDefaultDisplay().getRotation();
            mTotalRotation = sensorToDeviceRotation(cameraCharacteristics, deviceOrientation);
            boolean swapRotation = mTotalRotation == 90 || mTotalRotation == 270;
            int rotatedWidth = width;
            int rotatedHeight = height;
            if (swapRotation) {
                rotatedWidth = height; // this is for rotation, it is possible it may throw a warning, its no issue
                rotatedHeight = width; // this too
            }
            mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class), rotatedWidth, rotatedHeight);
            Size mImageSize = chooseOptimalSize(map.getOutputSizes(imageFormat), rotatedWidth, rotatedHeight);
            mImageReader = ImageReader.newInstance(mImageSize.getWidth(), mImageSize.getHeight(), imageFormat, 1);
            mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, mBackgroundHandler);
            mCameraId = currentLarge;
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(mCameraId);
            hyperfocus = characteristics.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE);

            Log.d("CameraSetting", "Hyperfocus: " + hyperfocus);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    // first checks for permission then opens the camera with the selected cameraID found in the setupCamera method.
    private void connectCamera() {
        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED) {
                cameraManager.openCamera(mCameraId, mCameraDeviceStateCallback, mBackgroundHandler);
            } else {
                if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                    Toast.makeText(this,
                            "Video app required access to camera", Toast.LENGTH_SHORT).show();
                }
                requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO
                }, REQUEST_CAMERA_PERMISSION_RESULT);
            }

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    // the method that displays what the camera sees on screen. Since the resolution is currently stretched, in the xml,
    // we can fix it to display the image correctly. Not a huge deal since the user will not be looking at the screen during operation
    // and the image saved looks correct, but could be fixed for quality purposes.
    private void startPreview() {
        SurfaceTexture surfaceTexture = mTextureView.getSurfaceTexture();
        surfaceTexture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
        Surface previewSurface = new Surface(surfaceTexture);

        try {
            mCaptureRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mCaptureRequestBuilder.addTarget(previewSurface);

            mCameraDevice.createCaptureSession(Arrays.asList(previewSurface, mImageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            Log.d(TAG, "onConfigured: startPreview");
                            mPreviewCaptureSession = session;
                            try {
                                mPreviewCaptureSession.setRepeatingRequest(mCaptureRequestBuilder.build(),
                                        null, mBackgroundHandler);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            Log.d(TAG, "onConfigureFailed: startPreview");

                        }
                    }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    // this is the code that actually captures an image. if you need it to take a burst photo, call this function multiple times.
    //private void startStillCaptureRequest(CaptureRequest.Builder builder) {
    private void startStillCaptureRequest(long exposureTime) {
        try {
            mCaptureRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            //mCaptureRequestBuilder = builder;

            mCaptureRequestBuilder.addTarget(mImageReader.getSurface());
            mCaptureRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION, mTotalRotation);
            if(aeModeOffAvailable) {
                mCaptureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
                mCaptureRequestBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF);
                mCaptureRequestBuilder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX);
                mCaptureRequestBuilder.set(CaptureRequest.COLOR_CORRECTION_GAINS, colorTemperature(6600));
                mCaptureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
                mCaptureRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, hyperfocus);
                mCaptureRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, 64);  // 63 ISO
                mCaptureRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTime);
                mCaptureRequestBuilder.set(CaptureRequest.JPEG_QUALITY, (byte)100);
            }

            CameraCaptureSession.CaptureCallback stillCaptureCallback = new
                    CameraCaptureSession.CaptureCallback() {
                        @Override
                        public void onCaptureStarted(CameraCaptureSession session, CaptureRequest request, long timestamp, long frameNumber) {
                            super.onCaptureStarted(session, request, timestamp, frameNumber);
                        }
                    };
            //single request
            mPreviewCaptureSession.capture(mCaptureRequestBuilder.build(), stillCaptureCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void startStillCaptureRequest(){
        startStillCaptureRequest(125000L); //default exposure time of 1/8000s, 10000000 is 1/100s
    }

    //this rggb converter snippet was written by Francisco Durdin Garcia on stackoverflow
    //converts kelvin color temperature to color gain matrix
    public static RggbChannelVector colorTemperature(int whiteBalance) {
        float temperature = whiteBalance / 100f;
        float red;
        float green;
        float blue;

        //Calculate red
        if (temperature <= 66)
            red = 255;
        else {
            red = temperature - 60;
            red = (float) (329.698727446 * (Math.pow(red, -0.1332047592)));
            if (red < 0)
                red = 0;
            if (red > 255)
                red = 255;
        }


        //Calculate green
        if (temperature <= 66) {
            green = temperature;
            green = (float) (99.4708025861 * Math.log(green) - 161.1195681661);
        } else {
            green = temperature - 60;
            green = (float) (288.1221695283 * (Math.pow(green, -0.0755148492)));
        }
        if (green < 0)
            green = 0;
        if (green > 255)
            green = 255;

        //calculate blue
        if (temperature >= 66)
            blue = 255;
        else if (temperature <= 19)
            blue = 0;
        else {
            blue = temperature - 10;
            blue = (float) (138.5177312231 * Math.log(blue) - 305.0447927307);
            if (blue < 0)
                blue = 0;
            if (blue > 255)
                blue = 255;
        }

        Log.v(TAG, "red=" + red + ", green=" + green + ", blue=" + blue);
        return new RggbChannelVector((red / 255) * 2, (green / 255), (green / 255), (blue / 255) * 2);
    }

    // not closing the camera can cause a memory leak. as well as create privacy issues that can
    // develop into legal issues. when utilizing the camera always make sure to close it when not in use.
    private void closeCamera() {
        if (mCameraDevice != null) {
            mCameraDevice.close();
            mCameraDevice = null;
        }
        if (mMediaRecorder != null) {
            mMediaRecorder.release();
            mMediaRecorder = null;
        }
    }

    private void startBackgroundThread() {
        mBackgroundHandlerThread = new HandlerThread("Camera2VideoImage");
        mBackgroundHandlerThread.start();
        mBackgroundHandler = new Handler(mBackgroundHandlerThread.getLooper());
    }

    private void stopBackgroundThread() {
        mBackgroundHandlerThread.quitSafely();
        try {
            mBackgroundHandlerThread.join();
            mBackgroundHandlerThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    // this determines which direction the phone is oriented. im assuming that we will want our images to be vertical so it may be possible
    // to remove this if not necessary. i would leave it for now though because removing it may cause further issues we would have
    // to fix
    private static int sensorToDeviceRotation(CameraCharacteristics cameraCharacteristics, int deviceOrientation) {
        int sensorOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        deviceOrientation = ORIENTATIONS.get(deviceOrientation);
        return (sensorOrientation + deviceOrientation + 360) % 360;
    }

    private static Size chooseOptimalSize(Size[] choices, int width, int height) {
        List<Size> bigEnough = new ArrayList<>();
        for (Size option : choices) {
            if (option.getHeight() == option.getWidth() * height / width &&
                    option.getWidth() >= width && option.getHeight() >= height) {
                bigEnough.add(option);
            }
        }
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizeByArea());
        } else {
            return choices[0];
        }
    }

    // creates a folder in local files to store images at. it also stores them in the gallery
    @SuppressWarnings("ResultOfMethodCallIgnored")
    private void createImageFolder() {
        //external directory at `/Pictures/`, will not be deleted upon uninstall
        File imageFile = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);

        //internal directory, will be deleted upon uninstall
        //File imageFile = getFilesDir().getAbsoluteFile();

        //make folder at above location named camera2VideoImage
        mImageFolder = new File(imageFile, "SunSketcher");
        if (!mImageFolder.exists()) {
            mImageFolder.mkdirs();
            prefs = getSharedPreferences("eclipseDetails", Context.MODE_PRIVATE);
            SharedPreferences.Editor prefEdit = prefs.edit();
            prefEdit.putString("imageFolderDirectory", mImageFolder.getAbsolutePath());
            prefEdit.apply();
        }
    }

    // names the files. in here you may be able to change the file type based on the extension, might want to look into that if we want to save
    // RAW filetypes instead of jpg, which are lossy.
    private void createImageFileName() throws IOException {
        long timestampLong = System.currentTimeMillis();// + offset;
        String timestamp = "" + timestampLong;// + "NTP";

        //create image metadata
        SharedPreferences prefs = getSharedPreferences("eclipseDetails", Context.MODE_PRIVATE);
        float lat = prefs.getFloat("lat", 0f);
        float lon = prefs.getFloat("lon", 0f);
        float alt = prefs.getFloat("alt", 0f);

        //String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()); // also saves a timestamp which we can use to
        // create metadata files. Additionally, saves some weird number to the end of the filename. Not sure how to prevent that.
        String prepend = "IMAGE_" + timestamp;
        //File imageFile = File.createTempFile(prepend, ".jpg", mImageFolder);
        File imageFile = new File(mImageFolder, prepend + ".jpg");
        mImageFileName = imageFile.getAbsolutePath();

        db.addMetadata(new Metadata(mImageFileName, (double)lat, (double)lon, (double)alt, timestampLong, 0, 0, 0, 0, "", false));
    }

    private void lockFocus() {
        mCaptureState = STATE_WAIT_LOCK;
        mCaptureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
        try {
            mPreviewCaptureSession.capture(mCaptureRequestBuilder.build(), mPreviewCaptureCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }


    // Iterates through all the camera devices in the phone and returns the largest resolution camera and returns a resolution.
    // could not change it to return a cameraID so when implementing the method to find a cameraID be sure to create a string array
    // that stores the values of the cameraID's based on an iterator.

    @NonNull
    public Size getResolution(@NonNull final CameraManager cameraManager, @NonNull final String cameraId) throws CameraAccessException
    {
        final CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
        final StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

        // if there is no camera
        if (map == null)
        {
            throw new IllegalStateException("Failed to get configuration map.");
        }

        // stores the output sizes in JPEG format. im not sure if this will cause an issue if we try to store RAW type files.
        // if errors occur when trying to set up RAW file types try changing this line to ImageFormat.JPEG
        final Size[] choices = map.getOutputSizes(imageFormat);

        Log.d("Image_Resolution", Arrays.toString(choices));

        Arrays.sort(choices, Collections.reverseOrder((lhs, rhs) -> {
            // Cast to ensure the multiplications won't overflow
            return Long.signum((lhs.getWidth() * (long)lhs.getHeight()) - (rhs.getWidth() * (long)rhs.getHeight()));
        }));

        return choices[0];
    }

}
