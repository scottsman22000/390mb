package edu.umass.cs.client;

import java.util.ArrayList;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import edu.umass.cs.accelerometer.Filter;

/**
 *
 * Context_Service: This is a sample class to reads sensor data (accelerometer).
 *
 * @author CS390MB
 *
 */
public class Context_Service extends Service implements SensorEventListener {

    //my variables

    double dynamicThreshold = 0;
    int thresholdXYZ = 0; // 0 = x, 1 = y, 2 = z
    double[] maxThreshold = {0, 0, 0};
    double[] minThreshold = {100, 100, 100};
    int thresholdCounter = 0;
    long previousStep = System.currentTimeMillis();
    int thresholdReachedCounter = 0;

    double newSample = 0;
    double oldSample = 0;

    double x = 0;
    double y = 0;
    double z = 0;

    double padding = 1.5;

    /**
     * Notification manager to display notifications
     */
    private NotificationManager nm;

    /**
     * SensorManager
     */
    private SensorManager mSensorManager;
    /**
     * Accelerometer Sensor
     */
    private Sensor mAccelerometer;

    //List of bound clients/activities to this service
    ArrayList<Messenger> mClients = new ArrayList<Messenger>();

    //Message codes sent and received by the service
    static final int MSG_REGISTER_CLIENT = 1;
    static final int MSG_UNREGISTER_CLIENT = 2;
    static final int MSG_ACTIVITY_STATUS = 3;
    static final int MSG_STEP_COUNTER = 4;
    static final int MSG_ACCEL_VALUES = 5;
    static final int MSG_START_ACCELEROMETER = 6;
    static final int MSG_STOP_ACCELEROMETER = 7;
    static final int MSG_ACCELEROMETER_STARTED = 8;
    static final int MSG_ACCELEROMETER_STOPPED = 9;

    static Context_Service sInstance = null;
    private static boolean isRunning = false;
    private static boolean isAccelRunning = false;
    private static final int NOTIFICATION_ID = 777;

    /**
     * Filter class required to filter noise from accelerometer
     */
    private Filter filter = null;
    /**
     * Step count to be displayed in UI
     */
    private int stepCount = 0;

    //Messenger used by clients
    final Messenger mMessenger = new Messenger(new IncomingHandler());

