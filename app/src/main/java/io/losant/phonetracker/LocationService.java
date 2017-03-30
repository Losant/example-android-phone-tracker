package io.losant.phonetracker;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;
import android.telecom.ConnectionRequest;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.common.api.GoogleApiClient.ConnectionCallbacks;
import com.google.android.gms.common.api.GoogleApiClient.OnConnectionFailedListener;
import com.google.android.gms.location.LocationListener;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URL;
import java.text.DateFormat;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

import javax.net.ssl.HttpsURLConnection;

/**
 * Created by brandon on 1/19/16.
 */
public class LocationService extends Service implements
    ConnectionCallbacks, OnConnectionFailedListener, LocationListener  {

    private int sId = 4545;
    private GoogleApiClient mGoogleApiClient;
    private LocationRequest mLocationRequest;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d("Location Service", "Starting Service");
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        Log.d("Location Service", "Creating Service");

        // Setup the notification that is displayed when
        // we run this service in the foreground.
        NotificationCompat.Builder mBuilder =
            new NotificationCompat.Builder(this);
        mBuilder.setSmallIcon(android.R.drawable.ic_menu_mylocation);
        mBuilder.setContentTitle("Losant Location Service");
        mBuilder.setContentText("Collecting location data");

        // Run the service in the foreground. This keeps the service
        // as a high-priority process so Android doesn't idle it out
        // eventually.
        startForeground(sId, mBuilder.build());

        // Create the API client so we can use Google's
        // location services.
        mGoogleApiClient = new GoogleApiClient.Builder(this)
            .addConnectionCallbacks(this)
            .addOnConnectionFailedListener(this)
            .addApi(LocationServices.API)
            .build();

        mGoogleApiClient.connect();

        // Setup the location request to get location
        // every 5 seconds. The actual request is made in onConnected.
        mLocationRequest = new LocationRequest();
        mLocationRequest.setInterval(300000);
        mLocationRequest.setFastestInterval(300000);
        mLocationRequest.setPriority(
            LocationRequest.PRIORITY_HIGH_ACCURACY);
    }

    @Override
    public void onDestroy() {
        Log.d("Location Service", "Destroying Service");
        mGoogleApiClient.disconnect();
    }

    @Override
public void onConnected(Bundle connectionHint) {
    LocationServices.FusedLocationApi.requestLocationUpdates(
            mGoogleApiClient, mLocationRequest, this);
}

    @Override
    public void onConnectionSuspended(int cause) {
        Log.d("Location Service", "Connection Suspended");

    }

    @Override
    public void onConnectionFailed(ConnectionResult result) {
        Log.d("Location Service", "Connection failed");
    }


    @Override
    public void onLocationChanged(Location location) {

        try {

            // The API endpoint - /application/:appId/device/:deviceId
            URL url = new URL(
                "https://api.losant.com/" +
                "applications/56919b1a9d206d0100c54152/" +
                "devices/56919b3c9d206d0100c54153/state");

            // Setup the Https request.
            HttpsURLConnection urlConnection =
                (HttpsURLConnection) url.openConnection();

            urlConnection.addRequestProperty(
                "Content-Type", "application/json");
            urlConnection.addRequestProperty(
                "Authorization", "YOUR AUTH HEADER");

            urlConnection.setDoOutput(true);
            urlConnection.setRequestMethod("POST");
            urlConnection.setChunkedStreamingMode(0);

            // StreamWriter used to send state information to API.
            OutputStreamWriter out =
                new OutputStreamWriter(urlConnection.getOutputStream());

            // Conver the GPS coordinates into GPS NMEA GLL format.
            // Android doesn't have a good helper for this, so
            // doing it manually.
            TimeZone tz = TimeZone.getTimeZone("UTC");
            DateFormat nmeaDateFormat = new SimpleDateFormat("HHmmss");
            nmeaDateFormat.setTimeZone(tz);

            String lat = Location.convert(Math.abs(location.getLatitude()),
                Location.FORMAT_MINUTES);
            String lon = Location.convert(Math.abs(location.getLongitude()),
                Location.FORMAT_MINUTES);

            String degreeLat = lat.substring(0, lat.indexOf(":"));
            String degreeLon = lon.substring(0, lon.indexOf(":"));

            DecimalFormat df = new DecimalFormat("00.00000");

            String minutesLat = df.format(
                Double.parseDouble(lat.substring(lat.indexOf(":") + 1)));
            String minutesLon = df.format(
                Double.parseDouble(lon.substring(lon.indexOf(":") + 1)));

            String nmea = "$GPGLL,";
            nmea += degreeLat + minutesLat + ",N,";
            nmea += degreeLon + minutesLon + ",W,";
            nmea += nmeaDateFormat.format(new Date()) + ",";
            nmea += "A,";

            // Calculate the NMEA checksum.
            int checkSum = nmea.charAt(1);
            for(int i = 2; i < nmea.length(); i++) {
                checkSum = checkSum ^ (int)nmea.charAt(i);
            }

            nmea += "*" + Integer.toString(checkSum, 16);

            Log.d("Location Service", nmea);

            // Build the JSON payload.
            DateFormat payloadDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
            payloadDateFormat.setTimeZone(tz);

            JSONObject state = new JSONObject();
            state.put("time", payloadDateFormat.format(new Date()));

            JSONObject data = new JSONObject();
            data.put("location", nmea);
            data.put("speed", location.getSpeed());
            data.put("altitude", location.getAltitude());

            state.put("data", data);

            String result = state.toString();
            System.out.println(result);

            out.write(result);
            out.close();

            BufferedReader in = new BufferedReader(new InputStreamReader(urlConnection.getInputStream()));
            while((result = in.readLine()) != null) {
                System.out.println(result);
            }

            in.close();
            urlConnection.disconnect();
        }
        catch(Exception e) {
            Log.e("Error", e.toString());
        }
    }

}
