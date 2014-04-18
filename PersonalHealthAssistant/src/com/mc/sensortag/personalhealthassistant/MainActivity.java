package com.mc.sensortag.personalhealthassistant;

import java.util.ArrayList;
import java.util.List;

import ti.android.ble.common.BleDeviceInfo;
import ti.android.ble.common.BluetoothLeService;

import ti.android.util.CustomTimer;
import ti.android.util.CustomToast;

import com.mc.sensortag.personalhealthassistant.R;

//import com.mc.sensortag.adapters.BleDevicesAdapter;
import android.os.Bundle;
import android.os.IBinder;
import android.app.Activity;
import android.app.TabActivity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.TabHost;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends TabActivity {
	private static final String TAG = "MainActivity";

	// Requests to other activities
	private static final int REQ_ENABLE_BT = 0;
	private static final int REQ_DEVICE_ACT = 1;

	// Housekeeping
	private static final int NO_DEVICE = -1;
	private boolean mInitialised = false;

	private Intent mDeviceIntent;
	private static final int STATUS_DURATION = 5;

	// GUI
	private static MainActivity mThis = null;

	// BLE management
	private boolean mBleSupported = true;
	private boolean mScanning = false;
	private int mNumDevs = 0;
	private int mConnIndex = NO_DEVICE;
	private static BluetoothManager mBluetoothManager;
	private BluetoothAdapter mBtAdapter = null;
	private BluetoothDevice mBluetoothDevice = null;
	private BluetoothLeService mBluetoothLeService = null;
	private List<BleDeviceInfo> mDeviceInfoList;
	private String[] mDeviceFilter = null;
	private IntentFilter mFilter;

	private TextView mStatus;

	ScanActivity scanActivity;

	public MainActivity() {
		Log.i(TAG, "Construct");
		mThis = this;

	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		View view = getLayoutInflater().inflate(R.layout.activity_main, null);
		setContentView(view);
		scanActivity = new ScanActivity(MainActivity.this, view);

		Log.i(TAG, "onCreate");
		
		Log.i(TAG, "Displaying Tabs");
		TabHost tabhost = getTabHost();
		tabhost.addTab(tabhost.newTabSpec("one").setIndicator("Dashboard").setContent(new Intent(this, DashboardActivity.class)));
		tabhost.addTab(tabhost.newTabSpec("two").setIndicator("Recommendations").setContent(new Intent(this, RecommendationsActivity.class)));

		// Use this check to determine whether BLE is supported on the device.
		// Then
		// you can selectively disable BLE-related features.
		if (!getPackageManager().hasSystemFeature(
				PackageManager.FEATURE_BLUETOOTH_LE)) {
			Toast.makeText(this, "Bluetooth device is not supported",
					Toast.LENGTH_LONG).show();
			mBleSupported = false;
		}

		// Initializes a Bluetooth adapter. For API level 18 and above, get a
		// reference to BluetoothAdapter through BluetoothManager.
		mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
		mBtAdapter = mBluetoothManager.getAdapter();

		// Checks if Bluetooth is supported on the device.
		if (mBtAdapter == null) {
			Toast.makeText(this, "Bluetooth device is not supported",
					Toast.LENGTH_LONG).show();
			mBleSupported = false;
		}

		// Initialize device list container and device filter
		mDeviceInfoList = new ArrayList<BleDeviceInfo>();
		Resources res = getResources();
		mDeviceFilter = res.getStringArray(R.array.device_filter);

		// Register the BroadcastReceiver
		mFilter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
		mFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
		mFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);

		onScanViewReady();
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu items for use in the action bar
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.main, menu);
		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		// Handle presses on the action bar items
		switch (item.getItemId()) {
		case android.R.id.home:
			onBackPressed();
			return true;
		case R.id.scan_menu:
			onScan();
			break;
		case R.id.opt_about:
			//onAbout();
			break;
		case R.id.opt_exit:
			finish();
			break;
		default:
			return super.onOptionsItemSelected(item);
		}
		return true;
	}
	
	public void onScan() {
		// Scan for devices
		mScanning = true;
		startScan();
	}

	public void onScanViewReady() {
		// Initial state of widgets
		updateGuiState();

		if (!mInitialised) {
			// Broadcast receiver
			registerReceiver(mReceiver, mFilter);

			if (mBtAdapter.isEnabled()) {
				// Start straight away
				startBluetoothLeService();
			} else {
				// Request BT adapter to be turned on
				Intent enableIntent = new Intent(
						BluetoothAdapter.ACTION_REQUEST_ENABLE);
				startActivityForResult(enableIntent, REQ_ENABLE_BT);
			}
			mInitialised = true;
		} else {
			// mScanView.notifyDataSetChanged();
		}
	}

	// ////////////////////////////////////////////////////////////////////////////////////////////////
	//
	// GUI methods
	//
	public void updateGuiState() {
		boolean mBtEnabled = mBtAdapter.isEnabled();

		if (mBtEnabled) {
			if (mScanning) {
				// BLE Host connected
				if (mConnIndex != NO_DEVICE) {
					String txt = mBluetoothDevice.getName() + " connected";
					scanActivity.setStatus(txt);
				} else {
					scanActivity.setStatus(mNumDevs + " devices");
				}
			}
		} else {
			mDeviceInfoList.clear();
			// mScanView.notifyDataSetChanged();
		}
	}

	// ////////////////////////////////////////////////////////////////////////////////////////////////
	//
	// Broadcasted actions from Bluetooth adapter and BluetoothLeService
	//
	private BroadcastReceiver mReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			final String action = intent.getAction();

			if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
				// Bluetooth adapter state change
				switch (mBtAdapter.getState()) {
				case BluetoothAdapter.STATE_ON:
					mConnIndex = NO_DEVICE;
					startBluetoothLeService();
					break;
				case BluetoothAdapter.STATE_OFF:
					Toast.makeText(context, "app closing", Toast.LENGTH_LONG)
							.show();
					finish();
					break;
				default:
					Log.w(TAG, "Action STATE CHANGED not processed ");
					break;
				}

				updateGuiState();
			} else if (BluetoothLeService.ACTION_GATT_CONNECTED.equals(action)) {
				// GATT connect
				int status = intent.getIntExtra(
						BluetoothLeService.EXTRA_STATUS,
						BluetoothGatt.GATT_FAILURE);
				if (status == BluetoothGatt.GATT_SUCCESS) {
					scanActivity.setStatus(mBluetoothDevice.getName()
							+ " connected", STATUS_DURATION);
					// setBusy(false);
					startDeviceActivity();
				} else
					setError("Connect failed. Status: " + status);
			} else if (BluetoothLeService.ACTION_GATT_DISCONNECTED
					.equals(action)) {
				// GATT disconnect
				int status = intent.getIntExtra(
						BluetoothLeService.EXTRA_STATUS,
						BluetoothGatt.GATT_FAILURE);
				//stopDeviceActivity();
				stopDeviceService();
				if (status == BluetoothGatt.GATT_SUCCESS) {
					// setBusy(false);
					scanActivity.setStatus(mBluetoothDevice.getName()
							+ " disconnected", STATUS_DURATION);
					Toast.makeText(MainActivity.this, 
							mBluetoothDevice.getName() + " disconnected",
							Toast.LENGTH_LONG).show();
				} else {
					setError("Disconnect failed. Status: " + status);
				}
				mConnIndex = NO_DEVICE;
				mBluetoothLeService.close();
			} else {
				Log.w(TAG, "Unknown action: " + action);
			}

		}
	};

	private void setBusy(boolean f) {
		setBusy(f);
	}

	void setError(String txt) {
		setError(txt);
	}

	private void startBluetoothLeService() {
		boolean f;

		Intent bindIntent = new Intent(this, BluetoothLeService.class);
		startService(bindIntent);
		f = bindService(bindIntent, mServiceConnection,
				Context.BIND_AUTO_CREATE);
		if (f)
			Log.d(TAG, "BluetoothLeService - success");
		else {
			CustomToast.middleBottom(this, "Bind to BluetoothLeService failed");
			finish();
		}
	}

	// Code to manage Service life cycle.
	private final ServiceConnection mServiceConnection = new ServiceConnection() {

		public void onServiceConnected(ComponentName componentName,
				IBinder service) {
			mBluetoothLeService = ((BluetoothLeService.LocalBinder) service)
					.getService();
			if (!mBluetoothLeService.initialize()) {
				Log.e(TAG, "Unable to initialize BluetoothLeService");
				finish();
				return;
			}
			final int n = mBluetoothLeService.numConnectedDevices();
			if (n > 0) {
				runOnUiThread(new Runnable() {
					public void run() {
						mThis.setError("Multiple connections!");
					}
				});
			} else {
				startScan();
				Log.i(TAG, "BluetoothLeService connected");
			}
		}

		public void onServiceDisconnected(ComponentName componentName) {
			mBluetoothLeService = null;
			Log.i(TAG, "BluetoothLeService disconnected");
		}
	};

	private void startScan() {
		// Start device discovery
		if (mBleSupported) {
			mNumDevs = 0;
			mDeviceInfoList.clear();
			// mScanView.notifyDataSetChanged();
			// dataSetChanged();
			scanLeDevice(true);
			// mScanView.updateGui(mScanning);
			if (!mScanning) {
				setError("Device discovery start failed");
				// setBusy(false);
			}
		} else {
			setError("BLE not supported on this device");
		}

	}

	private void stopScan() {
		mScanning = false;
		scanActivity.updateGui(false);
		scanLeDevice(false);
	}

	private void startDeviceActivity() {
		
		 /* Log.i(TAG, "**Starting Device Activity**"); mDeviceIntent = new
		  Intent(this, DeviceActivity.class);
		  mDeviceIntent.putExtra(DeviceActivity.EXTRA_DEVICE,
		  mBluetoothDevice); startActivityForResult(mDeviceIntent,
		  REQ_DEVICE_ACT);*/

		Intent i = new Intent(MainActivity.this, DeviceService.class);
		i.putExtra(DeviceActivity.EXTRA_DEVICE, mBluetoothDevice);
		MainActivity.this.startService(i);

		Toast.makeText(MainActivity.this,
				"Starting SensorTag tracking in the background",
				Toast.LENGTH_LONG).show();
	}

	/*private void stopDeviceActivity() {
		finishActivity(REQ_DEVICE_ACT);
	}*/
	
	private void stopDeviceService(){
		Intent i = new Intent(MainActivity.this, DeviceService.class);
		//i.putExtra(DeviceActivity.EXTRA_DEVICE, mBluetoothDevice);
		MainActivity.this.stopService(i);
	}

	private boolean scanLeDevice(boolean enable) {
		if (enable) {
			mScanning = mBtAdapter.startLeScan(mLeScanCallback);
		} else {
			mScanning = false;
			mBtAdapter.stopLeScan(mLeScanCallback);
		}
		return mScanning;
	}

	// Device scan callback.
	// NB! Nexus 4 and Nexus 7 (2012) only provide one scan result per scan
	private BluetoothAdapter.LeScanCallback mLeScanCallback = new BluetoothAdapter.LeScanCallback() {

		public void onLeScan(final BluetoothDevice device, final int rssi,
				byte[] scanRecord) {
			runOnUiThread(new Runnable() {
				public void run() {
					int deviceIndex;
					// Filter devices
					if (checkDeviceFilter(device)) {
						if (!deviceInfoExists(device.getAddress())) {
							// New device
							BleDeviceInfo deviceInfo = createDeviceInfo(device,
									rssi);
							addDevice(deviceInfo);
							// connect to the device
							if (mDeviceInfoList.contains(deviceInfo)) {
								deviceIndex = mDeviceInfoList
										.indexOf(deviceInfo);
								onDeviceClick(deviceIndex);
							}
						} else {
							// Already in list, update RSSI info
							BleDeviceInfo deviceInfo = findDeviceInfo(device);
							deviceInfo.updateRssi(rssi);
							// mScanView.notifyDataSetChanged();
							// dataSetChanged(deviceInfo);
							// updateDeviceReading(deviceInfo);
						}
					}
				}

			});
		}
	};

	private boolean checkDeviceFilter(BluetoothDevice device) {
		int n = mDeviceFilter.length;
		if (n > 0) {
			boolean found = false;
			for (int i = 0; i < n && !found; i++) {
				String deviceName = device.getName();
				if (deviceName != null) {
					found = device.getName().equals(mDeviceFilter[i]);
				}
			}
			return found;
		} else
			// Allow all devices if the device filter is empty
			return true;
	}

	private boolean deviceInfoExists(String address) {
		for (int i = 0; i < mDeviceInfoList.size(); i++) {
			if (mDeviceInfoList.get(i).getBluetoothDevice().getAddress()
					.equals(address)) {
				return true;
			}
		}
		return false;
	}

	private BleDeviceInfo createDeviceInfo(BluetoothDevice device, int rssi) {
		BleDeviceInfo deviceInfo = new BleDeviceInfo(device, rssi);

		return deviceInfo;
	}

	private void addDevice(BleDeviceInfo device) {
		mNumDevs++;
		mDeviceInfoList.add(device);
		// dataSetChanged();
		updateDeviceReading(device);
		if (mNumDevs > 1)
			scanActivity.setStatus(mNumDevs + " devices");
		else
			scanActivity.setStatus("1 device");
	}

	private BleDeviceInfo findDeviceInfo(BluetoothDevice device) {
		for (int i = 0; i < mDeviceInfoList.size(); i++) {
			if (mDeviceInfoList.get(i).getBluetoothDevice().getAddress()
					.equals(device.getAddress())) {
				return mDeviceInfoList.get(i);
			}
		}
		return null;
	}

	void updateDeviceReading(BleDeviceInfo deviceInfo) {
		BluetoothDevice device = deviceInfo.getBluetoothDevice();
		int rssi = deviceInfo.getRssi();
		String descr = "Device Name: " + device.getName() + "\n"
				+ "Device Address: " + device.getAddress() + "\n"
				+ "Device Rssi: " + rssi + " dBm";
		
		Log.i(TAG, descr);

		//TextView deviceInfoTextView = (TextView) findViewById(R.id.deviceInfo);
		//deviceInfoTextView.setText(descr);
	}

	public void onDeviceClick(int pos) {

		if (mScanning)
			stopScan();

		// setBusy(true);
		mBluetoothDevice = mDeviceInfoList.get(pos).getBluetoothDevice();
		if (mConnIndex == NO_DEVICE) {
			scanActivity.setStatus("Connecting");
			mConnIndex = pos;
			onConnect();
		} else {
			scanActivity.setStatus("Disconnecting");
			if (mConnIndex != NO_DEVICE) {
				mBluetoothLeService.disconnect(mBluetoothDevice.getAddress());
			}
		}
	}

	void onConnect() {
		if (mNumDevs > 0) {
			int connState = mBluetoothManager.getConnectionState(
					mBluetoothDevice, BluetoothGatt.GATT);

			switch (connState) {
			case BluetoothGatt.STATE_CONNECTED:
				mBluetoothLeService.disconnect(null);
				break;
			case BluetoothGatt.STATE_DISCONNECTED:
				boolean ok = mBluetoothLeService.connect(mBluetoothDevice
						.getAddress());
				if (!ok) {
					setError("Connect failed");
				}
				break;
			default:
				setError("Device busy (connecting/disconnecting)");
				break;
			}
		}
	}

	void updateGui(boolean scanning) {
		if (scanning) {
			mStatus.setText("Scanning...");
			updateGuiState();
		} else {
		}
	}

	public void onClickScan(View view) {
		// Scan for devices
		mScanning = true;
		startScan();
	}

	public static MainActivity getmThis() {
		return mThis;
	}

	public static void setmThis(MainActivity mThis) {
		MainActivity.mThis = mThis;
	}
}
