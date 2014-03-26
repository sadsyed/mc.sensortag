package com.mc.sensortag.personalhealthassistant;

import static ti.android.ble.sensortag.SensorTag.UUID_ACC_DATA;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import ti.android.ble.common.BluetoothLeService;
import ti.android.ble.common.GattInfo;
import ti.android.ble.sensortag.Sensor;
import ti.android.ble.sensortag.SensorTag;
import ti.android.util.Point3D;

import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

public class DeviceService extends Service{

	// Log
		private static String TAG = "DeviceActivity";
		public static FileOutputStream accelerometerOutputStream;

		// Activity
		public static final String EXTRA_DEVICE = "EXTRA_DEVICE";

		// BLE
		private BluetoothLeService mBtLeService = null;
		private BluetoothDevice mBluetoothDevice = null;
		private BluetoothGatt mBtGatt = null;
		private List<BluetoothGattService> mServiceList = null;
		private static final int GATT_TIMEOUT = 100; // milliseconds
		private boolean mServicesRdy = false;
		private boolean mIsReceiving = false;

		// SensorTag
		private List<Sensor> mEnabledSensors = new ArrayList<Sensor>();

		// House-keeping
		private DecimalFormat decimal = new DecimalFormat("+0.00;-0.00");

		private TextView accelerometerReadingView;
	@Override
	public IBinder onBind(Intent intent) {
		// TODO Auto-generated method stub
		return null;
	}
	
	@Override
	public int onStartCommand(Intent intent, int flags, int startId){
		Log.e("BACKGROUND", "initializing background service");
		
		//Intent mIntent = getIntent();

		// UI widgets
		//accelerometerReadingView = (TextView) findViewById(R.id.accelerometerReadingView);

		// BLE
		mBtLeService = BluetoothLeService.getInstance();
		mBluetoothDevice = intent.getParcelableExtra(EXTRA_DEVICE);
		mServiceList = new ArrayList<BluetoothGattService>();

		// GATT database
		Resources res = getResources();
		XmlResourceParser xpp = res.getXml(R.xml.gatt_uuid);
		new GattInfo(xpp);
		
	    // Initialize Accelerometer Logs
	    createAccelerometerLogOnDevice();

		// Initialize sensor list
		updateSensorList();

		Log.d(TAG, "Gatt view ready");

		// Set title bar to device name
		//setTitle(mBluetoothDevice.getName());

		// Create GATT object
		mBtGatt = BluetoothLeService.getBtGatt();

		// Start service discovery
		if (!mServicesRdy && mBtGatt != null) {
			if (mBtLeService.getNumServices() == 0)
				discoverServices();
			else
				displayServices();
		}
		
		if (!mIsReceiving) {
			registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());
			mIsReceiving = true;
		}
		
