/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.bluetoothlegatt;

import android.app.Activity;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.ExpandableListView;
import android.widget.SimpleExpandableListAdapter;
import android.widget.TextView;
import android.widget.Toast;
import cm.example.android.mw.OSDCommon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

/**
 * For a given BLE device, this Activity provides the user interface to connect, display data,
 * and display GATT services and characteristics supported by the device.  The Activity
 * communicates with {@code BluetoothLeService}, which in turn interacts with the
 * Bluetooth LE API.
 */
public class DeviceControlActivity extends Activity {
    private final static String TAG = DeviceControlActivity.class.getSimpleName();

    public static final String EXTRAS_DEVICE_NAME = "DEVICE_NAME";
    public static final String EXTRAS_DEVICE_ADDRESS = "DEVICE_ADDRESS";

    private TextView mConnectionState;
    private TextView mDataField;
    private String mDeviceName;
    private String mDeviceAddress;
    private ExpandableListView mGattServicesList;
    private BluetoothLeService mBluetoothLeService;
    private ArrayList<ArrayList<BluetoothGattCharacteristic>> mGattCharacteristics =
            new ArrayList<ArrayList<BluetoothGattCharacteristic>>();
    private boolean mConnected = false;
    private BluetoothGattCharacteristic mNotifyCharacteristic;

    private final String LIST_NAME = "NAME";
    private final String LIST_UUID = "UUID";

    private Button mBtnMagCalibrate;
    private Button mBtnAccCalibrate;
    private Button mBtnTakeOff;
    private Button mBtnStop;
    private SeekBar mSeekBar;
    private int mValue = 0;
    
    private Timer timer;
    private byte dataPackage[] = new byte[11];
    Handler handler = new Handler();
    
    private static final int FPS = 14; // max 17
    
