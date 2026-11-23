package com.wkuxr.sunsketcher.networking;

import static com.wkuxr.sunsketcher.activities.SendConfirmationActivity.Companion;
import static com.wkuxr.sunsketcher.activities.SendConfirmationActivity.prefs;
import static com.wkuxr.sunsketcher.database.MetadataDB.db;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.wkuxr.sunsketcher.App;
import com.wkuxr.sunsketcher.database.Metadata;
import com.wkuxr.sunsketcher.database.MetadataDAO;
import com.wkuxr.sunsketcher.database.MetadataDB;

import java.net.*;
import java.io.*;
import java.security.SecureRandom;
import java.util.*;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Key;

import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class ClientRunOnTransfer {
    static SharedPreferences prefs;

    static SecretKey aesKey;
    static BufferedReader fromServer;
    static DataOutputStream toServer;

    public static boolean clientTransferSequence() throws Exception {
        Log.d("NetworkTransfer", "Loading...");
        prefs = App.getContext().getSharedPreferences("eclipseDetails", Context.MODE_PRIVATE);
        MetadataDB.Companion.createDB(App.getContext());

        Log.d("NetworkTransfer", "Checkpoint 0");
        Socket socket = new Socket();
        socket.setSoTimeout(2000);
        socket.connect(new InetSocketAddress("161.6.61.49", 10000), 10000);
        Log.d("NetworkTransfer", "Created Socket");

        //continue only if client is from the US
        fromServer = new BufferedReader(new InputStreamReader(socket.getInputStream()));

        String clearToSend = fromServer.readLine();

        Log.d("NetworkTransfer", "Clear to send received.");
        Log.d("NetworkTransfer", clearToSend);

        if (!clearToSend.contains("true")) {
            //do not retry
            Log.d("NetworkTransfer", "Clear to send is false.");
            prefs.edit().putInt("finishedUpload", 2).apply();
            //the intent here is that 2 indicates that the client is ineligible for transfer at all, and should not retry
            //because the function returns true the app will not attempt to retry
            return true;
        }

        toServer = new DataOutputStream(socket.getOutputStream());
        toServer.writeBytes("TransferRequest\n");
        toServer.flush();

        Log.d("NetworkTransfer", "Clear to send is true.");

        //in event client is from US
        //attempt to complete transfer
        boolean success = startTransfer(socket);

        prefs.edit().putInt("finishedUpload", 1).apply();
        //trust, this could be null if the transfer fails
        return success;
    }


    static boolean startTransfer(Socket socket) throws Exception {
        Log.d("NetworkTransfer", "Starting Transfer request...");
        //Authentication and Security
        //Generate RSA key needed for authentication and security
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");//KeyProperties.KEY_ALGORITHM_RSA);

        keyPairGenerator.initialize(2048);
        Log.d("NetworkTransfer", "Key Generator Initialized");

        KeyPair keyPair = keyPairGenerator.generateKeyPair();
        Log.d("NetworkTransfer", "Keys Generated");

        PublicKey publicKey = keyPair.getPublic();
        PrivateKey privateKey = keyPair.getPrivate();
        Log.d("NetworkTransfer", "keys initialized");
        Log.d("NetworkTransfer", "public key value: " + Arrays.toString(publicKey.getEncoded()));


        BufferedReader fromServer = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        Log.d("NetworkTransfer", "checkpoint 1");
        DataOutputStream toServer = new DataOutputStream(socket.getOutputStream());
        toServer.flush();

        Log.d("NetworkTransfer", "checkpoint 2");


        Log.d("NetworkTransfer", "Communication streams open");

        //Send public key to server
        Base64.Encoder encoder = Base64.getEncoder();
        String publicKeyString = new String(encoder.encode(publicKey.getEncoded()));
        Log.d("NetworkTransfer", "checkpoint 3");
        Log.d("NetworkTransfer", publicKeyString);
        toServer.writeBytes(publicKeyString + '\n');
        toServer.flush();
        Log.d("NetworkTransfer", "Public key sent");

        //Receive AES key from server
        String encryptedMessage = fromServer.readLine();

        Log.d("NetworkTransfer", encryptedMessage);


        byte[] decodedBytes = Base64.getDecoder().decode(encryptedMessage);
        Log.d("NetworkTransfer", "Encrypted key value " + Arrays.toString(decodedBytes));


        Log.d("NetworkTransfer", "Aes key received.");

        // Decrypt key using private key
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        Log.d("NetworkTransfer", "Cipher created");
        cipher.init(Cipher.DECRYPT_MODE, privateKey);
        Log.d("NetworkTransfer", "cipher initialized");
        byte[] decryptedMessage = cipher.doFinal(decodedBytes);
        Log.d("NetworkTransfer", "key decrypted");
        Log.d("NetworkTransfer", "Decrypted key value " + Arrays.toString(decryptedMessage));
        aesKey = new SecretKeySpec(decryptedMessage, "AES");
        Log.d("NetworkTransfer", "Aes key acquired");


        //Encrypt passkey with AES key and send to server
        send("SarahSketcher2024");


        Log.d("NetworkTransfer", "Connection Successful!");

        SharedPreferences prefs = App.getContext().getSharedPreferences("eclipseDetails", Context.MODE_PRIVATE);


        //-------------------------------------------------------------------------------------------------------------------
        //begin transfer messaging


        //--------------------------------------------------------------------------------------------------------------
        //Value Initialization

        double latitude = 0;
        double longitude = 0;
        double altitude = 0;
        long time = 0;
        double aperture;
        double iso;
        double whitebalance;
        String focallength;
        double exposure;

        db.initialize();
        MetadataDAO metadataDao = db.metadataDao();
        List<Metadata> metadataList = metadataDao.getAllImageMetas();

        Log.d("NetworkTransfer", "Loading...");
        int currentPhoto = 0;
        String currentName = "nameError";

        Log.d("NetworkTransfer", "Connection Successful!");


        int clientID = (int) prefs.getLong("clientID", 9999999);


        //-------------------------------------------------------------------------------------------------------------------
        //begin transfer messaging


        //send client ID
        send(Integer.toString(clientID));

        //send total number of photos
        send(Integer.toString(metadataList.size()));

        for (Metadata metadata : metadataList) {
            Context context = App.getContext();
            prefs = context.getSharedPreferences("eclipseDetails", Context.MODE_PRIVATE);
            if (metadata.getId() > (prefs.getInt("numUploaded", -1) + 1)) {

                //initialize values
                String[] filepathSplit = metadata.getFilepath().split("/");
                currentName = filepathSplit[filepathSplit.length - 1];

                File file = new File(metadata.getFilepath());
                byte[] imageData = new byte[(int) file.length()];

                //---------------------------------------------------------------------------------------------------------
                //send pseudometadata

                Log.d("NetworkTransfer", "current name = " + currentName);
                send(currentName);

                Log.d("NetworkTransfer", "File length = " + (int) file.length());
                send(Integer.toString((int) file.length()));

                //---------------------------------------------------------------------------------------------------------
                //gather and send image
                Log.d("NetworkTransfer", "Importing Photo " + (metadataList.size() - prefs.getInt("numUploaded", -1)) + " ...");
                FileInputStream fileIn = new FileInputStream(file);
                fileIn.read(imageData);

                Log.d("NetworkTransfer", "Starting Transfer...");

                //send one photo to the server
                send(imageData);
                fileIn.close();

                //----------------------------------------------------------------------------------------------------------
                //metadata transfer

                latitude = metadata.getLatitude();
                longitude = metadata.getLongitude();
                altitude = metadata.getAltitude();
                time = metadata.getCaptureTime();
                aperture = metadata.getFstop();
                iso = metadata.getIso();
                whitebalance = metadata.getWhiteBalance();
                focallength = metadata.getFocalDistance();
                exposure = metadata.getExposure();

                // send metadata to server
                send(Double.toString(latitude));
                send(Double.toString(longitude));
                send(Double.toString(altitude));
                send(Long.toString(time));
                send(Double.toString(aperture));
                send(Double.toString(iso));
                send(Double.toString(whitebalance));
                send(focallength);
                send(Double.toString(exposure));


                Log.d("NetworkTransfer", "Transfer Successful!");
                SharedPreferences.Editor prefEdit = prefs.edit();

                //increment pref value for number of images uploaded after each successful upload, such that the number can be used to pick up transfer from where it was left off if connection was dropped
                prefEdit.putInt("numUploaded", prefs.getInt("numUploaded", -1) + 1);
                prefEdit.commit();
            }
        }


        //this conditional here waits for the server to tell the client that all data has been successfully received but it actually seems to not work properly on the server side so we removed it after v1.0.6
        /*if (receive().equals("freeToDisconnect")) {
            socket.close();
            Log.d("NetworkTransfer", "Program Complete. Closing...");
            return true;
        }*/
        return true;
    }

    public static void send(String message) throws Exception {
        Cipher AEScipher = Cipher.getInstance("AES/GCM/NoPadding");

        // Generate nonce
        byte[] nonce = generateNonce(AEScipher.getBlockSize());

        // Initialize cipher with nonce
        AEScipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(128, nonce));

        // Encrypt message
        byte[] encryptedMessage = AEScipher.doFinal(message.getBytes());

        Base64.Encoder encoder = Base64.getEncoder();

        // Concatenate nonce and encrypted message
        String encryptedEncodedMessage = encoder.encodeToString(nonce) + ":" +
                encoder.encodeToString(encryptedMessage);

        toServer.writeBytes(encryptedEncodedMessage + '\n');
        toServer.flush();
    }

    public static void send(byte[] message) throws Exception {
        Cipher AEScipher = Cipher.getInstance("AES/GCM/NoPadding");

        // Generate nonce
        byte[] nonce = generateNonce(AEScipher.getBlockSize());

        // Initialize cipher with nonce
        AEScipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(128, nonce));

        // Encrypt message
        byte[] encryptedMessage = AEScipher.doFinal(message);

        Base64.Encoder encoder = Base64.getEncoder();

        // Concatenate nonce and encrypted message
        String encryptedEncodedMessage = encoder.encodeToString(nonce) + ":" +
                encoder.encodeToString(encryptedMessage);

        toServer.writeBytes(encryptedEncodedMessage + '\n');
        toServer.flush();

    }

    public static byte[] receiveBytes() throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");

        // Read nonce and encrypted message
        String encryptedEncodedMessage = fromServer.readLine();
        String[] parts = encryptedEncodedMessage.split(":");
        byte[] nonce = Base64.getDecoder().decode(parts[0]);
        byte[] decodedBytes = Base64.getDecoder().decode(parts[1]);

        cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(128, nonce));

        // Decrypt message
        return cipher.doFinal(decodedBytes);
    }

    public static String receive() throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");

        // Read nonce and encrypted message
        String encryptedEncodedMessage = fromServer.readLine();
        String[] parts = encryptedEncodedMessage.split(":");
        byte[] nonce = Base64.getDecoder().decode(parts[0]);
        byte[] decodedBytes = Base64.getDecoder().decode(parts[1]);

        cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(128, nonce));

        // Decrypt message
        byte[] decryptedMessage = cipher.doFinal(decodedBytes);
        return new String(decryptedMessage);
    }

    private static byte[] generateNonce(int size) {
        byte[] nonce = new byte[size];
        SecureRandom secureRandom = new SecureRandom();
        secureRandom.nextBytes(nonce);
        return nonce;
    }
}