package org.witness.informacam.informa;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.json.JSONException;
import org.json.JSONObject;
import org.witness.informacam.R;
import org.witness.informacam.app.MainActivity;
import org.witness.informacam.app.editors.image.ImageRegion;
import org.witness.informacam.app.editors.image.ImageRegion.ImageRegionListener;
import org.witness.informacam.crypto.SignatureUtility;
import org.witness.informacam.informa.Informa.InformaListener;
import org.witness.informacam.informa.SensorLogger.OnSuckerUpdateListener;
import org.witness.informacam.informa.suckers.AccelerometerSucker;
import org.witness.informacam.informa.suckers.GeoSucker;
import org.witness.informacam.informa.suckers.PhoneSucker;
import org.witness.informacam.utils.Constants;
import org.witness.informacam.utils.Constants.Crypto.Signatures;
import org.witness.informacam.utils.Constants.Informa.Status;
import org.witness.informacam.utils.Constants.Suckers.Phone;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

public class InformaService extends Service implements OnSuckerUpdateListener, InformaListener, ImageRegionListener {
	public static InformaService informaService;
	private final IBinder binder = new LocalBinder();
	
	NotificationManager nm;
	
	Intent toMainActivity;
	private String informaCurrentStatusString;
	private int informaCurrentStatus;
		
	SensorLogger<GeoSucker> _geo;
	SensorLogger<PhoneSucker> _phone;
	SensorLogger<AccelerometerSucker> _acc;
	
	List<BroadcastReceiver> br = new ArrayList<BroadcastReceiver>();
	
	private LoadingCache<Long, LogPack> suckerCache;
	ExecutorService ex;
	
	Informa informa;
	
	public class LocalBinder extends Binder {
		public InformaService getService() {
			return InformaService.this;
		}
	}
	
	public void setCurrentStatus(int status) {
		informaCurrentStatus = status;
		informaCurrentStatusString = getResources().getStringArray(R.array.informa_statuses)[informaCurrentStatus];
		showNotification();
	}
	