    // Code to manage Service lifecycle.
    private final ServiceConnection mServiceConnection = new ServiceConnection() {

    	
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            mBluetoothLeService = ((BluetoothLeService.LocalBinder) service).getService();
            if (!mBluetoothLeService.initialize()) {
                Log.e(TAG, "Unable to initialize Bluetooth");
                finish();
            }
            // Automatically connects to the device upon successful start-up initialization.
            mBluetoothLeService.connect(mDeviceAddress);
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mBluetoothLeService = null;
        }
    };

    // Handles various events fired by the Service.
    // ACTION_GATT_CONNECTED: connected to a GATT server.
    // ACTION_GATT_DISCONNECTED: disconnected from a GATT server.
    // ACTION_GATT_SERVICES_DISCOVERED: discovered GATT services.
    // ACTION_DATA_AVAILABLE: received data from the device.  This can be a result of read
    //                        or notification operations.
    private final BroadcastReceiver mGattUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (BluetoothLeService.ACTION_GATT_CONNECTED.equals(action)) {
                mConnected = true;
                updateConnectionState(R.string.connected);
                invalidateOptionsMenu();
            } else if (BluetoothLeService.ACTION_GATT_DISCONNECTED.equals(action)) {
                mConnected = false;
                updateConnectionState(R.string.disconnected);
                invalidateOptionsMenu();
                clearUI();
            } else if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {
                // Show all the supported services and characteristics on the user interface.
                displayGattServices(mBluetoothLeService.getSupportedGattServices());
            } else if (BluetoothLeService.ACTION_DATA_AVAILABLE.equals(action)) {
                displayData(intent.getStringExtra(BluetoothLeService.EXTRA_DATA));
            }
        }
    };

    // If a given GATT characteristic is selected, check for supported features.  This sample
    // demonstrates 'Read' and 'Notify' features.  See
    // http://d.android.com/reference/android/bluetooth/BluetoothGatt.html for the complete
    // list of supported characteristic features.
    private final ExpandableListView.OnChildClickListener servicesListClickListner =
            new ExpandableListView.OnChildClickListener() {
                @Override
                public boolean onChildClick(ExpandableListView parent, View v, int groupPosition,
                                            int childPosition, long id) {
                    if (mGattCharacteristics != null) {
                        final BluetoothGattCharacteristic characteristic =
                                mGattCharacteristics.get(groupPosition).get(childPosition);
                        final int charaProp = characteristic.getProperties();
                        if ((charaProp | BluetoothGattCharacteristic.PROPERTY_READ) > 0) {
                            // If there is an active notification on a characteristic, clear
                            // it first so it doesn't update the data field on the user interface.
                            if (mNotifyCharacteristic != null) {
                                mBluetoothLeService.setCharacteristicNotification(
                                        mNotifyCharacteristic, false);
                                mNotifyCharacteristic = null;
                            }
                            mBluetoothLeService.readCharacteristic(characteristic);
                        }
                        if ((charaProp | BluetoothGattCharacteristic.PROPERTY_NOTIFY) > 0) {
                            mNotifyCharacteristic = characteristic;
                            mBluetoothLeService.setCharacteristicNotification(
                                    characteristic, true);
                        }
                        return true;
                    }
                    return false;
                }
    };

    private void clearUI() {
//        mGattServicesList.setAdapter((SimpleExpandableListAdapter) null);
        mDataField.setText(R.string.no_data);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
//        setContentView(R.layout.gatt_services_characteristics);
        setContentView(R.layout.main_layout);

        final Intent intent = getIntent();
        mDeviceName = intent.getStringExtra(EXTRAS_DEVICE_NAME);
        mDeviceAddress = intent.getStringExtra(EXTRAS_DEVICE_ADDRESS);

        // Sets up UI references.
//        ((TextView) findViewById(R.id.device_address)).setText(mDeviceAddress);
//        mGattServicesList = (ExpandableListView) findViewById(R.id.gatt_services_list);
//        mGattServicesList.setOnChildClickListener(servicesListClickListner);
//        mConnectionState = (TextView) findViewById(R.id.connection_state);
        mDataField = (TextView) findViewById(R.id.textView);

        getActionBar().setTitle(mDeviceName);
        getActionBar().setDisplayHomeAsUpEnabled(true);
        Intent gattServiceIntent = new Intent(this, BluetoothLeService.class);
        bindService(gattServiceIntent, mServiceConnection, BIND_AUTO_CREATE);
        
        mBtnMagCalibrate = (Button) findViewById( R.id.btn_calibrate_mag);
        mBtnMagCalibrate.setOnClickListener( new View.OnClickListener() {
			
			@Override
        	public void onClick(View arg0){
	    		byte[] mspData = OSDCommon.getSimpleCommand(OSDCommon.MSPCommnand.MSP_MAG_CALIBRATION);
	    		
	    		if( mConnected == true && mspData != null)
	    		{
	    			mBluetoothLeService.WriteValue( mspData );
	    			mDataField.append("Send ACC Calibration \n");
	    		}
	    		else
	    		{
	    			Toast.makeText( getBaseContext(), "Not Connected", Toast.LENGTH_SHORT ).show();
	    			mDataField.append( "Not Connected \n");
	    			
	    		}
			}
		});
		

        mBtnAccCalibrate = (Button) findViewById( R.id.btn_calibrate_acc);
        mBtnAccCalibrate.setOnClickListener( new View.OnClickListener() {
			
			@Override
        	public void onClick(View arg0){
	    		byte[] mspData = OSDCommon.getSimpleCommand(OSDCommon.MSPCommnand.MSP_ACC_CALIBRATION);
	    		
	    		if( mConnected == true && mspData != null)
	    		{
	    			mBluetoothLeService.WriteValue( mspData );
	    			mDataField.append("Send ACC Calibration \n");
	    		}
	    		else
	    		{
	    			Toast.makeText( getBaseContext(), "Not Connected", Toast.LENGTH_SHORT ).show();
	    			mDataField.append( "Not Connected \n");
	    			
	    		}
			}
		});
        

        mBtnTakeOff = (Button) findViewById( R.id.btn_takeoff);
        mBtnTakeOff.setOnClickListener( new View.OnClickListener() {
			
			@Override
        	public void onClick(View arg0){
	    		byte[] mspData = OSDCommon.getSimpleCommand(OSDCommon.MSPCommnand.MSP_ARM);
	    		
	    		if( mConnected == true && mspData != null)
	    		{
	    			mBluetoothLeService.WriteValue( mspData );
	    			mDataField.append("Send MSP_ARM \n");
	    		}
	    		else
	    		{
	    			Toast.makeText( getBaseContext(), "Not Connected", Toast.LENGTH_SHORT ).show();
	    			mDataField.append( "Not Connected \n");
	    			
	    		}
			}
		});

        mBtnStop = (Button) findViewById( R.id.btn_stop);
        mBtnStop.setOnClickListener( new View.OnClickListener() {
			
			@Override
        	public void onClick(View arg0){
	    		byte[] mspData = OSDCommon.getSimpleCommand(OSDCommon.MSPCommnand.MSP_DISARM);
	    		
	    		if( mConnected == true && mspData != null)
	    		{
	    			mBluetoothLeService.WriteValue( mspData );
	    			mDataField.append("Send MSP_DISARM \n");
	    		}
	    		else
	    		{
	    			Toast.makeText( getBaseContext(), "Not Connected", Toast.LENGTH_SHORT ).show();
	    			mDataField.append( "Not Connected \n");
	    			
	    		}
			}
		});
        
        mSeekBar = (SeekBar) findViewById( R.id.seekBar );
        mSeekBar.setProgress( mValue );
        mSeekBar.setOnSeekBarChangeListener( new SeekBar.OnSeekBarChangeListener() {
			
			@Override
			public void onStopTrackingTouch(SeekBar seekBar) {
				// TODO Auto-generated method stub
				
			}
			
			@Override
			public void onStartTrackingTouch(SeekBar seekBar) {
				// TODO Auto-generated method stub
				
			}
			
			@Override
			public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
				// TODO Auto-generated method stub
				Log.d( TAG, "OnProgressChanged: " + progress);
				mValue = progress;
				
			}
		});
        
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());
        if (mBluetoothLeService != null) {
            final boolean result = mBluetoothLeService.connect(mDeviceAddress);
            Log.d(TAG, "Connect request result=" + result);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(mGattUpdateReceiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unbindService(mServiceConnection);
        mBluetoothLeService = null;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.gatt_services, menu);
        if (mConnected) {
            menu.findItem(R.id.menu_connect).setVisible(false);
            menu.findItem(R.id.menu_disconnect).setVisible(true);
        } else {
            menu.findItem(R.id.menu_connect).setVisible(true);
            menu.findItem(R.id.menu_disconnect).setVisible(false);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch(item.getItemId()) {
            case R.id.menu_connect:
                mBluetoothLeService.connect(mDeviceAddress);
                return true;
            case R.id.menu_disconnect:
                mBluetoothLeService.disconnect();
                return true;
            case android.R.id.home:
                onBackPressed();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void updateConnectionState(final int resourceId) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
//                mConnectionState.setText(resourceId);
            	
            	String connectionState = getResources().getString( resourceId);
            	mDataField.append( "State: " + connectionState + "\n");;
            }
        });
    }

    private void displayData(String data) {
        if (data != null) {
            mDataField.setText(data);
        }
    }

    // Demonstrates how to iterate through the supported GATT Services/Characteristics.
    // In this sample, we populate the data structure that is bound to the ExpandableListView
    // on the UI.
    private void displayGattServices(List<BluetoothGattService> gattServices) {
        if (gattServices == null) return;
        String uuid = null;
        String unknownServiceString = getResources().getString(R.string.unknown_service);
        String unknownCharaString = getResources().getString(R.string.unknown_characteristic);
        ArrayList<HashMap<String, String>> gattServiceData = new ArrayList<HashMap<String, String>>();
        ArrayList<ArrayList<HashMap<String, String>>> gattCharacteristicData
                = new ArrayList<ArrayList<HashMap<String, String>>>();
        mGattCharacteristics = new ArrayList<ArrayList<BluetoothGattCharacteristic>>();

        // Loops through available GATT Services.
        for (BluetoothGattService gattService : gattServices) {
            HashMap<String, String> currentServiceData = new HashMap<String, String>();
            uuid = gattService.getUuid().toString();
            currentServiceData.put(
                    LIST_NAME, SampleGattAttributes.lookup(uuid, unknownServiceString));
            currentServiceData.put(LIST_UUID, uuid);
            gattServiceData.add(currentServiceData);

            ArrayList<HashMap<String, String>> gattCharacteristicGroupData =
                    new ArrayList<HashMap<String, String>>();
            List<BluetoothGattCharacteristic> gattCharacteristics =
                    gattService.getCharacteristics();
            ArrayList<BluetoothGattCharacteristic> charas =
                    new ArrayList<BluetoothGattCharacteristic>();

            // Loops through available Characteristics.
            for (BluetoothGattCharacteristic gattCharacteristic : gattCharacteristics) {
                charas.add(gattCharacteristic);
                HashMap<String, String> currentCharaData = new HashMap<String, String>();
                uuid = gattCharacteristic.getUuid().toString();
                currentCharaData.put(
                        LIST_NAME, SampleGattAttributes.lookup(uuid, unknownCharaString));
                currentCharaData.put(LIST_UUID, uuid);
                gattCharacteristicGroupData.add(currentCharaData);
            }
            mGattCharacteristics.add(charas);
            gattCharacteristicData.add(gattCharacteristicGroupData);
        }

        SimpleExpandableListAdapter gattServiceAdapter = new SimpleExpandableListAdapter(
                this,
                gattServiceData,
                android.R.layout.simple_expandable_list_item_2,
                new String[] {LIST_NAME, LIST_UUID},
                new int[] { android.R.id.text1, android.R.id.text2 },
                gattCharacteristicData,
                android.R.layout.simple_expandable_list_item_2,
                new String[] {LIST_NAME, LIST_UUID},
                new int[] { android.R.id.text1, android.R.id.text2 }
        );
        mGattServicesList.setAdapter(gattServiceAdapter);
    }

    private static IntentFilter makeGattUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
        intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE);
        return intentFilter;
    }
    

	private void initDataPackage(){
		dataPackage[0] = '$';
		dataPackage[1] = 'M';
		dataPackage[2] = '<';
		dataPackage[3] = 4;
		dataPackage[4] = (byte)(OSDCommon.MSPCommnand.MSP_SET_RAW_RC_TINY.value());
		
		updateDataPackage();
	}
	
    private void updateDataPackage(){
		byte checkSum = 0;
	    
	    int dataSizeIdx = 3;
	    int checkSumIdx = 10;
	    
	    dataPackage[dataSizeIdx] = 5;
	    
	    checkSum ^= (dataPackage[dataSizeIdx] & 0xFF);
	    checkSum ^= (dataPackage[dataSizeIdx + 1] & 0xFF);
	    
	    dataPackage[5] = (byte)0x7D;
	    checkSum ^= (dataPackage[5] & 0xFF);

	    dataPackage[6] = (byte)0x7D;
	    checkSum ^= (dataPackage[6] & 0xFF);
	    
	    dataPackage[7] = (byte)0x7D;
	    checkSum ^= (dataPackage[7] & 0xFF);
	    
	    dataPackage[8] = (byte) (mValue & 0xFF);
	    checkSum ^= (dataPackage[8] & 0xFF);
	    
	    dataPackage[9] = (byte)0x55;
	    checkSum ^= (dataPackage[9] & 0xFF);
    
	    
	    dataPackage[checkSumIdx] = (byte) checkSum;
	}

    
    
	public void start(){
		stop();
		
		initDataPackage();
		
		timer = new Timer();
		timer.schedule(new TimerTask() {
			
			@Override
			public void run() {
				handler.post(new Runnable() {
					
					@Override
					public void run() {
						transmmit();						
					}
				});
				
			}
		}, 0, 1000 / FPS);
	}
	
	public void stop(){
		if (timer != null) {
			timer.cancel();
			timer = null;
		}
	}
	
	private void transmmit()
	{
		updateDataPackage();
		
		if(mConnected == true && dataPackage != null)
		{
			mBluetoothLeService.WriteValue(dataPackage);;
		}
		else
		{
			Toast.makeText( getBaseContext(), "Not Connected", Toast.LENGTH_SHORT ).show();
			mDataField.append( "Not Connected \n");
		}
	}

    
    
    
}