    /**
     * Handler to handle incoming messages
     */
    @SuppressLint("HandlerLeak")
    class IncomingHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_REGISTER_CLIENT:
                    mClients.add(msg.replyTo);
                    break;
                case MSG_UNREGISTER_CLIENT:
                    mClients.remove(msg.replyTo);
                    break;
                case MSG_START_ACCELEROMETER: {
                    isAccelRunning = true;
                    mSensorManager.registerListener(sInstance, mAccelerometer, SensorManager.SENSOR_DELAY_GAME);
                    sendMessageToUI(MSG_ACCELEROMETER_STARTED);
                    showNotification();
                    //Set up filter
                    //Following sets up smoothing filter from mcrowdviz
                    int SMOOTH_FACTOR = 4;
                    filter = new Filter(SMOOTH_FACTOR);
                    //OR Use Butterworth filter from mcrowdviz
                    //double CUTOFF_FREQUENCY = 0.3;
                    //filter = new Filter(CUTOFF_FREQUENCY);
                    stepCount = 0;
                    break;
                }
                case MSG_STOP_ACCELEROMETER: {
                    isAccelRunning = false;
                    mSensorManager.unregisterListener(sInstance);
                    sendMessageToUI(MSG_ACCELEROMETER_STOPPED);
                    showNotification();
                    //Free filter and step detector
                    filter = null;
                    break;
                }
                default:
                    super.handleMessage(msg);
            }
        }
    }


    private void sendMessageToUI(int message) {
        for (int i = mClients.size() - 1; i >= 0; i--) {
            try {
                // Send message value
                mClients.get(i).send(Message.obtain(null, message));
            } catch (RemoteException e) {
                // The client is dead. Remove it from the list; we are going through the list from back to front so this is safe to do inside the loop.
                mClients.remove(i);
            }
        }
    }

    private void sendAccelValuesToUI(float accX, float accY, float accZ) {
        for (int i = mClients.size() - 1; i >= 0; i--) {
            try {

                //Send Accel Values
                Bundle b = new Bundle();
                b.putFloat("accx", accX);
                b.putFloat("accy", accY);
                b.putFloat("accz", accZ);
                Message msg = Message.obtain(null, MSG_ACCEL_VALUES);
                msg.setData(b);
                mClients.get(i).send(msg);

            } catch (RemoteException e) {
                // The client is dead. Remove it from the list; we are going through the list from back to front so this is safe to do inside the loop.
                mClients.remove(i);
            }
        }
    }

    private void sendUpdatedStepCountToUI() {
        for (int i = mClients.size() - 1; i >= 0; i--) {
            try {
                //Send Step Count
                Message msg = Message.obtain(null, MSG_STEP_COUNTER, stepCount, 0);
                mClients.get(i).send(msg);

            } catch (RemoteException e) {
                // The client is dead. Remove it from the list; we are going through the list from back to front so this is safe to do inside the loop.
                mClients.remove(i);
            }
        }
    }


    /**
     * On Binding, return a binder
     */
    @Override
    public IBinder onBind(Intent intent) {
        return mMessenger.getBinder();
    }


    //Start service automatically if we reboot the phone
    public static class Context_BGReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Intent bootUp = new Intent(context, Context_Service.class);
            context.startService(bootUp);
        }
    }

    @SuppressWarnings("deprecation")
    private void showNotification() {
        //Cancel previous notification
        if (nm != null)
            nm.cancel(NOTIFICATION_ID);
        else
            nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        // The PendingIntent to launch our activity if the user selects this notification
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), 0);

        // Use the commented block of code if your target environment is Android-16 or higher
      /*Notification notification = new Notification.Builder(this)
      .setContentTitle("Context Service")
      .setContentText("Running").setSmallIcon(R.drawable.icon)
      .setContentIntent(contentIntent)
      .build();

      nm.notify(NOTIFICATION_ID, notification); */

        //For lower versions of Android, the following code should work
        Notification notification = new Notification();
        notification.icon = R.drawable.icon;
        notification.tickerText = getString(R.string.app_name);
        notification.contentIntent = contentIntent;
        notification.when = System.currentTimeMillis();
        if (isAccelerometerRunning())
            notification.setLatestEventInfo(getApplicationContext(), getString(R.string.app_name), "Accelerometer Running", contentIntent);
        else
            notification.setLatestEventInfo(getApplicationContext(), getString(R.string.app_name), "Accelerometer Not Started", contentIntent);

        // Send the notification.
        nm.notify(NOTIFICATION_ID, notification);
    }


    /* getInstance() and isRunning() are required by the */
    static Context_Service getInstance() {
        return sInstance;
    }

    protected static boolean isRunning() {
        return isRunning;
    }

    protected static boolean isAccelerometerRunning() {
        return isAccelRunning;
    }


    @Override
    public void onCreate() {
        super.onCreate();
        showNotification();
        isRunning = true;
        sInstance = this;
        mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        mAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        nm.cancel(NOTIFICATION_ID); // Cancel the persistent notification.
        isRunning = false;
        //Don't let Context_Service die!
        Intent mobilityIntent = new Intent(this, Context_Service.class);
        startService(mobilityIntent);
    }


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY; // run until explicitly stopped.
    }


    /* (non-Javadoc)
     * @see android.hardware.SensorEventListener#onAccuracyChanged(android.hardware.Sensor, int)
     */
    @Override
    public void onAccuracyChanged(Sensor arg0, int arg1) {

    }

    /* (non-Javadoc)
     * @see android.hardware.SensorEventListener#onSensorChanged(android.hardware.SensorEvent)
     */
    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {

            float accel[] = event.values;
            sendAccelValuesToUI(accel[0], accel[1], accel[2]);


            /**
             * TODO: Step Detection
             */
            //First, Get filtered values
            double filtAcc[] = filter.getFilteredValues(accel[0], accel[1], accel[2]);


            //Now, increment 'stepCount' variable if you detect any steps here
            stepCount += detectSteps(filtAcc[0], filtAcc[1], filtAcc[2]);
            //detectSteps() is not implemented
            sendUpdatedStepCountToUI();

        }

    }

    /**
     * This should return number of steps detected.
     *
     * @param filt_acc_x
     * @param filt_acc_y
     * @param filt_acc_z
     * @return
     */
    public int detectSteps(double filt_acc_x, double filt_acc_y, double filt_acc_z) {

        setDynamicThreshold(filt_acc_x, filt_acc_y, filt_acc_z);

        //Log.i("", "entered the detect steps");
        if (thresholdXYZ == 0) {
            //Log.i("", "entered the detect steps X PORTION!!: "+ Math.abs(filt_acc_x));
            //Log.i("","this is the dynamimc threshold: " + Math.abs(dynamicThreshold)+padding);
            if (Math.abs(filt_acc_x) > Math.abs(dynamicThreshold) && checkTime()) {
                //Log.i("", "entered the detect steps X PORTION!!: "+ Math.abs(filt_acc_x));
                //Log.i("","this is the dynamimc threshold: " + Math.abs(dynamicThreshold));
                return 1;
            }


        }
        if (thresholdXYZ == 1) {
            if (Math.abs(filt_acc_y) > Math.abs(dynamicThreshold) + padding && checkTime()) {
                //Log.i("", "entered the detect steps Y PORTION!!");
                return 1;
            }
        }
        if (thresholdXYZ == 2) {
            //Log.i("", "THIS IS THE VALUE OF THE THRESHOLD FOR Z: " + Math.abs(dynamicThreshold));
            if (Math.abs(filt_acc_z) > Math.abs(dynamicThreshold) + padding && checkTime()) {
                //Log.i("", "entered the detect steps z  INNER-part");
                return 1;
            }

            /*oldSample = newSample;

            if(Math.abs(filt_acc_z) - Math.abs(newSample) > 1){
                newSample = filt_acc_z;
            }*/

        }


        return 0;
    }

    public void setDynamicThreshold(double filt_acc_x, double filt_acc_y, double filt_acc_z) {
        //Log.i("", "Just entered the dynamimcThreshould call");
        if (thresholdCounter >= 50) {
            thresholdReachedCounter++;
            if (thresholdReachedCounter == 1) {
                stepCount = 0;
            }

            //Log.i("", "entered the 50 or more mark");
            //finding the max value out of the three


            x = (maxThreshold[0] + minThreshold[0]) / 2;
            y = (maxThreshold[1] + minThreshold[1]) / 2;
            z = (maxThreshold[2] + minThreshold[2]) / 2;


            if (x >= y && x >= z) {
                //Log.i("", "set x");
                dynamicThreshold = x;
                newSample = filt_acc_x;
                thresholdXYZ = 0;
            } else if (y >= x && y >= z) {
                //Log.i("", "set y");
                dynamicThreshold = y;
                newSample = filt_acc_y;
                thresholdXYZ = 1;
            } else {
                //Log.i("", "set z");
                dynamicThreshold = z;
                newSample = filt_acc_z;
                thresholdXYZ = 2;
            }

            thresholdCounter = 0;
            maxThreshold[0] = 0;
            maxThreshold[1] = 0;
            maxThreshold[2] = 0;
            minThreshold[0] = 100;
            minThreshold[1] = 100;
            minThreshold[2] = 100;

        } else {
            //For Max
            //Log.i("", "got to the max min area");
            if (filt_acc_x > maxThreshold[0]) {
                maxThreshold[0] = filt_acc_x;
            }
            if (filt_acc_y > maxThreshold[1]) {
                maxThreshold[1] = filt_acc_y;
            }
            if (filt_acc_z > maxThreshold[2]) {
                maxThreshold[2] = filt_acc_z;
            }
            //For Min
            if (filt_acc_x < minThreshold[0]) {
                minThreshold[0] = filt_acc_x;
            }
            if (filt_acc_y < minThreshold[1]) {
                minThreshold[1] = filt_acc_y;
            }
            if (filt_acc_z < minThreshold[2]) {
                minThreshold[2] = filt_acc_z;
            }

            thresholdCounter++;
        }

    }

    public boolean checkTime() {
        //Log.i("", "This is the time: " + System.currentTimeMillis());
        long temp = System.currentTimeMillis();
        int tempDiff = (int)(temp - previousStep);

        previousStep = temp;

        if (tempDiff > 200 && tempDiff < 2000) {
            //&& System.currentTimeMillis()-previousStep < 2
            //this is a valid step
            Log.i("inIf", String.valueOf(tempDiff));
            return true;
        }

        Log.i("inIfAfter", "false");
        return false;
    }

}