	@Override
	public IBinder onBind(Intent intent) {
		return binder;
	}
	
	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		return START_STICKY;
	}
	
	public static InformaService getInstance() {
		return informaService;
	}
	
	public int getStatus() {
		return informaCurrentStatus;
	}
	
	@Override
	public void onCreate() {
		Log.d(Constants.Informa.LOG, "InformaService running");
		
		toMainActivity = new Intent(this, MainActivity.class);
		nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
		
		br.add(new Broadcaster(new IntentFilter(BluetoothDevice.ACTION_FOUND)));
		
		for(BroadcastReceiver b : br)
			registerReceiver(b, ((Broadcaster) b).intentFilter);
		
		informaService = this;
	}
	
	@Override
	public void onDestroy() {
		super.onDestroy();
		Log.d(Constants.Informa.LOG, "InformaService stopped");
	}
			
	@SuppressWarnings({"unchecked"})
	public void init() {
		suckerCache = CacheBuilder.newBuilder()
				.maximumSize(200000L)
				.build(new CacheLoader<Long, LogPack>() {
					@Override
					public LogPack load(Long timestamp) throws Exception {
						return suckerCache.get(timestamp);
					}
				});
		
		_geo = new GeoSucker(InformaService.this);
		_phone = new PhoneSucker(InformaService.this);
		_acc = new AccelerometerSucker(InformaService.this);
		this.setCurrentStatus(Status.RUNNING);
	}
	
	public void suspend() {
		this.setCurrentStatus(Status.STOPPED);
		_geo.getSucker().stopUpdates();
		_phone.getSucker().stopUpdates();
		_acc.getSucker().stopUpdates();
		
		_geo = null;
		_phone = null;
		_acc = null;
	}
	
	@SuppressWarnings("unused")
	private void doShutdown() {
		suspend();
		
		for(BroadcastReceiver b : br)
			unregisterReceiver(b);
		
		stopSelf();
	}
	
	@SuppressWarnings("deprecation")
	public void showNotification() {
		Notification n = new Notification(
				R.drawable.ic_ssc,
				getString(R.string.app_name),
				System.currentTimeMillis());
		
		PendingIntent pi = PendingIntent.getActivity(
				this,
				Constants.Informa.FROM_NOTIFICATION_BAR, 
				toMainActivity,
				PendingIntent.FLAG_UPDATE_CURRENT);
		
		n.setLatestEventInfo(this, getString(R.string.app_name), informaCurrentStatusString, pi);
		nm.notify(R.string.app_name_lc, n);
	}
	
	@SuppressWarnings("unused")
	private void pushToSucker(SensorLogger<?> sucker, LogPack logPack) throws JSONException {
		if(sucker.getClass().equals(PhoneSucker.class))
			_phone.sendToBuffer(logPack);
	}

	@SuppressWarnings("unchecked")
	@Override
	public void onSuckerUpdate(long timestamp, final LogPack logPack) {
		try {
			//Log.d(Suckers.LOG, timestamp + " :\n" + logPack.toString());
			ex = Executors.newFixedThreadPool(100);
			Future<String> sig = ex.submit(new Callable<String>() {

				@Override
				public String call() throws Exception {
					return SignatureUtility.getInstance().signData(logPack.toString().getBytes());
				}
				
			});
			
			LogPack lp = suckerCache.getIfPresent(timestamp);
			if(lp != null) {
				Iterator<String> lIt = lp.keys();
				while(lIt.hasNext()) {
					String key = lIt.next();
					logPack.put(key, lp.get(key));
				}
			}
			
			logPack.put(Signatures.Keys.SIGNATURE, sig.get());
			suckerCache.put(timestamp, logPack);
			ex.shutdown();
		} catch (JSONException e) {
			e.printStackTrace();
		} catch (InterruptedException e) {
			e.printStackTrace();
		} catch (ExecutionException e) {
			e.printStackTrace();
		}
	}
	
	private class Broadcaster extends BroadcastReceiver {
		IntentFilter intentFilter;
		
		public Broadcaster(IntentFilter intentFilter) {
			this.intentFilter = intentFilter;
		}
		
		@Override
		public void onReceive(Context c, Intent i) {
			if(BluetoothDevice.ACTION_FOUND.equals(i.getAction())) {
				try {
					BluetoothDevice bd = (BluetoothDevice) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
					LogPack logPack = new LogPack(Phone.Keys.BLUETOOTH_DEVICE_ADDRESS, bd.getAddress());
					logPack.put(Phone.Keys.BLUETOOTH_DEVICE_NAME, bd.getName());
					suckerCache.put(System.currentTimeMillis(), logPack);
				} catch(JSONException e) {}
			}
			
		}
		
	}

	@Override
	public void onInformaInit() {
		informa = new Informa(this);
		try {
			informa.setDeviceCredentials(_phone.getSucker().forceReturn());
			
		} catch(JSONException e) {}
		
		
	}

	@SuppressWarnings("unchecked")
	@Override
	public void onImageRegionCreated(ImageRegion ir) {
		// TODO: add new image region
		try {
			JSONObject rep = ir.getRepresentation();
			long timestamp = (Long) rep.remove(Constants.Informa.Keys.Data.ImageRegion.TIMESTAMP);
			
			LogPack logPack = new LogPack();
			Iterator<String> repIt = rep.keys();
			while(repIt.hasNext()) {
				String key = repIt.next();
				logPack.put(key, rep.get(key));
			}
			
			this.onSuckerUpdate(timestamp, logPack);
			
			Log.d(Constants.Informa.LOG, "new image region!");
			Log.d(Constants.Informa.LOG, logPack.toString());
		} catch(JSONException e) {}
	}

	@SuppressWarnings("unchecked")
	@Override
	public void onImageRegionChanged(ImageRegion ir) {
		// TODO pull out image region from cache and update values
		try {
			JSONObject rep = ir.getRepresentation();
			long timestamp = (Long) rep.remove(Constants.Informa.Keys.Data.ImageRegion.TIMESTAMP);
			
			LogPack logPack = suckerCache.get(timestamp);
			Iterator<String> repIt = rep.keys();
			while(repIt.hasNext()) {
				String key = repIt.next();
				logPack.put(key, rep.get(key));
			}
			
			
			Log.d(Constants.Informa.LOG, "updating image region!");
			Log.d(Constants.Informa.LOG, logPack.toString());
		} catch(JSONException e) {}
		catch (ExecutionException e) {
			Log.d(Constants.Informa.LOG, e.toString());
			e.printStackTrace();
		}
		
		
	}

}