		return START_NOT_STICKY;
	}
	
	@Override
	public boolean onUnbind(Intent intent){
		return super.onUnbind(intent);
	}
	
	private void createAccelerometerLogOnDevice() {
	  	Log.e("AccelerometerLog", "initializing BLE SERVICE");
	  	
	  	File deviceRoot = Environment.getExternalStorageDirectory();
	  	Log.e("AccelerometeLog", deviceRoot.toString());
	  
	  	if(deviceRoot.canWrite()){
	  		File accelerometerLog = new File(deviceRoot, "/baccelerometerLogFile.txt");
	  		try {
	  			
					accelerometerOutputStream = new FileOutputStream(accelerometerLog);
					
					Date timestamp = new Date();
					accelerometerOutputStream.write(( String.valueOf(timestamp) + ": ***** Accelerometer Log *****").getBytes());
					
				} catch (IOException e) {
					e.printStackTrace();
			}
	  	}
  }

	//
	// Application implementation
	//
	private void updateSensorList() {
		mEnabledSensors.clear();

		for (int i = 0; i < Sensor.SENSOR_LIST.length; i++) {
			Sensor sensor = Sensor.SENSOR_LIST[i];
			Log.i(TAG, sensor.name().toString());
			mEnabledSensors.add(sensor);
		}
	}

	private void discoverServices() {
		if (mBtGatt.discoverServices()) {
			Log.i(TAG, "START SERVICE DISCOVERY");
			mServiceList.clear();
			setStatus("Service discovery started");
		} else {
			setError("Service discovery start failed");
		}
	}

	private void displayServices() {
		mServicesRdy = true;

		try {
			mServiceList = mBtLeService.getSupportedGattServices();
		} catch (Exception e) {
			e.printStackTrace();
			mServicesRdy = false;
		}

		// Characteristics descriptor readout done
		if (mServicesRdy) {
			setStatus("Service discovery complete");
			enableSensors(true);
			Log.i(TAG, "**Enabling Sensors**");
			enableNotifications(true);
		} else {
			setError("Failed to read services");
		}
	}

	private void setError(String txt) {
		Log.i(TAG, txt);
	}

	private void setStatus(String txt) {
		Log.i(TAG, txt);
	}

	private void enableSensors(boolean enable) {
		for (Sensor sensor : mEnabledSensors) {
			UUID servUuid = sensor.getService();
			UUID confUuid = sensor.getConfig();

			// Skip keys
			if (confUuid == null)
				break;

			// Barometer calibration
			if (confUuid.equals(SensorTag.UUID_BAR_CONF) && enable) {
				// calibrateBarometer();
			}

			BluetoothGattService serv = mBtGatt.getService(servUuid);
			BluetoothGattCharacteristic charac = serv
					.getCharacteristic(confUuid);
			byte value = enable ? sensor.getEnableSensorCode()
					: Sensor.DISABLE_SENSOR_CODE;
			mBtLeService.writeCharacteristic(charac, value);
			mBtLeService.waitIdle(GATT_TIMEOUT);
		}

	}

	private void enableNotifications(boolean enable) {
		for (Sensor sensor : mEnabledSensors) {
			UUID servUuid = sensor.getService();
			UUID dataUuid = sensor.getData();
			BluetoothGattService serv = mBtGatt.getService(servUuid);
			BluetoothGattCharacteristic charac = serv
					.getCharacteristic(dataUuid);

			mBtLeService.setCharacteristicNotification(charac, enable);
			mBtLeService.waitIdle(GATT_TIMEOUT);
		}
	}

	/*@Override
	protected void onResume() {
		Log.d(TAG, "onResume");
		super.onResume();
		if (!mIsReceiving) {
			registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());
			mIsReceiving = true;
		}
	}*/

	private static IntentFilter makeGattUpdateIntentFilter() {
		final IntentFilter fi = new IntentFilter();
		fi.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
		fi.addAction(BluetoothLeService.ACTION_DATA_NOTIFY);
		fi.addAction(BluetoothLeService.ACTION_DATA_WRITE);
		fi.addAction(BluetoothLeService.ACTION_DATA_READ);
		return fi;
	}

	private final BroadcastReceiver mGattUpdateReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			final String action = intent.getAction();
			int status = intent.getIntExtra(BluetoothLeService.EXTRA_STATUS,
					BluetoothGatt.GATT_SUCCESS);

			if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED
					.equals(action)) {
				if (status == BluetoothGatt.GATT_SUCCESS) {
					displayServices();
					// checkOad();
				} else {
					Toast.makeText(getApplication(),
							"Service discovery failed", Toast.LENGTH_LONG)
							.show();
					return;
				}
			} else if (BluetoothLeService.ACTION_DATA_NOTIFY.equals(action)) {
				// Notification
				byte[] value = intent
						.getByteArrayExtra(BluetoothLeService.EXTRA_DATA);
				String uuidStr = intent
						.getStringExtra(BluetoothLeService.EXTRA_UUID);
				//logAccelerometerReading("\n onCharacteristicChanged--------");
				onCharacteristicChanged(uuidStr, value);
			} else if (BluetoothLeService.ACTION_DATA_WRITE.equals(action)) {
				// Data written
				String uuidStr = intent
						.getStringExtra(BluetoothLeService.EXTRA_UUID);
				onCharacteristicWrite(uuidStr, status);
			} else if (BluetoothLeService.ACTION_DATA_READ.equals(action)) {
				// Data read
				String uuidStr = intent
						.getStringExtra(BluetoothLeService.EXTRA_UUID);
				byte[] value = intent
						.getByteArrayExtra(BluetoothLeService.EXTRA_DATA);
				onCharacteristicsRead(uuidStr, value, status);
			}

			if (status != BluetoothGatt.GATT_SUCCESS) {
				setError("GATT error code: " + status);
			}
		}
	};

	private void onCharacteristicWrite(String uuidStr, int status) {
		Log.d(TAG, "onCharacteristicWrite: " + uuidStr);
	}

	private void onCharacteristicsRead(String uuidStr, byte[] value, int status) {
		Log.i(TAG, "onCharacteristicsRead: " + uuidStr);
	}

	/**
	 * Handle changes in sensor values
	 * */
	public void onCharacteristicChanged(String uuidStr, byte[] rawValue) {
		Point3D v;
		String msg;
		String log;

		if (uuidStr.equals(UUID_ACC_DATA.toString())) {
			v = Sensor.ACCELEROMETER.convert(rawValue);
			msg = "x=" + decimal.format(v.x) + "\n" + "y="
					+ decimal.format(v.y) + "\n" + "z=" + decimal.format(v.z)
					+ "\n";
			log = "x=" + decimal.format(v.x) + "\n" + "y="
					+ decimal.format(v.y) + "\n" + "z=" + decimal.format(v.z);
			Date timestamp = new Date();
			logAccelerometerReading("\n" + String.valueOf(timestamp) + ": " + log);
			//accelerometerReadingView.setText(msg);
		}
	}

	private void logAccelerometerReading(String msg) {
		try {
			accelerometerOutputStream.write("\n".getBytes());
			accelerometerOutputStream.write(msg.getBytes());
		} catch (IOException ex) {
			ex.printStackTrace();
		}
	}

}
